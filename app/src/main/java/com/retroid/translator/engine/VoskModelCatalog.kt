package com.retroid.translator.engine

import android.content.Context

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

    /**
     * Opt-in "accuracy tier" models - a real, cheap middle ground between
     * [MODELS]' small default and Vosk's much larger "big" tier, per
     * docs/specs/engines-upgrade-plan.md's scoped recommendation. English is
     * the one entry piloted here: `vosk-model-en-us-0.22-lgraph`, ~124.5MiB
     * vs. the default's 39MiB, WER 7.82/8.20 vs. 9.85/10.38 (~20% relative
     * accuracy improvement for ~3x the download - the "big" tier would be
     * ~46x for a further ~1.4x gain, not worth defaulting to). Deliberately
     * NOT folded into [MODELS]/[byCode]: those feed [PackInventory]'s
     * flat "one row per language" pack list, and this is a quality toggle
     * on an existing language's row, not a second downloadable language.
     */
    val ACCURACY_TIERS: Map<String, VoskModelInfo> = mapOf(
        "en" to VoskModelInfo(
            "en", "English (high accuracy)",
            "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22-lgraph.zip", 125
        ),
    )

    fun accuracyTierFor(mlKitCode: String): VoskModelInfo? = ACCURACY_TIERS[mlKitCode]

    /**
     * The model to actually download/load for [mlKitCode] right now: the
     * accuracy tier if one exists for this language AND the user opted in
     * via [VoskAccuracyPreference], otherwise the standard [forLanguage]
     * entry. This is the ONE seam real downloads should read through -
     * [forLanguage] alone stays the base-catalog lookup every existing
     * "is this language supported at all" check already uses and shouldn't
     * have to change.
     */
    fun effectiveModelInfo(context: Context, mlKitCode: String): VoskModelInfo? {
        val tier = accuracyTierFor(mlKitCode)
        if (tier != null && VoskAccuracyPreference.isHighAccuracyEnabled(context, mlKitCode)) return tier
        return forLanguage(mlKitCode)
    }
}
