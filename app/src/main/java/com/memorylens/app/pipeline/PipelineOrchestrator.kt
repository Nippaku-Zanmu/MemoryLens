package com.memorylens.app.pipeline

import android.content.Context
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.ImageProxy
import com.memorylens.app.capture.AudioTranscriber
import com.memorylens.app.inference.CavaFaceRunner
import com.memorylens.app.inference.FaceDetector
import com.memorylens.app.inference.LlmRunner
import com.memorylens.app.storage.AppDatabase
import com.memorylens.app.storage.ConversationEntity
import com.memorylens.app.storage.PersonEntity
import com.memorylens.app.utils.LatencyTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

/**
 * Central coordinator — camera frame → face detection → embedding → DB lookup → cue.
 * Also manages Checkpoint-5 conversation sessions:
 *   • Session starts when a face transitions IDLE → IDENTIFIED.
 *   • A 5-second exit timer fires when no face is found for that duration.
 *   • [onSessionEnded] is invoked with the matched person + duration so the UI
 *     can prompt for a summary and persist it.
 *
 * Cue caching: the LLM/template cue is generated once per unique person match and
 * reused for subsequent frames of the same person — avoiding redundant inference.
 */
class PipelineOrchestrator(private val context: Context) {

    private val TAG = "PipelineOrchestrator"
    private val FACE_EXIT_TIMEOUT_MS = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val faceDetector = FaceDetector(context)
    private val embeddingRunner = CavaFaceRunner(context)
    private val llmRunner = LlmRunner()
    private val db = AppDatabase.getInstance(context)
    private val audioTranscriber = AudioTranscriber(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── ViewModel callbacks ──────────────────────────────────────────────────
    var onStateChanged: ((PipelineState) -> Unit)? = null
    var onPersonMatched: ((PersonEntity, Float) -> Unit)? = null
    var onCueGenerated: ((String, Int) -> Unit)? = null
    var onFaceDetected: ((List<RectF>) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    /** Fired when a conversation session ends. Args: person, duration in seconds. */
    var onSessionEnded: ((PersonEntity, Int) -> Unit)? = null

    // ── Per-frame pipeline state ─────────────────────────────────────────────
    private var currentState: PipelineState = PipelineState.IDLE
    private var processingJob: Job? = null

    // ── Cue cache — reset on person change or IDLE ───────────────────────────
    private var cachedPersonId: Int? = null
    private var cachedCue: String? = null

    // ── Conversation session tracking (Checkpoint 5) ─────────────────────────
    private var sessionPerson: PersonEntity? = null
    private var sessionStartMs: Long = 0L
    private var faceExitJob: Job? = null   // 5-second countdown

    /**
     * Load all ML models on [Dispatchers.IO].
     * Each init() already switches to IO internally, but we also wrap here so
     * the caller (ViewModel) can safely call this from any dispatcher.
     * Throws on unrecoverable failure (e.g. model file missing).
     */
    suspend fun loadModels() = withContext(Dispatchers.IO) {
        faceDetector.init()
        embeddingRunner.init()
        llmRunner.init()
        // Check ASR availability on Main (SpeechRecognizer requirement)
        withContext(Dispatchers.Main) { audioTranscriber.checkAvailability() }
        Log.i(TAG, "All models loaded")
    }

    /**
     * Entry point from CameraManager.  Processes at most one frame at a time;
     * excess frames are dropped and [release] is called immediately.
     * [stage: total_pipeline]
     */
    fun onNewFrame(imageProxy: ImageProxy, release: () -> Unit) {
        if (processingJob?.isActive == true) {
            imageProxy.close()
            release()
            return
        }
        processingJob = scope.launch {
            LatencyTracker.markStart(LatencyTracker.STAGE_TOTAL_PIPELINE)
            try {
                runPipeline(imageProxy)
            } finally {
                LatencyTracker.markEnd(LatencyTracker.STAGE_TOTAL_PIPELINE)
                imageProxy.close()
                release()
            }
        }
    }

    // ── Core pipeline ────────────────────────────────────────────────────────

    private suspend fun runPipeline(imageProxy: ImageProxy) {
        // ── Stage 1: Face Detection ──────────────────────────────────────────
        val detection = faceDetector.detect(imageProxy)

        if (detection == null) {
            onFaceDetected?.invoke(emptyList())
            if (currentState != PipelineState.IDLE && currentState != PipelineState.SPEAKING) {
                startFaceExitTimer()
            }
            return
        }

        // Face found — cancel any pending exit countdown
        cancelFaceExitTimer()
        onFaceDetected?.invoke(listOf(detection.boundingBox))
        setState(PipelineState.FACE_DETECTED)

        // ── Stage 2: Face Embedding ──────────────────────────────────────────
        val embedding = embeddingRunner.embed(detection.croppedBitmap) ?: run {
            // Embedding failed — treat same as no-face so session exit timer can fire
            setState(PipelineState.IDLE)
            if (sessionPerson != null) startFaceExitTimer()
            return
        }

        // ── Stage 3: DB Lookup ───────────────────────────────────────────────
        LatencyTracker.markStart(LatencyTracker.STAGE_DB_LOOKUP)
        val persons = withContext(Dispatchers.IO) { db.personDao().getAllPersons() }
        var bestMatch: PersonEntity? = null
        var bestSim = 0f
        for (person in persons) {
            val enrolled = embeddingRunner.blobToFloatArray(person.embeddingBlob)
            val sim = cosineSimilarity(embedding, enrolled)
            if (sim > bestSim) { bestSim = sim; bestMatch = person }
        }
        LatencyTracker.markEnd(LatencyTracker.STAGE_DB_LOOKUP)

        if (bestMatch == null || bestSim < 0.65f) {
            setState(PipelineState.UNKNOWN_FACE)
            return
        }

        // Person matched — check if it's a new person (session change or first match)
        val isNewPerson = bestMatch.id != cachedPersonId
        setState(PipelineState.IDENTIFIED)
        onPersonMatched?.invoke(bestMatch, bestSim)

        // Notify session start if this is a new match
        if (isNewPerson) {
            commitPreviousSession()   // end any prior session cleanly
            beginSession(bestMatch)
        }

        // ── Stage 4: Cue Generation ──────────────────────────────────────────
        // Only regenerate when the person changes; reuse cached cue otherwise.
        if (isNewPerson || cachedCue == null) {
            val summaries = withContext(Dispatchers.IO) {
                db.conversationDao().getRecentSummaries(bestMatch.id)
            }
            val (cue, tokens) = llmRunner.generate(
                name = bestMatch.name,
                relationship = bestMatch.relationship,
                summaries = summaries.map { it.summaryText }
            )
            cachedPersonId = bestMatch.id
            cachedCue = cue
            onCueGenerated?.invoke(cue, tokens)
        }

        logPipelineRun(bestMatch.name, bestSim)
    }

    // ── Session management ────────────────────────────────────────────────────

    private fun beginSession(person: PersonEntity) {
        sessionPerson = person
        sessionStartMs = System.currentTimeMillis()
        Log.d(TAG, "Session started for ${person.name} — starting audio transcription")
        // SpeechRecognizer requires Main thread
        mainHandler.post { audioTranscriber.startSession() }
    }

    /** Start the 5-second countdown. Commits session if face doesn't return. */
    private fun startFaceExitTimer() {
        if (faceExitJob?.isActive == true) return
        faceExitJob = scope.launch {
            delay(FACE_EXIT_TIMEOUT_MS)
            setState(PipelineState.IDLE)
            commitPreviousSession()
        }
    }

    private fun cancelFaceExitTimer() {
        faceExitJob?.cancel()
        faceExitJob = null
    }

    /**
     * End the current session:
     * 1. Stops audio transcription and receives the transcript via callback.
     * 2. Saves a [ConversationEntity] directly to the database — no dialog, no user input.
     * 3. Fires [onSessionEnded] for UI state reset only.
     */
    private fun commitPreviousSession() {
        val person = sessionPerson ?: return
        val duration = ((System.currentTimeMillis() - sessionStartMs) / 1000).toInt()
        sessionPerson = null
        cachedPersonId = null
        cachedCue = null
        Log.d(TAG, "Session ended for ${person.name}, duration=${duration}s — collecting transcript")

        // Notify ViewModel to reset UI (hide name card, clear cue)
        onSessionEnded?.invoke(person, duration)

        // stopSession() must be posted to Main; the callback fires back on Main
        mainHandler.post {
            audioTranscriber.stopSession { transcript ->
                scope.launch(Dispatchers.IO) {
                    val summary = buildSummary(person.name, transcript, duration)
                    db.conversationDao().insert(
                        ConversationEntity(
                            personId = person.id,
                            summaryText = summary,
                            durationSeconds = duration
                        )
                    )
                    Log.i(TAG, "Saved conversation for ${person.name}: \"$summary\"")
                }
            }
        }
    }

    /**
     * Build the summary stored in the database.
     * Uses the ASR transcript when non-empty; falls back to the presence-log format
     * so there is always a record even in silence.
     */
    private fun buildSummary(name: String, transcript: String, durationSeconds: Int): String {
        val clean = transcript.trim()
        return if (clean.isNotBlank()) {
            clean.take(200)
        } else {
            val dateStr = SimpleDateFormat("MMM d 'at' h:mm a", Locale.US).format(Date())
            "$name was present on $dateStr for $durationSeconds seconds."
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun setState(state: PipelineState) {
        if (currentState != state) {
            currentState = state
            onStateChanged?.invoke(state)
        }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var normA = 0f; var normB = 0f
        val len = minOf(a.size, b.size)
        for (i in 0 until len) {
            dot += a[i] * b[i]; normA += a[i] * a[i]; normB += b[i] * b[i]
        }
        val denom = sqrt((normA * normB).toDouble()).toFloat()
        return if (denom < 1e-8f) 0f else dot / denom
    }

    private fun logPipelineRun(name: String, sim: Float) {
        val l = LatencyTracker.getAllLatencies()
        Log.i(TAG, "[MemoryLens] " +
            "face_det=${l[LatencyTracker.STAGE_FACE_DETECTION]}ms " +
            "emb=${l[LatencyTracker.STAGE_FACE_EMBEDDING]}ms " +
            "db=${l[LatencyTracker.STAGE_DB_LOOKUP]}ms " +
            "llm=${l[LatencyTracker.STAGE_LLM_GENERATION]}ms " +
            "tts=${l[LatencyTracker.STAGE_TTS_SPEAK]}ms " +
            "total=${l[LatencyTracker.STAGE_TOTAL_PIPELINE]}ms " +
            "person=$name sim=${"%.2f".format(sim)}")
    }

    fun shutdown() {
        cancelFaceExitTimer()
        commitPreviousSession()
        faceDetector.close()
        embeddingRunner.close()
        llmRunner.close()
        // stopSession with no-op callback — best-effort cleanup at shutdown
        mainHandler.post { audioTranscriber.stopSession { } }
    }
}
