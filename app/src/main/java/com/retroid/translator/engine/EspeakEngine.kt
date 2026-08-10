package com.retroid.translator.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
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
 * Fully offline, in-app text-to-speech built directly on the eSpeak NG native
 * library (bundled as jniLibs/libttsespeak.so + assets/espeak-ng-data — no
 * separate TTS engine app, no network, no Play Store dependency). This
 * bypasses the Android TextToSpeech framework entirely: we call the native
 * synthesizer ourselves and stream the resulting PCM straight into an
 * AudioTrack.
 */
class EspeakEngine(context: Context) {
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
    @Volatile private var currentOnAudioStart: (() -> Unit)? = null
    @Volatile private var currentLangCode: String? = null
    @Volatile private var currentGender: VoiceGender? = null
    private var framesWrittenThisUtterance = 0L

    private val synthCallback = object : SpeechSynthesis.SynthReadyCallback {
        override fun onSynthDataReady(audioData: ByteArray?) {
            if (audioData == null || audioData.isEmpty()) return
            try {
                if (framesWrittenThisUtterance == 0L) {
                    // First real PCM bytes for this utterance about to reach the
                    // AudioTrack - the genuine "TTS audio start" instant, not a
                    // proxy for it. Fired synchronously (not posted to main) so a
                    // latency-measuring caller (ContinuousConversationController)
                    // gets an accurate System.nanoTime() rather than one skewed
                    // by main-thread message-queue delay; this runs on
                    // EspeakEngine's own worker thread, same as onSynthDataReady
                    // always has - callers that touch UI from this callback must
                    // hop threads themselves.
                    currentOnAudioStart?.invoke()
                }
                audioTrack?.write(audioData, 0, audioData.size)
                // 16-bit mono PCM -> 2 bytes/frame. Logged per-utterance below as
                // the same kind of hard "real audio was produced" evidence used
                // for Piper's sample counts, rather than just "no exception".
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
            currentOnAudioStart = null
            Log.i(
                TAG,
                "eSpeak synth: lang=$currentLangCode gender=$currentGender framesWritten=$framesWrittenThisUtterance"
            )
            framesWrittenThisUtterance = 0L
            try {
                audioTrack?.stop()
            } catch (e: Exception) { /* ignore */ }
            mainHandler.post { done?.invoke() }
        }
    }

    /** Blocking init — call off the main thread (e.g. from [worker] or a caller-provided thread). */
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

    fun availableLanguageCodes(): Set<String> = voicesByLang.keys

    fun speak(
        text: String,
        langCode: String,
        gender: VoiceGender = VoiceGender.FEMALE,
        onDone: () -> Unit,
        onError: (String) -> Unit,
        onAudioStart: (() -> Unit)? = null
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
        // Single-flight: stop whatever is currently speaking.
        if (speaking.get()) {
            stop()
        }
        currentOnDone = onDone
        currentOnError = onError
        currentOnAudioStart = onAudioStart
        currentLangCode = langCode
        currentGender = gender
        speaking.set(true)
        worker.execute {
            try {
                // GENDER_MALE/GENDER_FEMALE (via nativeSetVoiceByProperties) lets
                // espeak-ng pick a gender-appropriate variant for *this* voice's
                // language itself, rather than hardcoding a single numbered
                // variant (e.g. "m3") that may not exist/sound right for every
                // one of the 100+ bundled languages.
                val variantName = if (gender == VoiceGender.MALE) "male" else "female"
                val variant = VoiceVariant.parseVoiceVariant(variantName) ?: VoiceVariant.parseVoiceVariant("female")
                synth?.setVoice(voice, variant!!)
                audioTrack?.play()
                synth?.synthesize(text, false)
                // If the native call returned without ever invoking onSynthDataComplete
                // (defensive; normally it does), make sure we still resolve the callback.
                if (speaking.compareAndSet(true, false)) {
                    val done = currentOnDone
                    currentOnDone = null
                    currentOnError = null
                    currentOnAudioStart = null
                    mainHandler.post { done?.invoke() }
                }
            } catch (e: Exception) {
                speaking.set(false)
                val err = currentOnError
                currentOnDone = null
                currentOnError = null
                currentOnAudioStart = null
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
        try {
            audioTrack?.release()
        } catch (e: Exception) { /* ignore */ }
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
        private const val TAG = "EspeakEngine"
    }
}
