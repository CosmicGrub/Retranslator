package com.retroid.translator.settings

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment

/**
 * STUB - Settings destination for the Translate tab's layout pickers
 * (cover-screen variant + Flex Mode/tabletop variant). Reached from
 * [SettingsHubFragment]'s "Translate layout" row.
 *
 * Deliberately unbuilt by this pass (see docs/specs/fold5-adaptation.md and
 * the settings-foundation task this file was created under) - a later phase
 * fleshes this out into a real picker UI. To do that:
 *
 * - Read the current selections with
 *   `LayoutPreferences.getVariant(context, SettingsTab.TRANSLATE, ScreenMode.COVER)`
 *   and `..., ScreenMode.FLEX)`; write them back with
 *   `LayoutPreferences.setVariant(...)`. [LayoutPreferences.DEFAULT_VARIANT]
 *   ("default") is the only variant ID guaranteed to exist today - define
 *   whatever additional variant IDs this tab needs (e.g. "single_circle",
 *   "live_transcript") as plain strings; no shared file needs editing to
 *   add one.
 * - This file (and its layout, once one exists) is this phase's to edit
 *   freely - nothing else in the settings foundation depends on its
 *   internal structure, only on [SettingsHubFragment] continuing to
 *   instantiate `TranslateLayoutSettingsFragment()` as a plain no-arg
 *   Fragment.
 * - If `TranslateFragment` itself gains a real cover-screen layout variant,
 *   have it implement [FoldAwareLayoutHost] (`settingsTab = SettingsTab.TRANSLATE`)
 *   so `MainActivity`'s fold-driven auto-switch and the Fold behavior
 *   screen's manual force-compact toggle can actually apply the selection
 *   made here - no `MainActivity` changes needed for that either.
 */
class TranslateLayoutSettingsFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return TextView(requireContext()).apply {
            text = "Translate layout settings — coming soon."
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? AppCompatActivity)?.supportActionBar?.title = "Translate layout"
    }
}
