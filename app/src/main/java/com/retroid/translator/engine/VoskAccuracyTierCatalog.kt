package com.retroid.translator.engine

/**
 * Opt-in higher-accuracy tier(s) on top of [VoskModelCatalog]'s standard
 * ("small") models - see docs/specs/engines-upgrade-plan.md's Tier 3
 * "English Vosk lgraph model as an opt-in accuracy tier".
 *
 * Deliberately NOT merged into [VoskModelCatalog.MODELS]: the standard entry
 * there for a language (e.g. "en", the ~39MB small model) is this catalog's
 * permanent floor/default and is never silently replaced - a tier here is
 * an ADDITIONAL, separately-downloaded, separately-activated pack the user
 * must explicitly opt into (see [com.retroid.translator.packs.VoskAccuracyPreferences]).
 *
 * [model] reuses [VoskModelInfo]'s exact shape, with [VoskModelInfo.mlKitCode]
 * doubling as [VoskEngine]'s opaque resident/storage key for this tier
 * ("en-lgraph") rather than a real ML Kit `TranslateLanguage` code - it
 * downloads to its own `vosk-models/en-lgraph/` directory, distinct from the
 * standard pack's `vosk-models/en/`, so both can be downloaded and switched
 * between without either overwriting the other. This reuse is deliberate:
 * it lets a tier ride every existing generic per-pack code path unchanged -
 * [com.retroid.translator.engine.DownloadManager.downloadAndUnzip],
 * [com.retroid.translator.packs.BulkDownloadCoordinator],
 * [com.retroid.translator.packs.PackStatus], and "Manage language packs"'
 * per-row download/delete UI - with zero changes to any of them; only
 * `PackInventory.all()` and the picker UI that lets a downloaded tier be
 * made ACTIVE needed real, tier-aware code (see
 * [com.retroid.translator.packs.VoskAccuracyPreferences] and
 * `ManagePacksFragment`).
 */
data class VoskAccuracyTierInfo(
    /** Which [VoskModelCatalog] language this tier upgrades - "en". */
    val baseMlKitCode: String,
    /** The tier's own pack - its `mlKitCode` is a distinct storage key ("en-lgraph"), not a second entry for the same key. */
    val model: VoskModelInfo,
    /** Human-readable accuracy comparison shown in the picker UI. */
    val accuracyNote: String,
)

object VoskAccuracyTierCatalog {
    val TIERS: List<VoskAccuracyTierInfo> = listOf(
        VoskAccuracyTierInfo(
            baseMlKitCode = "en",
            model = VoskModelInfo(
                mlKitCode = "en-lgraph",
                displayName = "English (Higher accuracy)",
                // Real URL, confirmed resolving via `curl -I` at the time this
                // was added: HTTP 200, Content-Type application/zip,
                // Content-Length 130557655 bytes (= 124.5MiB, matching the
                // plan's figure exactly) - not guessed from the version number.
                url = "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22-lgraph.zip",
                approxSizeMiB = 125,
            ),
            accuracyNote = "Real measured WER 7.82/8.20 vs. the standard pack's 9.85/10.38 " +
                "(Vosk's own published upstream benchmark) - roughly 20% fewer word errors " +
                "for about 3x the download size.",
        ),
    )

    fun tierFor(baseMlKitCode: String): VoskAccuracyTierInfo? = TIERS.find { it.baseMlKitCode == baseMlKitCode }

    fun byStorageKey(storageKey: String): VoskAccuracyTierInfo? = TIERS.find { it.model.mlKitCode == storageKey }
}
