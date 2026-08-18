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
 * Manages the currently-loaded offline Vosk speech model(s). How many models
 * are kept simultaneously resident is device-tiered (see
 * [DeviceCapabilities.voskResidentModelCap]) - **1** on this app's original
 * design point ("this device has ~1GB usable RAM" - true for the Retroid
 * Pocket 2+ target and low-RAM builds/editions, unchanged behavior:
 * switching language always unloads the previous model before loading the
 * new one, exactly as before this class supported >1 resident model), up to
 * **3** on real high-RAM devices/editions
 * (docs/specs/engines-upgrade-plan.md's Tier 3 "device-tiered Vosk
 * resident-model cap"). Beyond the cap, the single least-recently-activated
 * model is evicted (closed) to make room for a new one - ordinary LRU, not a
 * FIFO queue.
 *
 * Two ways to bring a language into residency, serving different callers:
 * - [loadModelAsync] - activates [langCode] for [newRecognizer]'s benefit
 *   (moves the "current" pointer). This is the pre-existing, unchanged
 *   contract every caller in this app already uses.
 * - [prewarmAsync] - additively loads [langCode] into residency WITHOUT
 *   activating it (does not move the "current" pointer). Used to
 *   proactively warm a model that isn't needed yet but likely will be soon
 *   - see [com.retroid.translator.ui.ConversationsFragment]'s continuous-
 *   mode start, which pre-warms a 3rd language into [com.retroid.translator.TranslatorApp.vosk]
 *   alongside its own two dedicated dual-recognizer instances, on high-RAM
 *   devices only.
 *
 * All resident-model map access happens either on [worker]'s single thread
 * or under `synchronized(residentModels)` (the map itself is also the lock
 * object) - callers may invoke [newRecognizer] from the main thread while
 * [worker] is mid-load for a *different* language safely.
 */
class VoskEngine(context: Context) {
    private val appContext = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val residentCap = DeviceCapabilities.voskResidentModelCap(appContext)

    // Access-ordered (true) so the eldest entry is genuinely the
    // least-recently-USED (get OR put touches order), not merely
    // least-recently-inserted - real LRU semantics for eviction below.
    private val residentModels = LinkedHashMap<String, Model>(16, 0.75f, true)

    /** The language [newRecognizer] currently operates on - null if nothing has been activated (via [loadModelAsync]) yet. A model can be resident (via [prewarmAsync]) without being this. */
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

    /** Real snapshot of which languages are genuinely resident (native `Model` objects held open) right now - not just requested. Exposed for on-device verification (matches this feature's spec section's dumpsys-meminfo-driven methodology) and tests. */
    fun residentLangCodes(): Set<String> = synchronized(residentModels) { residentModels.keys.toSet() }

    /** How many models this instance may keep resident simultaneously - see [DeviceCapabilities.voskResidentModelCap]. Exposed for verification/logging. */
    fun residentCapacity(): Int = residentCap

    /**
     * Activates [langCode]: makes it the language [newRecognizer] operates
     * on. If already resident (loaded OR merely pre-warmed via
     * [prewarmAsync]), this is a fast in-memory activation with no I/O -
     * genuinely instant, which is the entire payoff of pre-warming. If not
     * resident, loads it from disk, evicting the LRU resident model first if
     * already at [residentCapacity].
     */
    fun loadModelAsync(langCode: String, onResult: (success: Boolean, error: String?) -> Unit) {
        worker.execute {
            val already = synchronized(residentModels) { residentModels[langCode] } // get() bumps LRU order
            if (already != null) {
                loadedLangCode = langCode
                Log.i(TAG, "$langCode already resident - activated with no I/O")
                mainHandler.post { onResult(true, null) }
                return@execute
            }
            loadInternal(langCode, activate = true, onResult)
        }
    }

    /**
     * Proactively loads [langCode] into residency WITHOUT activating it (see
     * class doc). A no-op success if [langCode] is already resident
     * (activated or not). Fails quietly if the model isn't downloaded -
     * this is opportunistic background work triggered by the app itself,
     * never a direct user action, so it deliberately does not surface an
     * error the way [loadModelAsync]'s callers do.
     */
    fun prewarmAsync(langCode: String, onResult: (success: Boolean, error: String?) -> Unit = { _, _ -> }) {
        worker.execute {
            val already = synchronized(residentModels) { residentModels[langCode] }
            if (already != null) {
                mainHandler.post { onResult(true, null) }
                return@execute
            }
            loadInternal(langCode, activate = false, onResult)
        }
    }

    /** Runs on [worker]'s thread only. */
    private fun loadInternal(langCode: String, activate: Boolean, onResult: (success: Boolean, error: String?) -> Unit) {
        try {
            val path = effectiveModelPath(langCode)
            if (path == null) {
                mainHandler.post { onResult(false, "Speech-recognition pack not downloaded yet") }
                return
            }
            evictLruIfAtCapacity()
            val model = Model(path.absolutePath)
            val residentCount = synchronized(residentModels) {
                residentModels[langCode] = model
                residentModels.size
            }
            if (activate) loadedLangCode = langCode
            Log.i(TAG, "Vosk model loaded for $langCode from ${path.path} (resident=$residentCount/$residentCap, activate=$activate)")
            mainHandler.post { onResult(true, null) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Vosk model for $langCode", e)
            mainHandler.post { onResult(false, e.message ?: "Failed to load model") }
        }
    }

    /** Evicts the single least-recently-used resident model if adding one more would exceed [residentCap]. Runs on [worker]'s thread only (called right before a new [Model] is constructed). */
    private fun evictLruIfAtCapacity() {
        synchronized(residentModels) {
            if (residentModels.size < residentCap) return
            val eldest = residentModels.entries.firstOrNull() ?: return
            Log.i(TAG, "Resident-model cap ($residentCap) reached - evicting ${eldest.key} to make room")
            eldest.value.close()
            residentModels.remove(eldest.key)
            if (loadedLangCode == eldest.key) loadedLangCode = null
        }
    }

    /**
     * Must be called after [loadModelAsync] (or [prewarmAsync] followed by
     * [loadModelAsync] to activate it) succeeded. Caller owns .close().
     * Operates on the currently ACTIVE language ([loadedLangCode]) - this
     * external contract is unchanged from before this class supported >1
     * resident model.
     */
    fun newRecognizer(sampleRate: Float = 16000f): Recognizer? {
        val code = loadedLangCode ?: return null
        val model = synchronized(residentModels) { residentModels[code] } ?: return null
        return try {
            Recognizer(model, sampleRate)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create recognizer", e)
            null
        }
    }

    fun release() {
        synchronized(residentModels) {
            residentModels.values.forEach { it.close() }
            residentModels.clear()
        }
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
     * still has them open). Unloads [langCode] whether it's merely resident
     * (pre-warmed) or actively loaded - both cases hold the files open.
     */
    fun deleteModel(langCode: String) {
        val isResident = synchronized(residentModels) { residentModels.containsKey(langCode) }
        if (isResident) unloadBlocking(langCode)
        val dir = modelRootDir(langCode)
        if (dir.exists()) dir.deleteRecursively()
    }

    private fun unloadBlocking(langCode: String) {
        val latch = java.util.concurrent.CountDownLatch(1)
        worker.execute {
            synchronized(residentModels) { residentModels.remove(langCode)?.close() }
            if (loadedLangCode == langCode) loadedLangCode = null
            latch.countDown()
        }
        try { latch.await(5, java.util.concurrent.TimeUnit.SECONDS) } catch (e: InterruptedException) { /* ignore */ }
    }

    companion object {
        private const val TAG = "VoskEngine"
    }
}
