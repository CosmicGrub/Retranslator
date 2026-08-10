package com.retroid.translator.prototype

import android.content.Context
import android.util.Log
import com.reecedunn.espeak.SpeechSynthesis
import com.reecedunn.espeak.VoiceVariant
import com.retroid.translator.audio.AudioResample
import com.retroid.translator.audio.WavFileWriter
import com.retroid.translator.engine.EspeakDataInstaller
import com.retroid.translator.engine.EspeakLanguageMap
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Synthesizes short, real-sentence test utterances (English + Spanish) as
 * 16kHz/16-bit/mono WAV files, so [DualRecognizerPrototype] has reproducible
 * ground-truth audio without a human speaker involved.
 *
 * There is no existing "TTS straight to a WAV file" path in this codebase —
 * [com.retroid.translator.engine.EspeakEngine] only exposes play-to-speaker
 * (it streams into an [android.media.AudioTrack] and never hands raw PCM to
 * its caller). Rather than modify that shipped engine to add a capture hook,
 * this drives the same underlying `com.reecedunn.espeak.SpeechSynthesis` JNI
 * class directly (the exact class EspeakEngine wraps), using the same
 * init/voice-selection sequence, but with a [SpeechSynthesis.SynthReadyCallback]
 * that appends PCM bytes to a buffer instead of an AudioTrack. The resulting
 * WAV is written with the project's existing [WavFileWriter], matching the
 * exact PCM/WAV conventions [com.retroid.translator.audio.MicPipeline] uses
 * for real mic capture.
 *
 * eSpeak NG's native sample rate (observed on-device, logged below) is not
 * necessarily 16kHz, so the captured PCM is linearly resampled down to
 * [TARGET_SAMPLE_RATE] before being written — this is what lets the output
 * be fed into Vosk exactly as if it were live 16kHz mic audio.
 */
object TestAudioSynth {
    private const val TAG = "DualRecoProto"
    const val TARGET_SAMPLE_RATE = 16000

    data class Clip(val label: String, val langCode: String, val text: String, val file: File)

    private data class Phrase(val label: String, val langCode: String, val text: String)

    // Real sentences, not word salad — at least 3 per language, per the spec.
    private val PHRASES = listOf(
        Phrase("en_1", "en", "Where is the train station?"),
        Phrase("en_2", "en", "I would like a cup of coffee, please."),
        Phrase("en_3", "en", "What time does the museum open?"),
        Phrase("es_1", "es", "¿Dónde está la estación de tren?"),
        Phrase("es_2", "es", "Quisiera una taza de café, por favor."),
        Phrase("es_3", "es", "¿A qué hora abre el museo?")
    )

    /** Blocking; call from a background thread. Returns the synthesized clips in [PHRASES] order. */
    fun synthesizeAll(context: Context, outDir: File): List<Clip> {
        outDir.mkdirs()
        if (!EspeakDataInstaller.ensureInstalled(context)) {
            throw IllegalStateException("espeak-ng data install failed")
        }

        val captureBuffer = ByteArrayOutputStream()
        val callback = object : SpeechSynthesis.SynthReadyCallback {
            override fun onSynthDataReady(audioData: ByteArray?) {
                if (audioData != null && audioData.isNotEmpty()) captureBuffer.write(audioData)
            }
            override fun onSynthDataComplete() { /* nativeSynthesize() is blocking; nothing to do here */ }
        }
        val synth = SpeechSynthesis(context, callback)
        if (synth.sampleRate == 0) {
            throw IllegalStateException("espeak-ng failed to initialize (sampleRate=0)")
        }
        val nativeRate = synth.sampleRate
        Log.i(TAG, "TestAudioSynth: espeak-ng native sampleRate=$nativeRate, target=$TARGET_SAMPLE_RATE")

        val voicesByLang = synth.availableVoices.associateBy { it.locale.language }
        val variant = VoiceVariant.parseVoiceVariant("female")
            ?: throw IllegalStateException("VoiceVariant.parseVoiceVariant(\"female\") returned null")

        val clips = mutableListOf<Clip>()
        for (phrase in PHRASES) {
            val espeakLang = EspeakLanguageMap.toEspeakLanguage(phrase.langCode)
            val voice = voicesByLang[espeakLang]
                ?: throw IllegalStateException("No bundled espeak-ng voice for language '${phrase.langCode}' (espeak code '$espeakLang')")
            synth.setVoice(voice, variant)

            captureBuffer.reset()
            synth.synthesize(phrase.text, false)
            val rawPcm = captureBuffer.toByteArray()
            if (rawPcm.isEmpty()) {
                throw IllegalStateException("espeak-ng produced zero bytes for \"${phrase.text}\"")
            }

            val resampled = AudioResample.resampleS16Mono(rawPcm, nativeRate, TARGET_SAMPLE_RATE)
            val file = File(outDir, "${phrase.label}.wav")
            val writer = WavFileWriter(file, TARGET_SAMPLE_RATE)
            writer.write(resampled, resampled.size)
            writer.close()

            val durationMs = (resampled.size / 2).toLong() * 1000L / TARGET_SAMPLE_RATE
            Log.i(
                TAG,
                "TestAudioSynth: label=${phrase.label} lang=${phrase.langCode} text=\"${phrase.text}\" " +
                    "nativeBytes=${rawPcm.size} resampledBytes=${resampled.size} durationMs=$durationMs file=${file.path}"
            )
            clips.add(Clip(phrase.label, phrase.langCode, phrase.text, file))
        }
        return clips
    }

}
