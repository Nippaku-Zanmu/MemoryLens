package com.memorylens.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.memorylens.app.R
import com.memorylens.app.databinding.ActivityMainBinding
import com.memorylens.app.pipeline.PipelineState
import com.memorylens.app.utils.ModelVerifier
import com.memorylens.app.utils.TtsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Entry point of the app. Hosts full-screen camera preview, face overlay,
 * cue text card, speak button, and latency dashboard.
 * All inference is delegated to [MainViewModel].
 *
 * Session DB saves are now fully automatic via AudioTranscriber in PipelineOrchestrator.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val cameraGranted = results[Manifest.permission.CAMERA] == true
        val audioGranted  = results[Manifest.permission.RECORD_AUDIO] == true
        when {
            cameraGranted && audioGranted -> onPermissionsGranted()
            !cameraGranted -> showPermissionRationale(getString(R.string.permission_camera_rationale))
            !audioGranted  -> showPermissionRationale(getString(R.string.permission_audio_rationale))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        checkAndRequestPermissions()
        setupUI()
        observeViewModel()
    }

    private fun checkAndRequestPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) onPermissionsGranted() else permissionLauncher.launch(missing.toTypedArray())
    }

    /**
     * Called once all permissions are granted.
     * Runs asset verification first on IO; shows a blocking dialog if any model file is
     * missing so we surface a clear message instead of crashing deep in init.
     */
    private fun onPermissionsGranted() {
        viewModel.startCamera(this, binding.previewView)
        // AudioTranscriber uses SpeechRecognizer directly — no separate AudioRecord needed.
        lifecycleScope.launch(Dispatchers.IO) {
            val missing = ModelVerifier.findMissingModels(this@MainActivity)
            withContext(Dispatchers.Main) {
                if (missing.isEmpty()) viewModel.loadModels()
                else showMissingModelsDialog(missing)
            }
        }
    }

    private fun showMissingModelsDialog(missing: List<String>) {
        val list = missing.joinToString("\n• ", prefix = "• ")
        AlertDialog.Builder(this)
            .setTitle("Model files missing")
            .setMessage("The following files are required but not found in assets/models/:\n\n$list\n\nSee MODELS_README.md for download instructions.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun setupUI() {
        binding.btnSpeak.setOnClickListener {
            when (viewModel.pipelineState.value) {
                PipelineState.IDENTIFIED -> {
                    val cue = viewModel.currentCue.value ?: return@setOnClickListener
                    TtsManager.speak(cue)
                    viewModel.onSpeakPressed()
                }
                PipelineState.SPEAKING -> {
                    TtsManager.stop()
                    viewModel.onSpeakPressed()
                }
                else -> { /* button disabled, ignore */ }
            }
        }

        // TTS done → return button to READY state
        TtsManager.onDone = {
            runOnUiThread {
                if (viewModel.pipelineState.value == PipelineState.SPEAKING) {
                    viewModel.onSpeakPressed()   // back to IDENTIFIED
                }
            }
        }

        binding.btnMenu.setOnClickListener { showMenu(it) }
    }

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menuInflater.inflate(R.menu.main_menu, menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.menu_enroll -> {
                        startActivity(Intent(this@MainActivity, EnrollmentActivity::class.java)); true
                    }
                    R.id.menu_enrolled_list -> {
                        startActivity(Intent(this@MainActivity, EnrolledPersonsActivity::class.java)); true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun observeViewModel() {
        viewModel.pipelineState.observe(this) { updateStateUI(it) }

        viewModel.matchedPerson.observe(this) { person ->
            if (person != null) {
                binding.cardPersonName.visibility = View.VISIBLE
                binding.tvPersonName.text = person.name
                binding.tvPersonRelationship.text = person.relationship
            } else {
                binding.cardPersonName.visibility = View.GONE
            }
        }

        viewModel.currentCue.observe(this) { cue ->
            binding.tvCueText.text = when {
                cue != null -> cue
                viewModel.pipelineState.value == PipelineState.IDENTIFIED ->
                    getString(R.string.label_face_recognised)
                else -> getString(R.string.label_listening)
            }
        }

        viewModel.faceDetections.observe(this) { rects ->
            binding.faceOverlayView.updateDetections(
                rects,
                binding.previewView.width,
                binding.previewView.height,
                unknown = viewModel.pipelineState.value == PipelineState.UNKNOWN_FACE
            )
        }

        viewModel.latencies.observe(this) { map ->
            binding.latencyDashboard.updateLatencies(
                map,
                viewModel.pipelineState.value,
                viewModel.matchedPerson.value,
                viewModel.lastSimilarity,
                viewModel.lastTokenCount
            )
        }

        viewModel.errorMessage.observe(this) { msg ->
            if (!msg.isNullOrBlank()) Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }

        // Session ended — DB save is automatic (AudioTranscriber → PipelineOrchestrator).
        // This observer only resets the UI state (hiding name card, clearing cue).
        viewModel.sessionEnded.observe(this) { person ->
            person ?: return@observe
            viewModel.consumeSessionEndedEvent()
            Log.d("MainActivity", "Session ended for ${person.name} — transcript being saved automatically")
        }
    }

    private fun updateStateUI(state: PipelineState) {
        val label: String
        val color: Int
        when (state) {
            PipelineState.IDLE -> {
                label = "IDLE"; color = R.color.state_idle
                binding.btnSpeak.isEnabled = false
                binding.btnSpeak.backgroundTintList = ContextCompat.getColorStateList(this, R.color.btn_disabled)
                binding.tvCueText.text = getString(R.string.label_listening)
                binding.cardPersonName.visibility = View.GONE
            }
            PipelineState.FACE_DETECTED -> {
                label = "DETECTED"; color = R.color.state_detected
                binding.btnSpeak.isEnabled = false
                binding.btnSpeak.backgroundTintList = ContextCompat.getColorStateList(this, R.color.btn_disabled)
            }
            PipelineState.IDENTIFIED -> {
                label = "IDENTIFIED"; color = R.color.state_identified
                binding.btnSpeak.isEnabled = true
                binding.btnSpeak.backgroundTintList = ContextCompat.getColorStateList(this, R.color.btn_ready)
                if (viewModel.currentCue.value == null) {
                    binding.tvCueText.text = getString(R.string.label_face_recognised)
                }
            }
            PipelineState.SPEAKING -> {
                label = "SPEAKING"; color = R.color.state_speaking
                binding.btnSpeak.isEnabled = true
                binding.btnSpeak.backgroundTintList = ContextCompat.getColorStateList(this, R.color.btn_speaking)
            }
            PipelineState.UNKNOWN_FACE -> {
                label = "UNKNOWN"; color = R.color.state_unknown
                binding.btnSpeak.isEnabled = false
                binding.btnSpeak.backgroundTintList = ContextCompat.getColorStateList(this, R.color.btn_disabled)
                binding.tvCueText.text = getString(R.string.label_unknown_face)
            }
        }
        binding.tvPipelineState.text = label
        binding.tvPipelineState.setTextColor(ContextCompat.getColor(this, color))
    }

    private fun showPermissionRationale(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage(message)
            .setPositiveButton("Grant") { _, _ -> checkAndRequestPermissions() }
            .setNegativeButton("Exit") { _, _ -> finish() }
            .show()
    }

    override fun onDestroy() {
        viewModel.shutdown()
        super.onDestroy()
    }
}
