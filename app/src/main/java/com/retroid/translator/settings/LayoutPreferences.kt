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
    private const val KEY_CONVERSATIONS_CONTINUOUS_USER_SET = "conversations_continuous_user_set"

    /**
     * Fold5 edition cold-launch default (docs/specs/fold5-adaptation.md's
     * dated Fold5-edition section): before Translate's cover-screen variant
     * preference has ever been explicitly written by the user, this device
     * edition resolves it to "single_circle" instead of the generic
     * [DEFAULT_VARIANT] - §6/§7 of that spec real-device-confirmed
     * `single_circle` as this exact device's best cover-screen
     * quick-translate experience (serial `RFCW80CK2RW`), so a first-time
     * fold-closed cold launch lands there directly rather than squeezing the
     * full book-portrait layout onto the narrow cover display. Deliberately
     * narrow in scope: only consulted by [getVariant] for exactly
     * (TRANSLATE, COVER) - see there. Not imported from
     * `com.retroid.translator.ui.TranslateCoverVariant.SINGLE_CIRCLE` (whose
     * value this must match) to avoid a `settings` -> `ui` reverse
     * dependency in this otherwise tab-agnostic shared foundation file (see
     * class doc above).
     */
    private const val FOLD5_TRANSLATE_COVER_DEFAULT_VARIANT = "single_circle"

    /**
     * Fold5 edition cold-launch default (docs/specs/fold5-adaptation.md's
     * dated Fold5-edition section): whether Conversations' "Continuous
     * listening" toggle (dual-recognizer auto-detect, spec §4) should be
     * attempted automatically the first time the Conversations tab is shown
     * on this device edition, before the user has ever explicitly touched
     * the toggle themselves. Hardcoded true for this edition - the
     * mechanism itself was real-device-verified working on this exact
     * device (serial `RFCW80CK2RW`) per spec §4/§11. See
     * [ConversationsFragment.maybeApplyFold5ContinuousDefault] for the
     * actual trigger, which reuses [ConversationsFragment.startContinuousMode]
     * completely unmodified (same mic-permission / Vosk-model-presence /
     * mic-busy checks, same graceful Toast-and-revert-to-off on any of them
     * failing) - this constant only decides whether that existing, already
     * real-verified path is attempted automatically or only on an explicit
     * tap.
     */
    const val CONVERSATIONS_CONTINUOUS_DEFAULT_ON = true

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun variantKey(tab: SettingsTab, mode: ScreenMode) = "variant_${tab.storageKey}_${mode.storageKey}"

    // -------------------------------------------------------------------
    // Per-tab, per-posture layout variant selection
    // -------------------------------------------------------------------

    /**
     * The variant ID currently configured for [tab]'s [mode] layout.
     * [DEFAULT_VARIANT] until the user picks something else - EXCEPT for
     * (TRANSLATE, COVER) on this Fold5 edition, which resolves to
     * [FOLD5_TRANSLATE_COVER_DEFAULT_VARIANT] instead until the user picks
     * something else (see that constant's doc). The `stored != null` check
     * (rather than `stored != DEFAULT_VARIANT`) is deliberate: a user who
     * explicitly re-picks "Default" through the Settings picker persists the
     * literal string [DEFAULT_VARIANT] and must see that honored, not
     * silently overridden back to the Fold5 default - only a preference
     * that was truly never written falls through to it.
     */
    fun getVariant(context: Context, tab: SettingsTab, mode: ScreenMode): String {
        val stored = prefs(context).getString(variantKey(tab, mode), null)
        if (stored != null) return stored
        if (tab == SettingsTab.TRANSLATE && mode == ScreenMode.COVER) return FOLD5_TRANSLATE_COVER_DEFAULT_VARIANT
        return DEFAULT_VARIANT
    }

    fun setVariant(context: Context, tab: SettingsTab, mode: ScreenMode, variantId: String) {
        prefs(context).edit().putString(variantKey(tab, mode), variantId).apply()
    }

    // -------------------------------------------------------------------
    // Conversations continuous-listening cold-launch default (Fold5 edition)
    // -------------------------------------------------------------------

    /**
     * True once the user has explicitly interacted with Conversations'
     * "Continuous listening" toggle themselves (tapped it on OR off) -
     * see [CONVERSATIONS_CONTINUOUS_DEFAULT_ON]'s doc. Once true,
     * [ConversationsFragment.maybeApplyFold5ContinuousDefault] never
     * auto-applies the default again for this install, so the user's own
     * choice - including explicitly turning it back off - always wins.
     */
    fun hasUserSetConversationsContinuous(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CONVERSATIONS_CONTINUOUS_USER_SET, false)

    fun markConversationsContinuousUserSet(context: Context) {
        prefs(context).edit().putBoolean(KEY_CONVERSATIONS_CONTINUOUS_USER_SET, true).apply()
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
}
