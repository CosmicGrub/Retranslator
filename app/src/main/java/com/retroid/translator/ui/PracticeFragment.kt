package com.retroid.translator.ui

import android.media.MediaPlayer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.mlkit.nl.translate.TranslateLanguage
import com.retroid.translator.MainActivity
import com.retroid.translator.audio.MicPipeline
import com.retroid.translator.audio.RecordingsStore
import com.retroid.translator.databinding.FragmentPracticeBinding
import com.retroid.translator.databinding.ItemRecordingBinding
import com.retroid.translator.engine.LanguageCatalog
import com.retroid.translator.engine.PiperVoiceCatalog
import java.io.File

class PracticeFragment : Fragment() {

    private var _binding: FragmentPracticeBinding? = null
    private val binding get() = _binding!!

    private lateinit var languageCodes: List<String>
    private lateinit var recordingsStore: RecordingsStore
    private val mainActivity get() = activity as? MainActivity
    private var player: MediaPlayer? = null
    private var lastAttempt: File? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPracticeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recordingsStore = RecordingsStore(requireContext(), "practice")
        setupSpinner()

        binding.btnHearReference.setOnClickListener { hearReference() }
        binding.btnRecordAttempt.setOnClickListener { toggleRecordAttempt() }
        binding.btnPlayAttempt.setOnClickListener { lastAttempt?.let { playFile(it) } }
        binding.btnDownloadNaturalVoicePractice.setOnClickListener { downloadNaturalVoice() }

        refreshRecordingsList()
        refreshNaturalVoiceStatus()
    }

    private fun setupSpinner() {
        languageCodes = LanguageCatalog.codes
        val names = languageCodes.map { LanguageCatalog.displayNameFor(it) }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerPracticeLang.adapter = adapter
        binding.spinnerPracticeLang.setSelection(languageCodes.indexOf(TranslateLanguage.ENGLISH).coerceAtLeast(0))
        binding.spinnerPracticeLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = refreshNaturalVoiceStatus()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun selectedCode() = languageCodes[binding.spinnerPracticeLang.selectedItemPosition]

    private fun hearReference() {
        val app = mainActivity?.app ?: return
        val text = binding.editPhrase.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            Toast.makeText(requireContext(), "Type a word or phrase first", Toast.LENGTH_SHORT).show()
            return
        }
        val code = selectedCode()
        app.tts.speak(text, code, onDone = {}, onError = { err ->
            if (isAdded) Toast.makeText(requireContext(), err, Toast.LENGTH_LONG).show()
        })
    }

    // ---------------------------------------------------------------------
    // Natural-voice (Piper via sherpa-onnx) pack management
    // ---------------------------------------------------------------------

    private fun refreshNaturalVoiceStatus() {
        val app = mainActivity?.app ?: return
        val code = selectedCode()
        val info = PiperVoiceCatalog.forLanguage(code)
        if (info == null) {
            binding.textNaturalVoiceStatusPractice.text = "No natural voice available yet for ${LanguageCatalog.displayNameFor(code)} - using eSpeak (built-in, robotic)."
            binding.btnDownloadNaturalVoicePractice.visibility = View.GONE
            return
        }
        binding.btnDownloadNaturalVoicePractice.visibility = View.VISIBLE
        if (app.piper.isVoiceDownloaded(code)) {
            binding.textNaturalVoiceStatusPractice.text = "Natural voice (${info.displayName}) downloaded — reference pronunciation uses it automatically."
            binding.btnDownloadNaturalVoicePractice.text = "Re-download natural voice"
        } else {
            binding.textNaturalVoiceStatusPractice.text = "Natural voice available: ${info.displayName} (~${info.approxSizeMiB}MB, Wi-Fi, ${info.license}). Using eSpeak (robotic) until downloaded."
            binding.btnDownloadNaturalVoicePractice.text = "Download natural voice (Wi-Fi)"
        }
    }

    private fun downloadNaturalVoice() {
        val app = mainActivity?.app ?: return
        val code = selectedCode()
        binding.textNaturalVoiceStatusPractice.text = "Downloading natural voice (Wi-Fi required)…"
        app.piper.downloadVoice(
            requireContext(), code,
            onProgress = { pct ->
                if (_binding != null) binding.textNaturalVoiceStatusPractice.text = "Downloading natural voice… $pct%"
            }
        ) { success, error ->
            if (_binding == null) return@downloadVoice
            if (success) {
                Toast.makeText(requireContext(), "Natural voice downloaded.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Download failed: $error", Toast.LENGTH_LONG).show()
            }
            refreshNaturalVoiceStatus()
        }
    }

    private fun toggleRecordAttempt() {
        val activity = mainActivity ?: return
        val app = activity.app
        if (app.mic.isRunning()) {
            app.mic.stop()
            return
        }
        if (!activity.hasMicPermission()) {
            activity.requestMicPermissionIfNeeded()
            Toast.makeText(requireContext(), "Grant microphone permission, then tap again", Toast.LENGTH_LONG).show()
            return
        }
        val phrase = binding.editPhrase.text?.toString()?.trim().orEmpty().ifEmpty { "phrase" }
        val file = recordingsStore.newFile(phrase)
        binding.btnRecordAttempt.text = "⏹ Stop recording"
        binding.textPracticeStatus.text = "Recording… tap Stop when done"
        app.mic.start(
            recognizer = null,
            recordToFile = file,
            listener = object : MicPipeline.Listener {
                override fun onListeningStopped() {
                    if (_binding == null) return
                    binding.btnRecordAttempt.text = "🎙 Record my attempt"
                    binding.textPracticeStatus.text = ""
                }
                override fun onRecordingSaved(file: File, bytes: Long) {
                    if (_binding == null) return
                    lastAttempt = file
                    binding.btnPlayAttempt.isEnabled = true
                    refreshRecordingsList()
                }
                override fun onError(message: String) {
                    if (_binding == null) return
                    binding.btnRecordAttempt.text = "🎙 Record my attempt"
                    binding.textPracticeStatus.text = ""
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun refreshRecordingsList() {
        if (_binding == null) return
        binding.practiceRecordingsList.removeAllViews()
        val files = recordingsStore.list()
        if (files.isEmpty()) {
            val tv = TextView(requireContext())
            tv.text = "No practice attempts yet."
            tv.textSize = 12f
            binding.practiceRecordingsList.addView(tv)
            return
        }
        for (f in files) {
            val row = ItemRecordingBinding.inflate(layoutInflater, binding.practiceRecordingsList, false)
            row.textRecordingName.text = f.name
            row.btnPlay.setOnClickListener { playFile(f) }
            row.btnDelete.setOnClickListener {
                recordingsStore.delete(f)
                if (lastAttempt == f) {
                    lastAttempt = null
                    binding.btnPlayAttempt.isEnabled = false
                }
                refreshRecordingsList()
            }
            binding.practiceRecordingsList.addView(row.root)
        }
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

    override fun onPause() {
        super.onPause()
        mainActivity?.app?.mic?.stop()
        player?.release()
        player = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        player?.release()
        player = null
        _binding = null
    }
}
