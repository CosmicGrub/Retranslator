package com.retroid.translator.settings

import android.media.MediaPlayer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.retroid.translator.MainActivity
import com.retroid.translator.databinding.FragmentVoiceCloneSettingsBinding
import com.retroid.translator.engine.LanguageCatalog
import com.retroid.translator.engine.VoiceCloneEngine
import com.retroid.translator.voiceclone.VoiceCloneLanguageCoverage
import com.retroid.translator.voiceclone.VoiceProfileStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Voice cloning" - Settings -> Voice cloning. Same established pattern as
 * [ManagePacksFragment]'s cellular-data toggle: a real Settings row/screen,
 * off the forefront of Translate/Conversations, holding a real checkable
 * option ([VoiceClonePreferences.isEnabled]) that other screens
 * ([com.retroid.translator.ui.PracticeFragment]) read as their single
 * source of truth.
 *
 * Before onboarding has ever completed, this screen shows only a "Set up
 * voice cloning" entry point (never a dead/disabled toggle - the task's own
 * explicit requirement). Once a profile exists, the real toggle, model
 * management, a preview action, and a re-record entry point all appear.
 */
class VoiceCloneSettingsFragment : Fragment() {

    private var _binding: FragmentVoiceCloneSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var profileStore: VoiceProfileStore
    private var player: MediaPlayer? = null

    private val mainActivity get() = activity as? MainActivity

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVoiceCloneSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        profileStore = VoiceProfileStore(requireContext())

        binding.btnStartOnboarding.setOnClickListener {
            VoiceCloneOnboardingFragment.newInstance().show(parentFragmentManager, VoiceCloneOnboardingFragment.TAG)
        }
        binding.btnRerecord.setOnClickListener {
            VoiceCloneOnboardingFragment.newInstance().show(parentFragmentManager, VoiceCloneOnboardingFragment.TAG)
        }
        binding.switchVoiceCloneEnabled.setOnCheckedChangeListener(null)
        binding.switchVoiceCloneEnabled.setOnCheckedChangeListener { _, isChecked ->
            VoiceClonePreferences.setEnabled(requireContext(), isChecked)
            renderEnabledSubtitle(isChecked)
        }
        binding.btnDeleteModel.setOnClickListener { deleteModel() }
        binding.btnPreviewVoice.setOnClickListener { previewVoice() }

