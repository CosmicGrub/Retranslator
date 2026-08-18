package com.retroid.translator.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import com.retroid.translator.MainActivity
import com.retroid.translator.databinding.FragmentManagePacksBinding
import com.retroid.translator.engine.TranslationEngine
import com.retroid.translator.packs.BulkDownloadCoordinator
import com.retroid.translator.packs.LanguagePackPreferences
import com.retroid.translator.packs.PackCategory
import com.retroid.translator.packs.PackDescriptor
import com.retroid.translator.packs.PackInventory
import com.retroid.translator.packs.PackStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Manage language packs" - docs/specs/galaxy-tab-s9fe-adaptation.md. Every
 * pack across all three catalogs (translation/voice-input/natural-voice),
 * with per-pack download/delete, a "Download all remaining" bulk action
 * (reuses [BulkDownloadCoordinator], the same engine that drives the
 * first-launch/first-Wi-Fi auto-download prompt in [MainActivity]), and a
 * "Check for updates" action.
 *
 * "Check for updates" is honestly scoped: none of the three upstream
 * catalogs (ML Kit's translation models, Vosk's model list, Piper's voice
 * releases) are pinned to a live version feed this app polls - the URLs in
 * [com.retroid.translator.engine.VoskModelCatalog] /
 * [com.retroid.translator.engine.PiperVoiceCatalog] are specific, versioned
 * file names, not a "latest" alias. "Check for updates" therefore means
 * "re-verify every supposedly-downloaded pack is actually present and
 * intact" (catches a pack that was deleted outside the app, or one whose
 * download/extraction was interrupted and left incomplete - the exact
 * completeness bug [com.retroid.translator.engine.PiperTtsEngine] already
 * guards against for Piper voices) - not "check whether a newer release
 * exists upstream". The UI says this plainly rather than implying a live
 * version check that isn't actually happening.
 */
class ManagePacksFragment : Fragment() {

    private var _binding: FragmentManagePacksBinding? = null
    private val binding get() = _binding!!

