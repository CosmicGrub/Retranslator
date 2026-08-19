package com.retroid.translator.settings

import android.media.MediaPlayer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.retroid.translator.MainActivity
import com.retroid.translator.audio.MicPipeline
import com.retroid.translator.databinding.FragmentVoiceCloneOnboardingBinding
import com.retroid.translator.databinding.ItemVoiceCloneTakeBinding
import com.retroid.translator.engine.VoiceCloneEngine
import com.retroid.translator.voiceclone.VoiceCloneAudioQuality
import com.retroid.translator.voiceclone.VoiceProfileStore
import java.io.File

private enum class OnboardingStep { INTRO, RECORD, CHOOSE, BUILD, DONE }

/**
 * "Set up voice cloning" - a dedicated modal (full-screen [DialogFragment],
 * per the task's explicit "dedicated modal within the app" requirement)
 * that walks the user through voice-clone training end to end: intro ->
 * record 3 short guided sentences (real mic capture via [MicPipeline], the
 * exact same recording infrastructure [com.retroid.translator.ui.PracticeFragment]
 * already uses for "record my attempt" - not a new capture path) -> real
 * per-take audio-quality feedback
 * ([com.retroid.translator.voiceclone.VoiceCloneAudioQuality], built on
 * [com.retroid.translator.practice.WaveformReader]) -> choose which take
 * becomes the active reference clip -> build the voice profile (download
 * the cloning model if needed, then a real synthesized preview before
 * anything is committed) -> done.
 *
 * Reachable from [VoiceCloneSettingsFragment]'s "Set up voice cloning" /
 * "Re-record my voice" entry points - never auto-shown.
 *
 * Deliberately NOT cancelable via back-press/outside-tap ([onCreate] sets
 * [isCancelable] = false) - an in-progress recording or a not-yet-saved
 * profile choice is real, session-only work a stray back-gesture shouldn't
 * silently discard; the explicit "✕ Close" button is the one way out before
 * [finishOnboarding] commits anything.
 */
class VoiceCloneOnboardingFragment : DialogFragment() {

    private var _binding: FragmentVoiceCloneOnboardingBinding? = null
    private val binding get() = _binding!!

    private lateinit var profileStore: VoiceProfileStore
    private var step: OnboardingStep = OnboardingStep.INTRO

    private var promptIndex = 0
    private val takeFiles = arrayOfNulls<File>(PROMPTS.size)
    private val takeQuality = arrayOfNulls<VoiceCloneAudioQuality.Result>(PROMPTS.size)
    private var chosenTakeIndex = 0

    private var player: MediaPlayer? = null

    private val mainActivity get() = activity as? MainActivity

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        isCancelable = false
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVoiceCloneOnboardingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        profileStore = VoiceProfileStore(requireContext())

