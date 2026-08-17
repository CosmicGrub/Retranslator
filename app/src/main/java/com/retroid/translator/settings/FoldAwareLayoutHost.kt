package com.retroid.translator.settings

/**
 * Contract implemented by each of the three tab Fragments with a real
 * cover-screen layout variant to switch to (the "24 layout variants" work
 * per docs/specs/fold5-adaptation.md §6). All three of `TranslateFragment`,
 * `PracticeFragment`, and `LearnFragment` implement this today (each with
 * its own `override val settingsTab`).
 *
 * `MainActivity`'s fold-driven auto-switch coordinator and the "Fold
 * behavior" screen's manual force-compact toggle both look up the
 * currently-attached fragment via `as? FoldAwareLayoutHost` and safely no-op
 * (just a log line) when it isn't one - so implementing this interface on a
 * tab Fragment is the only change any future tab needs to make to start
 * receiving real switch events; no `MainActivity` edits required.
 */
interface FoldAwareLayoutHost {

    /** Which [SettingsTab] this Fragment corresponds to, for looking up its configured variant via [LayoutPreferences]. */
    val settingsTab: SettingsTab

    /**
     * Apply this tab's configured cover/compact layout. Called when this
     * tab is the one currently on screen and either (a) the device folds
     * while "auto-switch on fold" is enabled, or (b) the user has the
     * manual force-compact toggle on (checked on every tab switch, not only
     * at the moment the toggle is flipped).
     *
     * @param variantId whatever [LayoutPreferences.getVariant] currently
     *   returns for ([settingsTab], [ScreenMode.COVER]) -
     *   [LayoutPreferences.DEFAULT_VARIANT] until a later phase's picker UI
     *   lets the user choose something else.
     */
    fun applyCoverLayout(variantId: String)

    /**
     * Revert to this tab's normal (unfolded) layout. Called when the device
     * unfolds back out of a fold-closed posture while auto-switch is
     * enabled. Never called for the manual force-compact toggle turning off
     * while this tab isn't the one currently on screen - that case is
     * handled by [applyCoverLayout] simply not being (re-)invoked next time
     * this tab becomes active, since the toggle is checked fresh on every
     * tab switch.
     */
    fun applyDefaultLayout()
}
