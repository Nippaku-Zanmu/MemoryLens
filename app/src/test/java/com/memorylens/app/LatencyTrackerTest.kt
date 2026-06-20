package com.memorylens.app

import com.memorylens.app.utils.LatencyTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [LatencyTracker]. Verifies correct start/end timing and map retrieval.
 */
class LatencyTrackerTest {

    @Before
    fun setUp() {
        LatencyTracker.reset()
    }

    @Test
    fun `getLatency returns -1 before any measurement`() {
        assertEquals(-1L, LatencyTracker.getLatency(LatencyTracker.STAGE_FACE_DETECTION))
    }

    @Test
    fun `markStart and markEnd records positive latency`() {
        LatencyTracker.markStart(LatencyTracker.STAGE_FACE_DETECTION)
        Thread.sleep(10)
        LatencyTracker.markEnd(LatencyTracker.STAGE_FACE_DETECTION)
        val latency = LatencyTracker.getLatency(LatencyTracker.STAGE_FACE_DETECTION)
        assertTrue("Expected latency >= 10ms but was $latency", latency >= 10L)
    }

    @Test
    fun `getAllLatencies returns all recorded stages`() {
        LatencyTracker.markStart(LatencyTracker.STAGE_DB_LOOKUP)
        LatencyTracker.markEnd(LatencyTracker.STAGE_DB_LOOKUP)
        LatencyTracker.markStart(LatencyTracker.STAGE_TTS_SPEAK)
        LatencyTracker.markEnd(LatencyTracker.STAGE_TTS_SPEAK)
        val all = LatencyTracker.getAllLatencies()
        assertTrue(all.containsKey(LatencyTracker.STAGE_DB_LOOKUP))
        assertTrue(all.containsKey(LatencyTracker.STAGE_TTS_SPEAK))
    }

    @Test
    fun `reset clears all latency data`() {
        LatencyTracker.markStart(LatencyTracker.STAGE_LLM_GENERATION)
        LatencyTracker.markEnd(LatencyTracker.STAGE_LLM_GENERATION)
        LatencyTracker.reset()
        assertEquals(-1L, LatencyTracker.getLatency(LatencyTracker.STAGE_LLM_GENERATION))
        assertTrue(LatencyTracker.getAllLatencies().isEmpty())
    }
}
