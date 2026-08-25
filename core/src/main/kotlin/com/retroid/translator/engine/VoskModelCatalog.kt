package com.retroid.translator.engine

/**
 * Curated from the official Vosk model catalog
 * (https://alphacephei.com/vosk/models/model-list.json), keeping only the
 * small (~30-100MB), non-obsolete models and mapping each to the matching
 * ML Kit TranslateLanguage code. Only languages present in BOTH catalogs are
 * ever shown as STT-capable in the UI (computed at runtime by intersecting
 * this list with `TranslateLanguage.getAllLanguages()`, so a bad guess here
 * just quietly disables that language's mic support rather than crashing).
 */
data class VoskModelInfo(
    val mlKitCode: String,
    val displayName: String,
    val url: String,
    val approxSizeMiB: Int
)

object VoskModelCatalog {
    val MODELS: List<VoskModelInfo> = listOf(
        VoskModelInfo("en", "English", "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip", 39),
        VoskModelInfo("es", "Spanish", "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip", 38),
        VoskModelInfo("fr", "French", "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip", 40),
        VoskModelInfo("de", "German", "https://alphacephei.com/vosk/models/vosk-model-small-de-0.15.zip", 44),
        VoskModelInfo("it", "Italian", "https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip", 47),
        VoskModelInfo("pt", "Portuguese", "https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip", 31),
        VoskModelInfo("ru", "Russian", "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip", 44),
        VoskModelInfo("nl", "Dutch", "https://alphacephei.com/vosk/models/vosk-model-small-nl-0.22.zip", 39),
        VoskModelInfo("zh", "Chinese", "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip", 42),
        VoskModelInfo("ja", "Japanese", "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip", 47),
        VoskModelInfo("ko", "Korean", "https://alphacephei.com/vosk/models/vosk-model-small-ko-0.22.zip", 83),
        VoskModelInfo("hi", "Hindi", "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip", 42),
        VoskModelInfo("tr", "Turkish", "https://alphacephei.com/vosk/models/vosk-model-small-tr-0.3.zip", 35),
        VoskModelInfo("pl", "Polish", "https://alphacephei.com/vosk/models/vosk-model-small-pl-0.22.zip", 51),
        VoskModelInfo("cs", "Czech", "https://alphacephei.com/vosk/models/vosk-model-small-cs-0.4-rhasspy.zip", 44),
        VoskModelInfo("ca", "Catalan", "https://alphacephei.com/vosk/models/vosk-model-small-ca-0.4.zip", 41),
        VoskModelInfo("fa", "Persian", "https://alphacephei.com/vosk/models/vosk-model-small-fa-0.42.zip", 51),
        VoskModelInfo("uk", "Ukrainian", "https://alphacephei.com/vosk/models/vosk-model-small-uk-v3-small.zip", 137),
        VoskModelInfo("vi", "Vietnamese", "https://alphacephei.com/vosk/models/vosk-model-small-vn-0.4.zip", 32),
        VoskModelInfo("ar", "Arabic", "https://alphacephei.com/vosk/models/vosk-model-small-ar-0.3.zip", 100),
        VoskModelInfo("eo", "Esperanto", "https://alphacephei.com/vosk/models/vosk-model-small-eo-0.42.zip", 42),
        VoskModelInfo("gu", "Gujarati", "https://alphacephei.com/vosk/models/vosk-model-small-gu-0.42.zip", 103),
        VoskModelInfo("te", "Telugu", "https://alphacephei.com/vosk/models/vosk-model-small-te-0.42.zip", 58),
        VoskModelInfo("sv", "Swedish", "https://alphacephei.com/vosk/models/vosk-model-small-sv-rhasspy-0.15.zip", 289),
        VoskModelInfo("kk", "Kazakh", "https://alphacephei.com/vosk/models/vosk-model-small-kz-0.42.zip", 57),
    )

    private val byCode = MODELS.associateBy { it.mlKitCode }

    fun forLanguage(mlKitCode: String): VoskModelInfo? = byCode[mlKitCode]

    fun supportedCodes(): Set<String> = byCode.keys
}
