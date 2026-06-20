package com.memorylens.app.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.memorylens.app.utils.LatencyTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Face embedding runner using MobileFaceNet (stored as cavaface.tflite).
 * Same I/O spec as CavaFace from Qualcomm AI Hub:
 *   Input:  [1, 3, 112, 112] float32, CHW, normalised to [-1, 1]
 *   Output: [1, 512] float32 embedding vector
 *
 * All initialisation and model loading happen on [Dispatchers.IO].
 * Inference runs on [Dispatchers.Default] (the pipeline coroutine scope).
 * Delegate strategy: GPU (Mali on A34) → CPU fallback.
 * Stage: [LatencyTracker.STAGE_FACE_EMBEDDING]
 *
 * ---------------------------------------------------------------------------
 * TODO (Hackathon – S25 Ultra): Replace with ExecuTorch .pte runtime.
 * ---------------------------------------------------------------------------
 */
class CavaFaceRunner(private val context: Context) {

    private val TAG = "CavaFaceRunner"
    private val TFLITE_MODEL = "models/cavaface.tflite"
    private val INPUT_SIZE = 112

    // Detected at init() by reading the model's actual tensor shapes.
    // MobileFaceNet = 192-dim NHWC; CavaFace/ArcFace = 512-dim CHW.
    private var embeddingDim = 512        // updated after interpreter loads
    private var inputIsNhwc = true        // updated after interpreter loads

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null

    /**
     * Load cavaface.tflite on [Dispatchers.IO].
     * Tries GPU delegate (Mali) first; CPU is the silent fallback.
     * Logs actual input tensor shape to verify CHW vs HWC at runtime.
     */
    suspend fun init() = withContext(Dispatchers.IO) {
        try {
            val model = loadMappedBuffer(TFLITE_MODEL)
            // Try GPU first, fall back to CPU
            interpreter = buildInterpreterGpu(model) ?: buildInterpreterCpu(model)
            interpreter?.let { interp ->
                val inShape  = interp.getInputTensor(0).shape().toList()
                val outShape = interp.getOutputTensor(0).shape().toList()
                // CHW: [1, 3, 112, 112] — channel first (CavaFace/ArcFace)
                // NHWC: [1, 112, 112, 3] — channel last (MobileFaceNet)
                inputIsNhwc = (inShape.size == 4 && inShape[1] != 3)
                embeddingDim = outShape.last()
                Log.i(TAG, "CavaFaceRunner ready — input=$inShape nhwc=$inputIsNhwc output=$outShape dim=$embeddingDim")
            } ?: Log.e(TAG, "CavaFaceRunner: all delegates failed")
        } catch (e: Exception) {
            Log.e(TAG, "CavaFaceRunner init failed", e)
        }
    }

    private fun buildInterpreterGpu(model: MappedByteBuffer): Interpreter? {
        // Catch Throwable (not just Exception) because GpuDelegate.<init> can throw
        // NoClassDefFoundError when GpuDelegateFactory$Options is absent from the device
        // — this happens on some Mali configurations with tensorflow-lite-gpu 2.14.
        return try {
            val gpu = GpuDelegate()
            val opts = Interpreter.Options().addDelegate(gpu)
            val interp = Interpreter(model, opts)
            gpuDelegate = gpu
            Log.d(TAG, "CavaFaceRunner: using GPU delegate")
            interp
        } catch (t: Throwable) {
            Log.w(TAG, "GPU delegate failed (${t.javaClass.simpleName}: ${t.message}) — falling back to CPU")
            gpuDelegate?.close()
            gpuDelegate = null
            null
        }
    }

    private fun buildInterpreterCpu(model: MappedByteBuffer): Interpreter? {
        return try {
            val opts = Interpreter.Options().setNumThreads(4)
            Log.d(TAG, "CavaFaceRunner: using CPU (4 threads)")
            Interpreter(model, opts)
        } catch (e: Exception) {
            Log.e(TAG, "CPU interpreter also failed: ${e.message}")
            null
        }
    }

