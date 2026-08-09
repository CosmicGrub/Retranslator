package com.retroid.translator.engine

import android.content.Context
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
 * be downloaded before this can speak a given language - [TtsRouter] is what
 * decides whether to use this or fall back to eSpeak.
 *
 * Only one voice is kept loaded/resident at a time - loading a different
 * language's voice releases the previous native OfflineTts instance first
 * (same "one model in RAM" discipline as [VoskEngine], since a Piper medium
 * voice's ONNX weights are themselves ~60MB and this device has ~1GB usable
 * RAM alongside everything else already running).
 */
class PiperTtsEngine(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private var tts: OfflineTts? = null
    @Volatile var loadedLangCode: String? = null
        private set

    private var audioTrack: AudioTrack? = null
    private var trackSampleRate = -1
    private val speaking = AtomicBoolean(false)

    // ---------------------------------------------------------------------
    // Voice-pack storage / download
    // ---------------------------------------------------------------------

    fun voiceRootDir(langCode: String): File = File(appContext.filesDir, "piper-voices/$langCode")

    /** The extracted voice directory (containing `<voiceId>.onnx`, `tokens.txt`, `espeak-ng-data/`), or null if not downloaded. */
    fun effectiveVoiceDir(langCode: String): File? {
        val info = PiperVoiceCatalog.forLanguage(langCode) ?: return null
        val root = voiceRootDir(langCode)
        if (!root.exists()) return null
        // The archive extracts into one nested "vits-piper-<voiceId>" folder.
        val nested = File(root, "vits-piper-${info.voiceId}")
        val direct = if (File(nested, "${info.voiceId}.onnx").exists()) nested else root
        return if (File(direct, "${info.voiceId}.onnx").exists()) direct else null
    }

    fun isVoiceDownloaded(langCode: String): Boolean = effectiveVoiceDir(langCode) != null

    fun downloadVoice(
        context: Context,
        langCode: String,
        onProgress: (percent: Int) -> Unit = {},
        onDone: (success: Boolean, error: String?) -> Unit
    ) {
        val info = PiperVoiceCatalog.forLanguage(langCode)
        if (info == null) {
            onDone(false, "No natural voice available for this language yet")
            return
        }
        DownloadManager.downloadAndExtractTarBz2(
            context, info.url, voiceRootDir(langCode), requireWifi = true,
            onProgress = onProgress
        ) { success, error ->
            if (success && loadedLangCode == langCode) {
                // The previously-loaded voice for this language, if any, is now stale.
                unloadBlocking()
            }
            onDone(success, error)
        }
    }

    fun deleteVoice(langCode: String) {
        if (loadedLangCode == langCode) unloadBlocking()
        DownloadManager.deleteDir(voiceRootDir(langCode))
    }

    // ---------------------------------------------------------------------
    // Model loading
    // ---------------------------------------------------------------------

    fun loadVoiceAsync(langCode: String, onResult: (success: Boolean, error: String?) -> Unit) {
        if (loadedLangCode == langCode && tts != null) {
            onResult(true, null)
            return
        }
        worker.execute {
            try {
                val dir = effectiveVoiceDir(langCode)
                val info = PiperVoiceCatalog.forLanguage(langCode)
                if (dir == null || info == null) {
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
                loadedLangCode = langCode
                Log.i(TAG, "Piper voice loaded: lang=$langCode voice=${info.voiceId} sampleRate=${t.sampleRate()}")
                mainHandler.post { onResult(true, null) }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to load Piper voice for $langCode", e)
                tts = null
                loadedLangCode = null
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
        loadedLangCode = null
    }

    // ---------------------------------------------------------------------
    // Synthesis + playback
    // ---------------------------------------------------------------------

    /** True only once a voice for exactly this language is loaded and ready to speak. */
    fun isReadyFor(langCode: String): Boolean = tts != null && loadedLangCode == langCode

    fun speak(text: String, langCode: String, onDone: () -> Unit, onError: (String) -> Unit) {
        val t = tts
        if (t == null || loadedLangCode != langCode) {
            onError("Natural voice not loaded for this language")
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
                    "Piper synth: lang=$langCode chars=${text.length} samples=${audio.samples.size} " +
                        "sampleRate=${audio.sampleRate} synthMs=$synthMs audioMs=$audioMs rtf=%.2f".format(rtf)
                )
                val track = ensureAudioTrack(audio.sampleRate)
                track.play()
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
    }
}
