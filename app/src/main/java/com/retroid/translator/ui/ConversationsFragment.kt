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
import com.retroid.translator.MainActivity
import com.retroid.translator.databinding.FragmentConversationsBinding
import com.retroid.translator.databinding.ItemRecordingBinding
import com.retroid.translator.audio.MicPipeline
import com.retroid.translator.audio.RecordingsStore
import com.retroid.translator.engine.LanguageCatalog
import com.retroid.translator.engine.TranslationEngine
import com.retroid.translator.engine.VoskModelCatalog
import com.google.mlkit.nl.translate.TranslateLanguage
import java.io.File

class ConversationsFragment : Fragment() {

    private var _binding: FragmentConversationsBinding? = null
    private val binding get() = _binding!!

    private lateinit var languageCodes: List<String>
    private val mainActivity get() = activity as? MainActivity
    private lateinit var recordingsStore: RecordingsStore

    private var turnIsA = true
    private var player: MediaPlayer? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentConversationsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recordingsStore = RecordingsStore(requireContext(), "conversations")
        setupSpinners()
        updateTurnIndicator()
        binding.btnConversationMic.setOnClickListener { onMicTap() }
        refreshRecordingsList()
    }

    private fun setupSpinners() {
        languageCodes = LanguageCatalog.codes
        val names = languageCodes.map { LanguageCatalog.displayNameFor(it) }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerLangA.adapter = adapter
        binding.spinnerLangB.adapter = adapter
        binding.spinnerLangA.setSelection(languageCodes.indexOf(TranslateLanguage.ENGLISH).coerceAtLeast(0))
        binding.spinnerLangB.setSelection(languageCodes.indexOf(TranslateLanguage.SPANISH).let { if (it >= 0) it else 1.coerceAtMost(languageCodes.size - 1) })

        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { updateTurnIndicator() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        binding.spinnerLangA.onItemSelectedListener = listener
        binding.spinnerLangB.onItemSelectedListener = listener
    }

    private fun langA() = languageCodes[binding.spinnerLangA.selectedItemPosition]
    private fun langB() = languageCodes[binding.spinnerLangB.selectedItemPosition]
    private fun speakerLang() = if (turnIsA) langA() else langB()
    private fun listenerLang() = if (turnIsA) langB() else langA()

    private fun updateTurnIndicator() {
        val name = if (turnIsA) LanguageCatalog.displayNameFor(langA()) else LanguageCatalog.displayNameFor(langB())
        val who = if (turnIsA) "Person A" else "Person B"
        binding.textTurnIndicator.text = "$who's turn — speak $name"
    }

    private fun appendTranscript(line: String) {
        if (_binding == null) return
        binding.textTranscript.append(if (binding.textTranscript.text.isEmpty()) line else "\n$line")
    }

    private fun onMicTap() {
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
        val code = speakerLang()
        if (VoskModelCatalog.forLanguage(code) == null) {
            Toast.makeText(requireContext(), "No offline voice-input model for ${LanguageCatalog.displayNameFor(code)}", Toast.LENGTH_LONG).show()
            return
        }
        if (!app.vosk.isModelDownloaded(code)) {
            Toast.makeText(requireContext(), "Download the voice-input pack for ${LanguageCatalog.displayNameFor(code)} on the Translate tab first", Toast.LENGTH_LONG).show()
            return
        }

        binding.textConversationStatus.text = "Loading model…"
        app.vosk.loadModelAsync(code) { success, error ->
            if (_binding == null) return@loadModelAsync
            if (!success) {
                binding.textConversationStatus.text = ""
                Toast.makeText(requireContext(), "Couldn't load model: $error", Toast.LENGTH_LONG).show()
                return@loadModelAsync
            }
            val recognizer = app.vosk.newRecognizer()
            if (recognizer == null) {
                binding.textConversationStatus.text = ""
                Toast.makeText(requireContext(), "Couldn't start recognizer", Toast.LENGTH_LONG).show()
                return@loadModelAsync
            }
            val recordFile: File? = if (binding.toggleRecordSession.isChecked) {
                recordingsStore.newFile(if (turnIsA) "A" else "B")
            } else null

            binding.textConversationStatus.text = "Listening…"
            app.mic.start(recognizer, recordFile, object : MicPipeline.Listener {
                override fun onFinal(text: String) {
                    if (_binding == null) return
                    binding.textConversationStatus.text = ""
                    val who = if (turnIsA) "A" else "B"
                    appendTranscript("$who (${LanguageCatalog.displayNameFor(speakerLang())}): $text")
                    val srcCode = speakerLang()
                    val dstCode = listenerLang()
                    TranslationEngine.translate(srcCode, dstCode, text,
                        onResult = onResult@{ translated ->
                            if (_binding == null) return@onResult
                            appendTranscript("   → (${LanguageCatalog.displayNameFor(dstCode)}): $translated")
                            val router = mainActivity?.app?.tts
                            router?.speak(translated, dstCode, onDone = { switchTurn() }, onError = { switchTurn() })
                                ?: switchTurn()
                        },
                        onError = onError@{ err ->
                            if (_binding == null) return@onError
                            appendTranscript("   (translation failed: $err)")
                            switchTurn()
                        }
                    )
                }
                override fun onError(message: String) {
                    if (_binding != null) {
                        binding.textConversationStatus.text = ""
                        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onRecordingSaved(file: File, bytes: Long) {
                    refreshRecordingsList()
                }
            })
        }
    }

    private fun switchTurn() {
        turnIsA = !turnIsA
        if (_binding != null) updateTurnIndicator()
    }

    // ---------------------------------------------------------------------
    // Saved recordings list
    // ---------------------------------------------------------------------

    private fun refreshRecordingsList() {
        if (_binding == null) return
        binding.recordingsList.removeAllViews()
        val files = recordingsStore.list()
        if (files.isEmpty()) {
            val tv = TextView(requireContext())
            tv.text = "No recordings yet."
            tv.textSize = 12f
            binding.recordingsList.addView(tv)
            return
        }
        for (f in files) {
            val row = ItemRecordingBinding.inflate(layoutInflater, binding.recordingsList, false)
            row.textRecordingName.text = f.name
            row.btnPlay.setOnClickListener { playRecording(f) }
            row.btnDelete.setOnClickListener {
                recordingsStore.delete(f)
                refreshRecordingsList()
            }
            binding.recordingsList.addView(row.root)
        }
    }

    private fun playRecording(file: File) {
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
