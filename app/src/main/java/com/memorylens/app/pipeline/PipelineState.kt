package com.memorylens.app.pipeline

/**
 * Represents every possible state of the real-time recognition pipeline.
 * Drives UI transitions and inference gating throughout the app.
 */
enum class PipelineState {
    /** No face visible in the camera frame. */
    IDLE,

    /** Face bounding box found; embedding computation is in progress. */
    FACE_DETECTED,

    /** Face matched to an enrolled person; cue generation has completed. */
    IDENTIFIED,

    /** TTS is actively speaking a generated cue. */
    SPEAKING,

    /** Face detected but cosine similarity < 0.65 — no enrolled match. */
    UNKNOWN_FACE
}
