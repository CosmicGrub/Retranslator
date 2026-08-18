package com.retroid.translator.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/**
 * Plain HttpURLConnection + ZipInputStream downloader for Vosk speech-model
 * packs (no extra HTTP/zip library needed). Mirrors the same "Wi-Fi
 * required, one-time download, offline forever after" UX as the ML Kit
 * translation-model download already in the Translate screen, so both
 * "language pack" flows in the app feel like one system.
 */
object DownloadManager {
    private const val TAG = "DownloadManager"
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * fold5-device-version branch: this build targets the Galaxy Z Fold 5, a
     * phone with its own cellular plan (unlike the Wi-Fi-only default every
     * other branch of this app enforces for good reason - see c1e4a9e "fix
     * silent cellular-download gating bug" for why that default exists and
     * is enforced for real, not just requested). Set to `true` here, per
     * explicit instruction for this device-specific branch only, so
     * translation and every pack download may use cellular data.
     *
     * This does not reopen the bug c1e4a9e fixed: every download here is
     * still exclusively user-initiated (tapping Translate, or an explicit
     * Download/Manage-packs button) - this single flag only relaxes which
     * network type those already-explicit actions may use. Nothing happens
     * automatically or silently that wasn't already going to happen; the
     * user still sees the same download-progress UI either way, just
     * without a Wi-Fi requirement blocking it first. A single source of
     * truth here (rather than flipping `requireWifi = true` at each of the
     * ~7 call sites across this file, [com.retroid.translator.engine.TranslationEngine],
     * [com.retroid.translator.engine.PiperTtsEngine],
     * [com.retroid.translator.packs.BulkDownloadCoordinator], and
     * [com.retroid.translator.ui.TranslateFragment]) keeps this branch's
     * actual policy auditable in one place.
     */
    const val ALLOW_CELLULAR_DOWNLOADS = true

    fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun downloadAndUnzip(
        context: Context,
        url: String,
        destDir: File,
        requireWifi: Boolean = true,
        onProgress: (percent: Int) -> Unit = {},
        onDone: (success: Boolean, error: String?) -> Unit
    ) {
        runDownload(context, url, "dl_${System.currentTimeMillis()}.zip", destDir, requireWifi, onProgress, onDone) { tmp ->
            if (destDir.exists()) destDir.deleteRecursively()
            destDir.mkdirs()
            ZipInputStream(tmp.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                val buf = ByteArray(64 * 1024)
                while (entry != null) {
                    val outFile = File(destDir, entry.name)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { out ->
                            while (true) {
                                val n = zis.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
    }

    /**
     * Same Wi-Fi-gated download UX as [downloadAndUnzip], but for the
     * `.tar.bz2` archives Piper natural-voice packs ship as (sherpa-onnx's
     * own "tts-models" release format) rather than `.zip`.
     */
    fun downloadAndExtractTarBz2(
        context: Context,
        url: String,
        destDir: File,
        requireWifi: Boolean = true,
        onProgress: (percent: Int) -> Unit = {},
        onDone: (success: Boolean, error: String?) -> Unit
    ) {
        runDownload(context, url, "dl_${System.currentTimeMillis()}.tar.bz2", destDir, requireWifi, onProgress, onDone) { tmp ->
            if (destDir.exists()) destDir.deleteRecursively()
            destDir.mkdirs()
            BZip2CompressorInputStream(tmp.inputStream().buffered()).use { bz ->
                TarArchiveInputStream(bz).use { tar ->
                    val buf = ByteArray(64 * 1024)
                    var entry = tar.nextTarEntry
                    while (entry != null) {
                        val outFile = File(destDir, entry.name)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { out ->
                                while (true) {
                                    val n = tar.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                }
                            }
                        }
                        entry = tar.nextTarEntry
                    }
                }
            }
        }
    }

    /**
     * Shared Wi-Fi-gated "download to a temp file, then hand it off to
     * [extract]" flow. If anything goes wrong partway through - the download
     * connection drops, the device loses connectivity, extraction throws -
     * [destDir] is deleted rather than left containing a partial extraction.
     * A caller that only checks "does the output directory / a marker file in
     * it exist" to decide whether a pack is downloaded would otherwise treat
     * a half-written pack as complete (this happened for real during
     * on-device testing with a Piper voice pack: the small files landed
     * before the connection dropped, the bulk of espeak-ng-data did not).
     */
    private fun runDownload(
        context: Context,
        url: String,
        tmpName: String,
        destDir: File,
        requireWifi: Boolean,
        onProgress: (percent: Int) -> Unit,
        onDone: (success: Boolean, error: String?) -> Unit,
        extract: (File) -> Unit
    ) {
        if (requireWifi && !ALLOW_CELLULAR_DOWNLOADS && !isOnWifi(context)) {
            onDone(false, "Wi-Fi required for the first-time download")
            return
        }
        executor.execute {
            var tmp: File? = null
            try {
                tmp = File(context.cacheDir, tmpName)
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                }
                conn.connect()
                if (conn.responseCode !in 200..299) {
                    mainHandler.post { onDone(false, "Server returned ${conn.responseCode}") }
                    return@execute
                }
                val total = conn.contentLength
                var downloaded = 0L
                conn.inputStream.use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            downloaded += n
                            if (total > 0) {
                                val pct = ((downloaded * 100) / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    mainHandler.post { onProgress(pct) }
                                }
                            }
                        }
                    }
                }
                conn.disconnect()
                extract(tmp)
                mainHandler.post { onDone(true, null) }
            } catch (e: Exception) {
                Log.e(TAG, "Download/extract failed for $url", e)
                try { if (destDir.exists()) destDir.deleteRecursively() } catch (e2: Exception) { /* ignore */ }
                mainHandler.post { onDone(false, e.message ?: "Download failed") }
            } finally {
                tmp?.delete()
            }
        }
    }

    fun deleteDir(dir: File) {
        if (dir.exists()) dir.deleteRecursively()
    }
}
