package com.retroid.translator.diagnostics

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.retroid.translator.MainActivity
import com.retroid.translator.databinding.FragmentDiagnosticsBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local diagnostics journal viewer - docs/specs/engineering-systems-pitch.md
 * system #5. Reads [DiagnosticsStore] directly (no ViewModel, matching this
 * screen's sibling settings fragments' simplicity), shows the most recent
 * events newest-first, and offers a "Share diagnostic log…" export via
 * [FileProvider] plus a "Clear log" wipe.
 *
 * Everything shown here is already reviewed, at the source, to be
 * technical/metadata-only (error types, voice/language identifiers, URLs,
 * exception messages) - never translated or recognized speech content; the
 * on-screen disclaimer states this plainly rather than leaving it implicit.
 */
class DiagnosticsFragment : Fragment() {

    private var _binding: FragmentDiagnosticsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDiagnosticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnShareDiagnosticsLog.setOnClickListener { shareLog() }
        binding.btnClearDiagnosticsLog.setOnClickListener { clearLog() }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        (activity as? AppCompatActivity)?.supportActionBar?.title = "Diagnostics"
    }

    private fun store(): DiagnosticsStore? = (activity as? MainActivity)?.app?.diagnostics

    private fun refresh() {
        val store = store() ?: return
        val events = store.recent(50)
        val total = store.count()
        binding.textDiagnosticsSummary.text = if (total > events.size) {
            "Showing $total most recent of the last $total events (capped at ${DiagnosticsStore.MAX_EVENTS})"
        } else {
            "$total event${if (total == 1) "" else "s"} recorded"
        }
        binding.textNoEvents.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
        binding.listEvents.removeAllViews()
        for (event in events) {
            binding.listEvents.addView(buildEventRow(event))
        }
    }

    private fun buildEventRow(event: DiagnosticsStore.Event): View {
        val card = CardView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
            radius = dp(8).toFloat()
            cardElevation = dp(1).toFloat()
        }
        val column = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val header = TextView(requireContext()).apply {
            textSize = 13f
            text = "${levelLabel(event.level)} · ${event.tag} · ${formatTimestamp(event.tsEpochMs)}"
        }
        val message = TextView(requireContext()).apply {
            textSize = 14f
            setPadding(0, dp(2), 0, 0)
            text = event.message
        }
        column.addView(header)
        column.addView(message)
        if (!event.stackTrace.isNullOrBlank()) {
            column.addView(
                TextView(requireContext()).apply {
                    textSize = 11f
                    alpha = 0.7f
                    setPadding(0, dp(4), 0, 0)
                    typeface = android.graphics.Typeface.MONOSPACE
                    text = event.stackTrace
                }
            )
        }
        card.addView(column)
        return card
    }

    private fun levelLabel(level: String): String = when (level) {
        "F" -> "FATAL"
        "E" -> "ERROR"
        "W" -> "WARN"
        else -> level
    }

    private fun formatTimestamp(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(epochMs))

    private fun shareLog() {
        val store = store() ?: return
        val events = store.recent(DiagnosticsStore.MAX_EVENTS)
        if (events.isEmpty()) {
            Toast.makeText(requireContext(), "No diagnostic events to share", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val text = buildString {
                appendLine("RetroidTranslator diagnostics log - ${events.size} event(s), newest first")
                appendLine("Technical/metadata only - never translated or recognized speech content.")
                appendLine()
                for (event in events) {
                    appendLine("[${formatTimestamp(event.tsEpochMs)}] ${levelLabel(event.level)}/${event.tag}: ${event.message}")
                    if (!event.stackTrace.isNullOrBlank()) {
                        appendLine(event.stackTrace)
                    }
                    appendLine()
                }
            }
            val dir = File(requireContext().cacheDir, "diagnostics_export").apply { mkdirs() }
            val file = File(dir, "retroid_diagnostics.txt")
            file.writeText(text)
            val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.diagnostics.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share diagnostic log"))
        } catch (e: Exception) {
            Diag.e("DiagnosticsFragment", "Failed to prepare diagnostics share", e)
            Toast.makeText(requireContext(), "Couldn't prepare the log for sharing", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearLog() {
        val store = store() ?: return
        store.clear()
        Toast.makeText(requireContext(), "Diagnostics log cleared", Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