        binding.btnOnboardingClose.setOnClickListener { confirmCloseAndDismiss() }
        binding.textIntroModelNote.text =
            "Uses ZipVoice (k2-fsa, Apache-2.0) via sherpa-onnx, already part of this app - " +
                "about ${VoiceCloneEngine.TOTAL_APPROX_SIZE_MIB}MB to download once, on this device only."
        binding.btnOnboardingBegin.setOnClickListener {
            val activity = mainActivity ?: return@setOnClickListener
            if (!activity.hasMicPermission()) {
                activity.requestMicPermissionIfNeeded()
                Toast.makeText(requireContext(), "Grant microphone permission, then tap Begin again", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            goToStep(OnboardingStep.RECORD)
        }

        binding.btnRecordPrompt.setOnClickListener { toggleRecording() }
        binding.btnRetryTake.setOnClickListener { resetCurrentTakeUi() }
        binding.btnAcceptTake.setOnClickListener { acceptCurrentTake() }

        binding.btnChooseContinue.setOnClickListener { goToStep(OnboardingStep.BUILD) }

        binding.btnBuildDownload.setOnClickListener { startBuildDownload() }
        binding.btnPreview.setOnClickListener { previewVoice() }
        binding.btnBuildContinue.setOnClickListener { goToStep(OnboardingStep.DONE) }

        binding.btnFinish.setOnClickListener { finishOnboarding() }

        renderStep()
    }

    // ---------------------------------------------------------------------
    // Step machine
    // ---------------------------------------------------------------------

    private fun goToStep(target: OnboardingStep) {
        step = target
        renderStep()
    }

    private fun renderStep() {
        binding.cardIntro.visibility = if (step == OnboardingStep.INTRO) View.VISIBLE else View.GONE
        binding.cardRecord.visibility = if (step == OnboardingStep.RECORD) View.VISIBLE else View.GONE
        binding.cardChoose.visibility = if (step == OnboardingStep.CHOOSE) View.VISIBLE else View.GONE
        binding.cardBuild.visibility = if (step == OnboardingStep.BUILD) View.VISIBLE else View.GONE
        binding.cardDone.visibility = if (step == OnboardingStep.DONE) View.VISIBLE else View.GONE

        binding.textStepIndicator.text = when (step) {
            OnboardingStep.INTRO -> "Set up voice cloning"
            OnboardingStep.RECORD -> "Step 1 of 3: Record"
            OnboardingStep.CHOOSE -> "Step 2 of 3: Choose"
            OnboardingStep.BUILD -> "Step 3 of 3: Build"
            OnboardingStep.DONE -> "Done"
        }

        when (step) {
            OnboardingStep.RECORD -> renderRecordStep()
            OnboardingStep.CHOOSE -> renderChooseStep()
            OnboardingStep.BUILD -> renderBuildStep()
            OnboardingStep.DONE -> renderDoneStep()
            else -> {}
        }
    }

    // ---------------------------------------------------------------------
    // RECORD - one guided sentence at a time
    // ---------------------------------------------------------------------

    private fun renderRecordStep() {
        binding.textPromptCounter.text = "Sentence ${promptIndex + 1} of ${PROMPTS.size}"
        binding.textPromptSentence.text = PROMPTS[promptIndex]
        resetCurrentTakeUi()
    }

    private fun resetCurrentTakeUi() {
        binding.textRecordStatus.text = ""
        binding.textQualityFeedback.visibility = View.GONE
        binding.btnRetryTake.visibility = View.GONE
        binding.btnAcceptTake.isEnabled = false
        binding.btnRecordPrompt.text = "🎙 Record"
        binding.btnRecordPrompt.isEnabled = true
    }

    private fun toggleRecording() {
        val activity = mainActivity ?: return
        val app = activity.app
        if (app.mic.isRunning()) {
            app.mic.stop()
            return
        }
        if (!activity.hasMicPermission()) {
            activity.requestMicPermissionIfNeeded()
            Toast.makeText(requireContext(), "Grant microphone permission, then try again", Toast.LENGTH_LONG).show()
            return
        }
        val scratch = File(profileStore.scratchDir(), "take_${promptIndex}_${System.currentTimeMillis()}.wav")
        binding.btnRecordPrompt.text = "⏹ Stop"
        binding.textRecordStatus.text = "Recording… tap Stop when you're done"
        binding.textQualityFeedback.visibility = View.GONE
        binding.btnRetryTake.visibility = View.GONE
        binding.btnAcceptTake.isEnabled = false
        app.mic.start(
            recognizer = null,
            recordToFile = scratch,
            listener = object : MicPipeline.Listener {
                override fun onListeningStopped() {
                    if (_binding == null) return
                    binding.btnRecordPrompt.text = "🎙 Record"
                }
                override fun onRecordingSaved(file: File, bytes: Long) {
                    if (_binding == null) return
                    // Retrying the same prompt - drop the previous take for
                    // this slot rather than leaving it orphaned in scratch.
                    takeFiles[promptIndex]?.let { previous -> if (previous != file) previous.delete() }
                    takeFiles[promptIndex] = file
                    val result = VoiceCloneAudioQuality.analyze(file)
                    takeQuality[promptIndex] = result
                    showQualityFeedback(result)
                }
                override fun onError(message: String) {
                    if (_binding == null) return
                    binding.textRecordStatus.text = ""
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun showQualityFeedback(result: VoiceCloneAudioQuality.Result) {
        binding.textRecordStatus.text = ""
        binding.textQualityFeedback.visibility = View.VISIBLE
        val lines = mutableListOf<String>()
        if (result.passable) {
            lines += if (result.warnings.isEmpty()) "✓ Good take." else "✓ Usable take, with a note:"
            lines += result.warnings
        } else {
            lines += "✗ Let's try that again:"
            lines += result.blockers
        }
        binding.textQualityFeedback.text = lines.joinToString("\n")
        binding.btnRetryTake.visibility = View.VISIBLE
        binding.btnAcceptTake.isEnabled = result.passable
    }

    private fun acceptCurrentTake() {
        if (promptIndex < PROMPTS.size - 1) {
            promptIndex++
            renderRecordStep()
        } else {
            promptIndex = 0
            chosenTakeIndex = bestTakeIndex()
            goToStep(OnboardingStep.CHOOSE)
        }
    }

    /** Deterministic ranking over the same real, on-screen signals (fewer warnings, less clipping, closer to a healthy average loudness) - not a hidden heuristic, just picking a sane default the user can still override on the Choose step. */
    private fun bestTakeIndex(): Int {
        var best = 0
        var bestScore = Float.NEGATIVE_INFINITY
        for (i in takeQuality.indices) {
            val q = takeQuality[i] ?: continue
            val score = -q.warnings.size.toFloat() - (q.clippingRatio * 10f) + (q.averageAbsoluteAmplitude / 32768f)
            if (score > bestScore) {
                bestScore = score
                best = i
            }
        }
        return best
    }

    // ---------------------------------------------------------------------
    // CHOOSE - pick which take becomes the active reference clip
    // ---------------------------------------------------------------------

    private fun renderChooseStep() {
        binding.listTakes.removeAllViews()
        for (i in PROMPTS.indices) {
            val file = takeFiles[i] ?: continue
            val quality = takeQuality[i]
            val row = ItemVoiceCloneTakeBinding.inflate(layoutInflater, binding.listTakes, false)
            row.textTakeTitle.text = "Sentence ${i + 1}"
            row.textTakeQuality.text = quality?.let { q ->
                if (q.warnings.isEmpty()) "Clean take, %.1fs".format(q.durationSeconds)
                else "%.1fs - %s".format(q.durationSeconds, q.warnings.joinToString("; "))
            } ?: "Recorded"
            row.radioTakeSelect.isChecked = (i == chosenTakeIndex)
            val select = { chosenTakeIndex = i; renderChooseStep() }
            row.radioTakeSelect.setOnClickListener { select() }
            row.root.setOnClickListener { select() }
            row.btnTakePlay.setOnClickListener { playFile(file) }
            binding.listTakes.addView(row.root)
        }
    }

    // ---------------------------------------------------------------------
    // BUILD - download the model if needed, then a real preview
    // ---------------------------------------------------------------------

    private fun renderBuildStep() {
        val app = mainActivity?.app ?: return
        val engine = app.voiceClone
        if (engine.isFullyDownloaded()) {
            binding.btnBuildDownload.visibility = View.GONE
            binding.progressBuild.visibility = View.GONE
            binding.textBuildStatus.text = "Voice-cloning model already downloaded."
            binding.btnPreview.isEnabled = true
            loadEngineThenEnablePreview()
        } else {
            binding.btnBuildDownload.visibility = View.VISIBLE
            binding.btnBuildDownload.isEnabled = true
            binding.btnBuildDownload.text = "Download voice-cloning model (~${VoiceCloneEngine.TOTAL_APPROX_SIZE_MIB}MB, Wi-Fi)"
            binding.textBuildStatus.text = "The cloning model (ZipVoice, Apache-2.0, via sherpa-onnx) isn't downloaded yet."
            binding.btnPreview.isEnabled = false
        }
    }

    private fun startBuildDownload() {
        val ctx = context ?: return
        val app = mainActivity?.app ?: return
        val engine = app.voiceClone
        binding.btnBuildDownload.isEnabled = false
        binding.progressBuild.visibility = View.VISIBLE
        binding.progressBuild.progress = 0
        binding.textBuildStatus.text = "Downloading voice-cloning model… 0%"

        engine.downloadModel(ctx, onProgress = { pct ->
            if (_binding == null) return@downloadModel
            binding.progressBuild.progress = pct / 2
            binding.textBuildStatus.text = "Downloading voice-cloning model (1 of 2)… $pct%"
        }) { success, error ->
            if (_binding == null) return@downloadModel
            if (success) {
                downloadVocoderStep(ctx, engine)
            } else {
                binding.btnBuildDownload.isEnabled = true
                binding.textBuildStatus.text = "Download failed: $error"
            }
        }
    }

    private fun downloadVocoderStep(ctx: android.content.Context, engine: VoiceCloneEngine) {
        binding.textBuildStatus.text = "Downloading voice (2 of 2)… 0%"
        engine.downloadVocoder(ctx, onProgress = { pct ->
            if (_binding == null) return@downloadVocoder
            binding.progressBuild.progress = 50 + (pct / 2)
            binding.textBuildStatus.text = "Downloading voice (2 of 2)… $pct%"
        }) { success, error ->
            if (_binding == null) return@downloadVocoder
            binding.btnBuildDownload.isEnabled = true
            if (success) {
                binding.btnBuildDownload.visibility = View.GONE
                binding.progressBuild.visibility = View.GONE
                binding.textBuildStatus.text = "Voice-cloning model downloaded."
                binding.btnPreview.isEnabled = true
                loadEngineThenEnablePreview()
            } else {
                binding.textBuildStatus.text = "Download failed: $error"
            }
        }
    }

    private fun loadEngineThenEnablePreview() {
        val app = mainActivity?.app ?: return
        app.voiceClone.loadAsync { success, error ->
            if (_binding == null) return@loadAsync
            if (!success) {
                binding.textBuildStatus.text = "Model downloaded, but failed to load: $error"
                binding.btnPreview.isEnabled = false
            }
        }
    }

    private fun previewVoice() {
        val app = mainActivity?.app ?: return
        val engine = app.voiceClone
        val referenceFile = takeFiles[chosenTakeIndex] ?: return
        val referenceText = PROMPTS[chosenTakeIndex]
        binding.btnPreview.isEnabled = false
        binding.textPreviewStatus.text = "Synthesizing… this can take a few seconds on this hardware."
        engine.speak(
            text = PREVIEW_TEXT,
            referenceAudioFile = referenceFile,
            referenceText = referenceText,
            onDone = {
                if (_binding == null) return@speak
                binding.btnPreview.isEnabled = true
                binding.textPreviewStatus.text = "That's your cloned voice."
                binding.btnBuildContinue.isEnabled = true
            },
            onError = { err ->
                if (_binding == null) return@speak
                binding.btnPreview.isEnabled = true
                binding.textPreviewStatus.text = "Preview failed: $err"
            }
        )
    }

    // ---------------------------------------------------------------------
    // DONE
    // ---------------------------------------------------------------------

    private fun renderDoneStep() {
        binding.textDoneSummary.text =
            "Your voice profile is saved on this device. You can turn voice cloning on or off, " +
                "preview it again, or re-record your voice any time from Settings -> Voice cloning."
    }

    private fun finishOnboarding() {
        val referenceFile = takeFiles[chosenTakeIndex]
        val referenceText = PROMPTS.getOrNull(chosenTakeIndex)
        if (referenceFile == null || referenceText == null) {
            Toast.makeText(requireContext(), "Something went wrong saving your voice profile - please try again.", Toast.LENGTH_LONG).show()
            return
        }
        profileStore.save(referenceFile, referenceText)
        VoiceClonePreferences.setOnboardingCompleted(requireContext(), true)
        VoiceClonePreferences.setEnabled(requireContext(), true)
        cleanupScratch()
        Toast.makeText(requireContext(), "Voice cloning is set up and turned on.", Toast.LENGTH_LONG).show()
        dismissAllowingStateLoss()
    }

    private fun confirmCloseAndDismiss() {
        mainActivity?.app?.mic?.stop()
        cleanupScratch()
        dismissAllowingStateLoss()
    }

    /** Discards every scratch take - [VoiceProfileStore.save] already copies the chosen one out into the committed profile before this runs. */
    private fun cleanupScratch() {
        profileStore.clearScratch()
    }

    private fun playFile(file: File) {
        try {
            player?.release()
            val mp = MediaPlayer()
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener { it.release() }
            mp.prepare()
            mp.start()
            player = mp
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Playback failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mainActivity?.app?.mic?.stop()
        player?.release()
        player = null
        _binding = null
    }

    companion object {
        const val TAG = "VoiceCloneOnboarding"

        // Standard, non-copyrighted speech-elicitation sentences (the second
        // is the well-known public-domain "Stella" passage used ubiquitously
        // in speech research) chosen for reasonable phonetic variety and a
        // natural, short (a few seconds at normal pace) reading length -
        // matching ZipVoice's own documented guidance that a short reference
        // clip works best for this model.
        private val PROMPTS = listOf(
            "The quick brown fox jumps over the lazy dog near the old stone bridge.",
            "Please call Stella and ask her to bring these things with her from the store.",
            "On a bright winter morning, the children walked quietly through the snow-covered park."
        )

        private const val PREVIEW_TEXT = "This is what your voice sounds like when it speaks a brand new sentence."

        fun newInstance(): VoiceCloneOnboardingFragment = VoiceCloneOnboardingFragment()
    }
}
