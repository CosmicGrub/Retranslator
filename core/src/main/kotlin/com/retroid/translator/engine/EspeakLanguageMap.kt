package com.retroid.translator.engine

/**
 * espeak-ng ships ~114 language dictionaries (extracted directly from the
 * project's own official signed release APK, see espeak-ng-data). Most use
 * plain ISO 639-1 codes that already line up with ML Kit's TranslateLanguage
 * codes, but a handful use different conventions (e.g. Mandarin is "cmn",
 * not "zh"). This table only needs to cover the mismatches; everything else
 * falls through as a direct code match.
 */
object EspeakLanguageMap {

    /** ML Kit TranslateLanguage code -> espeak-ng voice locale-language code. */
    private val MLKIT_TO_ESPEAK = mapOf(
        "zh" to "cmn",   // Chinese -> Mandarin voice
        "iw" to "he",    // Hebrew (ML Kit's legacy code) -> espeak "he"
    )

    fun toEspeakLanguage(mlKitCode: String): String =
        MLKIT_TO_ESPEAK[mlKitCode] ?: mlKitCode
}
