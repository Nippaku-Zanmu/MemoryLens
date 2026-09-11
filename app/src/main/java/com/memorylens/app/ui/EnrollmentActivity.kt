package com.memorylens.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.memorylens.app.databinding.ActivityEnrollmentBinding
import com.memorylens.app.inference.CavaFaceRunner
import com.memorylens.app.inference.FaceDetector
import com.memorylens.app.storage.AppDatabase
import com.memorylens.app.storage.PersonEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Enrollment screen. Captures 5 face samples from the live camera,
 * averages their embeddings, and stores a [PersonEntity] in the database.
 */
class EnrollmentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEnrollmentBinding
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val faceDetector by lazy { FaceDetector(this) }
    private val embeddingRunner by lazy { CavaFaceRunner(this) }
    private val db by lazy { AppDatabase.getInstance(this) }

    private val capturedEmbeddings = mutableListOf<FloatArray>()
    private val TARGET_SAMPLES = 5
    private var isCapturing = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEnrollmentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }

        lifecycleScope.launch(Dispatchers.IO) {
            faceDetector.init()
            embeddingRunner.init()
        }

        binding.btnCaptureSample.setOnClickListener {
            isCapturing = true
            updateCaptureButton()
        }

        binding.btnConfirmEnroll.isEnabled = false
        binding.btnConfirmEnroll.setOnClickListener { confirmEnrollment() }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.enrollPreviewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(analysisExecutor) { proxy -> processFrame(proxy) }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(imageProxy: ImageProxy) {
        if (!isCapturing) { imageProxy.close(); return }
        // detect() is now suspend (ML Kit uses async API internally)
        lifecycleScope.launch(Dispatchers.IO) {
            val result = faceDetector.detect(imageProxy)
            imageProxy.close()
            if (result != null) {
                val embedding = embeddingRunner.embed(result.croppedBitmap)
                if (embedding != null) {
                    capturedEmbeddings.add(embedding)
                    isCapturing = false
                    withContext(Dispatchers.Main) { onSampleCaptured() }
                }
            }
        }
    }

    private fun onSampleCaptured() {
        val count = capturedEmbeddings.size
        binding.btnCaptureSample.text = getString(com.memorylens.app.R.string.label_capture_samples, count)
        if (count >= TARGET_SAMPLES) {
            binding.btnCaptureSample.isEnabled = false
            binding.btnConfirmEnroll.isEnabled = true
        }
        Toast.makeText(this, "Sample $count/$TARGET_SAMPLES captured", Toast.LENGTH_SHORT).show()
    }

    private fun updateCaptureButton() {
        binding.btnCaptureSample.text = "Capturing…"
    }

    private fun confirmEnrollment() {
        val name = binding.etName.text.toString().trim()
        val relationship = binding.etRelationship.text.toString().trim()
        if (name.isEmpty() || relationship.isEmpty()) {
            Toast.makeText(this, "Please enter name and relationship", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val avgEmbedding = embeddingRunner.averageEmbeddings(capturedEmbeddings)
            val blob = embeddingRunner.floatArrayToBlob(avgEmbedding)
            val person = PersonEntity(name = name, relationship = relationship, embeddingBlob = blob)
            withContext(Dispatchers.IO) { db.personDao().insert(person) }
            Toast.makeText(this@EnrollmentActivity, "$name enrolled successfully", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onDestroy() {
        faceDetector.close()
        embeddingRunner.close()
        analysisExecutor.shutdown()
        super.onDestroy()
    }
}
