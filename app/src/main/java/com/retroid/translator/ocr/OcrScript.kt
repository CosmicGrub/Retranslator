package com.retroid.translator.ocr

/**
 * Which ML Kit Text Recognition v2 script/model [CameraCaptureActivity]'s
 * capture should run. See [OcrEngine]'s doc comment for why [LATIN] is
 * always ready (bundled in the APK) while every other entry here is
 * downloaded on demand via `ModuleInstallClient`.
 *
 * [translateLangCode] is the concrete ML Kit `TranslateLanguage` code this
 * script's recognized text should be translated FROM. For most scripts this
 * is unambiguous (Chinese text -> "zh"). [DEVANAGARI] is the one case where
 * this matters: Devanagari is a SCRIPT shared by several languages
 * (Hindi/Marathi/Nepali/Sanskrit), not a language itself, but
 * [com.retroid.translator.engine.TranslationEngine.translate] needs one
 * concrete source code - Hindi ("hi") is used, per
 * docs/specs/engines-upgrade-plan.md's Tier 2 recommendation ("Hindi is the
 * natural pick since it's already in the Vosk catalog").
 */
enum class OcrScript(val displayName: String, val translateLangCode: String) {
    LATIN("Latin script (English, Spanish, French, German, ...)", "en"),
    CHINESE("Chinese", "zh"),
    JAPANESE("Japanese", "ja"),
    KOREAN("Korean", "ko"),
    /** Covers Hindi/Marathi/Nepali/Sanskrit and other Devanagari-script languages - translated as Hindi, see class doc above. */
    DEVANAGARI("Devanagari (Hindi and related languages)", "hi"),
}
