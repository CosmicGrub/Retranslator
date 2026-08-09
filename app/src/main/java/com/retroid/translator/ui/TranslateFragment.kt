package com.retroid.translator.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.retroid.translator.MainActivity
import com.retroid.translator.databinding.FragmentTranslateBinding
import com.retroid.translator.engine.DownloadManager
import com.retroid.translator.engine.LanguageCatalog
import com.retroid.translator.engine.PiperVoiceCatalog
import com.retroid.translator.engine.TranslationEngine
import com.retroid.translator.engine.VoiceGender
import com.retroid.translator.engine.VoicePreferences
import com.retroid.translator.engine.VoskModelCatalog

class TranslateFragment : Fragment() {

    private var _binding: FragmentTranslateBinding? = null
    private val binding get() = _binding!!

    private lateinit var languageCodes: List<String>
    private val languageIdentifier by lazy { LanguageIdentification.getClient() }

    private val mainActivity get() = activity as? MainActivity
    private fun selectedGender(): VoiceGender = if (binding.radioMale.isChecked) VoiceGender.MALE else VoiceGender.FEMALE

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTranslateBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupLanguageSpinners()
        setupListeners()
        setupGenderToggle()
        refreshModelStatus()
        refreshSttStatus()
        refreshNaturalVoiceStatus()
    }

    private fun setupGenderToggle() {
        val gender = VoicePreferences.getGender(requireContext())
        binding.radioMale.isChecked = gender == VoiceGender.MALE
        binding.radioFemale.isChecked = gender == VoiceGender.FEMALE
        binding.radioGroupGender.setOnCheckedChangeListener { _, _ ->
            VoicePreferences.setGender(requireContext(), selectedGender())
            refreshNaturalVoiceStatus()
        }
    }

    private fun setupLanguageSpinners() {
        languageCodes = LanguageCatalog.codes
        val names = languageCodes.map { LanguageCatalog.displayNameFor(it) }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSource.adapter = adapter
        binding.spinnerTarget.adapter = adapter

        val defaultSourceIdx = languageCodes.indexOf(TranslateLanguage.ENGLISH).let { if (it >= 0) it else 0 }
        val defaultTargetIdx = languageCodes.indexOf(TranslateLanguage.SPANISH).let {
            if (it >= 0) it else if (languageCodes.size > 1) 1 else 0
        }
        binding.spinnerSource.setSelection(defaultSourceIdx)
        binding.spinnerTarget.setSelection(defaultTargetIdx)

        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                refreshModelStatus()
                refreshSttStatus()
                refreshNaturalVoiceStatus()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        binding.spinnerSource.onItemSelectedListener = listener
        binding.spinnerTarget.onItemSelectedListener = listener
    }

    private fun setupListeners() {
        binding.checkboxAutoDetect.setOnCheckedChangeListener { _, checked ->
            binding.spinnerSource.isEnabled = !checked
            binding.spinnerSource.alpha = if (checked) 0.5f else 1f
            refreshSttStatus()
        }

        binding.btnSwapLanguages.setOnClickListener {
            if (binding.checkboxAutoDetect.isChecked) return@setOnClickListener
            val s = binding.spinnerSource.selectedItemPosition
            val t = binding.spinnerTarget.selectedItemPosition
            binding.spinnerSource.setSelection(t)
            binding.spinnerTarget.setSelection(s)
        }

        binding.btnDownloadModels.setOnClickListener { downloadTranslateModels() }
        binding.btnDownloadStt.setOnClickListener { downloadSttModel() }
        binding.btnDownloadNaturalVoice.setOnClickListener { downloadNaturalVoice() }
        binding.btnTranslate.setOnClickListener { performTranslate() }
        binding.btnMic.setOnClickListener { startVoiceInput() }
        binding.btnSpeak.setOnClickListener { speakResult() }
    }

    private fun selectedSourceCode() = languageCodes[binding.spinnerSource.selectedItemPosition]
    private fun selectedTargetCode() = languageCodes[binding.spinnerTarget.selectedItemPosition]

    // ---------------------------------------------------------------------
    // Translation-model management
    // ---------------------------------------------------------------------

    private fun refreshModelStatus() {
        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models ->
                if (_binding == null) return@addOnSuccessListener
                val downloaded = models.map { it.language }.toSet()
                val srcOk = downloaded.contains(selectedSourceCode())
                val tgtOk = downloaded.contains(selectedTargetCode())
                binding.textModelStatus.text = when {
                    srcOk && tgtOk -> "Both translation packs downloaded — works fully offline, no network needed."
                    srcOk || tgtOk -> "One translation pack downloaded, one still needed — tap Download (needs Wi-Fi)."
                    else -> "Translation packs not downloaded yet — tap Download once on Wi-Fi, then it's offline."
                }
            }
            .addOnFailureListener {
                if (_binding != null) binding.textModelStatus.text = "Could not check translation pack status."
            }
    }

    private fun downloadTranslateModels() {
        val src = selectedSourceCode()
        val tgt = selectedTargetCode()
        binding.textModelStatus.text = "Downloading translation packs (Wi-Fi required)…"
        TranslationEngine.downloadModel(src, requireWifi = true) { okSrc, errSrc ->
            if (!okSrc) {
                Toast.makeText(requireContext(), "Download failed: $errSrc", Toast.LENGTH_LONG).show()
                refreshModelStatus()
                return@downloadModel
            }
            TranslationEngine.downloadModel(tgt, requireWifi = true) { okTgt, errTgt ->
                if (okTgt) {
                    Toast.makeText(requireContext(), "Translation packs downloaded. Offline from now on.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Download failed: $errTgt", Toast.LENGTH_LONG).show()
                }
                refreshModelStatus()
            }
        }
    }

    // ---------------------------------------------------------------------
    // Speech-recognition (Vosk) pack management, for the source language
    // ---------------------------------------------------------------------

    private fun refreshSttStatus() {
        val app = mainActivity?.app ?: return
        val code = selectedSourceCode()
        val info = VoskModelCatalog.forLanguage(code)
        if (info == null) {
            binding.textSttStatus.text = "No offline voice-input model available for ${LanguageCatalog.displayNameFor(code)}."
            binding.btnDownloadStt.visibility = View.GONE
            return
        }
        binding.btnDownloadStt.visibility = View.VISIBLE
        if (app.vosk.isModelDownloaded(code)) {
            binding.textSttStatus.text = "Voice-input pack for ${info.displayName} downloaded — mic works fully offline."
        } else {
            binding.textSttStatus.text = "Voice-input pack for ${info.displayName} not downloaded (~${info.approxSizeMiB}MB, Wi-Fi)."
        }
    }

    private fun downloadSttModel() {
        val app = mainActivity?.app ?: return
        val code = selectedSourceCode()
        val info = VoskModelCatalog.forLanguage(code) ?: return
        binding.textSttStatus.text = "Downloading voice-input pack (Wi-Fi required)…"
        DownloadManager.downloadAndUnzip(
            requireContext(), info.url, app.vosk.modelRootDir(code), requireWifi = true,
            onProgress = { pct ->
                if (_binding != null) binding.textSttStatus.text = "Downloading voice-input pack… $pct%"
            }
        ) { success, error ->
            if (_binding == null) return@downloadAndUnzip
            if (success) {
                Toast.makeText(requireContext(), "Voice-input pack downloaded. Mic works offline now.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Download failed: $error", Toast.LENGTH_LONG).show()
            }
            refreshSttStatus()
        }
    }

    // ---------------------------------------------------------------------
    // Translation
    // ---------------------------------------------------------------------

    private fun performTranslate() {
        val text = binding.editInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            Toast.makeText(requireContext(), "Type or speak something first", Toast.LENGTH_SHORT).show()
            return
        }
        if (binding.checkboxAutoDetect.isChecked) {
            languageIdentifier.identifyLanguage(text)
                .addOnSuccessListener { code ->
                    if (_binding == null) return@addOnSuccessListener
                    if (code == "und") {
                        Toast.makeText(requireContext(), "Couldn't detect the language, please pick one manually", Toast.LENGTH_LONG).show()
                    } else {
                        binding.textDetected.text = "Detected source language: ${LanguageCatalog.displayNameFor(code)}"
                        translateWith(code, selectedTargetCode(), text)
                    }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(requireContext(), "Language detection failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
        } else {
            binding.textDetected.text = ""
            translateWith(selectedSourceCode(), selectedTargetCode(), text)
        }
    }

    private fun translateWith(sourceCode: String, targetCode: String, text: String) {
        binding.textResult.text = "Translating…"
        TranslationEngine.translate(
            sourceCode, targetCode, text,
            onResult = onResult@{ translated ->
                if (_binding == null) return@onResult
                binding.textResult.text = translated
                refreshModelStatus()
            },
            onError = onError@{ err ->
                if (_binding == null) return@onError
                binding.textResult.text = ""
                Toast.makeText(requireContext(), "Translation failed: $err", Toast.LENGTH_LONG).show()
            }
        )
    }

    // ---------------------------------------------------------------------
    // Voice input (offline Vosk)
    // ---------------------------------------------------------------------

    private fun startVoiceInput() {
        val activity = mainActivity ?: return
        if (!activity.hasMicPermission()) {
            activity.requestMicPermissionIfNeeded()
            Toast.makeText(requireContext(), "Grant microphone permission, then tap the mic again", Toast.LENGTH_LONG).show()
            return
        }
        val app = activity.app
        val code = selectedSourceCode()
        val info = VoskModelCatalog.forLanguage(code)
        if (info == null) {
            Toast.makeText(requireContext(), "No offline voice-input model for ${LanguageCatalog.displayNameFor(code)}", Toast.LENGTH_LONG).show()
            return
        }
        if (!app.vosk.isModelDownloaded(code)) {
            Toast.makeText(requireContext(), "Download the voice-input pack for this language first (see button above)", Toast.LENGTH_LONG).show()
            return
        }
        if (app.mic.isRunning()) {
            app.mic.stop()
            return
        }
        binding.textMicStatus.text = "Loading voice-input model…"
        app.vosk.loadModelAsync(code) { success, error ->
            if (_binding == null) return@loadModelAsync
            if (!success) {
                binding.textMicStatus.text = ""
                Toast.makeText(requireContext(), "Couldn't load voice-input model: $error", Toast.LENGTH_LONG).show()
                return@loadModelAsync
            }
            val recognizer = app.vosk.newRecognizer()
            if (recognizer == null) {
                binding.textMicStatus.text = ""
                Toast.makeText(requireContext(), "Couldn't start recognizer", Toast.LENGTH_LONG).show()
                return@loadModelAsync
            }
            binding.textMicStatus.text = "Listening… speak now"
            app.mic.start(recognizer, recordToFile = null, listener = object : com.retroid.translator.audio.MicPipeline.Listener {
                override fun onPartial(text: String) {
                    if (_binding != null) binding.editInput.setText(text)
                }
                override fun onFinal(text: String) {
                    if (_binding == null) return
                    binding.editInput.setText(text)
                    binding.editInput.setSelection(text.length)
                    binding.textMicStatus.text = "Heard: “$text”"
                    performTranslate()
                }
                override fun onError(message: String) {
                    if (_binding != null) {
                        binding.textMicStatus.text = ""
                        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onListeningStopped() {
                    if (_binding != null && binding.textMicStatus.text == "Listening… speak now") {
                        binding.textMicStatus.text = ""
                    }
                }
            })
        }
    }

    // ---------------------------------------------------------------------
    // Spoken output (offline eSpeak NG)
    // ---------------------------------------------------------------------

    private fun speakResult() {
        val app = mainActivity?.app ?: return
        val text = binding.textResult.text?.toString().orEmpty()
        if (text.isBlank() || text == "Translating…") {
            Toast.makeText(requireContext(), "Nothing to speak yet", Toast.LENGTH_SHORT).show()
            return
        }
        val target = selectedTargetCode()
        app.tts.speak(
            text, target, selectedGender(),
            onDone = {},
            onError = { err -> if (isAdded) Toast.makeText(requireContext(), err, Toast.LENGTH_LONG).show() }
        )
    }

    // ---------------------------------------------------------------------
    // Natural-voice (Piper via sherpa-onnx) pack management, for the target
    // language + currently-selected gender. eSpeak (Tier 1) always covers
    // both genders for every language with zero download, so there's never
    // a "no voice at all" state here - only "robotic eSpeak" vs "natural".
    // ---------------------------------------------------------------------

    private fun refreshNaturalVoiceStatus() {
        val app = mainActivity?.app ?: return
        val code = selectedTargetCode()
        val gender = selectedGender()
        val info = app.tts.naturalVoiceInfo(code, gender)
        if (info == null) {
            val genderLabel = if (gender == VoiceGender.MALE) "male" else "female"
            binding.textNaturalVoiceStatus.text = "No natural $genderLabel voice available yet for ${LanguageCatalog.displayNameFor(code)} - eSpeak (built-in, robotic) will be used."
            binding.btnDownloadNaturalVoice.visibility = View.GONE
            return
        }
        binding.btnDownloadNaturalVoice.visibility = View.VISIBLE
        if (app.tts.isNaturalVoiceDownloaded(code, gender)) {
            binding.textNaturalVoiceStatus.text = "Natural voice (${info.displayName}) downloaded — used automatically instead of eSpeak."
            binding.btnDownloadNaturalVoice.text = "Re-download natural voice"
        } else {
            binding.textNaturalVoiceStatus.text = "Natural voice available: ${info.displayName} (~${info.approxSizeMiB}MB, Wi-Fi, ${info.license}). Falls back to eSpeak (robotic) until downloaded."
            binding.btnDownloadNaturalVoice.text = "Download natural voice (Wi-Fi)"
        }
    }

    private fun downloadNaturalVoice() {
        val app = mainActivity?.app ?: return
        val code = selectedTargetCode()
        val gender = selectedGender()
        binding.textNaturalVoiceStatus.text = "Downloading natural voice (Wi-Fi required)…"
        app.tts.downloadNaturalVoice(
            requireContext(), code, gender,
            onProgress = { pct ->
                if (_binding != null) binding.textNaturalVoiceStatus.text = "Downloading natural voice… $pct%"
            }
        ) onDownloadDone@{ success, error ->
            if (_binding == null) return@onDownloadDone
            if (success) {
                Toast.makeText(requireContext(), "Natural voice downloaded. Used automatically from now on.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Download failed: $error", Toast.LENGTH_LONG).show()
            }
            refreshNaturalVoiceStatus()
        }
    }

    override fun onPause() {
        super.onPause()
        mainActivity?.app?.mic?.stop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
