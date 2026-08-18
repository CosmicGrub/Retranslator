package com.retroid.translator.engine

/**
 * Curated set of Piper VITS voices (neural TTS), packaged as self-contained
 * sherpa-onnx archives — each `.tar.bz2` bundles `model.onnx` + `tokens.txt`
 * + a matching `espeak-ng-data` phonemizer directory, downloaded from
 * k2-fsa/sherpa-onnx's own "tts-models" GitHub release (Apache-2.0 project;
 * re-hosts the upstream Piper ONNX voice weights unmodified, just repackaged
 * for sherpa-onnx's OfflineTts loader). This is a deliberately small,
 * hand-picked list — every entry here has been checked against its own
 * MODEL_CARD for a clear, permissive license (see [license] / [licenseUrl]),
 * unlike some Piper voices whose training data carries research-only or
 * NC-only restrictions.
 *
 * Piper's overall voice catalog only covers a subset of languages, and most
 * of those have just ONE speaker/gender available, not a male/female pair.
 * A male+female pair only exists here for a language when Piper genuinely
 * ships both with an acceptable license (currently true for all 4 languages
 * this app covers: en/de/es/fr). Where that's not the case for a future
 * language, add a single-gender entry rather than skipping the language -
 * [EspeakEngine] is the real "every language, both genders" floor; this
 * catalog is strictly a per-language, per-gender upgrade on top of it.
 *
 * Only mlKitCode values also present in ML Kit's TranslateLanguage list are
 * ever surfaced in the UI (same "intersect at runtime" pattern as
 * [VoskModelCatalog]).
 */
data class PiperVoiceInfo(
    val mlKitCode: String,
    val gender: VoiceGender,
    val voiceId: String,       // e.g. "en_US-ljspeech-medium" - also the on-disk storage key
    val displayName: String,
    val quality: String,       // "medium" | "low" | "high" | "x_low"
    val url: String,
    val approxSizeMiB: Int,
    val license: String,
    val licenseUrl: String,
)

object PiperVoiceCatalog {
    private const val RELEASE_BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"

