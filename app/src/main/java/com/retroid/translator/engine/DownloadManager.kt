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
        runDownload(context, url, "dl_${System.currentTimeMillis()}.zip", requireWifi, onProgress, onDone) { tmp ->
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
        runDownload(context, url, "dl_${System.currentTimeMillis()}.tar.bz2", requireWifi, onProgress, onDone) { tmp ->
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

    /** Shared Wi-Fi-gated "download to a temp file, then hand it off to [extract]" flow. */
    private fun runDownload(
        context: Context,
        url: String,
        tmpName: String,
        requireWifi: Boolean,
        onProgress: (percent: Int) -> Unit,
        onDone: (success: Boolean, error: String?) -> Unit,
        extract: (File) -> Unit
    ) {
        if (requireWifi && !isOnWifi(context)) {
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