        renderLanguageCoverage()
    }

    override fun onResume() {
        super.onResume()
        (activity as? AppCompatActivity)?.supportActionBar?.title = "Voice cloning"
        refresh()
    }

    private fun refresh() {
        val ctx = context ?: return
        val onboarded = VoiceClonePreferences.hasCompletedOnboarding(ctx) && profileStore.exists()
        binding.cardNotSetUp.visibility = if (onboarded) View.GONE else View.VISIBLE
        binding.cardEnabledToggle.visibility = if (onboarded) View.VISIBLE else View.GONE
        binding.textProfileInfo.visibility = if (onboarded) View.VISIBLE else View.GONE
        binding.textModelStatus.visibility = if (onboarded) View.VISIBLE else View.GONE
        binding.btnDeleteModel.visibility = if (onboarded) View.VISIBLE else View.GONE
        binding.btnPreviewVoice.visibility = if (onboarded) View.VISIBLE else View.GONE
        binding.btnRerecord.visibility = if (onboarded) View.VISIBLE else View.GONE
        binding.textPreviewStatusSettings.visibility = if (onboarded) View.VISIBLE else View.GONE

        if (!onboarded) return

        val isChecked = VoiceClonePreferences.isEnabled(ctx)
        binding.switchVoiceCloneEnabled.isChecked = isChecked
        renderEnabledSubtitle(isChecked)

        val profile = profileStore.load()
        binding.textProfileInfo.text = if (profile != null && profile.createdAtMs > 0) {
            "Voice profile created " + SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault()).format(Date(profile.createdAtMs))
        } else {
            "Voice profile saved on this device."
        }

        val app = mainActivity?.app
        val downloaded = app?.voiceClone?.isFullyDownloaded() == true
        binding.textModelStatus.text = if (downloaded) {
            "Cloning model downloaded (~${VoiceCloneEngine.TOTAL_APPROX_SIZE_MIB}MB) - ZipVoice (k2-fsa, Apache-2.0) via sherpa-onnx."
        } else {
            "Cloning model not downloaded yet - it will download the first time you preview or use your voice."
        }
        binding.btnDeleteModel.isEnabled = downloaded
    }

    private fun renderEnabledSubtitle(enabled: Boolean) {
        binding.textEnabledSubtitle.text = if (enabled) {
            "Offers a \"Hear in my voice\" option on the Practice tab."
        } else {
            "Turned off - your voice profile is kept, but no \"Hear in my voice\" option is offered anywhere."
        }
    }

    private fun deleteModel() {
        val app = mainActivity?.app ?: return
        app.voiceClone.deleteAll()
        Toast.makeText(requireContext(), "Cloning model deleted. Your voice profile itself is unaffected.", Toast.LENGTH_LONG).show()
        refresh()
    }

    private fun previewVoice() {
        val app = mainActivity?.app ?: return
        val profile = profileStore.load()
        if (profile == null) {
            Toast.makeText(requireContext(), "No voice profile yet - set up voice cloning first.", Toast.LENGTH_SHORT).show()
            return
        }
        binding.btnPreviewVoice.isEnabled = false
        binding.textPreviewStatusSettings.text = "Preparing…"
        val engine = app.voiceClone

        fun synth() {
            binding.textPreviewStatusSettings.text = "Synthesizing… this can take a few seconds."
            engine.speak(
                text = "This is what your voice sounds like when it speaks a brand new sentence.",
                referenceAudioFile = profile.audioFile,
                referenceText = profile.referenceText,
                onDone = {
                    if (_binding == null) return@speak
                    binding.btnPreviewVoice.isEnabled = true
                    binding.textPreviewStatusSettings.text = "That's your cloned voice."
                },
                onError = { err ->
                    if (_binding == null) return@speak
                    binding.btnPreviewVoice.isEnabled = true
                    binding.textPreviewStatusSettings.text = "Preview failed: $err"
                }
            )
        }

        if (engine.isLoaded) {
            synth()
            return
        }
        if (!engine.isFullyDownloaded()) {
            binding.textPreviewStatusSettings.text = "Downloading voice-cloning model (~${VoiceCloneEngine.TOTAL_APPROX_SIZE_MIB}MB)…"
            engine.downloadModel(requireContext(), onProgress = { pct ->
                if (_binding != null) binding.textPreviewStatusSettings.text = "Downloading model… $pct%"
            }) { modelOk, modelErr ->
                if (_binding == null) return@downloadModel
                if (!modelOk) {
                    binding.btnPreviewVoice.isEnabled = true
                    binding.textPreviewStatusSettings.text = "Download failed: $modelErr"
                    return@downloadModel
                }
                engine.downloadVocoder(requireContext(), onProgress = { pct ->
                    if (_binding != null) binding.textPreviewStatusSettings.text = "Downloading voice… $pct%"
                }) { vocOk, vocErr ->
                    if (_binding == null) return@downloadVocoder
                    if (!vocOk) {
                        binding.btnPreviewVoice.isEnabled = true
                        binding.textPreviewStatusSettings.text = "Download failed: $vocErr"
                        return@downloadVocoder
                    }
                    engine.loadAsync { loadOk, loadErr ->
                        if (_binding == null) return@loadAsync
                        if (loadOk) synth() else {
                            binding.btnPreviewVoice.isEnabled = true
                            binding.textPreviewStatusSettings.text = "Failed to load model: $loadErr"
                        }
                    }
                }
            }
            return
        }
        binding.textPreviewStatusSettings.text = "Loading model…"
        engine.loadAsync { ok, err ->
            if (_binding == null) return@loadAsync
            if (ok) synth() else {
                binding.btnPreviewVoice.isEnabled = true
                binding.textPreviewStatusSettings.text = "Failed to load model: $err"
            }
        }
    }

    private fun renderLanguageCoverage() {
        binding.listLanguageCoverage.removeAllViews()
        for (code in COVERAGE_LANGUAGES) {
            val row = TextView(requireContext())
            row.textSize = 12f
            row.setPadding(0, dp(4), 0, dp(4))
            val name = LanguageCatalog.displayNameFor(code)
            val tierLabel = when (VoiceCloneLanguageCoverage.tierFor(code)) {
                VoiceCloneLanguageCoverage.Tier.TRAINED -> "Trained"
                VoiceCloneLanguageCoverage.Tier.EXPERIMENTAL -> "Experimental"
            }
            row.text = "• $name - $tierLabel"
            binding.listLanguageCoverage.addView(row)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        player?.release()
        player = null
        _binding = null
    }

    companion object {
        // The languages this app already has some natural-voice story for
        // (README.md's Piper voice table) plus Chinese (the cloning model's
        // other trained language) - a short, relevant list rather than all
        // ~59 ML Kit languages.
        private val COVERAGE_LANGUAGES = listOf("en", "zh", "de", "es", "fr", "it")
    }
}