    val VOICES: List<PiperVoiceInfo> = listOf(
        // --- English ---
        // Trained from scratch on the public-domain LJSpeech dataset - no
        // "finetuned from a research-license voice" ancestry to worry about.
        PiperVoiceInfo(
            "en", VoiceGender.FEMALE, "en_US-ljspeech-medium", "English (US) - ljspeech", "medium",
            "$RELEASE_BASE/vits-piper-en_US-ljspeech-medium.tar.bz2", 65,
            "Public domain (LJSpeech dataset)", "https://keithito.com/LJ-Speech-Dataset/"
        ),
        PiperVoiceInfo(
            "en", VoiceGender.MALE, "en_US-joe-medium", "English (US) - joe", "medium",
            "$RELEASE_BASE/vits-piper-en_US-joe-medium.tar.bz2", 65,
            "CC0", "https://github.com/OHF-Voice/voice-datasets"
        ),
        // --- German ---
        PiperVoiceInfo(
            "de", VoiceGender.MALE, "de_DE-thorsten-medium", "German - thorsten", "medium",
            "$RELEASE_BASE/vits-piper-de_DE-thorsten-medium.tar.bz2", 65,
            "CC0", "https://github.com/thorstenMueller/Thorsten-Voice"
        ),
        PiperVoiceInfo(
            "de", VoiceGender.FEMALE, "de_DE-kerstin-low", "German - kerstin", "low",
            "$RELEASE_BASE/vits-piper-de_DE-kerstin-low.tar.bz2", 65,
            "CC0", "https://github.com/rhasspy/dataset-voice-kerstin"
        ),
        // --- Spanish ---
        PiperVoiceInfo(
            "es", VoiceGender.MALE, "es_ES-davefx-medium", "Spanish - davefx", "medium",
            "$RELEASE_BASE/vits-piper-es_ES-davefx-medium.tar.bz2", 65,
            "CC0", "https://github.com/NabuCasa/voice-datasets"
        ),
        // es_MX rather than es_ES - the best-licensed genuinely-female Spanish
        // Piper voice found (checked several es_ES female candidates first;
        // this is the one with an unambiguous permissive license).
        PiperVoiceInfo(
            "es", VoiceGender.FEMALE, "es_MX-claude-high", "Spanish (Mexico) - claude", "high",
            "$RELEASE_BASE/vits-piper-es_MX-claude-high.tar.bz2", 65,
            "Apache-2.0", "https://huggingface.co/spaces/HirCoir/Piper-TTS-Spanish"
        ),
        // --- French ---
        PiperVoiceInfo(
            "fr", VoiceGender.FEMALE, "fr_FR-siwis-medium", "French - siwis", "medium",
            "$RELEASE_BASE/vits-piper-fr_FR-siwis-medium.tar.bz2", 65,
            "CC-BY 4.0", "https://datashare.is.ed.ac.uk/handle/10283/2353"
        ),
        // Note: this voice's *base checkpoint* was finetuned from the English
        // "ryan" voice (which this catalog otherwise excludes for its own
        // CC-BY-NC-SA dataset license) - but gilles's own French dataset,
        // the one actually being licensed here, is CC0 in its own right. Same
        // "the voice's own dataset license is what's recorded" policy already
        // applied to thorsten/davefx (both finetuned from the Blizzard/Lessac
        // voice, which is itself excluded from this catalog).
        PiperVoiceInfo(
            "fr", VoiceGender.MALE, "fr_FR-gilles-low", "French - gilles", "low",
            "$RELEASE_BASE/vits-piper-fr_FR-gilles-low.tar.bz2", 65,
            "CC0", "https://www.kaggle.com/datasets/bryanpark/french-single-speaker-speech-dataset"
        ),
        // --- Italian ---
        // engines-upgrade-plan.md Tier 2 pilot addition (2026-08-18, Fold5
        // edition): single-gender entry, same "no acceptable-license pair
        // exists yet for this language" shape as fr_FR-gilles above. Dataset
        // is M-AILABS' Italian split - LibriVox (public-domain audio) read
        // from Project Gutenberg (public-domain text), BSD-style redistribution
        // terms, independently verified via the dataset's own README (the
        // M-AILABS site itself was unreachable this session - DNS failure,
        // not a license concern). M-AILABS' one documented per-language
        // carve-out is Ukrainian ("for machine learning purposes only") -
        // Italian carries no such restriction. Real archive downloaded and
        // inspected directly (not assumed): 26,496,614 bytes, onnx model
        // 20,578,720 bytes (comfortably above PiperTtsEngine's 10MB
        // truncated-download floor), tokens.txt non-empty, all four required
        // espeak-ng-data files present (phontab/phonindex/phondata/
        // intonations) - see docs/evidence/fold5-edition/
        // italian-voice-verification.md. Only "x_low" quality exists for
        // this speaker upstream (no medium/high tier) - smaller and lower
        // fidelity than this catalog's other entries, disclosed via
        // [PiperVoiceInfo.quality] same as any other entry.
        PiperVoiceInfo(
            "it", VoiceGender.MALE, "it_IT-riccardo-x_low", "Italian - riccardo", "x_low",
            "$RELEASE_BASE/vits-piper-it_IT-riccardo-x_low.tar.bz2", 26,
            "Public domain / BSD-style redistribution (M-AILABS dataset: LibriVox + Project Gutenberg)",
            "https://github.com/imdatceleste/m-ailabs-dataset/blob/master/README.md"
        ),
    )

    private val byKey = VOICES.associateBy { it.mlKitCode to it.gender }

    /** The single voice for this exact (language, gender) pair, if Piper has one with an acceptable license. */
    fun forLanguageAndGender(mlKitCode: String, gender: VoiceGender): PiperVoiceInfo? = byKey[mlKitCode to gender]

    /** Every catalog entry for a language, regardless of gender (0, 1, or 2 entries). */
    fun allForLanguage(mlKitCode: String): List<PiperVoiceInfo> = VOICES.filter { it.mlKitCode == mlKitCode }

    fun supportedCodes(): Set<String> = VOICES.map { it.mlKitCode }.toSet()
}