    /**
     * Compute a 512-dim embedding for the given 112×112 [face] bitmap.
     * Returns null on failure; never throws.
     * [stage: face_embedding]
     */
    fun embed(face: Bitmap): FloatArray? {
        val interp = interpreter ?: run {
            Log.w(TAG, "embed() called but interpreter is null")
            return null
        }
        val inputBuf = preprocessFace(face) ?: return null

        LatencyTracker.markStart(LatencyTracker.STAGE_FACE_EMBEDDING)
        val outputArray = Array(1) { FloatArray(embeddingDim) }
        val err = runCatching { interp.run(inputBuf, outputArray) }.exceptionOrNull()
        LatencyTracker.markEnd(LatencyTracker.STAGE_FACE_EMBEDDING)

        return if (err != null) {
            Log.e(TAG, "TFLite run failed", err)
            null
        } else {
            outputArray[0]
        }
    }

    /**
     * Convert a 112×112 [Bitmap] to the layout the model expects.
     * Format is detected at init time from the model's input tensor shape:
     *   NHWC [1, 112, 112, 3] — pixel-interleaved, used by MobileFaceNet
     *   CHW  [1, 3, 112, 112] — channel-first, used by CavaFace / ArcFace
     * Normalisation in both cases: (pixel / 255.0 − 0.5) / 0.5 → range [−1, 1].
     */
    private fun preprocessFace(bitmap: Bitmap): ByteBuffer? {
        return try {
            val scaled = if (bitmap.width != INPUT_SIZE || bitmap.height != INPUT_SIZE) {
                Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
            } else bitmap

            val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
            scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

            val buf = ByteBuffer
                .allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4)
                .order(ByteOrder.nativeOrder())

            if (inputIsNhwc) {
                // NHWC: for each pixel write R, G, B sequentially
                for (px in pixels) {
                    buf.putFloat((((px shr 16) and 0xFF) / 255f - 0.5f) / 0.5f)  // R
                    buf.putFloat((((px shr  8) and 0xFF) / 255f - 0.5f) / 0.5f)  // G
                    buf.putFloat((( px         and 0xFF) / 255f - 0.5f) / 0.5f)  // B
                }
            } else {
                // CHW: write full R channel, then full G, then full B
                for (px in pixels) buf.putFloat((((px shr 16) and 0xFF) / 255f - 0.5f) / 0.5f)
                for (px in pixels) buf.putFloat((((px shr  8) and 0xFF) / 255f - 0.5f) / 0.5f)
                for (px in pixels) buf.putFloat((( px         and 0xFF) / 255f - 0.5f) / 0.5f)
            }

            buf.rewind()
            buf
        } catch (e: Exception) {
            Log.e(TAG, "preprocessFace failed", e)
            null
        }
    }

    /** Average multiple embedding vectors into one representative vector. */
    fun averageEmbeddings(embeddings: List<FloatArray>): FloatArray {
        require(embeddings.isNotEmpty()) { "Cannot average empty list" }
        val dim = embeddings[0].size
        val avg = FloatArray(dim)
        for (emb in embeddings) for (i in 0 until dim) avg[i] += emb[i]
        for (i in 0 until dim) avg[i] /= embeddings.size.toFloat()
        return avg
    }

    /** Serialise FloatArray → ByteArray (little-endian, 4 bytes/float). */
    fun floatArrayToBlob(floats: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        floats.forEach { buf.putFloat(it) }
        return buf.array()
    }

    /** Deserialise ByteArray → FloatArray. */
    fun blobToFloatArray(blob: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(blob.size / 4) { buf.float }
    }

    private fun loadMappedBuffer(assetPath: String): MappedByteBuffer {
        val fd = context.assets.openFd(assetPath)
        return FileInputStream(fd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        gpuDelegate?.close()
        gpuDelegate = null
    }
}