    private var downloadedTranslationCodes: Set<String> = emptySet()
    private var bulkCoordinator: BulkDownloadCoordinator? = null
    private var autoStartConsumed = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentManagePacksBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnDownloadAllRemaining.setOnClickListener { startBulkDownload() }
        binding.btnCancelBulkDownload.setOnClickListener { bulkCoordinator?.cancel() }
        binding.btnCheckForUpdates.setOnClickListener { checkForUpdates() }
        setUpCellularToggle()
        refresh()
    }

    /**
     * Fold5 edition only (docs/specs/fold5-adaptation.md §13/§14, explicit
     * user request): the real, user-adjustable "allow cellular data"
     * setting, deliberately placed here - Settings -> Manage language packs
     * - and nowhere on the Translate tab or any other forefront screen.
     * Backed by [LanguagePackPreferences.allowCellularDownloads]; every real
     * download/translate call site already reads that same preference
     * (see TranslationEngine.kt, TranslateFragment.kt, PiperTtsEngine.kt,
     * BulkDownloadCoordinator.kt), so flipping this switch takes effect
     * immediately on the next download/translate, no restart needed.
     */
    private fun setUpCellularToggle() {
        val ctx = context ?: return
        binding.cardCellularToggle.visibility = View.VISIBLE
        // setChecked below would otherwise re-fire this listener with the
        // exact value it was just set to - harmless (writes the same value
        // back) but worth avoiding the redundant SharedPreferences write.
        binding.switchAllowCellular.setOnCheckedChangeListener(null)
        binding.switchAllowCellular.isChecked = LanguagePackPreferences.allowCellularDownloads(ctx)
        binding.switchAllowCellular.setOnCheckedChangeListener { _, isChecked ->
            LanguagePackPreferences.setAllowCellularDownloads(ctx, isChecked)
            binding.textCellularToggleSubtitle.text = if (isChecked) {
                "Translation, voice-input, and natural-voice downloads may use cellular data, not just Wi-Fi."
            } else {
                "Translation, voice-input, and natural-voice downloads require Wi-Fi, same as the universal build."
            }
            renderAll()
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? AppCompatActivity)?.supportActionBar?.title = "Language packs"
    }

    // -------------------------------------------------------------------
    // Status refresh
    // -------------------------------------------------------------------

    private fun refresh() {
        val ctx = context ?: return
        PackStatus.fetchDownloadedTranslationCodes { codes ->
            if (_binding == null) return@fetchDownloadedTranslationCodes
            downloadedTranslationCodes = codes
            renderAll()
        }
        val lastCheck = LanguagePackPreferences.lastUpdateCheckAt(ctx)
        binding.textUpdateCheckStatus.text = if (lastCheck == 0L) {
            "Never checked. Re-verifies downloaded packs are present and intact - these are pinned, versioned downloads, not a live \"latest version\" feed."
        } else {
            "Last checked " + SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(lastCheck))
        }
    }

    private fun renderAll() {
        val app = mainActivity()?.app ?: return
        val ctx = context ?: return
        val all = PackInventory.all()
        val downloaded = all.count { PackStatus.isDownloaded(app, it, downloadedTranslationCodes) }
        val remaining = all.size - downloaded
        val remainingSizeMiB = all.filter { !PackStatus.isDownloaded(app, it, downloadedTranslationCodes) }.sumOf { it.approxSizeMiB }
        // Fold5 edition: reflects the real Settings toggle above, instead of
        // unconditionally claiming "Wi-Fi" like the universal build (where
        // it's always true, since that build has no cellular option at all).
        val networkNote = if (LanguagePackPreferences.allowCellularDownloads(ctx)) "" else ", Wi-Fi"
        binding.textPacksSummary.text =
            "$downloaded of ${all.size} packs downloaded. $remaining remaining (~${remainingSizeMiB}MB$networkNote)."
        binding.btnDownloadAllRemaining.isEnabled = remaining > 0 && bulkCoordinator == null
        binding.btnDownloadAllRemaining.text = if (remaining == 0) "All packs downloaded"
            else if (networkNote.isEmpty()) "Download all remaining packs"
            else "Download all remaining packs (Wi-Fi)"

        renderSection(binding.listTranslation, PackCategory.TRANSLATION)
        renderSection(binding.listVoiceInput, PackCategory.VOICE_INPUT)
        renderSection(binding.listNaturalVoice, PackCategory.NATURAL_VOICE)

        // Reached via MainActivity's first-launch/first-Wi-Fi bulk-download
        // prompt (newInstanceAutoStart) - kick off exactly once, only after
        // the first real status snapshot confirms there's something to do.
        if (!autoStartConsumed && arguments?.getBoolean(ARG_AUTO_START) == true) {
            autoStartConsumed = true
            if (remaining > 0 && bulkCoordinator == null) startBulkDownload()
        }
    }

    private fun renderSection(container: LinearLayout, category: PackCategory) {
        val app = mainActivity()?.app ?: return
        container.removeAllViews()
        for (item in PackInventory.byCategory(category)) {
            val downloaded = PackStatus.isDownloaded(app, item, downloadedTranslationCodes)
            container.addView(buildPackRow(item, downloaded))
        }
    }

    // -------------------------------------------------------------------
    // Per-pack row
    // -------------------------------------------------------------------

    private fun buildPackRow(item: PackDescriptor, downloaded: Boolean): View {
        val card = CardView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
            radius = dp(8).toFloat()
            cardElevation = dp(1).toFloat()
        }
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val textCol = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val title = TextView(requireContext()).apply { text = item.displayName; textSize = 15f }
        val subtitle = TextView(requireContext()).apply {
            textSize = 12f
            text = if (downloaded) "Downloaded" else "Not downloaded (~${item.approxSizeMiB}MB)"
        }
        textCol.addView(title)
        textCol.addView(subtitle)
        val btn = Button(requireContext()).apply {
            text = if (downloaded) "Delete" else "Download"
        }
        btn.setOnClickListener {
            if (downloaded) deleteSingle(item, subtitle, btn) else downloadPack(item, subtitle, btn)
        }
        row.addView(textCol)
        row.addView(btn)
        card.addView(row)
        return card
    }

    private fun downloadPack(item: PackDescriptor, subtitle: TextView, btn: Button) {
        val app = mainActivity()?.app ?: return
        val ctx = context ?: return
        btn.isEnabled = false
        subtitle.text = "Downloading… 0%"
        val coordinator = BulkDownloadCoordinator(ctx.applicationContext, app)
        coordinator.downloadSingle(
            item,
            onProgress = { pct -> if (_binding != null) subtitle.text = "Downloading… $pct%" }
        ) { success, error ->
            if (_binding == null) return@downloadSingle
            if (success) {
                Toast.makeText(requireContext(), "${item.displayName} downloaded", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Download failed: $error", Toast.LENGTH_LONG).show()
            }
            refresh()
        }
    }

    private fun deleteSingle(item: PackDescriptor, subtitle: TextView, btn: Button) {
        val app = mainActivity()?.app ?: return
        btn.isEnabled = false
        subtitle.text = "Deleting…"
        when (item) {
            is PackDescriptor.Translation -> TranslationEngine.deleteModel(item.mlKitCode) { _, _ ->
                if (_binding != null) refresh()
            }
            is PackDescriptor.VoiceInput -> {
                app.vosk.deleteModel(item.info.mlKitCode)
                refresh()
            }
            is PackDescriptor.NaturalVoice -> {
                app.piper.deleteVoice(item.info)
                refresh()
            }
        }
    }

    // -------------------------------------------------------------------
    // Bulk download
    // -------------------------------------------------------------------

    private fun startBulkDownload() {
        val app = mainActivity()?.app ?: return
        val ctx = context ?: return
        val remaining = PackInventory.all().filterNot { PackStatus.isDownloaded(app, it, downloadedTranslationCodes) }
        if (remaining.isEmpty()) return
        binding.btnDownloadAllRemaining.isEnabled = false
        binding.btnCancelBulkDownload.visibility = View.VISIBLE
        binding.progressBulkDownload.visibility = View.VISIBLE
        binding.progressBulkDownload.progress = 0
        val coordinator = BulkDownloadCoordinator(ctx.applicationContext, app)
        bulkCoordinator = coordinator
        coordinator.start(remaining, object : BulkDownloadCoordinator.Listener {
            override fun onProgress(index: Int, total: Int, item: PackDescriptor, itemPercent: Int) {
                if (_binding == null) return
                binding.progressBulkDownload.progress = if (total > 0) (index * 100) / total else 0
                binding.textBulkDownloadStatus.text = "Pack ${index + 1} of $total: ${item.displayName}${if (itemPercent > 0) " ($itemPercent%)" else ""}"
            }
            override fun onItemFailed(item: PackDescriptor, error: String?) {
                if (_binding == null) return
                android.util.Log.w("ManagePacksFragment", "Pack failed: ${item.displayName} ($error)")
            }
            override fun onFinished(successCount: Int, failCount: Int, cancelled: Boolean) {
                if (_binding == null) return
                bulkCoordinator = null
                binding.btnCancelBulkDownload.visibility = View.GONE
                binding.progressBulkDownload.visibility = View.GONE
                binding.textBulkDownloadStatus.text = when {
                    cancelled -> "Cancelled. $successCount downloaded, $failCount failed before stopping."
                    failCount == 0 -> "Done. $successCount packs downloaded."
                    else -> "Done. $successCount downloaded, $failCount failed (Wi-Fi may have dropped - try again)."
                }
                if (!cancelled) LanguagePackPreferences.setBulkDownloadCompleted(requireContext(), true)
                refresh()
            }
        })
    }

    // -------------------------------------------------------------------
    // Check for updates
    // -------------------------------------------------------------------

    private fun checkForUpdates() {
        val app = mainActivity()?.app ?: return
        val ctx = context ?: return
        binding.textUpdateCheckStatus.text = "Checking…"
        PackStatus.fetchDownloadedTranslationCodes { codes ->
            if (_binding == null) return@fetchDownloadedTranslationCodes
            downloadedTranslationCodes = codes
            // "Downloaded" per-category checks already validate completeness
            // (PiperTtsEngine.effectiveVoiceDir / VoskEngine.effectiveModelPath
            // both refuse to report a partially-extracted pack as present) -
            // re-running renderAll() against a fresh snapshot IS the real
            // integrity re-check this action performs. See this file's class
            // doc for why that's the honest scope of "check for updates" here.
            val incomplete = PackInventory.all().count { !PackStatus.isDownloaded(app, it, downloadedTranslationCodes) }
            LanguagePackPreferences.setLastUpdateCheckAt(ctx, System.currentTimeMillis())
            binding.textUpdateCheckStatus.text =
                "Checked just now. $incomplete of ${PackInventory.all().size} packs not currently downloaded/intact. " +
                    "(Pinned, versioned downloads - this re-verifies what's on disk, it doesn't poll for newer upstream releases.)"
            renderAll()
        }
    }

    private fun mainActivity() = activity as? MainActivity

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        bulkCoordinator?.cancel()
        bulkCoordinator = null
        _binding = null
    }

    companion object {
        private const val ARG_AUTO_START = "auto_start_bulk_download"

        /** Used by MainActivity's first-launch/first-Wi-Fi bulk-download confirmation to land directly on this screen with the download already running, instead of requiring a second tap. */
        fun newInstanceAutoStart(): ManagePacksFragment = ManagePacksFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_AUTO_START, true) }
        }
    }
}
