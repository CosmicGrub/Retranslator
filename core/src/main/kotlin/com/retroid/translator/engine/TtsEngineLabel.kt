package com.retroid.translator.engine

/**
 * Pure label pick, extracted from [TtsRouter.activeEngineLabel] - the
 * function itself was already a plain 3-way `when` over two
 * already-computed booleans with no [android.content.Context] touch at all
 * (docs/specs/engineering-systems-pitch.md system #2).
 */
object TtsEngineLabel {
    fun forState(naturalVoiceDownloaded: Boolean, espeakSupportsLanguage: Boolean): String = when {
        naturalVoiceDownloaded -> "natural voice"
        espeakSupportsLanguage -> "eSpeak (built-in, robotic)"
        else -> "no voice available yet"
    }
}
