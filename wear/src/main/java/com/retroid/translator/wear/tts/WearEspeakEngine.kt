package com.retroid.translator.wear.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.reecedunn.espeak.SpeechSynthesis
import com.reecedunn.espeak.Voice
import com.reecedunn.espeak.VoiceVariant
import com.retroid.translator.wear.diagnostics.WearDiag
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * :wear's own offline, in-app text-to-speech, built directly on the eSpeak
 * NG native library - the same engine `:app`'s `EspeakEngine` wraps, now
 * actually buildable here.
 *
 * **Why this exists now and didn't before**: `SystemTtsSpeaker`'s own doc
 * comment (see that class) previously stated eSpeak/sherpa-onnx "were NOT
 * ported" because both were vendored in `:app` as prebuilt `arm64-v8a`-only
 * `.so` files with no in-repo build recipe, and the real Watch6 Classic is
 * 32-bit-ARM-only (`armeabi-v7a`) - those exact binaries could never load on
 * it. That was a real, correctly-identified constraint at the time, but the
 * conclusion drawn from it ("building armeabi-v7a from source is a real
 * undertaking, out of scope") turned out to be based on an unchecked
 * assumption. Investigation this pass found upstream espeak-ng's own
 * *official signed release* (`github.com/espeak-ng/espeak-ng/releases/
 * download/1.52.0/espeak-1.52.0-signed.apk`) already bundles a prebuilt
 * `lib/armeabi-v7a/libttsespeak.so` alongside its `arm64-v8a` one - no NDK
 * cross-compile needed at all, just extracting a second ABI from a release
 * artifact this project already trusts (its `arm64-v8a/libttsespeak.so` is
 * byte-for-byte identical, sha256
 * `1c4983b276367420e720c0b681197ceee442a18cedf470b7c025dde55e20f2e7`, to the
 * one in that same release APK - confirmed directly, not assumed - proving
 * this project's existing phone binary already came from this exact
 * release). The armeabi-v7a build was verified before vendoring: real ELF
 * header (`ELFCLASS32`/`EM_ARM`), and all 11 JNI symbols
 * `SpeechSynthesis.java`'s native methods require
 * (`Java_com_reecedunn_espeak_SpeechSynthesis_native*`) present by name in
 * the binary, identical to the already-working arm64-v8a build's symbol
 * set.
 *
 * Structurally this is a near-verbatim port of `:app`'s `EspeakEngine`
 * (same streaming-PCM-into-AudioTrack design, same worker-thread execution
 * model) - trimmed of the male/female gender toggle (`:wear` has no gender
 * UI yet; always synthesizes with the female voice variant, matching the
 * phone app's own default) since adding that toggle is a UI decision outside
 * this pass's scope (proving/wiring the native TTS stack, not adding new
 * settings surface).
 */
class WearEspeakEngine(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    @Volatile private var synth: SpeechSynthesis? = null
    @Volatile private var audioTrack: AudioTrack? = null
    private var voicesByLang: Map<String, Voice> = emptyMap()

    @Volatile var ready = false
        private set
    @Volatile var initFailed = false
        private set

    /**
     * Set by [release] and never cleared - this engine is deliberately
     * single-use, because [release] shuts [worker] down for good and a
     * `ThreadPoolExecutor` cannot be restarted.
     *
     * **Real crash this exists to prevent** (found on the real Watch6
     * Classic on 2026-08-25, see docs/specs/watch6-classic-adaptation.md
     * section 14's addendum for the captured logcat): launching while the
     * display is dozing gets `MainActivity` destroyed almost immediately, so
     * `onDestroy -> TranslateController.release() -> release()` here runs
     * *while [initBlocking] is still executing on [worker]*.
     * `ExecutorService.shutdown()` lets that already-running init task run to
     * completion, so init then posted its `onReady(true)` to the main thread,
     * `TranslateController`'s startup self-test called [speak], and [speak]'s
     * `worker.execute` threw `RejectedExecutionException` on the main thread
     * - uncaught, taking the process with it. Guarding the submits alone
     * would not have been enough: the honest fix is that a released engine
     * must stop calling *back* as well as stop accepting work, which is what
     * this flag gates.
     */
    @Volatile private var released = false

    private val speaking = AtomicBoolean(false)
    @Volatile private var currentOnDone: (() -> Unit)? = null
    @Volatile private var currentOnError: ((String) -> Unit)? = null
    @Volatile private var currentLangCode: String? = null
    private var framesWrittenThisUtterance = 0L

    private val synthCallback = object : SpeechSynthesis.SynthReadyCallback {
        override fun onSynthDataReady(audioData: ByteArray?) {
            if (audioData == null || audioData.isEmpty()) return
            try {
                audioTrack?.write(audioData, 0, audioData.size)
                // 16-bit mono PCM -> 2 bytes/frame. Logged per-utterance below
                // as real "audio was actually produced" evidence, the same
                // standard the phone app's EspeakEngine uses.
                framesWrittenThisUtterance += audioData.size / 2
            } catch (e: Exception) {
                WearDiag.e(TAG, "AudioTrack write failed", e)
            }
        }

        override fun onSynthDataComplete() {
            speaking.set(false)
            val done = currentOnDone
            currentOnDone = null
            currentOnError = null
            Log.i(TAG, "eSpeak synth: lang=$currentLangCode framesWritten=$framesWrittenThisUtterance")
            framesWrittenThisUtterance = 0L
            try { audioTrack?.stop() } catch (e: Exception) { /* ignore */ }
            mainHandler.post { done?.invoke() }
        }
    }

    /** Blocking init - call off the main thread. */
    fun initBlocking() {
        if (ready || initFailed) return
        try {
            if (!EspeakDataInstaller.ensureInstalled(appContext)) {
                initFailed = true
                return
            }
            val s = SpeechSynthesis(appContext, synthCallback)
            if (s.sampleRate == 0) {
                WearDiag.e(TAG, "espeak-ng failed to initialize (sampleRate=0)")
                initFailed = true
                return
            }
            synth = s
            audioTrack = buildAudioTrack(s.sampleRate)
            voicesByLang = s.availableVoices.associateBy { it.locale.language }
            Log.i(TAG, "espeak-ng ready: sampleRate=${s.sampleRate}, voices=${voicesByLang.size}, version=${SpeechSynthesis.getVersion()}")
            ready = true
            // [release] can land mid-init (see [released]), in which case it
            // already tore the field down before the line above replaced it -
            // so the track built moments ago would leak a real AudioTrack for
            // the life of the process. Tear it down here rather than pretend
            // the ordering cannot happen.
            if (released) teardownAudio()
        } catch (e: Throwable) {
            WearDiag.e(TAG, "espeak-ng init failed", e)
            initFailed = true
        }
    }

    fun initAsync(onReady: (Boolean) -> Unit) {
        // A rejected submit here means [release] already ran, so there is no
        // longer anyone to call back - dropping it is the correct outcome,
        // not a swallowed error.
        submitToWorker {
            initBlocking()
            // The load-bearing half of the crash fix: init runs to completion
            // even after `worker.shutdown()`, so without this guard a
            // destroyed Activity's callback still fires and drives fresh work
            // back into the dead executor. See [released].
            mainHandler.post { if (!released) onReady(ready) }
        }
    }

    /** True if a bundled offline espeak-ng voice exists for this ML Kit-style language code. */
    fun supportsLanguage(langCode: String): Boolean =
        ready && voicesByLang.containsKey(EspeakLanguageMap.toEspeakLanguage(langCode))

    fun speak(
        text: String,
        langCode: String,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (released) {
            onError("Offline speech engine was shut down")
            return
        }
        if (!ready) {
            onError("Offline speech engine is not ready yet")
            return
        }
        val key = EspeakLanguageMap.toEspeakLanguage(langCode)
        val voice = voicesByLang[key]
        if (voice == null) {
            onError("No bundled offline voice for this language yet")
            return
        }
        if (text.isBlank()) {
            onError("Nothing to speak")
            return
        }
        if (speaking.get()) stop()
        currentOnDone = onDone
        currentOnError = onError
        currentLangCode = langCode
        speaking.set(true)
        val submitted = submitToWorker {
            try {
                val variant = VoiceVariant.parseVoiceVariant("female") ?: return@submitToWorker
                synth?.setVoice(voice, variant)
                audioTrack?.play()
                synth?.synthesize(text, false)
                if (speaking.compareAndSet(true, false)) {
                    val done = currentOnDone
                    currentOnDone = null
                    currentOnError = null
                    mainHandler.post { done?.invoke() }
                }
            } catch (e: Exception) {
                speaking.set(false)
                val err = currentOnError
                currentOnDone = null
                currentOnError = null
                mainHandler.post { err?.invoke(e.message ?: "Speech synthesis failed") }
            }
        }
        if (!submitted) {
            // [release] won the race against the `released` check above. Undo
            // the speaking bookkeeping and report through the caller's own
            // error path, so the "exactly one of onDone/onError always fires"
            // contract still holds here and callers cannot hang.
            speaking.set(false)
            currentOnDone = null
            currentOnError = null
            onError("Offline speech engine was shut down")
        }
    }

    fun stop() {
        try {
            synth?.stop()
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (e: Exception) { /* ignore */ }
        speaking.set(false)
    }

    fun release() {
        // Set first, so anything racing this on another thread observes the
        // engine as dead before the executor actually goes away. See
        // [released] for the crash that ordering prevents.
        released = true
        stop()
        teardownAudio()
        worker.shutdown()
    }

    private fun teardownAudio() {
        val track = audioTrack
        audioTrack = null
        try { track?.release() } catch (e: Exception) { /* ignore */ }
    }

    /**
     * Submits [task] to [worker], returning false instead of throwing when
     * the executor is already shut down.
     *
     * `Executors.newSingleThreadExecutor()` throws
     * `RejectedExecutionException` - an *unchecked* exception - from
     * `execute()` after `shutdown()`, which is exactly how this class used to
     * kill the process from a main-thread callback (see [released]). Every
     * submit goes through here so a post-[release] straggler degrades into a
     * dropped task instead of a crash.
     */
    private fun submitToWorker(task: () -> Unit): Boolean =
        try {
            worker.execute { task() }
            true
        } catch (e: RejectedExecutionException) {
            Log.i(TAG, "worker task dropped: engine already released")
            false
        }

    private fun buildAudioTrack(sampleRate: Int): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    companion object {
        private const val TAG = "WearEspeakEngine"
    }
}
