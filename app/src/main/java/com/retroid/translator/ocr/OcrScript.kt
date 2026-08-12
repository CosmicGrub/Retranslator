package com.retroid.translator.ocr

/**
 * Which ML Kit Text Recognition v2 script/model [CameraCaptureActivity]'s
 * capture should run. See [OcrEngine]'s doc comment for why [LATIN] is
 * always ready (bundled in the APK) while [CHINESE] is downloaded on
 * demand, and why Japanese/Korean/Devanagari aren't offered yet.
 */
enum class OcrScript(val displayName: String) {
    LATIN("Latin script (English, Spanish, French, German, ...)"),
    CHINESE("Chinese"),
}
