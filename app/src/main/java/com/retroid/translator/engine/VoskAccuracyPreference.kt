package com.retroid.translator.engine

import android.content.Context

/**
 * Per-language opt-in flag for [VoskModelCatalog.ACCURACY_TIERS] - whether
 * the user wants the larger, more accurate model tier for a given language
 * instead of the default small one. Same shape as
 * [DownloadManager.allowCellularDownloads]: a real, persisted,
 * user-adjustable setting, generic on `langCode` even though only "en" has
 * a tier to opt into today, so this file doesn't need touching again if a
 * second language gets one later (docs/specs/engines-upgrade-plan.md
 * explicitly leaves that door open: "before deciding whether the
 * catalog/UI/download-plumbing work is worth repeating for the more
 * expensive tiers elsewhere").
 *
 * Deliberately does NOT delete or touch any already-downloaded model file
 * when flipped - [VoskEngine.modelRootDir] is keyed by language only (one
 * tier resident on disk at a time per language, matching this app's
 * existing "single set of files per language" pack model), so the caller
 * that changes this preference (see ManagePacksFragment's accuracy toggle)
 * is responsible for reconciling on-disk state so a stale wrong-tier
 * download is never silently reported as "the tier you asked for".
 */
object VoskAccuracyPreference {
    private const val PREFS_NAME = "vosk_accuracy_prefs"
    private const val KEY_PREFIX = "high_accuracy_"

    fun isHighAccuracyEnabled(context: Context, langCode: String): Boolean =
        prefs(context).getBoolean(KEY_PREFIX + langCode, false)

    fun setHighAccuracyEnabled(context: Context, langCode: String, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PREFIX + langCode, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
