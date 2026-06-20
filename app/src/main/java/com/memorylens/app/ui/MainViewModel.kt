package com.memorylens.app.ui

import android.app.Application
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.memorylens.app.capture.CameraManager
import com.memorylens.app.capture.MicrophoneManager
import com.memorylens.app.pipeline.PipelineOrchestrator
import com.memorylens.app.pipeline.PipelineState
import com.memorylens.app.storage.PersonEntity
import com.memorylens.app.utils.LatencyTracker
import com.memorylens.app.storage.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for MainActivity.
 * Owns CameraManager, MicrophoneManager, and PipelineOrchestrator.
 * Survives screen rotation; all heavy work lives on Dispatchers.Default/IO.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "MainViewModel"

    private val cameraManager = CameraManager(application)
    val micManager = MicrophoneManager()
    val orchestrator = PipelineOrchestrator(application)

    // ── Observed state ───────────────────────────────────────────────────────

    private val _pipelineState = MutableLiveData<PipelineState>(PipelineState.IDLE)
    val pipelineState: LiveData<PipelineState> = _pipelineState

    private val _matchedPerson = MutableLiveData<PersonEntity?>(null)
    val matchedPerson: LiveData<PersonEntity?> = _matchedPerson

    private val _currentCue = MutableLiveData<String?>(null)
    val currentCue: LiveData<String?> = _currentCue

    private val _faceDetections = MutableLiveData<List<android.graphics.RectF>>(emptyList())
    val faceDetections: LiveData<List<android.graphics.RectF>> = _faceDetections

    private val _latencies = MutableLiveData<Map<String, Long>>(emptyMap())
    val latencies: LiveData<Map<String, Long>> = _latencies

    private val _errorMessage = MutableLiveData<String?>(null)
    val errorMessage: LiveData<String?> = _errorMessage

    /** Fires when a session ends — for UI reset only. DB save happens in Orchestrator. */
    private val _sessionEnded = MutableLiveData<PersonEntity?>(null)
    val sessionEnded: LiveData<PersonEntity?> = _sessionEnded

    var lastSimilarity: Float = 0f
    var lastTokenCount: Int = 0

    private var latencyPollingJob: Job? = null

    private val db = AppDatabase.getInstance(application)

    init {
        startLatencyPolling()
        wireOrchestratorCallbacks()
        dumpDbToLogcat()
    }

    private fun startLatencyPolling() {
        latencyPollingJob = viewModelScope.launch {
            while (isActive) {
                _latencies.postValue(LatencyTracker.getAllLatencies())
                delay(500)
            }
        }
    }

    private fun wireOrchestratorCallbacks() {
        orchestrator.onStateChanged = { state -> _pipelineState.postValue(state) }
        orchestrator.onPersonMatched = { person, sim ->
            _matchedPerson.postValue(person)
            lastSimilarity = sim
        }
        orchestrator.onCueGenerated = { cue, tokens ->
            _currentCue.postValue(cue)
            lastTokenCount = tokens
        }
        orchestrator.onFaceDetected = { rects -> _faceDetections.postValue(rects) }
        orchestrator.onError = { msg -> _errorMessage.postValue(msg) }
        // Session ended — DB save happens inside PipelineOrchestrator via AudioTranscriber.
        // Here we only reset the UI: hide the person name card and clear the cue.
        orchestrator.onSessionEnded = { person, _ ->
            _sessionEnded.postValue(person)
            _matchedPerson.postValue(null)
            _currentCue.postValue(null)
        }
    }

    /** Bind CameraX to [owner]'s lifecycle. Stage: frame_capture. */
    fun startCamera(owner: LifecycleOwner, previewView: PreviewView) {
        cameraManager.start(owner, previewView) { imageProxy, release ->
            LatencyTracker.markEnd(LatencyTracker.STAGE_FRAME_CAPTURE)
            orchestrator.onNewFrame(imageProxy, release)
            LatencyTracker.markStart(LatencyTracker.STAGE_FRAME_CAPTURE)
        }
    }

    /** Start continuous background microphone capture. */
    fun startMicrophone() {
        micManager.start(viewModelScope)
    }

    /** Load all ML models on [Dispatchers.IO]. Posts an error message on failure. */
    fun loadModels() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                orchestrator.loadModels()
            } catch (e: Exception) {
                Log.e(TAG, "Model load error", e)
                _errorMessage.postValue(
                    getApplication<Application>()
                        .getString(com.memorylens.app.R.string.error_model_not_found)
                )
            }
        }
    }

    /** Called by the speak button. Toggles IDENTIFIED ↔ SPEAKING. */
    fun onSpeakPressed() {
        _pipelineState.value = if (_pipelineState.value == PipelineState.SPEAKING) {
            PipelineState.IDENTIFIED
        } else {
            PipelineState.SPEAKING
        }
    }

    /** Acknowledge the session-ended event so it is not replayed on re-observation. */
    fun consumeSessionEndedEvent() { _sessionEnded.value = null }

    /**
     * Dumps all enrolled persons and their conversations to Logcat (tag: MemoryLensDB).
     * Called once at init so we can verify save/load without needing sqlite3 on device.
     */
    private fun dumpDbToLogcat() {
        viewModelScope.launch(Dispatchers.IO) {
            val persons = db.personDao().getAllPersons()
            if (persons.isEmpty()) {
                Log.i("MemoryLensDB", "persons table: EMPTY (enroll someone first)")
            } else {
                persons.forEach { p ->
                    val blobDim = p.embeddingBlob.size / 4
                    Log.i("MemoryLensDB", "PERSON id=${p.id} name='${p.name}' rel='${p.relationship}' embeddingDim=$blobDim")
                    val convos = db.conversationDao().getAllForPerson(p.id)
                    if (convos.isEmpty()) {
                        Log.i("MemoryLensDB", "  └─ conversations: NONE")
                    } else {
                        convos.forEach { c ->
                            Log.i("MemoryLensDB", "  └─ convo id=${c.id} dur=${c.durationSeconds}s summary='${c.summaryText}'")
                        }
                    }
                }
            }
        }
    }

    fun shutdown() {
        latencyPollingJob?.cancel()
        micManager.stop()
        orchestrator.shutdown()
    }
}
