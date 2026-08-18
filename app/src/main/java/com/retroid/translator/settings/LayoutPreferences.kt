package com.retroid.translator.settings

import android.content.Context

/**
 * The tabs that get a user-selectable, per-posture layout variant (the "24
 * layout variants" this settings foundation exists to support - see
 * docs/specs/fold5-adaptation.md).
 *
 * Conversations is deliberately NOT included here: per spec §2/§3 it already
 * has its own bespoke, hardcoded fold-aware layout switching
 * (`ConversationsFragment`, driven directly by `FoldPostureProvider`, not by
 * a user-selectable variant), built and committed before this settings
 * system existed. Folding it into this enum would imply Conversations picks
 * variants the same way Translate/Practice/Learn do, which is not true.
 */
enum class SettingsTab(val storageKey: String, val displayName: String) {
    TRANSLATE("translate", "Translate"),
    PRACTICE("practice", "Practice"),
    LEARN("learn", "Learn")
}

/** Which physical posture a stored layout-variant selection applies to. */
enum class ScreenMode(val storageKey: String, val displayName: String) {
    /** Folded closed, app running on the narrow cover display (spec §3). */
    COVER("cover", "Cover screen"),

    /**
     * Unfolded, tabletop/Flex-Mode posture - the HORIZONTAL-hinge rows of
     * spec §2's posture matrix ([com.retroid.translator.fold.FoldPosture.TABLETOP_LANDSCAPE_FLAT] /
     * [com.retroid.translator.fold.FoldPosture.TABLETOP_LANDSCAPE_ANGLED]).
     */
    FLEX("flex", "Flex Mode (tabletop)")
}

/**
 * Shared settings foundation for fold-aware layout selection on the
 * Translate/Practice/Learn tabs, plus the fold-behavior toggles (auto-switch
 * on fold, manual force-compact). Backed by plain `SharedPreferences`,
 * matching the existing precedent in this codebase
 * ([com.retroid.translator.engine.VoicePreferences]) rather than introducing
 * DataStore or another new dependency for a handful of small values.
 *
 * This is the foundation later per-tab phases build against:
 * - Each tab's own "layout" settings screen (not built by this pass - see
 *   [TranslateLayoutSettingsFragment]/[PracticeLayoutSettingsFragment]/
 *   [LearnLayoutSettingsFragment]) reads/writes its own variant selections
 *   via [getVariant]/[setVariant], keyed by its own [SettingsTab] constant.
 * - Variant IDs are plain strings, not a closed enum, on purpose: each tab
 *   owns its own set of variant IDs (e.g. "single_circle", "live_transcript"
 *   for Translate's cover screen) and picks/extends that set independently,
 *   without ever needing to modify this shared file - exactly the kind of
 *   parallel-work conflict this foundation pass is meant to avoid. [DEFAULT_VARIANT]
 *   ("default") is the one ID every tab is guaranteed to support from day
 *   one (today's existing single layout, before any bespoke variant exists).
 */
object LayoutPreferences {

    /** The always-available layout every tab already ships today. Returned until the user picks something else. */
    const val DEFAULT_VARIANT = "default"

    private const val PREFS_NAME = "layout_prefs"
    private const val KEY_AUTO_SWITCH_ON_FOLD = "auto_switch_on_fold"
    private const val KEY_FORCE_COMPACT_LAYOUT = "force_compact_layout"
    private const val KEY_DEVICE_DEFAULTS_SEEDED = "device_defaults_seeded"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun variantKey(tab: SettingsTab, mode: ScreenMode) = "variant_${tab.storageKey}_${mode.storageKey}"

    // -------------------------------------------------------------------
    // Per-tab, per-posture layout variant selection
    // -------------------------------------------------------------------

    /** The variant ID currently configured for [tab]'s [mode] layout. [DEFAULT_VARIANT] until the user picks something else. */
    fun getVariant(context: Context, tab: SettingsTab, mode: ScreenMode): String =
        prefs(context).getString(variantKey(tab, mode), DEFAULT_VARIANT) ?: DEFAULT_VARIANT

    fun setVariant(context: Context, tab: SettingsTab, mode: ScreenMode, variantId: String) {
        prefs(context).edit().putString(variantKey(tab, mode), variantId).apply()
    }

    // -------------------------------------------------------------------
    // Fold behavior
    // -------------------------------------------------------------------

    /**
     * Whether folding the device while this app is in the foreground should
     * automatically switch the active tab to its configured cover-screen
     * variant. Default true - matches "Fold behavior" screen's default per
     * this pass's spec.
     */
    fun isAutoSwitchOnFoldEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_SWITCH_ON_FOLD, true)

    fun setAutoSwitchOnFold(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_SWITCH_ON_FOLD, enabled).apply()
    }

    /**
     * Manual force-toggle state (Fold behavior screen's quick-toggle button):
     * when true, the compact/cover-style layout is forced for the active tab
     * regardless of physical fold state - e.g. a preview, or an explicit
     * user preference to stay in the compact view while physically unfolded.
     * Persisted (survives app restart) rather than a one-shot action, since
     * "stay in the compact view" reads as a standing preference, not a
     * single instantaneous nudge. Independent of [isAutoSwitchOnFoldEnabled]:
     * a user can leave auto-switch off and still force compact manually, or
     * leave auto-switch on and additionally force compact while unfolded.
     * Default false.
     */
    fun isForceCompactLayoutEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FORCE_COMPACT_LAYOUT, false)

    fun setForceCompactLayout(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FORCE_COMPACT_LAYOUT, enabled).apply()
    }

    // -------------------------------------------------------------------
    // Device-tuned default seeding (fold5-device-version branch)
    // -------------------------------------------------------------------

    /**
     * One-time marker for [com.retroid.translator.MainActivity]'s
     * device-specific first-launch variant seeding (this branch pre-selects
     * this device's best-evidenced cover-screen variant per tab instead of
     * leaving every tab on [DEFAULT_VARIANT] until the user separately
     * discovers Settings has better options). Deliberately generic here -
     * this file stays free of any hardcoded variant ID or device assumption,
     * matching its own "no shared enum, each tab owns its ids" design; the
     * actual Fold-5-specific choices live in `MainActivity`, the natural
     * per-branch customization point, not here.
     */
    fun areDeviceDefaultsSeeded(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DEVICE_DEFAULTS_SEEDED, false)

    fun markDeviceDefaultsSeeded(context: Context) {
        prefs(context).edit().putBoolean(KEY_DEVICE_DEFAULTS_SEEDED, true).apply()
    }
}
