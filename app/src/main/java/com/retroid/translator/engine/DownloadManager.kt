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
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
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
        runDownload(context, url, ".zip", destDir, requireWifi, onProgress, onDone) { tmp ->
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
        runDownload(context, url, ".tar.bz2", destDir, requireWifi, onProgress, onDone) { tmp ->
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
     * A deterministic, content-addressed temp filename for [url] - the same
     * URL always maps to the same on-disk file, even across separate app
     * launches, so a retry can find bytes a previous attempt already wrote
     * instead of starting over (docs/specs/engineering-systems-pitch.md
     * system #4). Replaces the old `"dl_${System.currentTimeMillis()}.ext"`
     * naming, which made resume structurally impossible: every attempt got
     * its own unique filename, so nothing downstream could ever tell "this
     * is where I left off" from "this is scratch space nobody's touched yet."
     */
    private fun stableTmpName(url: String, suffix: String): String {
        val hex = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)
        return "dl_$hex$suffix"
    }

    private fun journalFile(tmp: File): File = File(tmp.parentFile, "${tmp.name}.journal")

    /** Two lines: the URL this checkpoint belongs to, then the server's ETag or Last-Modified validator. */
    private fun writeJournal(tmp: File, url: String, validator: String) {
        try {
            journalFile(tmp).writeText("$url\n$validator")
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't write resume journal (non-fatal - next attempt just won't resume)", e)
        }
    }

    /** (url, validator) if a journal exists for [tmp] and is well-formed, else null. Never throws. */
    private fun readJournal(tmp: File): Pair<String, String>? {
        val f = journalFile(tmp)
        if (!f.exists()) return null
        return try {
            val lines = f.readLines()
            if (lines.size >= 2) lines[0] to lines[1] else null
        } catch (e: Exception) {
            null
        }
    }

    private fun clearResumeCheckpoint(tmp: File) {
        tmp.delete()
        journalFile(tmp).delete()
    }

    /**
     * Deletes any leftover `dl_*` temp file/journal pair older than
     * [maxAgeMs] (default 7 days) from [Context.cacheDir] - a resume
     * checkpoint nobody ever came back to retry (the user gave up, deleted
     * and reinstalled, or the pack simply isn't needed anymore) would
     * otherwise sit in cache indefinitely now that [runDownload] no longer
     * deletes [tmp] on every failure. Call once at app startup, not on a
     * timer - this is storage hygiene, not something that needs to run more
     * than once per process lifetime.
     */
    fun pruneStaleResumeCheckpoints(context: Context, maxAgeMs: Long = 7L * 24 * 60 * 60 * 1000) {
        val now = System.currentTimeMillis()
        try {
            context.cacheDir.listFiles { f -> f.isFile && f.name.startsWith("dl_") }
                ?.filter { now - it.lastModified() > maxAgeMs }
                ?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "pruneStaleResumeCheckpoints failed (non-fatal)", e)
        }
    }

    /**
     * Shared Wi-Fi-gated "download to a temp file, then hand it off to
     * [extract]" flow, with HTTP Range-based resume
     * (docs/specs/engineering-systems-pitch.md system #4): if a previous
     * attempt for this exact [url] left a partial [stableTmpName] file and a
     * matching [readJournal] entry, this sends `Range`/`If-Range` and
     * appends rather than re-downloading from byte zero. If the server
     * doesn't honor that (anything other than a real 206 response), the
     * partial bytes are discarded and this falls back to a full clean
     * restart automatically - never a silent corrupt merge of old and new
     * bytes.
     *
     * [destDir] is still deleted on any exception, unchanged from before -
     * that safeguard (a caller checking "does the output directory exist"
     * must never see a half-written extraction as complete) is orthogonal to
     * resume and this doesn't touch it. What changed: [tmp] itself is no
     * longer deleted on failure - it's now the resume checkpoint for the
     * *next* attempt, not scratch space to clean up after every attempt.
     * It's only deleted on real success (after [extract] completes) or when
     * a resume attempt is explicitly discarded above.
     */
    private fun runDownload(
        context: Context,
        url: String,
        tmpSuffix: String,
        destDir: File,
        requireWifi: Boolean,
        onProgress: (percent: Int) -> Unit,
        onDone: (success: Boolean, error: String?) -> Unit,
        extract: (File) -> Unit
    ) {
        if (DownloadPolicy.shouldBlockDownload(requireWifi, isOnWifi(context))) {
            onDone(false, "Wi-Fi required for the first-time download")
            return
        }
        executor.execute {
            val tmp = File(context.cacheDir, stableTmpName(url, tmpSuffix))
            try {
                val journal = readJournal(tmp)
                val resumeFrom = if (tmp.exists() && tmp.length() > 0 && journal?.first == url) tmp.length() else 0L

                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    if (resumeFrom > 0) {
                        setRequestProperty("Range", "bytes=$resumeFrom-")
                        journal?.second?.let { setRequestProperty("If-Range", it) }
                    }
                }
                conn.connect()

                val resumed = resumeFrom > 0 && conn.responseCode == HttpURLConnection.HTTP_PARTIAL
                if (resumeFrom > 0 && !resumed) {
                    // Server ignored Range (plain 200) or the If-Range
                    // validator no longer matches (resource changed) - can't
                    // trust the partial bytes on disk. Discard the
                    // checkpoint and restart clean, exactly like a
                    // first-ever attempt. Safe against infinite recursion:
                    // the recursive call below has nothing to resume from
                    // (clearResumeCheckpoint already ran), so it can't take
                    // this branch again.
                    conn.disconnect()
                    clearResumeCheckpoint(tmp)
                    return@execute runDownload(context, url, tmpSuffix, destDir, requireWifi, onProgress, onDone, extract)
                }

                if (conn.responseCode !in 200..299) {
                    mainHandler.post { onDone(false, "Server returned ${conn.responseCode}") }
                    return@execute
                }

                if (!resumed) {
                    // Fresh attempt (never a resume) - record a validator now,
                    // before any bytes are written, so a later retry has
                    // something to send If-Range against.
                    (conn.getHeaderField("ETag") ?: conn.getHeaderField("Last-Modified"))
                        ?.let { writeJournal(tmp, url, it) }
                }

                val total = if (resumed) {
                    conn.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                        ?: (resumeFrom + conn.contentLength)
                } else {
                    conn.contentLength.toLong()
                }

                var downloaded = if (resumed) resumeFrom else 0L
                conn.inputStream.use { input ->
                    FileOutputStream(tmp, resumed).use { output ->
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
                clearResumeCheckpoint(tmp)
                mainHandler.post { onDone(true, null) }
            } catch (e: Exception) {
                Log.e(TAG, "Download/extract failed for $url", e)
                try { if (destDir.exists()) destDir.deleteRecursively() } catch (e2: Exception) { /* ignore */ }
                // tmp is deliberately NOT deleted here - see this function's
                // own doc comment. It's the next attempt's resume checkpoint.
                mainHandler.post { onDone(false, e.message ?: "Download failed") }
            }
        }
    }

    fun deleteDir(dir: File) {
        if (dir.exists()) dir.deleteRecursively()
    }
}
