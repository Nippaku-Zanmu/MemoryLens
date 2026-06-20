package com.memorylens.app.utils

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID

/**
 * Singleton TTS wrapper backed by Android's on-device TextToSpeech engine.
 * Initialised once in [MemoryLensApp] to survive screen rotation.
 * Speech rate 0.9 and pitch 1.0 per spec.
 */
object TtsManager {

    private const val TAG = "TtsManager"

    private var tts: TextToSpeech? = null
    var isReady: Boolean = false
        private set

    /** Callback invoked when an utterance completes or errors. Receives the utterance ID. */
    var onDone: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /** Initialise TTS engine. Must be called from Application.onCreate(). */
    fun init(context: Context) {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "TTS language not supported")
                    isReady = false
                } else {
                    tts?.setSpeechRate(0.9f)
                    tts?.setPitch(1.0f)
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            LatencyTracker.markEnd(LatencyTracker.STAGE_TTS_SPEAK)
                            utteranceId?.let { onDone?.invoke(it) }
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            utteranceId?.let { onError?.invoke(it) }
                        }
                    })
                    isReady = true
                    Log.i(TAG, "TTS engine ready")
                }
            } else {
                Log.e(TAG, "TTS initialisation failed with status $status")
                isReady = false
            }
        }
    }

    /**
     * Speak [text] aloud. Returns the utterance ID used, or null if TTS not ready.
     * Tracks [LatencyTracker.STAGE_TTS_SPEAK] start time before speaking.
     */
    fun speak(text: String): String? {
        if (!isReady || tts == null) {
            Log.w(TAG, "speak() called but TTS not ready")
            return null
        }
        val utteranceId = UUID.randomUUID().toString()
        LatencyTracker.markStart(LatencyTracker.STAGE_TTS_SPEAK)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        return utteranceId
    }

    /** Stop current TTS playback immediately. */
    fun stop() {
        tts?.stop()
    }

    /** Release TTS resources on app termination. */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        isReady = false
    }
}
