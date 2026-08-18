package com.retroid.translator.packs

import android.content.Context
import android.util.Log
import com.retroid.translator.TranslatorApp
import com.retroid.translator.engine.DownloadManager
import com.retroid.translator.engine.TranslationEngine

/**
 * Sequential downloader for a batch of [PackDescriptor]s - drives the
 * "auto-download all language packs" flow
 * (docs/specs/galaxy-tab-s9fe-adaptation.md) and is reused by "Manage
 * language packs"' own "Download all remaining" action. Downloads are
 * deliberately sequential, not parallel: keeps peak bandwidth/memory
 * bounded, keeps progress reporting simple and unambiguous ("pack 7 of 92"),
 * and avoids contending with DownloadManager's own single-thread executor.
 *
 * Cancellation is cooperative, not preemptive: [cancel] is checked between
 * items, not mid-download - a download already in flight when cancel is
 * requested finishes (or fails) normally before the batch actually stops.
 * This is a real, disclosed limitation (an in-flight HTTP read can't be torn
 * down cleanly without deeper changes to [DownloadManager] itself, which
 * this pass deliberately avoids touching beyond what's additive), not an
 * oversight - "cancel" here means "don't start anything new", which is the
 * common case users actually want (stop the download from continuing to eat
 * data/battery) even though the very last item completes first.
 */
class BulkDownloadCoordinator(private val context: Context, private val app: TranslatorApp) {

    interface Listener {
        /** Fired right before item [index] (0-based) starts, and again as its own download reports progress (0-100; always 0 for Translation packs - ML Kit's download API doesn't expose byte-level progress). */
        fun onProgress(index: Int, total: Int, item: PackDescriptor, itemPercent: Int)
        fun onItemFailed(item: PackDescriptor, error: String?)
        fun onFinished(successCount: Int, failCount: Int, cancelled: Boolean)
    }

    @Volatile private var cancelled = false
    private var listener: Listener? = null

    fun cancel() {
        cancelled = true
    }

    fun start(items: List<PackDescriptor>, listener: Listener) {
        this.listener = listener
        cancelled = false
        downloadNext(items, 0, items.size, 0, 0)
    }

    private fun downloadNext(items: List<PackDescriptor>, index: Int, total: Int, successCount: Int, failCount: Int) {
        if (cancelled || index >= items.size) {
            listener?.onFinished(successCount, failCount, cancelled)
            return
        }
        val item = items[index]
        listener?.onProgress(index, total, item, 0)
        downloadSingle(
            item,
            onProgress = { pct -> listener?.onProgress(index, total, item, pct) }
        ) { success, error ->
            if (!success) {
                Log.w(TAG, "Bulk download: item failed id=${item.id} category=${item.category} error=$error")
                listener?.onItemFailed(item, error)
            }
            downloadNext(items, index + 1, total, successCount + if (success) 1 else 0, failCount + if (success) 0 else 1)
        }
    }

    /** Downloads exactly one pack, regardless of category. Public so "Manage language packs"' individual per-row Download button can reuse the same dispatch logic instead of duplicating it. */
    fun downloadSingle(item: PackDescriptor, onProgress: (Int) -> Unit, onDone: (Boolean, String?) -> Unit) {
        // Fold5 edition: user-adjustable Settings toggle (Settings -> Manage
        // language packs), not a fixed build-time constant - see
        // LanguagePackPreferences.allowCellularDownloads's doc comment.
        // Universal build and Tab S9 FE edition keep the real Wi-Fi
        // requirement (this preference/field doesn't exist there).
        val requireWifi = !LanguagePackPreferences.allowCellularDownloads(context)
        when (item) {
            is PackDescriptor.Translation ->
                TranslationEngine.downloadModel(item.mlKitCode, requireWifi = requireWifi) { ok, err -> onDone(ok, err) }
            is PackDescriptor.VoiceInput ->
                DownloadManager.downloadAndUnzip(
                    context, item.info.url, app.vosk.modelRootDir(item.info.mlKitCode), requireWifi = requireWifi,
                    onProgress = onProgress, onDone = onDone
                )
            is PackDescriptor.NaturalVoice ->
                app.piper.downloadVoice(context, item.info, onProgress = onProgress) { ok, err -> onDone(ok, err) }
        }
    }

    companion object {
        private const val TAG = "BulkDownloadCoordinator"
    }
}
