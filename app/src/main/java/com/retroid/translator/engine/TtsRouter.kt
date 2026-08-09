package com.retroid.translator.engine

import android.content.Context
import android.util.Log

/**
 * Single entry point every screen speaks through. Picks the best available
 * engine for a (language, gender) pair and hides the choice from callers:
 *
 *  - a downloaded Piper natural voice matching both language AND gender
 *    (see [PiperVoiceCatalog]) -> used if present
 *  - otherwise the bundled eSpeak NG voice for that language, with the
 *    matching gender variant (100+ languages, always available, zero
 *    download - this is the universal floor: every language this app
 *    supports has a working male option and a working female option)
 *
 * Piper is a strict upgrade path, never a replacement: if loading or
 * synthesizing with it fails for any reason, this transparently falls back
 * to eSpeak for that call rather than surfacing an error, so a flaky/corrupt
 * voice pack degrades to "sounds robotic again" instead of "stopped working".
 * Most languages only ever hit the eSpeak path, since Piper's own catalog
 * covers a small subset of languages and rarely both genders - see
 * [PiperVoiceCatalog]'s doc comment.
 */
class TtsRouter(private val espeak: EspeakEngine, private val piper: PiperTtsEngine) {

    fun speak(text: String, langCode: String, gender: VoiceGender, onDone: () -> Unit, onError: (String) -> Unit) {
        val info = PiperVoiceCatalog.forLanguageAndGender(langCode, gender)
        if (info != null && piper.isVoiceDownloaded(info)) {
            if (piper.isReadyFor(info)) {
                speakPiper(info, text, langCode, gender, onDone, onError)
            } else {
                piper.loadVoiceAsync(info) { ok, err ->
                    if (ok) {
                        speakPiper(info, text, langCode, gender, onDone, onError)
                    } else {
                        Log.w(TAG, "Natural voice load failed for ${info.voiceId} ($err), falling back to eSpeak")
                        speakEspeak(text, langCode, gender, onDone, onError)
                    }
                }
            }
        } else {
            speakEspeak(text, langCode, gender, onDone, onError)
        }
    }

    private fun speakPiper(info: PiperVoiceInfo, text: String, langCode: String, gender: VoiceGender, onDone: () -> Unit, onError: (String) -> Unit) {
        piper.speak(text, info, onDone = onDone, onError = { err ->
            Log.w(TAG, "Natural voice synthesis failed for ${info.voiceId} ($err), falling back to eSpeak")
            speakEspeak(text, langCode, gender, onDone, onError)
        })
    }

    private fun speakEspeak(text: String, langCode: String, gender: VoiceGender, onDone: () -> Unit, onError: (String) -> Unit) {
        if (!espeak.ready) {
            onError("Offline speech engine still starting up, try again in a moment")
            return
        }
        if (!espeak.supportsLanguage(langCode)) {
            onError("No offline voice for this language yet")
            return
        }
        espeak.speak(text, langCode, gender, onDone, onError)
    }

    fun stop() {
        piper.stop()
        espeak.stop()
    }

    /** Whether a natural-voice catalog entry exists for this exact (language, gender) pair. */
    fun hasNaturalVoiceOption(langCode: String, gender: VoiceGender): Boolean =
        PiperVoiceCatalog.forLanguageAndGender(langCode, gender) != null

    fun isNaturalVoiceDownloaded(langCode: String, gender: VoiceGender): Boolean {
        val info = PiperVoiceCatalog.forLanguageAndGender(langCode, gender) ?: return false
        return piper.isVoiceDownloaded(info)
    }

    fun downloadNaturalVoice(
        context: Context,
        langCode: String,
        gender: VoiceGender,
        onProgress: (percent: Int) -> Unit = {},
        onDone: (success: Boolean, error: String?) -> Unit
    ) {
        val info = PiperVoiceCatalog.forLanguageAndGender(langCode, gender)
        if (info == null) {
            onDone(false, "No natural voice available for this language/gender yet")
            return
        }
        piper.downloadVoice(context, info, onProgress, onDone)
    }

    fun naturalVoiceInfo(langCode: String, gender: VoiceGender): PiperVoiceInfo? =
        PiperVoiceCatalog.forLanguageAndGender(langCode, gender)

    /** Short label for "what will actually speak this (language, gender) right now", for status text in the UI. */
    fun activeEngineLabel(langCode: String, gender: VoiceGender): String = when {
        isNaturalVoiceDownloaded(langCode, gender) -> "natural voice"
        espeak.supportsLanguage(langCode) -> "eSpeak (built-in, robotic)"
        else -> "no voice available yet"
    }

    companion object {
        private const val TAG = "TtsRouter"
    }
}
