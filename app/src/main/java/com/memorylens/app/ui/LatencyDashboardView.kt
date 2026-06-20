package com.memorylens.app.ui

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.memorylens.app.R
import com.memorylens.app.pipeline.PipelineState
import com.memorylens.app.storage.PersonEntity
import com.memorylens.app.utils.LatencyTracker

/**
 * Collapsible latency dashboard anchored to the top-right of the screen.
 * Rendered as a monospace table and refreshed every 500 ms by the ViewModel.
 * The LLM row always shows "(template)" to communicate that neural inference
 * is not active on this build.
 * Toggle with the ⏱ icon.
 */
class LatencyDashboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val tvContent: TextView
    private val btnToggle: TextView
    private var expanded = false

    init {
        LayoutInflater.from(context).inflate(R.layout.view_latency_dashboard, this, true)
        tvContent  = findViewById(R.id.tvDashboardContent)
        btnToggle  = findViewById(R.id.btnToggleDashboard)
        tvContent.visibility = View.GONE

        btnToggle.setOnClickListener {
            expanded = !expanded
            tvContent.visibility = if (expanded) View.VISIBLE else View.GONE
            btnToggle.text = if (expanded) "⏱✕" else "⏱"
        }
    }

    /**
     * Refresh the table with current latency data.
     * Called from MainActivity's latency observer every 500 ms.
     */
    fun updateLatencies(
        latencies: Map<String, Long>,
        state: PipelineState?,
        person: PersonEntity?,
        similarity: Float,
        tokenCount: Int
    ) {
        if (!expanded) return
        val sb = StringBuilder()
        sb.appendLine("PIPELINE LATENCY")
        sb.appendLine("─────────────────────────────")
        appendRow(sb, "Frame Capture",  latencies[LatencyTracker.STAGE_FRAME_CAPTURE])
        appendRow(sb, "Face Detection", latencies[LatencyTracker.STAGE_FACE_DETECTION])
        appendRow(sb, "Face Embedding", latencies[LatencyTracker.STAGE_FACE_EMBEDDING])
        appendRow(sb, "DB Lookup",      latencies[LatencyTracker.STAGE_DB_LOOKUP])
        // LLM row — always shows "(template)" in this build
        val llmMs = latencies[LatencyTracker.STAGE_LLM_GENERATION]
        val llmValue = if (llmMs != null && llmMs >= 0) "$llmMs ms (template)" else "— (template)"
        sb.appendLine("%-16s %s".format("LLM Generation", llmValue))
        appendRow(sb, "TTS Speak",      latencies[LatencyTracker.STAGE_TTS_SPEAK])
        sb.appendLine("─────────────────────────────")
        appendRow(sb, "TOTAL PIPELINE", latencies[LatencyTracker.STAGE_TOTAL_PIPELINE])
        sb.appendLine()
        sb.appendLine("State : ${state?.name ?: "—"}")
        if (person != null) {
            sb.appendLine("Person: ${person.name}")
            sb.appendLine("Sim   : ${"%.2f".format(similarity)}")
        }
        if (tokenCount > 0) sb.appendLine("Tokens: $tokenCount")

        tvContent.text = sb.toString()
        tvContent.setTextColor(colorFor(latencies[LatencyTracker.STAGE_TOTAL_PIPELINE] ?: -1L))
    }

    private fun appendRow(sb: StringBuilder, label: String, ms: Long?) {
        val value = if (ms != null && ms >= 0) "$ms ms" else "—"
        sb.appendLine("%-16s %8s".format(label, value))
    }

    private fun colorFor(ms: Long) = when {
        ms < 0    -> Color.WHITE
        ms < 100  -> Color.parseColor("#FF4CAF50")   // green
        ms < 500  -> Color.parseColor("#FFFFC107")   // yellow
        else      -> Color.parseColor("#FFF44336")   // red
    }
}
