package com.retroid.translator.engine

import android.content.Context
import com.retroid.translator.packs.LanguagePackPreferences
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Neural text-to-speech: sherpa-onnx (Apache-2.0, k2-fsa) driving an
 * on-demand-downloaded Piper VITS voice model (see [PiperVoiceCatalog]).
 * Unlike [EspeakEngine] (always available, bundled in the APK), a voice must
 * be downloaded before this can speak - [TtsRouter] is what decides whether
 * to use this or fall back to eSpeak.
 *
 * Every voice is identified by its [PiperVoiceInfo.voiceId] (e.g.
 * "en_US-joe-medium"), which also doubles as its on-disk storage key -
 * since a language can now have both a male and a female voice downloaded
 * at once (see [PiperVoiceCatalog]), keying storage by language code alone
 * is no longer enough to tell them apart.
 *
 * Only one voice is kept loaded/resident at a time - loading a different
 * voice releases the previous native OfflineTts instance first (same "one
 * model in RAM" discipline as [VoskEngine], since a Piper medium voice's
 * ONNX weights are themselves ~60MB and this device has ~1GB usable RAM
 * alongside everything else already running).
 */
class PiperTtsEngine(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private var tts: OfflineTts? = null
    @Volatile var loadedVoiceId: String? = null
        private set

    private var audioTrack: AudioTrack? = null
    private var trackSampleRate = -1
    private val speaking = AtomicBoolean(false)

    init {
        // One-time cleanup: before voices were keyed by voiceId (to support a
        // male+female pair per language), they lived at piper-voices/<langCode>/.
        // Those short-code directories are now dead weight - reclaim the space
        // rather than leaving ~65MB orphaned per previously-downloaded language.
        worker.execute { migrateLegacyLayoutBlocking() }
    }

    private fun migrateLegacyLayoutBlocking() {
        val root = File(appContext.filesDir, "piper-voices")
        val legacyCodes = setOf("en", "de", "es", "fr")
        legacyCodes.forEach { code ->
            val d = File(root, code)
            if (d.isDirectory) {
                Log.i(TAG, "Removing legacy voice layout dir ${d.path}")
                d.deleteRecursively()
            }
        }
    }

    // ---------------------------------------------------------------------
    // Voice-pack storage / download
    // ---------------------------------------------------------------------

    fun voiceRootDir(info: PiperVoiceInfo): File = File(appContext.filesDir, "piper-voices/${info.voiceId}")

    /**
     * The extracted voice directory (containing `<voiceId>.onnx`, `tokens.txt`,
     * `espeak-ng-data/`), or null if not downloaded *or the download/extraction
     * was interrupted partway through* (e.g. the app got backgrounded/killed,
     * or the device lost connectivity mid-download).
     *
     * This deliberately checks more than "does the .onnx file exist": a real
     * bug found during on-device testing was that an interrupted download can
     * leave a directory where the (small, early-in-the-archive) .onnx file
     * and tokens.txt made it to disk but espeak-ng-data - a much bigger,
     * later part of the same tar stream - did not. sherpa-onnx's native VITS
     * loader requires four specific files inside espeak-ng-data (phontab,
     * phonindex, phondata, intonations) and previously we had no Kotlin-side
     * check for that: constructing OfflineTts against such a directory logs
     * "Errors found in config!" natively and then a *second* native call
     * (getSampleRate on the resulting null/invalid handle) segfaults the
     * whole process - not something a Kotlin try/catch can recover from. The
     * only reliable fix is to never attempt to load an incomplete voice in
     * the first place.
     */
    fun effectiveVoiceDir(info: PiperVoiceInfo): File? {
        val root = voiceRootDir(info)
        if (!root.exists()) return null
        // The archive extracts into one nested "vits-piper-<voiceId>" folder.
        val nested = File(root, "vits-piper-${info.voiceId}")
        val direct = if (File(nested, "${info.voiceId}.onnx").exists()) nested else root
        return if (isCompleteVoiceDir(direct, info)) direct else null
    }

    private fun isCompleteVoiceDir(dir: File, info: PiperVoiceInfo): Boolean {
        val model = File(dir, "${info.voiceId}.onnx")
        val tokens = File(dir, "tokens.txt")
        if (!model.isFile || model.length() < MIN_MODEL_BYTES) return false
        if (!tokens.isFile || tokens.length() <= 0L) return false
        val dataDir = File(dir, "espeak-ng-data")
        if (!dataDir.isDirectory) return false
        // These are the exact files sherpa-onnx's native VITS loader requires
        // to exist inside --vits-data-dir (see OfflineTtsVitsModelConfig::Validate
        // in sherpa-onnx/csrc/offline-tts-vits-model-config.cc) - checking for
        // them directly, rather than just "the directory is non-empty", is what
        // actually catches a partial extraction like the one found here.
        return REQUIRED_ESPEAK_DATA_FILES.all { File(dataDir, it).isFile }
    }

    fun isVoiceDownloaded(info: PiperVoiceInfo): Boolean = effectiveVoiceDir(info) != null

    fun downloadVoice(
        context: Context,
        info: PiperVoiceInfo,
        onProgress: (percent: Int) -> Unit = {},
        onDone: (success: Boolean, error: String?) -> Unit
    ) {
        DownloadManager.downloadAndExtractTarBz2(
            // Fold5 edition: user-adjustable Settings toggle (Settings ->
            // Manage language packs), not a fixed build-time constant - see
            // LanguagePackPreferences.allowCellularDownloads's doc comment.
            context, info.url, voiceRootDir(info), requireWifi = !LanguagePackPreferences.allowCellularDownloads(context),
            onProgress = onProgress
        ) { success, error ->
            if (success && loadedVoiceId == info.voiceId) {
                // The previously-loaded copy of this exact voice, if any, is now stale.
                unloadBlocking()
            }
            var actualSuccess = success
            var actualError = error
            if (success && !isVoiceDownloaded(info)) {
                // The download/unzip reported success but the result doesn't pass
                // completeness validation - treat it as a failure and clean up
                // rather than leaving a half-extracted pack that would crash on load.
                Log.w(TAG, "Voice pack ${info.voiceId} failed completeness check after extraction; discarding it")
                DownloadManager.deleteDir(voiceRootDir(info))
                actualSuccess = false
                actualError = "Downloaded pack was incomplete, please try again"
            }
            onDone(actualSuccess, actualError)
        }
    }

    fun deleteVoice(info: PiperVoiceInfo) {
        if (loadedVoiceId == info.voiceId) unloadBlocking()
        DownloadManager.deleteDir(voiceRootDir(info))
    }

    // ---------------------------------------------------------------------
    // Model loading
    // ---------------------------------------------------------------------

    fun loadVoiceAsync(info: PiperVoiceInfo, onResult: (success: Boolean, error: String?) -> Unit) {
        if (loadedVoiceId == info.voiceId && tts != null) {
            onResult(true, null)
            return
        }
        worker.execute {
            try {
                val dir = effectiveVoiceDir(info)
                if (dir == null) {
                    mainHandler.post { onResult(false, "Natural voice pack not downloaded yet") }
                    return@execute
                }
                releaseTtsLocked()
                val config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = File(dir, "${info.voiceId}.onnx").absolutePath,
                            tokens = File(dir, "tokens.txt").absolutePath,
                            dataDir = File(dir, "espeak-ng-data").absolutePath,
                        ),
                        numThreads = 2,
                        debug = false,
                        provider = "cpu",
                    )
                )
                val t = OfflineTts(assetManager = null, config = config)
                if (t.sampleRate() <= 0) {
                    throw IllegalStateException("sherpa-onnx returned an invalid sample rate")
                }
                tts = t
                loadedVoiceId = info.voiceId
                Log.i(TAG, "Piper voice loaded: lang=${info.mlKitCode} gender=${info.gender} voice=${info.voiceId} sampleRate=${t.sampleRate()}")
                mainHandler.post { onResult(true, null) }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to load Piper voice ${info.voiceId}", e)
                tts = null
                loadedVoiceId = null
                mainHandler.post { onResult(false, e.message ?: "Failed to load natural voice") }
            }
        }
    }

    private fun unloadBlocking() {
        val latch = java.util.concurrent.CountDownLatch(1)
        worker.execute {
            releaseTtsLocked()
            latch.countDown()
        }
        try { latch.await(5, java.util.concurrent.TimeUnit.SECONDS) } catch (e: InterruptedException) { /* ignore */ }
    }

    private fun releaseTtsLocked() {
        try { tts?.release() } catch (e: Exception) { /* ignore */ }
        tts = null
        loadedVoiceId = null
    }

    // ---------------------------------------------------------------------
    // Synthesis + playback
    // ---------------------------------------------------------------------

    /** True only once exactly this voice is loaded and ready to speak. */
    fun isReadyFor(info: PiperVoiceInfo): Boolean = tts != null && loadedVoiceId == info.voiceId

    fun speak(text: String, info: PiperVoiceInfo, onDone: () -> Unit, onError: (String) -> Unit, onAudioStart: (() -> Unit)? = null) {
        val t = tts
        if (t == null || loadedVoiceId != info.voiceId) {
            onError("Natural voice not loaded")
            return
        }
        if (text.isBlank()) {
            onError("Nothing to speak")
            return
        }
        if (speaking.get()) stop()
        speaking.set(true)
        worker.execute {
            try {
                val t0 = System.nanoTime()
                val audio = t.generate(text = text, sid = 0, speed = 1.0f)
                val synthMs = (System.nanoTime() - t0) / 1_000_000
                val audioMs = if (audio.sampleRate > 0) (audio.samples.size.toLong() * 1000L / audio.sampleRate) else 0L
                val rtf = if (audioMs > 0) synthMs.toFloat() / audioMs.toFloat() else -1f
                Log.i(
                    TAG,
                    "Piper synth: voice=${info.voiceId} gender=${info.gender} chars=${text.length} samples=${audio.samples.size} " +
                        "sampleRate=${audio.sampleRate} synthMs=$synthMs audioMs=$audioMs rtf=%.2f".format(rtf)
                )
                val track = ensureAudioTrack(audio.sampleRate)
                track.play()
                // Unlike EspeakEngine's streaming callback, Piper synthesizes the
                // whole utterance into memory first (t.generate above) - so
                // "audio start" is simply "right before the one write() call",
                // fired synchronously for the same reason EspeakEngine's is (see
                // that class's onSynthDataReady doc comment).
                onAudioStart?.invoke()
                track.write(audio.samples, 0, audio.samples.size, AudioTrack.WRITE_BLOCKING)
                track.stop()
                speaking.set(false)
                mainHandler.post { onDone() }
            } catch (e: Throwable) {
                speaking.set(false)
                Log.e(TAG, "Piper synthesis failed", e)
                mainHandler.post { onError(e.message ?: "Natural voice synthesis failed") }
            }
        }
    }

    fun stop() {
        try {
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (e: Exception) { /* ignore */ }
        speaking.set(false)
    }

    fun release() {
        stop()
        try { audioTrack?.release() } catch (e: Exception) { /* ignore */ }
        audioTrack = null
        releaseTtsLocked()
        worker.shutdown()
    }

    private fun ensureAudioTrack(sampleRate: Int): AudioTrack {
        val existing = audioTrack
        if (existing != null && trackSampleRate == sampleRate) return existing
        try { existing?.release() } catch (e: Exception) { /* ignore */ }
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        ).coerceAtLeast(4096)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack = track
        trackSampleRate = sampleRate
        return track
    }

    companion object {
        private const val TAG = "PiperTtsEngine"

        // Real medium-tier Piper .onnx weights are ~60MB; anything drastically
        // smaller means the download was truncated.
        private const val MIN_MODEL_BYTES = 10_000_000L

        // sherpa-onnx's native VITS loader (OfflineTtsVitsModelConfig::Validate)
        // requires exactly these four files inside --vits-data-dir.
        private val REQUIRED_ESPEAK_DATA_FILES = listOf("phontab", "phonindex", "phondata", "intonations")
    }
}
