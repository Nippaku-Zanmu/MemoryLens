package com.memorylens.app.utils

import java.util.concurrent.ConcurrentHashMap

/**
 * Singleton latency tracker for all pipeline stages.
 * Thread-safe via ConcurrentHashMap; stage names are defined as constants below.
 * Each markStart/markEnd pair records elapsed wall-clock milliseconds for a named stage.
 */
object LatencyTracker {

    const val STAGE_FRAME_CAPTURE = "frame_capture"
    const val STAGE_FACE_DETECTION = "face_detection"
    const val STAGE_FACE_EMBEDDING = "face_embedding"
    const val STAGE_DB_LOOKUP = "db_lookup"
    const val STAGE_LLM_GENERATION = "llm_generation"
    const val STAGE_TTS_SPEAK = "tts_speak"
    const val STAGE_TOTAL_PIPELINE = "total_pipeline"

    private val startTimes = ConcurrentHashMap<String, Long>()
    private val latencies = ConcurrentHashMap<String, Long>()

    /** Records the start timestamp (nanoseconds) for [stage]. */
    fun markStart(stage: String) {
        startTimes[stage] = System.nanoTime()
    }

    /**
     * Computes elapsed time since [markStart] was called for [stage].
     * Stores result in milliseconds. No-op if markStart was never called.
     */
    fun markEnd(stage: String) {
        val start = startTimes[stage] ?: return
        latencies[stage] = (System.nanoTime() - start) / 1_000_000L
    }

    /** Returns the last recorded latency in milliseconds for [stage], or -1 if not yet measured. */
    fun getLatency(stage: String): Long = latencies[stage] ?: -1L

    /** Returns a snapshot of all recorded latencies. */
    fun getAllLatencies(): Map<String, Long> = HashMap(latencies)

    /** Clears all recorded data — call between pipeline runs if desired. */
    fun reset() {
        startTimes.clear()
        latencies.clear()
    }
}
