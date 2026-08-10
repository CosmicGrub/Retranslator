package com.retroid.translator.prototype

import android.content.Context
import android.util.Log
import com.retroid.translator.audio.AudioResample
import com.retroid.translator.audio.WavFileWriter
import java.io.File

/**
 * Real recorded human-speech test clips for
 * docs/specs/fold5-adaptation.md §4's validation prerequisite: "repeat this
 * measurement with real human speech ... before wiring this into
 * ConversationsFragment" (§4's own numbered recommendation list, item 1).
 *
 * [TestAudioSynth] (still used, unmodified in spirit — only its resampler
 * was extracted out to [AudioResample] for reuse here) produces synthetic
 * eSpeak-TTS audio: clean, single-voice, no background noise, no accent
 * variation. This object instead bundles 6 short clips of REAL crowdsourced
 * human speech (2 English speakers, 3 Spanish speakers — see
 * `assets/real_speech_corpus/ATTRIBUTION.txt` for the exact source, license
 * (CC BY-SA 4.0), and citation for each), and produces the exact same
 * [TestAudioSynth.Clip] type so [DualRecognizerPrototype.evaluateClip] can
 * run against either source without any change to that evaluation code -
 * only the audio's origin differs.
 *
 * IMPORTANT — what this still is NOT: a live human speaking into this
 * device's microphone. These are real recordings, but pre-recorded, studio-
 * quality (48kHz, quiet room, no phone self-noise/room acoustics/mic
 * capsule coloration), played back through the exact same
 * chunk-feeding-a-Recognizer code path a live mic buffer would use rather
 * than actually captured by this device's AudioRecord. That gap is called
 * out explicitly in this project's report — "a literal live human speaking
 * into the device" remains unverified and is something no agent can
 * produce.
 */
object RealSpeechCorpus {
    private const val TAG = "DualRecoProto"
    const val TARGET_SAMPLE_RATE = 16000
    private const val ASSET_DIR = "real_speech_corpus"

    private data class RealPhrase(val label: String, val langCode: String, val assetFile: String, val text: String)

    // See assets/real_speech_corpus/ATTRIBUTION.txt for full source/license/citation per clip.
    private val PHRASES = listOf(
        RealPhrase("en_r1", "en", "en_r1.wav", "Four boys lurk outside his house"),
        RealPhrase("en_r2", "en", "en_r2.wav", "The score is tied at 1 point"),
        RealPhrase("en_r3", "en", "en_r3.wav", "I pulled it off the bookshelf for you"),
        RealPhrase("es_r1", "es", "es_r1.wav", "¿Cuánto cuesta una bicicleta para niños?"),
        RealPhrase("es_r2", "es", "es_r2.wav", "Por las tardes se escucha música en la Plaza."),
        RealPhrase("es_r3", "es", "es_r3.wav", "Me duele mucho la cabeza cuando hago ejercicio.")
    )

    /** Blocking; call from a background thread. Returns the resampled real-speech clips in [PHRASES] order. */
    fun loadAll(context: Context, outDir: File): List<TestAudioSynth.Clip> {
        outDir.mkdirs()
        val clips = mutableListOf<TestAudioSynth.Clip>()
        for (phrase in PHRASES) {
            val assetPath = "$ASSET_DIR/${phrase.assetFile}"
            val rawWavBytes = context.assets.open(assetPath).use { it.readBytes() }
            if (rawWavBytes.size <= 44) {
                throw IllegalStateException("real_speech_corpus asset '$assetPath' is too small (${rawWavBytes.size} bytes) - missing/corrupt?")
            }
            val sourceRate = readWavSampleRate(rawWavBytes)
            val pcm = rawWavBytes.copyOfRange(44, rawWavBytes.size)

            val resampled = AudioResample.resampleS16Mono(pcm, sourceRate, TARGET_SAMPLE_RATE)
            val file = File(outDir, "${phrase.label}.wav")
            val writer = WavFileWriter(file, TARGET_SAMPLE_RATE)
            writer.write(resampled, resampled.size)
            writer.close()

            val durationMs = (resampled.size / 2).toLong() * 1000L / TARGET_SAMPLE_RATE
            Log.i(
                TAG,
                "RealSpeechCorpus: label=${phrase.label} lang=${phrase.langCode} text=\"${phrase.text}\" " +
                    "sourceRate=$sourceRate sourceBytes=${pcm.size} resampledBytes=${resampled.size} " +
                    "durationMs=$durationMs file=${file.path}"
            )
            clips.add(TestAudioSynth.Clip(phrase.label, phrase.langCode, phrase.text, file))
        }
        return clips
    }

    /** Reads the sample rate out of a canonical 44-byte WAV header (offset 24, LE32) - same layout WavFileWriter always writes, and what these bundled assets use (verified: RIFF/WAVE/fmt /16-byte fmt chunk/data, no extra chunks). */
    private fun readWavSampleRate(wavBytes: ByteArray): Int {
        require(wavBytes.size >= 28) { "WAV too short to contain a sample-rate field" }
        return (wavBytes[24].toInt() and 0xFF) or
            ((wavBytes[25].toInt() and 0xFF) shl 8) or
            ((wavBytes[26].toInt() and 0xFF) shl 16) or
            ((wavBytes[27].toInt() and 0xFF) shl 24)
    }
}
