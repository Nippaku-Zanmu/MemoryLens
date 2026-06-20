package com.memorylens.app.inference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector as MLKitFaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.memorylens.app.utils.LatencyTracker
import kotlinx.coroutines.tasks.await

/**
 * Face detector backed by Google ML Kit (on-device, no model file required, works offline).
 *
 * Why ML Kit instead of MediaPipe Tasks Vision:
 *   The Qualcomm AI Hub float32 model lacks the NormalizationOptions metadata that
 *   MediaPipe Tasks requires. ML Kit has no such requirement and runs fully on-device.
 *
 * PERFORMANCE_MODE_FAST + minFaceSize 0.10 gives good accuracy at 15 fps on A34.
 * Stage: [LatencyTracker.STAGE_FACE_DETECTION]
 *
 * ---------------------------------------------------------------------------
 * TODO (Hackathon – S25 Ultra): If switching back to MediaPipe Tasks, use a model
 * that embeds NormalizationOptions metadata (e.g. the official MediaPipe model from
 * https://storage.googleapis.com/mediapipe-models/face_detector/).
 * The mediapipe_face_detection.tflite asset is kept in assets/models/ for reference.
 * ---------------------------------------------------------------------------
 */
@Suppress("UNUSED_PARAMETER")
class FaceDetector(context: Context) {

    data class DetectionResult(
        val boundingBox: RectF,     // normalised [0, 1] coordinates
        val croppedBitmap: Bitmap   // 112×112 face crop, ready for embedding
    )

    private val TAG = "FaceDetector"

    private val mlKitOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setMinFaceSize(0.10f)
        .build()

    // MLKit detector is thread-safe and reusable
    private val detector: MLKitFaceDetector = FaceDetection.getClient(mlKitOptions)

    /**
     * No-op: ML Kit requires no explicit initialisation.
     * Kept to match the interface expected by [PipelineOrchestrator].
     */
    suspend fun init() {
        Log.i(TAG, "ML Kit FaceDetector ready (no init required)")
    }

    /**
     * Detect the most prominent face in [imageProxy].
     * Returns a [DetectionResult] with normalised bounding box and 112×112 crop,
     * or null when no face is found.
     * [stage: face_detection]
     */
    suspend fun detect(imageProxy: ImageProxy): DetectionResult? {
        // Use CameraX's built-in toBitmap() (handles RGBA_8888 natively)
        val bitmap = imageProxy.toBitmap()
        val rotation = imageProxy.imageInfo.rotationDegrees

        LatencyTracker.markStart(LatencyTracker.STAGE_FACE_DETECTION)
        val faces = runCatching {
            val input = InputImage.fromBitmap(bitmap, rotation)
            detector.process(input).await()
        }.getOrNull()
        LatencyTracker.markEnd(LatencyTracker.STAGE_FACE_DETECTION)

        if (faces.isNullOrEmpty()) return null

        // Pick the face with the largest bounding box area (closest / most prominent)
        val best = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
            ?: return null

        val bb: Rect = best.boundingBox
        val imgW = bitmap.width.toFloat()
        val imgH = bitmap.height.toFloat()
        val normBox = RectF(
            bb.left  / imgW,
            bb.top   / imgH,
            bb.right / imgW,
            bb.bottom / imgH
        )

        val crop = cropAndScale(bitmap, bb) ?: return null
        return DetectionResult(normBox, crop)
    }

    private fun cropAndScale(source: Bitmap, rect: Rect): Bitmap? {
        return try {
            val left   = rect.left.coerceAtLeast(0)
            val top    = rect.top.coerceAtLeast(0)
            val right  = rect.right.coerceAtMost(source.width)
            val bottom = rect.bottom.coerceAtMost(source.height)
            val w = (right - left).coerceAtLeast(1)
            val h = (bottom - top).coerceAtLeast(1)
            val crop = Bitmap.createBitmap(source, left, top, w, h)
            Bitmap.createScaledBitmap(crop, 112, 112, true)
        } catch (e: Exception) {
            Log.e(TAG, "cropAndScale failed", e)
            null
        }
    }

    fun close() {
        detector.close()
    }
}
