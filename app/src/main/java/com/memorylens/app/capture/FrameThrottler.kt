package com.memorylens.app.capture

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Allows only one frame through at a time. While [busy] is true, subsequent frames
 * are dropped. The pipeline calls [release] when it finishes processing.
 * This prevents queuing more than 1 frame deep regardless of camera frame rate.
 */
class FrameThrottler {

    private val busy = AtomicBoolean(false)

    /** Returns true and marks busy if no frame is currently being processed. */
    fun shouldProcess(): Boolean = busy.compareAndSet(false, true)

    /** Must be called by the inference pipeline when frame processing is complete. */
    fun release() {
        busy.set(false)
    }
}
