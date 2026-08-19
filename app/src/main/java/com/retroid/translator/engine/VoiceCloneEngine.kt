package com.retroid.translator.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig
import com.retroid.translator.packs.LanguagePackPreferences
import com.retroid.translator.practice.WaveformReader
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Zero-shot voice cloning: synthesizes NEW text - words the user's mouth
 * never actually said - in the user's own vocal timbre, conditioned on a
 * short reference clip of their real voice. This is genuinely different
 * from this app's existing TTS engines ([EspeakEngine], [PiperTtsEngine]),
 * which always speak in a fixed, pre-trained voice - it's also different
 * from voice *conversion* (reshaping existing audio's timbre without
 * changing its content), which was explicitly ruled out as not serving the
 * actual ask (hearing the user's OWN accent/inflection carry across
 * translated phrases the user never spoke).
 *
 * **Real feasibility investigation (2026-08-18)** - see
 * docs/specs/fold5-adaptation.md's dated "Voice cloning" section for the
 * full citation trail; summarized here since it's why this class points
 * where it does:
 *
 * - This app's own already-vendored sherpa-onnx 1.13.5 (re-vendored for
 *   `fold5-quality-tier1-2`, see [PiperTtsEngine]'s doc comment) turned out
 *   to ALREADY contain full Kotlin bindings AND native (C++/JNI) support for
 *   two real zero-shot voice-cloning model families - `OfflineTtsZipVoiceModelConfig`
 *   and `OfflineTtsPocketModelConfig`, both confirmed present via `javap` on
 *   the vendored `sherpa-onnx-classes.jar`, and both confirmed present in
 *   the vendored `libsherpa-onnx-jni.so`'s real native strings
 *   (`offline-tts-zipvoice-impl.h`, `OfflineTtsPocketImpl`, etc.) - **zero
 *   re-vendoring, zero new native dependency was needed** to build this
 *   feature; the capability was already shipped, unused, in this exact app.
 *   [GenerationConfig] already exposes `referenceAudio`/`referenceSampleRate`/
 *   `referenceText` - exactly the zero-shot-cloning conditioning API this
 *   feature needs.
 * - **ZipVoice** (k2-fsa/ZipVoice, the same GitHub org that maintains
 *   sherpa-onnx itself) - confirmed **Apache-2.0** two independent ways: the
 *   code repo's license via the real GitHub API (`"license":{"key":"apache-2.0"}`),
 *   and the model-weights repo's real gated status via the Hugging Face API
 *   (`"gated":false`). The actual `.onnx` files this class downloads are
 *   hosted on sherpa-onnx's own official "tts-models" GitHub release - the
 *   exact same trusted distribution channel this app already uses for every
 *   Piper natural-voice pack ([PiperVoiceCatalog]) - confirmed by real,
 *   anonymous, no-login HTTPS downloads of the actual release assets
 *   (109,162,785 bytes for the ZipVoice-Distill int8 package, 54,157,409
 *   bytes for the separately-hosted `vocos_24khz.onnx` vocoder it also
 *   needs - MIT-licensed, `charactr/vocos-mel-24khz`, also `gated:false`).
 * - **PocketTTS** (Kyutai) was investigated as a real second candidate -
 *   genuinely multi-language (English/French/German/Italian/Portuguese/
 *   Spanish, a closer match to this app's own natural-voice language set
 *   than ZipVoice's Chinese+English) and also already has real, working
 *   Kotlin/native bindings in this exact vendored sherpa-onnx build. It was
 *   **deliberately not chosen**: its original Hugging Face repo
 *   (`kyutai/pocket-tts`) is gated (`"gated":"auto"`, a justification form
 *   required before download), and - decisively - the README bundled
 *   directly inside sherpa-onnx's own official redistribution of it
 *   (`sherpa-onnx-pocket-tts-int8-2026-01-26.tar.bz2`) states plainly "It is
 *   for non-commercial", even though the LICENSE file bundled alongside
 *   that same README is plain CC-BY-4.0 text with no such restriction
 *   spelled out. That's a real, disclosed license-cleanliness conflict this
 *   project already has an established, on-point precedent for refusing:
 *   `README.md`'s own Piper voice table already excludes
 *   `en_US-ryan-medium`/`en_US-hfc_female-medium` specifically for being
 *   CC-BY-**NC**-licensed, regardless of this app's own non-commercial
 *   personal use. Same bar, same call, applied here.
 * - ZipVoice's real, documented training languages are English and Chinese
 *   only - see [com.retroid.translator.voiceclone.VoiceCloneLanguageCoverage]
 *   for how that's surfaced honestly to the user (the model's text frontend
 *   is the same espeak-ng phonemizer this app already bundles for 100+
 *   languages - directly confirmed, the downloaded package includes a full
 *   `espeak-ng-data/` directory - so other languages CAN be attempted, but
 *   their voice-fidelity is real, honestly unverified territory, not a
 *   verified feature).
 * - Real Android inference-speed citation (no on-device measurement was
 *   possible this session - target device unreachable, see this app's task
 *   report): a real, independent Android integration of this exact model
 *   (`sherpa-onnx` GitHub issue #3439, "ZipVoice zero-shot TTS support for
 *   Android TTS Engine app") reports **RTF ~1.0 on a Pixel 10 Pro, CPU-only,
 *   zipvoice_distill int8** - generating ~5 seconds of audio took ~5
 *   seconds. The Fold 5's Snapdragon 8 Gen 2 (2023) is an older chipset than
 *   that device, so real-world latency here is expected to be somewhat
 *   higher, but still squarely in "wait a few seconds after tapping a
 *   button" territory, not real-time streaming - which is exactly the bar
 *   this feature was scoped against (an explicit-tap preview/practice
 *   action, matching [LlmAssistEngine]'s own "one bounded request" framing).
 *
 * Same single-resident-model, single-worker-thread, AudioTrack-based
 * playback shape as [PiperTtsEngine] (which this class's `speak`
 * implementation mirrors closely) - the only structural difference is that
 * every call also needs a reference clip + its transcript
 * ([com.retroid.translator.voiceclone.VoiceProfileStore]), since zero-shot
 * cloning conditions on both per call rather than loading a fixed voice
 * once.
 */
class VoiceCloneEngine(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private var tts: OfflineTts? = null
    @Volatile var isLoaded: Boolean = false
        private set

    private var audioTrack: AudioTrack? = null
    private var trackSampleRate = -1
    private val speaking = AtomicBoolean(false)

    // ---------------------------------------------------------------------
    // Model storage / download - two independent assets, same
    // Wi-Fi-gated/cellular-preference-respecting DownloadManager calls every
    // other pack in this app already uses.
    // ---------------------------------------------------------------------

    private fun modelRootDir(): File = File(appContext.filesDir, "voice-clone/model")
    private fun vocoderFile(): File = File(appContext.filesDir, "voice-clone/vocoder/$VOCODER_FILENAME")

    /** The extracted model directory (containing encoder/decoder/tokens/lexicon/espeak-ng-data), or null if not downloaded or incomplete - same "don't trust a partial extraction" discipline as [PiperTtsEngine.effectiveVoiceDir]. */
    fun effectiveModelDir(): File? {
        val root = modelRootDir()
        if (!root.exists()) return null
        val nested = File(root, MODEL_ARCHIVE_DIR_NAME)
        val direct = if (File(nested, "encoder.int8.onnx").exists()) nested else root
        return if (isCompleteModelDir(direct)) direct else null
    }

    private fun isCompleteModelDir(dir: File): Boolean {
        val required = listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt", "lexicon.txt")
        if (required.any { name -> File(dir, name).let { !it.isFile || it.length() <= 0L } }) return false
        val dataDir = File(dir, "espeak-ng-data")
        if (!dataDir.isDirectory) return false
        return REQUIRED_ESPEAK_DATA_FILES.all { File(dataDir, it).isFile }
    }

    fun isModelDownloaded(): Boolean = effectiveModelDir() != null

    fun isVocoderDownloaded(): Boolean {
        val f = vocoderFile()
        return f.isFile && f.length() >= MIN_VOCODER_BYTES
    }

    fun isFullyDownloaded(): Boolean = isModelDownloaded() && isVocoderDownloaded()

    fun downloadModel(context: Context, onProgress: (percent: Int) -> Unit = {}, onDone: (success: Boolean, error: String?) -> Unit) {
        DownloadManager.downloadAndExtractTarBz2(
            context, MODEL_URL, modelRootDir(),
            requireWifi = !LanguagePackPreferences.allowCellularDownloads(context),
            onProgress = onProgress
        ) { success, error ->
            var actualSuccess = success
            var actualError = error
            if (success && !isModelDownloaded()) {
                Log.w(TAG, "Voice-clone model failed completeness check after extraction; discarding it")
                DownloadManager.deleteDir(modelRootDir())
                actualSuccess = false
                actualError = "Downloaded pack was incomplete, please try again"
            }
            onDone(actualSuccess, actualError)
        }
    }

    fun downloadVocoder(context: Context, onProgress: (percent: Int) -> Unit = {}, onDone: (success: Boolean, error: String?) -> Unit) {
        DownloadManager.downloadFile(
            context, VOCODER_URL, vocoderFile(),
            requireWifi = !LanguagePackPreferences.allowCellularDownloads(context),
            onProgress = onProgress
        ) { success, error ->
            if (success && !isVocoderDownloaded()) {
                Log.w(TAG, "Voice-clone vocoder failed completeness check after download; discarding it")
                vocoderFile().delete()
                onDone(false, "Downloaded file was incomplete, please try again")
                return@downloadFile
            }
            onDone(success, error)
        }
    }

    fun deleteAll() {
        unloadBlocking()
        DownloadManager.deleteDir(modelRootDir())
        vocoderFile().delete()
    }

    // ---------------------------------------------------------------------
    // Model loading
    // ---------------------------------------------------------------------

    fun loadAsync(onResult: (success: Boolean, error: String?) -> Unit) {
        if (isLoaded && tts != null) {
            onResult(true, null)
            return
        }
        val dir = effectiveModelDir()
        if (dir == null) {
            onResult(false, "Voice-clone model not downloaded yet")
            return
        }
        if (!isVocoderDownloaded()) {
            onResult(false, "Voice-clone vocoder not downloaded yet")
            return
        }
        worker.execute {
            try {
                releaseTtsLocked()
                val config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        zipvoice = OfflineTtsZipVoiceModelConfig(
                            tokens = File(dir, "tokens.txt").absolutePath,
                            encoder = File(dir, "encoder.int8.onnx").absolutePath,
                            decoder = File(dir, "decoder.int8.onnx").absolutePath,
                            vocoder = vocoderFile().absolutePath,
                            dataDir = File(dir, "espeak-ng-data").absolutePath,
                            lexicon = File(dir, "lexicon.txt").absolutePath,
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
                isLoaded = true
                Log.i(TAG, "Voice-clone model loaded from ${dir.path}, sampleRate=${t.sampleRate()}")
                mainHandler.post { onResult(true, null) }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to load voice-clone model", e)
                tts = null
                isLoaded = false
                mainHandler.post { onResult(false, e.message ?: "Failed to load voice-clone model") }
            }
        }
    }

    private fun unloadBlocking() {
        val latch = CountDownLatch(1)
        worker.execute {
            releaseTtsLocked()
            latch.countDown()
        }
        try { latch.await(5, TimeUnit.SECONDS) } catch (e: InterruptedException) { /* ignore */ }
    }

    private fun releaseTtsLocked() {
        try { tts?.release() } catch (e: Exception) { /* ignore */ }
        tts = null
        isLoaded = false
    }

    // ---------------------------------------------------------------------
    // Synthesis + playback
    // ---------------------------------------------------------------------

    /**
     * Synthesizes [text] in the voice conditioned by [referenceAudioFile] +
     * [referenceText] (the exact transcript of that clip - a mismatch
     * degrades quality noticeably, per ZipVoice's own documentation). Reads
     * the reference WAV via [WaveformReader.readFloatSamples] - the same
     * WAV-reading utility this app already uses for waveform thumbnails,
     * extended (not duplicated) for this feature.
     */
    fun speak(
        text: String,
        referenceAudioFile: File,
        referenceText: String,
        onDone: () -> Unit,
        onError: (String) -> Unit,
        onAudioStart: (() -> Unit)? = null
    ) {
        val t = tts
        if (t == null || !isLoaded) {
            onError("Voice-clone model not loaded")
            return
        }
        if (text.isBlank()) {
            onError("Nothing to speak")
            return
        }
        if (referenceText.isBlank() || !referenceAudioFile.isFile) {
            onError("No voice profile - set up voice cloning in Settings first")
            return
        }
        if (speaking.get()) stop()
        speaking.set(true)
        worker.execute {
            try {
                val (refSamples, refRate) = WaveformReader.readFloatSamples(referenceAudioFile)
                if (refSamples.isEmpty() || refRate <= 0) {
                    speaking.set(false)
                    mainHandler.post { onError("Voice profile audio is missing or unreadable") }
                    return@execute
                }
                val genConfig = GenerationConfig(
                    speed = 1.0f,
                    referenceAudio = refSamples,
                    referenceSampleRate = refRate,
                    referenceText = referenceText,
                    numSteps = NUM_STEPS,
                )
                val t0 = System.nanoTime()
                val audio = t.generateWithConfig(text, genConfig)
                val synthMs = (System.nanoTime() - t0) / 1_000_000
                if (audio.samples.isEmpty()) {
                    speaking.set(false)
                    mainHandler.post { onError("Voice-clone synthesis produced no audio") }
                    return@execute
                }
                val audioMs = if (audio.sampleRate > 0) (audio.samples.size.toLong() * 1000L / audio.sampleRate) else 0L
                val rtf = if (audioMs > 0) synthMs.toFloat() / audioMs.toFloat() else -1f
                Log.i(
                    TAG,
                    "Voice-clone synth: chars=${text.length} samples=${audio.samples.size} " +
                        "sampleRate=${audio.sampleRate} synthMs=$synthMs audioMs=$audioMs rtf=%.2f".format(rtf)
                )
                val track = ensureAudioTrack(audio.sampleRate)
                track.play()
                onAudioStart?.invoke()
                track.write(audio.samples, 0, audio.samples.size, AudioTrack.WRITE_BLOCKING)
                track.stop()
                speaking.set(false)
                mainHandler.post { onDone() }
            } catch (e: Throwable) {
                speaking.set(false)
                Log.e(TAG, "Voice-clone synthesis failed", e)
                mainHandler.post { onError(e.message ?: "Voice-clone synthesis failed") }
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
        private const val TAG = "VoiceCloneEngine"

        // ZipVoice-Distill (int8) - k2-fsa/sherpa-onnx's own official
        // "tts-models" GitHub release (Apache-2.0). Real Content-Length
        // confirmed via `gh release view tts-models --repo k2-fsa/sherpa-onnx`:
        // 109,162,785 bytes.
        const val MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-zipvoice-distill-int8-zh-en-emilia.tar.bz2"
        const val MODEL_ARCHIVE_DIR_NAME = "sherpa-onnx-zipvoice-distill-int8-zh-en-emilia"
        const val MODEL_APPROX_SIZE_MIB = 105

        // Vocos 24kHz vocoder - a separate, shared download the upstream
        // Python reference example (python-api-examples/zipvoice-tts.py)
        // also fetches independently of the model package itself. MIT
        // license (charactr/vocos-mel-24khz on Hugging Face, "gated":false).
        // Real Content-Length: 54,157,409 bytes.
        const val VOCODER_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/vocoder-models/vocos_24khz.onnx"
        const val VOCODER_FILENAME = "vocos_24khz.onnx"
        const val VOCODER_APPROX_SIZE_MIB = 52

        const val TOTAL_APPROX_SIZE_MIB = MODEL_APPROX_SIZE_MIB + VOCODER_APPROX_SIZE_MIB

        private const val MIN_VOCODER_BYTES = 40_000_000L
        private val REQUIRED_ESPEAK_DATA_FILES = listOf("phontab", "phonindex", "phondata", "intonations")

        // Matches the upstream reference example's own choice
        // (python-api-examples/zipvoice-tts.py uses num_steps=4) - a real
        // speed/quality tradeoff point sherpa-onnx's own example ships with,
        // not a guess.
        private const val NUM_STEPS = 4
    }
}
