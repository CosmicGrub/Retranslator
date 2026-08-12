package com.retroid.translator.wear.engine

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
 * Wear port of the phone app's [com.retroid.translator.engine.VoskEngine].
 * Logic is intentionally near-identical (single-resident-model, same
 * load/release lifecycle) - this class is proof that the phone app's Vosk
 * wrapper needed ZERO Kotlin-level changes to run on Wear OS; only the
 * Gradle module's `ndk.abiFilters` differ (armeabi-v7a + x86_64 here, see
 * wear/build.gradle.kts, vs. the phone's arm64-v8a-only). Kept as a
 * standalone copy rather than a shared dependency for this pass - see
 * docs/specs/watch6-classic-adaptation.md "What's scaffolded but not
 * working" for why a shared `:core` module is recommended follow-up work,
 * not done here.
 *
 * Single-resident-model is an even more important constraint here than on
 * the phone: the real Watch6 Classic has ~1.8GB total RAM (confirmed via
 * `adb shell cat /proc/meminfo`, see spec), noticeably less than even the
 * Retroid Pocket 2+ comment this class's phone counterpart was written
 * against.
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
            // Deliberately caught as Throwable, not Exception - on a new ABI/
            // OS combination the failure mode of interest here could be an
            // UnsatisfiedLinkError (a LinkageError, not an Exception) if the
            // native libvosk.so failed to load for this device's ABI. See
            // this class's LAST_NATIVE_LOAD_ERROR for how MainActivity
            // surfaces this to the on-screen diagnostic panel.
            Log.w(TAG, "LibVosk.setLogLevel failed", e)
            lastNativeLoadError = e
        }
    }

    fun modelRootDir(langCode: String): File = File(appContext.filesDir, "vosk-models/$langCode")

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
     * Diagnostic-only probe, NOT part of the phone app's original class:
     * attempts to construct a [Model] against a path that is guaranteed not
     * to contain a real model, on a background thread, and reports which of
     * three outcomes happened. This exists specifically to answer this
     * pass's central question (does Vosk's native/JNI layer load and run at
     * all on this ABI/OS) WITHOUT requiring a real downloaded model - see
     * docs/specs/watch6-classic-adaptation.md's "hard technical question"
     * section for how this was used and what it found on the real device.
     */
    fun probeNativeLoad(onResult: (outcome: NativeProbeOutcome, detail: String) -> Unit) {
        worker.execute {
            val probeDir = File(appContext.cacheDir, "vosk_probe_missing_model_dir")
            try {
                Model(probeDir.absolutePath)
                // Vosk/Kaldi validating a missing directory and still
                // returning successfully would itself be a surprise worth
                // surfacing, not assumed impossible.
                mainHandler.post { onResult(NativeProbeOutcome.UNEXPECTED_SUCCESS, probeDir.path) }
            } catch (e: Throwable) {
                val outcome = if (e is LinkageError) {
                    NativeProbeOutcome.NATIVE_LOAD_FAILED
                } else {
                    NativeProbeOutcome.CLEAN_MANAGED_REJECTION
                }
                mainHandler.post { onResult(outcome, "${e.javaClass.simpleName}: ${e.message}") }
            }
        }
    }

    enum class NativeProbeOutcome { CLEAN_MANAGED_REJECTION, NATIVE_LOAD_FAILED, UNEXPECTED_SUCCESS }

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
        private const val TAG = "WearVoskEngine"
        @Volatile var lastNativeLoadError: Throwable? = null
            private set
    }
}
