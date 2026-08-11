package com.retroid.translator.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.Executors

/**
 * Manages the currently-loaded offline Vosk speech model. Only one model is
 * kept resident at a time (this device has ~1GB usable RAM) — switching
 * source language unloads the previous model before loading the new one.
 */
class VoskEngine(context: Context) {
    private val appContext = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var loadedModel: Model? = null
    @Volatile var loadedLangCode: String? = null
        private set

    init {
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
        } catch (e: Throwable) {
            Log.w(TAG, "LibVosk.setLogLevel failed (non-fatal)", e)
        }
    }

    fun modelRootDir(langCode: String): File = File(appContext.filesDir, "vosk-models/$langCode")

    /** The actual model directory Vosk needs (zip extracts into one nested folder). */
    fun effectiveModelPath(langCode: String): File? {
        val dir = modelRootDir(langCode)
        if (!dir.exists()) return null
        if (File(dir, "conf").exists() || File(dir, "am").exists()) return dir
        val subdirs = dir.listFiles { f -> f.isDirectory } ?: return null
        val match = subdirs.firstOrNull { File(it, "conf").exists() || File(it, "am").exists() }
        return match
    }

    fun isModelDownloaded(langCode: String): Boolean = effectiveModelPath(langCode) != null

    fun loadModelAsync(langCode: String, onResult: (success: Boolean, error: String?) -> Unit) {
        if (loadedLangCode == langCode && loadedModel != null) {
            onResult(true, null)
            return
        }
        worker.execute {
            try {
                val path = effectiveModelPath(langCode)
                if (path == null) {
                    mainHandler.post { onResult(false, "Speech-recognition pack not downloaded yet") }
                    return@execute
                }
                loadedModel?.close()
                loadedModel = null
                loadedLangCode = null
                val model = Model(path.absolutePath)
                loadedModel = model
                loadedLangCode = langCode
                Log.i(TAG, "Vosk model loaded for $langCode from ${path.path}")
                mainHandler.post { onResult(true, null) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load Vosk model for $langCode", e)
                mainHandler.post { onResult(false, e.message ?: "Failed to load model") }
            }
        }
    }

    /** Must be called after [loadModelAsync] succeeded for this language. Caller owns .close(). */
    fun newRecognizer(sampleRate: Float = 16000f): Recognizer? {
        val model = loadedModel ?: return null
        return try {
            Recognizer(model, sampleRate)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create recognizer", e)
            null
        }
    }

    fun release() {
        loadedModel?.close()
        loadedModel = null
        loadedLangCode = null
        worker.shutdown()
    }

    /**
     * Additive - used by the "Manage language packs" screen
     * (docs/specs/galaxy-tab-s9fe-adaptation.md) to let a downloaded model be
     * deleted to reclaim space and re-downloaded later. Mirrors
     * [com.retroid.translator.engine.PiperTtsEngine.deleteVoice]'s exact
     * "unload synchronously first, then delete the directory" pattern - the
     * same proven fix for the same class of bug that pattern's own doc
     * comment describes (deleting a model's files while a native object
     * still has them open).
     */
    fun deleteModel(langCode: String) {
        if (loadedLangCode == langCode) unloadBlocking()
        val dir = modelRootDir(langCode)
        if (dir.exists()) dir.deleteRecursively()
    }

    private fun unloadBlocking() {
        val latch = java.util.concurrent.CountDownLatch(1)
        worker.execute {
            loadedModel?.close()
            loadedModel = null
            loadedLangCode = null
            latch.countDown()
        }
        try { latch.await(5, java.util.concurrent.TimeUnit.SECONDS) } catch (e: InterruptedException) { /* ignore */ }
    }

    companion object {
        private const val TAG = "VoskEngine"
    }
}
