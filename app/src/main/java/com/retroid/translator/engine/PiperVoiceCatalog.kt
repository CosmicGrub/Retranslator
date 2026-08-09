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
 * Only mlKitCode values also present in ML Kit's TranslateLanguage list are
 * ever surfaced in the UI (same "intersect at runtime" pattern as
 * [VoskModelCatalog]).
 */
data class PiperVoiceInfo(
    val mlKitCode: String,
    val voiceId: String,       // e.g. "en_US-ljspeech-medium"
    val displayName: String,
    val quality: String,       // "medium" | "low"
    val url: String,
    val approxSizeMiB: Int,
    val license: String,
    val licenseUrl: String,
)

object PiperVoiceCatalog {
    private const val RELEASE_BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"

    val VOICES: List<PiperVoiceInfo> = listOf(
        // Trained from scratch on the public-domain LJSpeech dataset - no
        // "finetuned from a research-license voice" ancestry to worry about.
        PiperVoiceInfo(
            "en", "en_US-ljspeech-medium", "English (US) - ljspeech", "medium",
            "$RELEASE_BASE/vits-piper-en_US-ljspeech-medium.tar.bz2", 65,
            "Public domain (LJSpeech dataset)", "https://keithito.com/LJ-Speech-Dataset/"
        ),
        PiperVoiceInfo(
            "de", "de_DE-thorsten-medium", "German - thorsten", "medium",
            "$RELEASE_BASE/vits-piper-de_DE-thorsten-medium.tar.bz2", 65,
            "CC0", "https://github.com/thorstenMueller/Thorsten-Voice"
        ),
        PiperVoiceInfo(
            "es", "es_ES-davefx-medium", "Spanish - davefx", "medium",
            "$RELEASE_BASE/vits-piper-es_ES-davefx-medium.tar.bz2", 65,
            "CC0", "https://github.com/NabuCasa/voice-datasets"
        ),
        PiperVoiceInfo(
            "fr", "fr_FR-siwis-medium", "French - siwis", "medium",
            "$RELEASE_BASE/vits-piper-fr_FR-siwis-medium.tar.bz2", 65,
            "CC-BY 4.0", "https://datashare.is.ed.ac.uk/handle/10283/2353"
        ),
    )

    private val byCode = VOICES.associateBy { it.mlKitCode }

    fun forLanguage(mlKitCode: String): PiperVoiceInfo? = byCode[mlKitCode]

    fun supportedCodes(): Set<String> = byCode.keys
}
