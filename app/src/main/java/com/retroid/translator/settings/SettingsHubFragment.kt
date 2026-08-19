package com.retroid.translator.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.retroid.translator.R
import com.retroid.translator.databinding.FragmentSettingsHubBinding

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
        binding.rowTranslateLayout.setOnClickListener { openDestination(TranslateLayoutSettingsFragment(), "translate_layout") }
        binding.rowPracticeLayout.setOnClickListener { openDestination(PracticeLayoutSettingsFragment(), "practice_layout") }
        binding.rowLearnLayout.setOnClickListener { openDestination(LearnLayoutSettingsFragment(), "learn_layout") }
        binding.rowFoldBehavior.setOnClickListener { openDestination(FoldBehaviorFragment(), "fold_behavior") }
        binding.rowLanguagePacks.setOnClickListener { openDestination(ManagePacksFragment(), "language_packs") }
        binding.rowVoiceCloning.setOnClickListener { openDestination(VoiceCloneSettingsFragment(), "voice_cloning") }
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
