package com.retroid.translator.engine

import android.util.Log

/**
 * Single entry point every screen speaks through. Picks the best available
 * engine for a language and hides the choice from callers:
 *
 *  - a downloaded Piper natural voice (see [PiperVoiceCatalog]) -> used if present
 *  - otherwise the bundled eSpeak NG voice (100+ languages, always available)
 *
 * Piper is a strict upgrade path, never a replacement: if loading or
 * synthesizing with it fails for any reason, this transparently falls back
 * to eSpeak for that call rather than surfacing an error, so a flaky/corrupt
 * voice pack degrades to "sounds robotic again" instead of "stopped working".
 */
class TtsRouter(private val espeak: EspeakEngine, private val piper: PiperTtsEngine) {

    fun speak(text: String, langCode: String, onDone: () -> Unit, onError: (String) -> Unit) {
        if (PiperVoiceCatalog.forLanguage(langCode) != null && piper.isVoiceDownloaded(langCode)) {
            if (piper.isReadyFor(langCode)) {
                speakPiper(text, langCode, onDone, onError)
            } else {
                piper.loadVoiceAsync(langCode) { ok, err ->
                    if (ok) {
                        speakPiper(text, langCode, onDone, onError)
                    } else {
                        Log.w(TAG, "Natural voice load failed for $langCode ($err), falling back to eSpeak")
                        speakEspeak(text, langCode, onDone, onError)
                    }
                }
            }
        } else {
            speakEspeak(text, langCode, onDone, onError)
        }
    }

    private fun speakPiper(text: String, langCode: String, onDone: () -> Unit, onError: (String) -> Unit) {
        piper.speak(text, langCode, onDone = onDone, onError = { err ->
            Log.w(TAG, "Natural voice synthesis failed for $langCode ($err), falling back to eSpeak")
            speakEspeak(text, langCode, onDone, onError)
        })
    }

    private fun speakEspeak(text: String, langCode: String, onDone: () -> Unit, onError: (String) -> Unit) {
        if (!espeak.ready) {
            onError("Offline speech engine still starting up, try again in a moment")
            return
        }
        if (!espeak.supportsLanguage(langCode)) {
            onError("No offline voice for this language yet")
            return
        }
        espeak.speak(text, langCode, onDone, onError)
    }

    fun stop() {
        piper.stop()
        espeak.stop()
    }

    /** Whether a natural-voice catalog entry exists for this language at all (regardless of download state). */
    fun hasNaturalVoiceOption(langCode: String): Boolean = PiperVoiceCatalog.forLanguage(langCode) != null

    fun isNaturalVoiceDownloaded(langCode: String): Boolean = piper.isVoiceDownloaded(langCode)

    /** Short label for "what will actually speak this language right now", for status text in the UI. */
    fun activeEngineLabel(langCode: String): String = when {
        piper.isVoiceDownloaded(langCode) -> "natural voice"
        espeak.supportsLanguage(langCode) -> "eSpeak (built-in, robotic)"
        else -> "no voice available yet"
    }

    companion object {
        private const val TAG = "TtsRouter"
    }
}
