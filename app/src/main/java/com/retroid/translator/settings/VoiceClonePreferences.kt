package com.retroid.translator.settings

import android.content.Context

/**
 * Persisted state for voice cloning - plain SharedPreferences, matching this
 * codebase's existing precedent ([LayoutPreferences],
 * [com.retroid.translator.packs.LanguagePackPreferences]) rather than
 * introducing a new persistence dependency for a handful of small values.
 *
 * [isEnabled] is the real checkable Settings option the task asked for -
 * same shape as [com.retroid.translator.packs.LanguagePackPreferences.allowCellularDownloads]'s
 * own Settings toggle: every real call site that offers a "speak in my
 * voice" action (currently [com.retroid.translator.ui.PracticeFragment])
 * reads this single source of truth rather than each maintaining its own
 * notion of whether the feature is on.
 */
object VoiceClonePreferences {
    private const val PREFS_NAME = "voice_clone_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Whether "speak in my voice" affordances should be offered anywhere in the app. False until onboarding completes at least once; the user may also turn it back off without losing their trained profile. */
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    /** True once onboarding has completed at least once (a voice profile exists). Distinct from [isEnabled] - the user can disable the feature without discarding their trained profile, and re-enable it later without re-recording. */
    fun hasCompletedOnboarding(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONBOARDING_COMPLETED, false)

    fun setOnboardingCompleted(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ONBOARDING_COMPLETED, value).apply()
    }
}
