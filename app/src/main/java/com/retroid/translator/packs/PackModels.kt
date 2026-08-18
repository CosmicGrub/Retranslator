package com.retroid.translator.packs

import com.retroid.translator.engine.LanguageCatalog
import com.retroid.translator.engine.PiperVoiceCatalog
import com.retroid.translator.engine.PiperVoiceInfo
import com.retroid.translator.engine.VoskAccuracyTierCatalog
import com.retroid.translator.engine.VoskModelCatalog
import com.retroid.translator.engine.VoskModelInfo

/**
 * Auto-download / "Manage language packs" orchestration
 * (docs/specs/galaxy-tab-s9fe-adaptation.md's "auto-download all language
 * packs" requirement). This package is new code that USES the existing
 * catalogs ([com.retroid.translator.engine.VoskModelCatalog],
 * [com.retroid.translator.engine.PiperVoiceCatalog], and ML Kit's own
 * language list via [LanguageCatalog]) - it never duplicates or forks their
 * data, per that spec's explicit instruction. [PackDescriptor] is a thin,
 * read-only unification layer so a single "download everything" / "manage
 * packs" screen can iterate all three catalogs uniformly, without each of
 * Translate/Practice/Learn's per-pair download flows (unchanged, still the
 * primary way most users will ever add a single pack) needing to know this
 * package exists.
 */
enum class PackCategory(val displayName: String) {
    TRANSLATION("Translation packs"),
    VOICE_INPUT("Voice-input packs (Vosk speech recognition)"),
    NATURAL_VOICE("Natural voices (Piper neural text-to-speech)"),
}

/** One downloadable unit, from any of the three existing catalogs. */
sealed class PackDescriptor {
    abstract val category: PackCategory
    /** Unique within [category] - not necessarily unique across categories (e.g. "en" is both a Translation and a VoiceInput id). */
    abstract val id: String
    abstract val displayName: String
    abstract val approxSizeMiB: Int

    data class Translation(val mlKitCode: String) : PackDescriptor() {
        override val category = PackCategory.TRANSLATION
        override val id = mlKitCode
        override val displayName = LanguageCatalog.displayNameFor(mlKitCode)
        // ML Kit's RemoteModelManager doesn't expose a real per-model size -
        // this project's own README states "~30MB each" for translation
        // packs (measured on-device during earlier work), used consistently
        // here for the bulk-download size estimate shown before the user
        // confirms it.
        override val approxSizeMiB = 30
    }

    data class VoiceInput(val info: VoskModelInfo) : PackDescriptor() {
        override val category = PackCategory.VOICE_INPUT
        override val id = info.mlKitCode
        override val displayName = info.displayName
        override val approxSizeMiB = info.approxSizeMiB
    }

    data class NaturalVoice(val info: PiperVoiceInfo) : PackDescriptor() {
        override val category = PackCategory.NATURAL_VOICE
        override val id = info.voiceId
        override val displayName = "${info.displayName} (${info.gender.name.lowercase().replaceFirstChar { it.uppercase() }})"
        override val approxSizeMiB = info.approxSizeMiB
    }
}

/** The full, flat list of every pack this app knows how to download, across all three catalogs. */
object PackInventory {
    fun all(): List<PackDescriptor> =
        LanguageCatalog.codes.map { PackDescriptor.Translation(it) } +
            VoskModelCatalog.MODELS.map { PackDescriptor.VoiceInput(it) } +
            // Opt-in higher-accuracy Vosk tiers (docs/specs/engines-upgrade-plan.md's
            // Tier 3 "English Vosk lgraph model as an opt-in accuracy tier") -
            // each tier's `model` reuses VoskModelInfo's exact shape, so it
            // rides the same VoiceInput download/delete/status code below
            // unchanged; see VoskAccuracyTierCatalog's doc comment for why.
            VoskAccuracyTierCatalog.TIERS.map { PackDescriptor.VoiceInput(it.model) } +
            PiperVoiceCatalog.VOICES.map { PackDescriptor.NaturalVoice(it) }

    fun totalApproxSizeMiB(): Int = all().sumOf { it.approxSizeMiB }

    fun byCategory(category: PackCategory): List<PackDescriptor> = all().filter { it.category == category }
}
