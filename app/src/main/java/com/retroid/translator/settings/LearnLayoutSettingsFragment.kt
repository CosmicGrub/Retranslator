package com.retroid.translator.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.retroid.translator.databinding.FragmentLearnLayoutSettingsBinding
import com.retroid.translator.databinding.ViewLearnVariantOptionBinding
import com.retroid.translator.ui.LayoutVariantOption
import com.retroid.translator.ui.LearnCoverVariant
import com.retroid.translator.ui.LearnFlexVariant

/**
 * Settings destination for the Learn tab's layout pickers (cover-screen
 * variant + Flex Mode/tabletop variant). Reached from [SettingsHubFragment]'s
 * "Learn layout" row. This replaces the earlier stub - per that stub's own
 * doc comment, this file is Learn's to build out freely; nothing else in the
 * settings foundation depends on its internals, only on [SettingsHubFragment]
 * continuing to instantiate it as a plain no-arg Fragment (unchanged).
 *
 * Same structure as `TranslateLayoutSettingsFragment`/`PracticeLayoutSettingsFragment`
 * (read for the pattern, not modified): reads/writes via
 * [LayoutPreferences.getVariant]/[LayoutPreferences.setVariant], and the two
 * RadioGroups are populated from [LearnCoverVariant.OPTIONS]/
 * [LearnFlexVariant.OPTIONS] (owned by `com.retroid.translator.ui.LearnLayoutVariants.kt`,
 * new in this pass) so this screen and `LearnFragment`'s rendering logic can
 * never list a variant one of them doesn't know about.
 *
 * "Live, no restart" requirement: [LayoutPreferences.setVariant] writes
 * through `SharedPreferences`, and `LearnFragment` re-reads the current
 * selection both (a) naturally, every time its view is recreated - which is
 * exactly what happens when the user navigates back out of this screen,
 * since `SettingsHubFragment`/`MainActivity` use `replace()` + back-stack,
 * not `hide()`/`show()` - and (b) via its own direct
 * `SharedPreferences.OnSharedPreferenceChangeListener` for the (rarer, but
 * possible) case its view is still alive when a change lands. See
 * `LearnFragment`'s doc comment for exactly how that listener is wired.
 */
class LearnLayoutSettingsFragment : Fragment() {

    private var _binding: FragmentLearnLayoutSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLearnLayoutSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val currentCover = LayoutPreferences.getVariant(requireContext(), SettingsTab.LEARN, ScreenMode.COVER)
        val currentFlex = LayoutPreferences.getVariant(requireContext(), SettingsTab.LEARN, ScreenMode.FLEX)

        populateGroup(binding.radioGroupLearnCover, LearnCoverVariant.OPTIONS, currentCover) { selectedId ->
            LayoutPreferences.setVariant(requireContext(), SettingsTab.LEARN, ScreenMode.COVER, selectedId)
        }
        populateGroup(binding.radioGroupLearnFlex, LearnFlexVariant.OPTIONS, currentFlex) { selectedId ->
            LayoutPreferences.setVariant(requireContext(), SettingsTab.LEARN, ScreenMode.FLEX, selectedId)
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? AppCompatActivity)?.supportActionBar?.title = "Learn layout"
    }

    private fun populateGroup(
        group: RadioGroup,
        options: List<LayoutVariantOption>,
        currentId: String,
        onSelected: (String) -> Unit
    ) {
        group.removeAllViews()
        val radiosById = mutableMapOf<Int, String>()
        for (option in options) {
            // attachToParent=true is required here: the row's root layout is <merge>,
            // so it has no view of its own to add separately - inflate() attaches its
            // RadioButton/TextView children directly to `group` as it inflates. This is
            // also what makes RadioGroup's mutual-exclusion logic work at all (it only
            // instruments direct children) - see view_learn_variant_option.xml.
            val row = ViewLearnVariantOptionBinding.inflate(layoutInflater, group)
            val radioId = View.generateViewId()
            row.radioOption.id = radioId
            row.radioOption.text = option.title
            row.textOptionSubtitle.text = option.subtitle
            radiosById[radioId] = option.id
            if (option.id == currentId) {
                row.radioOption.isChecked = true
            }
        }
        group.setOnCheckedChangeListener { _, checkedRadioId ->
            val variantId = radiosById[checkedRadioId] ?: return@setOnCheckedChangeListener
            onSelected(variantId)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
