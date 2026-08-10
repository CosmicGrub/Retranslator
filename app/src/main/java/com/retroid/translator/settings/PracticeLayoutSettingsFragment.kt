package com.retroid.translator.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.retroid.translator.databinding.FragmentPracticeLayoutSettingsBinding
import com.retroid.translator.databinding.ViewPracticeVariantOptionBinding
import com.retroid.translator.ui.LayoutVariantOption
import com.retroid.translator.ui.PracticeCoverVariant
import com.retroid.translator.ui.PracticeFlexVariant

/**
 * Settings destination for the Practice tab's layout pickers (cover-screen
 * variant + Flex Mode/tabletop variant). Reached from [SettingsHubFragment]'s
 * "Practice layout" row. This replaces the earlier stub - per that stub's
 * own doc comment, this file was Practice's to build out freely; nothing
 * else in the settings foundation depends on its internals, only on
 * [SettingsHubFragment] continuing to instantiate it as a plain no-arg
 * Fragment (unchanged - not touched by this pass).
 *
 * Same structure as `TranslateLayoutSettingsFragment` (read for the
 * pattern, not modified): reads/writes via
 * [LayoutPreferences.getVariant]/[LayoutPreferences.setVariant], and the two
 * RadioGroups are populated from [PracticeCoverVariant.OPTIONS]/
 * [PracticeFlexVariant.OPTIONS] (owned by
 * `com.retroid.translator.ui.PracticeLayoutVariants.kt`, new in this pass)
 * so this screen and `PracticeFragment`'s rendering logic can never list a
 * variant one of them doesn't know about.
 *
 * "Live, no restart" requirement: [LayoutPreferences.setVariant] writes
 * through `SharedPreferences`, and `PracticeFragment` re-reads the current
 * selection both (a) naturally, every time its view is recreated - which is
 * exactly what happens when the user navigates back out of this screen,
 * since `SettingsHubFragment`/`MainActivity` use `replace()` + back-stack,
 * not `hide()`/`show()` - and (b) via its own direct
 * `SharedPreferences.OnSharedPreferenceChangeListener` for the (rarer, but
 * possible) case its view is still alive when a change lands. See
 * `PracticeFragment`'s doc comment for exactly how that listener is wired.
 */
class PracticeLayoutSettingsFragment : Fragment() {

    private var _binding: FragmentPracticeLayoutSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPracticeLayoutSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val currentCover = LayoutPreferences.getVariant(requireContext(), SettingsTab.PRACTICE, ScreenMode.COVER)
        val currentFlex = LayoutPreferences.getVariant(requireContext(), SettingsTab.PRACTICE, ScreenMode.FLEX)

        populateGroup(binding.radioGroupPracticeCover, PracticeCoverVariant.OPTIONS, currentCover) { selectedId ->
            LayoutPreferences.setVariant(requireContext(), SettingsTab.PRACTICE, ScreenMode.COVER, selectedId)
        }
        populateGroup(binding.radioGroupPracticeFlex, PracticeFlexVariant.OPTIONS, currentFlex) { selectedId ->
            LayoutPreferences.setVariant(requireContext(), SettingsTab.PRACTICE, ScreenMode.FLEX, selectedId)
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? AppCompatActivity)?.supportActionBar?.title = "Practice layout"
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
            val row = ViewPracticeVariantOptionBinding.inflate(layoutInflater, group, false)
            val radioId = View.generateViewId()
            row.radioOption.id = radioId
            row.radioOption.text = option.title
            row.textOptionSubtitle.text = option.subtitle
            radiosById[radioId] = option.id
            group.addView(row.root)
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
