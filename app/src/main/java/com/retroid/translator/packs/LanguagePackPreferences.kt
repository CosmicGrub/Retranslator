package com.retroid.translator.packs

import android.content.Context

/**
 * Persisted state for the auto-download-all-packs flow
 * (docs/specs/galaxy-tab-s9fe-adaptation.md). Plain SharedPreferences,
 * matching this codebase's existing precedent
 * ([com.retroid.translator.settings.LayoutPreferences],
 * [com.retroid.translator.engine.VoicePreferences]) rather than introducing
 * a new persistence dependency for a handful of small values.
 */
object LanguagePackPreferences {
    private const val PREFS_NAME = "language_pack_prefs"
    private const val KEY_HAS_PROMPTED_BULK_DOWNLOAD = "has_prompted_bulk_download"
    private const val KEY_BULK_DOWNLOAD_COMPLETED = "bulk_download_completed"
    private const val KEY_LAST_UPDATE_CHECK_AT = "last_update_check_at"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Whether the one-time "download everything?" confirmation has ever been shown (regardless of the user's answer). Prevents re-prompting on every launch. */
    fun hasPromptedBulkDownload(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HAS_PROMPTED_BULK_DOWNLOAD, false)

    fun setHasPromptedBulkDownload(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_HAS_PROMPTED_BULK_DOWNLOAD, value).apply()
    }

    /** True once a bulk download run has finished (successfully or partially - see BulkDownloadCoordinator). Used only to skip re-offering the bulk prompt; individual packs remain manageable regardless via "Manage language packs". */
    fun isBulkDownloadCompleted(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BULK_DOWNLOAD_COMPLETED, false)

    fun setBulkDownloadCompleted(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_BULK_DOWNLOAD_COMPLETED, value).apply()
    }

    /** Epoch millis of the last "Check for updates" run on the Manage packs screen, 0 if never run. */
    fun lastUpdateCheckAt(context: Context): Long =
        prefs(context).getLong(KEY_LAST_UPDATE_CHECK_AT, 0L)

    fun setLastUpdateCheckAt(context: Context, epochMs: Long) {
        prefs(context).edit().putLong(KEY_LAST_UPDATE_CHECK_AT, epochMs).apply()
    }
}
