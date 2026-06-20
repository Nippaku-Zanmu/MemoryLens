package com.memorylens.app.capture

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * Continuously transcribes speech during a face-recognition session using Android's
 * on-device SpeechRecognizer (API 31+, fully offline, no model file needed).
 *
 * Usage pattern:
 *   startSession()  — called when a person is first identified
 *   stopSession { transcript -> ... }  — called after the 5-second face-exit timer
 *
 * SpeechRecognizer must be accessed from the main thread. All public methods post
 * work to [mainHandler]; callers may invoke them from any thread.
 *
 * Recognition is chained: after each utterance result, [startListening] is called
 * again so the transcript accumulates over the full session lifetime.
 * A 1-second fallback timeout after [stopSession] ensures the caller always gets
 * a result even if the recognizer is mid-utterance.
 */
class AudioTranscriber(private val context: Context) {

    companion object {
        private const val TAG = "AudioTranscriber"
        private const val FALLBACK_TIMEOUT_MS = 1500L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    /** Thread-safe accumulator guarded by [lock]. */
    private val lock = Any()
    private var accumulated = ""

    @Volatile private var sessionActive = false
    @Volatile private var pendingCallback: ((String) -> Unit)? = null
    @Volatile private var consecutiveErrors = 0

    /** True after [checkAvailability] confirms an ASR service can be created. */
    var isAvailable = false
        private set

    /**
     * Probe on-device ASR availability by attempting to create a recognizer.
     * [SpeechRecognizer.createOnDeviceSpeechRecognizer] is API 31+; attempting it
     * and checking for immediate failure is the compatible way to detect support.
     * Call once at app startup (from any thread).
     */
    fun checkAvailability() {
        mainHandler.post {
            isAvailable = try {
                // Attempt creation; destroy immediately — this is just a probe
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { it.destroy() }
                Log.i(TAG, "On-device ASR: available")
                true
            } catch (e: Exception) {
                Log.w(TAG, "On-device ASR: not available (${e.message}) — transcription disabled")
                false
            }
        }
    }

    /**
     * Begin transcribing.  Clears any prior transcript and starts chaining
     * recognition sessions until [stopSession] is called.
     * Safe to call from any thread.
     */
    fun startSession() {
        if (!isAvailable) {
            Log.w(TAG, "startSession() ignored — on-device ASR not available")
            return
        }
        mainHandler.post {
            synchronized(lock) { accumulated = "" }
            sessionActive = true
            pendingCallback = null
            consecutiveErrors = 0
            createAndListen()
        }
    }

    /**
     * Stop transcribing and deliver the full accumulated transcript to [onComplete].
     * [onComplete] is always called exactly once, either from the final recognition
     * result callback or from a 1.5-second fallback timer.
     * Safe to call from any thread.
     */
    fun stopSession(onComplete: (String) -> Unit) {
        if (!isAvailable) {
            onComplete("")
            return
        }
        sessionActive = false
        pendingCallback = onComplete

        mainHandler.post {
            recognizer?.stopListening()

            // Fallback: if the recognizer doesn't deliver a final result within 1.5s,
            // deliver whatever we have accumulated so we never block the pipeline.
            mainHandler.postDelayed({
                val cb = pendingCallback ?: return@postDelayed
                pendingCallback = null
                val text = synchronized(lock) { accumulated }
                Log.d(TAG, "Fallback delivery — transcript: ${text.take(80)}")
                cb(text)
                destroyRecognizer()
            }, FALLBACK_TIMEOUT_MS)
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    /**
     * Create the recognizer, preferring on-device.
     * After 3 consecutive on-device failures, falls back to the system default
     * recognizer (e.g. Google Speech Services on Samsung) which has wider language
     * support.
     */
    private fun createAndListen() {
        destroyRecognizer()
        recognizer = if (consecutiveErrors < 3) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            Log.i(TAG, "Falling back to system default recognizer after repeated on-device failures")
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer?.setRecognitionListener(recognitionListener)
        startListening()
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // No EXTRA_LANGUAGE — use the device's active language to avoid ERROR_LANGUAGE_UNAVAILABLE
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 200L)
        }
        recognizer?.startListening(intent)
    }

    private fun destroyRecognizer() {
        recognizer?.destroy()
        recognizer = null
    }

    /** Deliver the final transcript to [pendingCallback] and clean up. */
    private fun deliverAndDestroy() {
        mainHandler.removeCallbacksAndMessages(null)   // cancel fallback timer
        val text = synchronized(lock) { accumulated }
        val cb = pendingCallback ?: run { destroyRecognizer(); return }
        pendingCallback = null
        Log.i(TAG, "ASR session complete — transcript (${text.length} chars): ${text.take(120)}")
        cb(text)
        destroyRecognizer()
    }

    private val recognitionListener = object : RecognitionListener {

        override fun onResults(results: Bundle?) {
            consecutiveErrors = 0   // reset on any successful result
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()

            if (!text.isNullOrBlank()) {
                synchronized(lock) {
                    accumulated = if (accumulated.isEmpty()) text else "$accumulated $text"
                }
                Log.d(TAG, "Utterance: \"$text\"  [total: ${accumulated.length} chars]")
            }

            if (sessionActive) {
                mainHandler.postDelayed({ if (sessionActive) startListening() }, 100)
            } else {
                deliverAndDestroy()
            }
        }

        override fun onError(error: Int) {
            val label = errorLabel(error)
            Log.d(TAG, "ASR error: $label (consecutive=$consecutiveErrors)")

            if (!sessionActive) {
                deliverAndDestroy()
                return
            }

            consecutiveErrors++

            val fatal = error in listOf(
                SpeechRecognizer.ERROR_CLIENT,
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
            )
            if (!fatal) {
                // Recreate recognizer after 3 errors to trigger system-default fallback
                val delay = if (consecutiveErrors >= 3) 200L else 400L
                mainHandler.postDelayed({
                    if (sessionActive) createAndListen()
                }, delay)
            }
        }

        private fun errorLabel(e: Int) = when (e) {
            SpeechRecognizer.ERROR_AUDIO               -> "audio"
            SpeechRecognizer.ERROR_CLIENT              -> "client"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "no_permission"
            SpeechRecognizer.ERROR_NETWORK             -> "network"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT     -> "network_timeout"
            SpeechRecognizer.ERROR_NO_MATCH            -> "no_match(silence)"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY     -> "busy"
            SpeechRecognizer.ERROR_SERVER              -> "server"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT      -> "speech_timeout"
            else -> "unknown($e)"
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
