package com.retroid.translator.audio

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** App-private (no special permission needed) storage for saved WAV recordings. */
class RecordingsStore(context: Context, subfolder: String) {
    val dir: File = File(context.filesDir, "recordings/$subfolder").apply { mkdirs() }
    private val nameFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    fun newFile(label: String): File {
        val safeLabel = label.replace(Regex("[^A-Za-z0-9_-]"), "_").take(24)
        val name = "${nameFormat.format(Date())}_$safeLabel.wav"
        return File(dir, name)
    }

    fun list(): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(".wav") }
        ?.sortedByDescending { it.lastModified() }
        ?: emptyList()

    fun delete(file: File): Boolean = file.delete()
}
