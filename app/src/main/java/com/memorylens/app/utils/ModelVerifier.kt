package com.memorylens.app.utils

import android.content.Context
import android.util.Log

/**
 * Checks that all required model files exist in assets before the pipeline starts.
 * Returns a list of missing filenames so the caller can surface a clear error dialog
 * instead of crashing with a FileNotFoundException deep inside the inference stack.
 */
object ModelVerifier {

    private val TAG = "ModelVerifier"

    /** Asset-relative paths for every model file the pipeline needs at runtime. */
    private val REQUIRED_MODELS = listOf(
        "models/mediapipe_face_detection.tflite",
        "models/cavaface.tflite"
        // "models/llama3_2_1b_instruct.pte"  — deferred until hackathon
    )

    /**
     * Return the list of required model paths that are absent from the APK assets.
     * An empty list means all files are present and the pipeline can start.
     * Must be called off the main thread (does file I/O via AssetManager).
     */
    fun findMissingModels(context: Context): List<String> {
        return REQUIRED_MODELS.filter { path ->
            val present = assetExists(context, path)
            if (!present) Log.e(TAG, "Missing model asset: $path")
            else Log.i(TAG, "Found model asset: $path")
            !present
        }
    }

    private fun assetExists(context: Context, path: String): Boolean {
        return try {
            context.assets.open(path).use { true }
        } catch (_: Exception) {
            false
        }
    }
}
