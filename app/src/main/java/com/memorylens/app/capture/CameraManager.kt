package com.memorylens.app.capture

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.memorylens.app.utils.LatencyTracker
import java.util.concurrent.Executors

/**
 * Manages CameraX lifecycle binding.
 * Uses a single-thread executor for image analysis so frames are serialised.
 * A [FrameThrottler] prevents the inference pipeline from being flooded.
 */
class CameraManager(private val context: Context) {

    private val TAG = "CameraManager"
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val throttler = FrameThrottler()

    /**
     * Bind camera to [owner]'s lifecycle, display on [previewView], and call
     * [onFrame] with each throttled frame and a [release] lambda that the caller
     * MUST invoke when pipeline processing is complete.
     * Frame rate target: 15 fps via STRATEGY_KEEP_ONLY_LATEST.
     */
    fun start(owner: LifecycleOwner, previewView: PreviewView, onFrame: (ImageProxy, release: () -> Unit) -> Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                LatencyTracker.markStart(LatencyTracker.STAGE_FRAME_CAPTURE)
                if (throttler.shouldProcess()) {
                    onFrame(imageProxy) { throttler.release() }
                } else {
                    imageProxy.close()
                }
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    owner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                Log.i(TAG, "Camera bound to lifecycle")
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }
}
