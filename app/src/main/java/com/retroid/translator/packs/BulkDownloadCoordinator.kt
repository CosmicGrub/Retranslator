package com.retroid.translator.packs

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.retroid.translator.TranslatorApp
import com.retroid.translator.diagnostics.Diag
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
 * data/battery) even though the very last item completes first. A scheduled
 * retry (see below) is a form of "not started yet" too - [cancel] is
 * checked before a retry fires, same as before any fresh item starts.
 *
 * Per-item retry with backoff (docs/specs/engineering-systems-pitch.md
 * system #4): a failed item gets up to [MAX_RETRIES_PER_ITEM] retries,
 * each waiting longer than the last, before this coordinator gives up on it
 * and moves on - previously a single transient failure (a dropped Wi-Fi
 * connection on item 7 of a ~92-pack run) permanently skipped that item for
 * the whole run, zero retry. This composes with [DownloadManager]'s own
 * resume support: each retry of a [PackDescriptor.VoiceInput]/
 * [PackDescriptor.NaturalVoice] item resumes from wherever the previous
 * attempt's temp file stopped rather than re-fetching from byte zero.
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
    private val mainHandler = Handler(Looper.getMainLooper())

    fun cancel() {
        cancelled = true
    }

    fun start(items: List<PackDescriptor>, listener: Listener) {
        this.listener = listener
        cancelled = false
        downloadNext(items, 0, items.size, 0, 0)
    }

    private fun downloadNext(
        items: List<PackDescriptor>,
        index: Int,
        total: Int,
        successCount: Int,
        failCount: Int,
        attempt: Int = 0
    ) {
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
            if (!success && attempt < MAX_RETRIES_PER_ITEM) {
                val backoffMs = BASE_BACKOFF_MS * (1L shl attempt) // 2s, 4s, 8s
                Diag.w(TAG, "Bulk download: item id=${item.id} attempt ${attempt + 1} failed ($error), retrying in ${backoffMs}ms")
                mainHandler.postDelayed(
                    { downloadNext(items, index, total, successCount, failCount, attempt + 1) },
                    backoffMs
                )
                return@downloadSingle
            }
            if (!success) {
                Diag.w(TAG, "Bulk download: item failed id=${item.id} category=${item.category} error=$error (out of retries)")
                listener?.onItemFailed(item, error)
            }
            downloadNext(items, index + 1, total, successCount + if (success) 1 else 0, failCount + if (success) 0 else 1)
        }
    }

    /** Downloads exactly one pack, regardless of category. Public so "Manage language packs"' individual per-row Download button can reuse the same dispatch logic instead of duplicating it. */
    fun downloadSingle(item: PackDescriptor, onProgress: (Int) -> Unit, onDone: (Boolean, String?) -> Unit) {
        when (item) {
            is PackDescriptor.Translation ->
                TranslationEngine.downloadModel(item.mlKitCode, requireWifi = true) { ok, err -> onDone(ok, err) }
            is PackDescriptor.VoiceInput ->
                DownloadManager.downloadAndUnzip(
                    context, item.info.url, app.vosk.modelRootDir(item.info.mlKitCode), requireWifi = true,
                    onProgress = onProgress, onDone = onDone
                )
            is PackDescriptor.NaturalVoice ->
                app.piper.downloadVoice(context, item.info, onProgress = onProgress) { ok, err -> onDone(ok, err) }
        }
    }

    companion object {
        private const val TAG = "BulkDownloadCoordinator"

        /**
         * Unmeasured starting defaults, not numbers derived from any
         * measured failure rate - this app has no telemetry, by design, to
         * calibrate them from (docs/specs/engineering-systems-pitch.md
         * system #4's own disclosed limitation). 3 retries at 2s/4s/8s caps
         * the worst case for a permanently-unreachable host at ~14s wasted
         * per item across a large batch - a deliberate trade against an
         * instant, un-retried skip.
         */
        private const val MAX_RETRIES_PER_ITEM = 3
        private const val BASE_BACKOFF_MS = 2_000L
    }
}
