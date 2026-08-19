package com.retroid.translator.voiceclone

/**
 * Honest per-language quality expectation for [com.retroid.translator.engine.VoiceCloneEngine]
 * - the underlying model (ZipVoice-Distill, k2-fsa, see that class's doc
 * comment for the real feasibility investigation) is documented and trained
 * on Chinese + English speech only (real citation: k2-fsa/ZipVoice's own
 * README, and the "-zh-en-emilia" name of every release asset - trained on
 * the Emilia corpus, Chinese+English). Its text frontend is the same
 * espeak-ng phonemizer this app already bundles for 100+ languages (real,
 * directly observed: the downloaded model package includes a full
 * `espeak-ng-data/` directory, not just Chinese/English dictionaries) - so
 * technically ANY language this app already speaks via eSpeak can be fed to
 * it as text, but the ACOUSTIC/voice model itself was never trained on
 * anything but Chinese and English speech. Whether it still sounds
 * genuinely like the user's own voice/accent for e.g. German or Spanish
 * text is a real, open, unverified question this app cannot claim an
 * answer to without on-device listening - so this is surfaced honestly to
 * the user rather than silently blocked or silently claimed to work,
 * matching this app's own established "quality varies, tell the user"
 * pattern (see e.g. the Vosk per-language WER disclosure already recommended
 * in docs/specs/engines-upgrade-plan.md).
 */
object VoiceCloneLanguageCoverage {
    private val TRAINED_LANGUAGES = setOf("en", "zh")

    enum class Tier { TRAINED, EXPERIMENTAL }

    fun tierFor(mlKitLangCode: String): Tier =
        if (mlKitLangCode.substringBefore("-").lowercase() in TRAINED_LANGUAGES) Tier.TRAINED else Tier.EXPERIMENTAL

    fun noteFor(mlKitLangCode: String): String = when (tierFor(mlKitLangCode)) {
        Tier.TRAINED -> "Trained language - this model learned to speak from real Chinese/English recordings, so your cloned voice should sound close to itself here."
        Tier.EXPERIMENTAL -> "Experimental - the cloning model was only trained on Chinese and English speech. It can still attempt this language (using the same phonetic engine eSpeak uses), but your voice's accent/timbre fidelity is unverified and may sound off."
    }
}
