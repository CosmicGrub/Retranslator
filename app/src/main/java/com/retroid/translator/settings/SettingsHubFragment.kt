package com.retroid.translator.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.retroid.translator.R
import com.retroid.translator.databinding.FragmentSettingsHubBinding
import com.retroid.translator.fold.FoldPosture
import com.retroid.translator.fold.FoldPostureProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Settings hub: a simple list linking to the four Settings destinations.
 * Reachable from the overflow/settings icon `MainActivity`'s toolbar adds
 * (see `MainActivity.onOptionsItemSelected`).
 *
 * Deliberately self-contained (per this pass's parallel-work constraint):
 * everything this screen needs - navigation, titles, destinations - lives
 * in this one file plus its layout XML. It navigates directly via
 * `parentFragmentManager` against the well-known `R.id.fragmentContainer`
 * (the same container `MainActivity` already swaps all four tabs into)
 * rather than calling back into `MainActivity`, so later phases building
 * out [TranslateLayoutSettingsFragment]/[PracticeLayoutSettingsFragment]/
 * [LearnLayoutSettingsFragment]'s real content never need to touch this
 * file, `MainActivity`, or each other's files.
 */
class SettingsHubFragment : Fragment() {

    private var _binding: FragmentSettingsHubBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsHubBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        applyFoldRowVisibility()
        binding.rowTranslateLayout.setOnClickListener { openDestination(TranslateLayoutSettingsFragment(), "translate_layout") }
        binding.rowPracticeLayout.setOnClickListener { openDestination(PracticeLayoutSettingsFragment(), "practice_layout") }
        binding.rowLearnLayout.setOnClickListener { openDestination(LearnLayoutSettingsFragment(), "learn_layout") }
        binding.rowFoldBehavior.setOnClickListener { openDestination(FoldBehaviorFragment(), "fold_behavior") }
        binding.rowLanguagePacks.setOnClickListener { openDestination(ManagePacksFragment(), "language_packs") }
    }

    /**
     * docs/specs/engineering-systems-pitch.md system #3: the four fold-only
     * rows (translate/practice/learn layout + fold behavior) used to be
     * either always visible (this branch) or hardcoded `android:visibility="gone"`
     * in XML (the tab-s9fe-device-version branch's own copy of this layout) -
     * two different per-branch hacks answering the same question two
     * different, both-wrong-in-general ways. A QA install of this branch's
     * APK on non-fold hardware would show four rows that don't apply; the
     * other branch's build would hide them even on hardware that DOES fold.
     * Replaced with one runtime check both branches can share: real hinge
     * detection via [FoldPostureProvider] (the identical
     * [FoldPosture.NO_FOLDING_FEATURE] signal [MainActivity]'s own
     * fold-auto-switch and layout-seeding logic already trust for this
     * exact question), not an assumption baked in from which branch built
     * the APK.
     */
    private fun applyFoldRowVisibility() {
        viewLifecycleOwner.lifecycleScope.launch {
            val hasFold = FoldPostureProvider(requireActivity())
                .postureFlow()
                .map { it.posture != FoldPosture.NO_FOLDING_FEATURE }
                .first()
            val v = if (hasFold) View.VISIBLE else View.GONE
            if (_binding == null) return@launch
            binding.foldSectionLabel.visibility = v
            binding.rowTranslateLayout.visibility = v
            binding.dividerTranslateLayout.visibility = v
            binding.rowPracticeLayout.visibility = v
            binding.dividerPracticeLayout.visibility = v
            binding.rowLearnLayout.visibility = v
            binding.dividerLearnLayout.visibility = v
            binding.rowFoldBehavior.visibility = v
            binding.dividerFoldBehavior.visibility = v
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? AppCompatActivity)?.supportActionBar?.title = "Settings"
    }

    private fun openDestination(fragment: Fragment, backStackName: String) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .addToBackStack(backStackName)
            .commit()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
