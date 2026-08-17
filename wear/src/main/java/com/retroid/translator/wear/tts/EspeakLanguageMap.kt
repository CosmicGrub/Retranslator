package com.retroid.translator.wear.tts

/**
 * espeak-ng ships ~114 language dictionaries; most use plain ISO 639-1 codes
 * that already line up with [com.retroid.translator.wear.engine.WearLanguages]'
 * codes (which are themselves ML Kit `TranslateLanguage` codes), but a
 * handful use different conventions. Same mapping as the phone app's own
 * `com.retroid.translator.engine.EspeakLanguageMap`, trimmed to only the
 * entries that matter for `:wear`'s curated 12-language set (only Mandarin
 * needs remapping - "zh" -> "cmn" - of the 12; Hebrew's "iw" -> "he" fix
 * doesn't apply since Hebrew isn't in the curated set, kept out rather than
 * carried over unused).
 */
object EspeakLanguageMap {
    private val MLKIT_TO_ESPEAK = mapOf(
        "zh" to "cmn", // Chinese (Mandarin) -> espeak-ng's "cmn" voice
    )

    fun toEspeakLanguage(mlKitCode: String): String =
        MLKIT_TO_ESPEAK[mlKitCode] ?: mlKitCode
}
