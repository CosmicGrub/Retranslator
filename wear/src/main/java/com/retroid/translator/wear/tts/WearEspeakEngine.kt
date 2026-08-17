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
import java.util.concurrent.Executors
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

    private var synth: SpeechSynthesis? = null
    private var audioTrack: AudioTrack? = null
    private var voicesByLang: Map<String, Voice> = emptyMap()

    @Volatile var ready = false
        private set
    @Volatile var initFailed = false
        private set

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
                Log.e(TAG, "AudioTrack write failed", e)
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
                Log.e(TAG, "espeak-ng failed to initialize (sampleRate=0)")
                initFailed = true
                return
            }
            synth = s
            audioTrack = buildAudioTrack(s.sampleRate)
            voicesByLang = s.availableVoices.associateBy { it.locale.language }
            Log.i(TAG, "espeak-ng ready: sampleRate=${s.sampleRate}, voices=${voicesByLang.size}, version=${SpeechSynthesis.getVersion()}")
            ready = true
        } catch (e: Throwable) {
            Log.e(TAG, "espeak-ng init failed", e)
            initFailed = true
        }
    }

    fun initAsync(onReady: (Boolean) -> Unit) {
        worker.execute {
            initBlocking()
            mainHandler.post { onReady(ready) }
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
        worker.execute {
            try {
                val variant = VoiceVariant.parseVoiceVariant("female") ?: return@execute
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
        stop()
        try { audioTrack?.release() } catch (e: Exception) { /* ignore */ }
        audioTrack = null
        worker.shutdown()
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
