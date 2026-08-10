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
 * STUB - Settings destination for the Practice tab's layout pickers
 * (cover-screen variant + Flex Mode/tabletop variant). Reached from
 * [SettingsHubFragment]'s "Practice layout" row.
 *
 * See [TranslateLayoutSettingsFragment]'s doc comment for the shape a later
 * phase should build here - same pattern, just
 * `SettingsTab.PRACTICE` instead of `SettingsTab.TRANSLATE`. This file is
 * this phase's to edit freely; nothing else depends on its internals.
 */
class PracticeLayoutSettingsFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return TextView(requireContext()).apply {
            text = "Practice layout settings — coming soon."
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? AppCompatActivity)?.supportActionBar?.title = "Practice layout"
    }
}
