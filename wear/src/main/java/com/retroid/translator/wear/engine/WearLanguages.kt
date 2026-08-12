package com.retroid.translator.wear.engine

/**
 * The watch's curated language subset (docs/specs/watch6-classic-adaptation.md
 * "Design decisions already made" - an earlier scoping decision, not
 * reopened here). 12 languages, chosen for global usage / common travel
 * relevance, each present in BOTH catalogs this needs to work fully
 * standalone:
 *
 *  - ML Kit Translate (`TranslateLanguage`, same as the phone app)
 *  - Vosk's small-model catalog (same source list as the phone app's
 *    [com.retroid.translator.engine.VoskModelCatalog], trimmed to these 12 -
 *    a full copy of the phone's ~25-language catalog was deliberately NOT
 *    made here; that would work against the whole point of a "curated
 *    subset, not the full catalog" for a 16GB-total-storage device)
 *
 * Selection rationale: English/Spanish/French/German/Portuguese/Italian/
 * Russian cover the large majority of Duolingo/Babbel's own most-studied-
 * language lists and Western tourism routes; Mandarin/Japanese/Korean cover
 * East Asia (three of the highest-volume outbound/inbound tourism
 * corridors); Arabic and Hindi each cover 300M+ speakers and are common
 * gaps in "top 10 European languages" lists that undersell their actual
 * global reach. This is a judgment call, not a formula - documented here so
 * a follow-up pass can revisit it deliberately rather than archaeologically
 * reverse-engineering "why these 12."
 *
 * Approximate on-device footprint per language if ALL 12 are downloaded in
 * full (Vosk small model + ML Kit translate model, both directions via
 * English pivot where ML Kit needs it): the Vosk half alone sums to
 * ~597MB (see approxVoskMiB below, real numbers from
 * [com.retroid.translator.engine.VoskModelCatalog], not re-guessed here) -
 * against this device's real, measured ~4.7GB available
 * (docs/specs/watch6-classic-adaptation.md's real `dumpsys diskstats`
 * evidence), auto-downloading all 12 in full on first run is NOT
 * recommended without further UX work (a "download only what you pick"
 * flow, or trimming the default auto-set further) - flagged as a real
 * open question for a follow-up pass, not silently assumed away.
 */
data class WearLanguage(
    val code: String,
    val displayName: String,
    val voskUrl: String,
    val approxVoskMiB: Int
)

object WearLanguages {
    val CURATED: List<WearLanguage> = listOf(
        WearLanguage("en", "English", "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip", 39),
        WearLanguage("es", "Spanish", "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip", 38),
        WearLanguage("fr", "French", "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip", 40),
        WearLanguage("de", "German", "https://alphacephei.com/vosk/models/vosk-model-small-de-0.15.zip", 44),
        WearLanguage("zh", "Mandarin", "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip", 42),
        WearLanguage("ja", "Japanese", "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip", 47),
        WearLanguage("ko", "Korean", "https://alphacephei.com/vosk/models/vosk-model-small-ko-0.22.zip", 83),
        WearLanguage("ar", "Arabic", "https://alphacephei.com/vosk/models/vosk-model-small-ar-0.3.zip", 100),
        WearLanguage("pt", "Portuguese", "https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip", 31),
        WearLanguage("it", "Italian", "https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip", 47),
        WearLanguage("ru", "Russian", "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip", 44),
        WearLanguage("hi", "Hindi", "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip", 42)
    )

    fun byCode(code: String): WearLanguage? = CURATED.firstOrNull { it.code == code }
}
