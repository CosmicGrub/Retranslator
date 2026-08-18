package com.retroid.translator.packs

import android.content.Context
import com.retroid.translator.engine.VoskAccuracyTierCatalog
import com.retroid.translator.engine.VoskEngine

/**
 * Which Vosk "accuracy tier" is active for a base language - the standard
 * catalog pack ([com.retroid.translator.engine.VoskModelCatalog], always the
 * default/floor) or an opt-in higher-accuracy tier
 * ([VoskAccuracyTierCatalog]), if the user explicitly chose one via "Manage
 * language packs" AND it's actually downloaded. See
 * docs/specs/engines-upgrade-plan.md's Tier 3 "English Vosk lgraph model as
 * an opt-in accuracy tier".
 */
object VoskAccuracyPreferences {
    private const val PREFS_NAME = "vosk_accuracy_prefs"
    private const val KEY_PREFIX_CHOSEN = "higher_accuracy_chosen_"

    fun isHigherAccuracyChosen(context: Context, baseMlKitCode: String): Boolean =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PREFIX_CHOSEN + baseMlKitCode, false)

    fun setHigherAccuracyChosen(context: Context, baseMlKitCode: String, chosen: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_PREFIX_CHOSEN + baseMlKitCode, chosen)
            .apply()
    }

    /**
     * The actual [VoskEngine] storage key to load/check for [baseMlKitCode]
     * right now - the higher-accuracy tier's own key ONLY if the user chose
     * it AND it's genuinely downloaded (never silently breaks an STT flow
     * because a chosen tier's files are missing or were deleted outside the
     * app - falls back to the always-available standard pack's own key,
     * [baseMlKitCode] itself, instead). Callers should gate on
     * `vosk.isModelDownloaded(resolveStorageKey(...))` exactly as they
     * already gate on `vosk.isModelDownloaded(baseMlKitCode)` before this
     * feature existed - the resolved key is a drop-in replacement.
     */
    fun resolveStorageKey(context: Context, vosk: VoskEngine, baseMlKitCode: String): String {
        if (!isHigherAccuracyChosen(context, baseMlKitCode)) return baseMlKitCode
        val tier = VoskAccuracyTierCatalog.tierFor(baseMlKitCode) ?: return baseMlKitCode
        return if (vosk.isModelDownloaded(tier.model.mlKitCode)) tier.model.mlKitCode else baseMlKitCode
    }
}
