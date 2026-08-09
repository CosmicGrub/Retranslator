package com.retroid.translator.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
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
        if (requireWifi && !isOnWifi(context)) {
            onDone(false, "Wi-Fi required for the first-time download")
            return
        }
        executor.execute {
            var tmp: File? = null
            try {
                tmp = File(context.cacheDir, "dl_${System.currentTimeMillis()}.zip")
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

                // Unzip into destDir.
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
                mainHandler.post { onDone(true, null) }
            } catch (e: Exception) {
                Log.e(TAG, "Download/unzip failed for $url", e)
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
