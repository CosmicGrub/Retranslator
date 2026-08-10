package com.retroid.translator.settings

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.retroid.translator.databinding.FragmentFoldBehaviorBinding
import com.retroid.translator.fold.FoldPostureProvider
import com.retroid.translator.fold.FoldState
import kotlinx.coroutines.launch

/**
 * "Fold behavior" settings screen (item 4 of the settings-foundation task):
 * the auto-switch-on-fold toggle and the manual force-compact quick-toggle,
 * both backed by [LayoutPreferences]. Also shows the live posture
 * [FoldPostureProvider] is currently reporting, purely as an on-screen
 * debug/transparency aid (same data `ConversationsFragment` already logs,
 * surfaced here as UI instead of logcat-only).
 *
 * This screen only *stores* the two preferences here - it does not itself
 * apply any layout switch, since it is never the "active tab" `MainActivity`
 * switches layouts on. The actual live push (fold-triggered auto-switch, and
 * re-checking the force-compact flag on every tab switch) lives in
 * `MainActivity`, which reads these same [LayoutPreferences] values; see its
 * `onPostureForAutoSwitch`/`applyForceCompactIfNeeded`.
 */
class FoldBehaviorFragment : Fragment() {

    private var _binding: FragmentFoldBehaviorBinding? = null
    private val binding get() = _binding!!
    private lateinit var foldPostureProvider: FoldPostureProvider

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFoldBehaviorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.switchAutoSwitchOnFold.isChecked = LayoutPreferences.isAutoSwitchOnFoldEnabled(requireContext())
        binding.switchAutoSwitchOnFold.setOnCheckedChangeListener { _, checked ->
            LayoutPreferences.setAutoSwitchOnFold(requireContext(), checked)
        }

        binding.switchForceCompact.isChecked = LayoutPreferences.isForceCompactLayoutEnabled(requireContext())
        updateForceCompactStatus(binding.switchForceCompact.isChecked)
        binding.switchForceCompact.setOnCheckedChangeListener { _, checked ->
            LayoutPreferences.setForceCompactLayout(requireContext(), checked)
            updateForceCompactStatus(checked)
        }

        observeFoldPosture()
    }

    override fun onResume() {
        super.onResume()
        (activity as? AppCompatActivity)?.supportActionBar?.title = "Fold behavior"
    }

    private fun updateForceCompactStatus(enabled: Boolean) {
        binding.textForceCompactStatus.text = if (enabled) {
            "Compact layout is forced on. Every tab will show its configured cover-screen layout, even unfolded, until you turn this off."
        } else {
            "Off — tabs use their normal (unfolded) layout unless the device is actually folded and auto-switch is on."
        }
    }

    // -------------------------------------------------------------------
    // Live posture readout - same FoldPostureProvider usage pattern as
    // ConversationsFragment (fold/ package is detection-only; this is just
    // another consumer), shown here for on-screen transparency.
    // -------------------------------------------------------------------

    private fun observeFoldPosture() {
        foldPostureProvider = FoldPostureProvider(requireActivity())
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                foldPostureProvider.postureFlow().collect { state -> renderPosture(state) }
            }
        }
    }

    private fun renderPosture(state: FoldState) {
        Log.d(TAG, "posture=${state.posture} feature.state=${state.feature?.state} feature.orientation=${state.feature?.orientation}")
        if (_binding == null) return
        val mirrored = if (state.posture.isMirroredTabletop) " (mirrored/Flex Mode)" else ""
        binding.textFoldPosture.text = "posture=${state.posture}$mirrored\n" +
            "hinge state=${state.feature?.state ?: "n/a"} orientation=${state.feature?.orientation ?: "n/a"}"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "FoldBehaviorFragment"
    }
}
