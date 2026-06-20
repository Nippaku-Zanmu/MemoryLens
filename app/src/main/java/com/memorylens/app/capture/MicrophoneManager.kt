package com.memorylens.app.capture

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ArrayBlockingQueue

/**
 * Continuously records 16 kHz mono PCM-16 audio in a background coroutine.
 * Maintains a ring buffer of the last 30 seconds of raw PCM chunks.
 * A conversation session is started/stopped via [startSession]/[stopSession];
 * during a session, chunks are also appended to [sessionBuffer].
 */
class MicrophoneManager {

    private val TAG = "MicrophoneManager"

    private val SAMPLE_RATE = 16000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val BUFFER_DURATION_MS = 2000   // 2-second read blocks
    private val RING_BUFFER_MAX_CHUNKS = 15  // 30 seconds / 2 s = 15 chunks

    private val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        .coerceAtLeast(SAMPLE_RATE * 2 * (BUFFER_DURATION_MS / 1000))

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null

    // Rolling ring buffer of the last 30 seconds
    private val ringBuffer = ArrayBlockingQueue<ShortArray>(RING_BUFFER_MAX_CHUNKS)

    // Session accumulator — non-null while a conversation session is active
    private val sessionBuffer = mutableListOf<ShortArray>()
    @Volatile private var sessionActive = false

    /**
     * Begin continuous audio capture on [Dispatchers.IO].
     * Safe to call multiple times — will not double-start.
     */
    fun start(scope: CoroutineScope) {
        if (recordingJob?.isActive == true) return
        recordingJob = scope.launch(Dispatchers.IO) {
            val ar = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            audioRecord = ar
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialise")
                return@launch
            }
            ar.startRecording()
            Log.i(TAG, "Microphone capture started")
            while (isActive) {
                val chunk = ShortArray(SAMPLE_RATE * BUFFER_DURATION_MS / 1000)
                val read = ar.read(chunk, 0, chunk.size)
                if (read > 0) {
                    val trimmed = chunk.copyOf(read)
                    // Maintain ring buffer capacity
                    if (ringBuffer.size >= RING_BUFFER_MAX_CHUNKS) ringBuffer.poll()
                    ringBuffer.offer(trimmed)
                    if (sessionActive) synchronized(sessionBuffer) { sessionBuffer.add(trimmed) }
                }
            }
            ar.stop()
            ar.release()
            Log.i(TAG, "Microphone capture stopped")
        }
    }

    /** Mark the start of a conversation session; clears the session accumulator. */
    fun startSession() {
        synchronized(sessionBuffer) { sessionBuffer.clear() }
        sessionActive = true
        Log.d(TAG, "Conversation session started")
    }

    /**
     * End the conversation session and return the full PCM audio as a contiguous ShortArray.
     * Returns null if no session was active.
     */
    fun stopSession(): ShortArray? {
        if (!sessionActive) return null
        sessionActive = false
        return synchronized(sessionBuffer) {
            if (sessionBuffer.isEmpty()) null
            else {
                val total = sessionBuffer.sumOf { it.size }
                val out = ShortArray(total)
                var pos = 0
                for (chunk in sessionBuffer) {
                    chunk.copyInto(out, pos)
                    pos += chunk.size
                }
                sessionBuffer.clear()
                out
            }
        }
    }

    /** Stop recording and release AudioRecord. */
    fun stop() {
        recordingJob?.cancel()
        audioRecord?.apply { if (state == AudioRecord.STATE_INITIALIZED) stop(); release() }
        audioRecord = null
    }
}
