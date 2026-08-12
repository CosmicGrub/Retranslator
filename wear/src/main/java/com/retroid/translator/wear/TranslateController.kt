package com.retroid.translator.wear

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.retroid.translator.wear.audio.MicPipeline
import com.retroid.translator.wear.engine.TranslationEngine
import com.retroid.translator.wear.engine.VoskEngine
import com.retroid.translator.wear.engine.VoskResultParsing
import com.retroid.translator.wear.engine.WearLanguage
import com.retroid.translator.wear.engine.WearLanguages
import com.retroid.translator.wear.tts.SystemTtsSpeaker
import org.vosk.Recognizer

enum class ListenState { IDLE, LOADING_MODEL, LISTENING, SPEECH_ACTIVE, TRANSLATING }

/**
 * Owns the watch's standalone translate flow end to end: continuous/ambient
 * mic listening (MicPipeline) -> offline STT (VoskEngine) -> on-device
 * translation (TranslationEngine) -> speech output (SystemTtsSpeaker). Not a
 * port of anything from the phone app - Conversations' two-way,
 * dual-recognizer auto-detect model (ContinuousConversationController)
 * doesn't fit a single-user "pick source+target, watch listens and
 * translates" flow, so this is a new, simpler, single-recognizer
 * controller, following the same continuous-listening SHAPE (VAD-driven,
 * no tap-to-talk) without copying logic that doesn't apply.
 *
 * Plain Kotlin class holding Compose `mutableStateOf` fields directly
 * (not a real `ViewModel`/`AndroidViewModel`) - deliberately minimal for
 * this pass's single-Activity, single-screen scope; a real ViewModel
 * wiring (surviving configuration changes cleanly) is reasonable follow-up
 * polish, not a functional gap.
 */
class TranslateController(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mic = MicPipeline()
    private val vosk = VoskEngine(context)
    private val ttsSpeaker = SystemTtsSpeaker(context)

    var sourceLang by mutableStateOf(WearLanguages.CURATED[0]) // English
        private set
    var targetLang by mutableStateOf(WearLanguages.CURATED[1]) // Spanish
        private set
    var state by mutableStateOf(ListenState.IDLE)
        private set
    var transcript by mutableStateOf("")
        private set
    var translated by mutableStateOf("")
        private set
    var statusMessage by mutableStateOf("Tap to start listening")
        private set

    private var activeRecognizer: Recognizer? = null

    init {
        // Answers this pass's central "hard technical question" (see spec)
        // without needing a real downloaded model: does Vosk's native/JNI
        // layer load and execute at all on this device's real ABI? Logged
        // at startup, unconditionally, tag VOSK_NATIVE_PROBE - grep for it
        // in the spec's real logcat evidence.
        vosk.probeNativeLoad { outcome, detail ->
            Log.i("VOSK_NATIVE_PROBE", "outcome=$outcome detail=$detail")
        }
    }

    fun cycleSourceLang() {
        if (state != ListenState.IDLE) return
        val idx = WearLanguages.CURATED.indexOf(sourceLang)
        var next = WearLanguages.CURATED[(idx + 1) % WearLanguages.CURATED.size]
        if (next == targetLang) next = WearLanguages.CURATED[(WearLanguages.CURATED.indexOf(next) + 1) % WearLanguages.CURATED.size]
        sourceLang = next
    }

    fun cycleTargetLang() {
        if (state != ListenState.IDLE) return
        val idx = WearLanguages.CURATED.indexOf(targetLang)
        var next = WearLanguages.CURATED[(idx + 1) % WearLanguages.CURATED.size]
        if (next == sourceLang) next = WearLanguages.CURATED[(WearLanguages.CURATED.indexOf(next) + 1) % WearLanguages.CURATED.size]
        targetLang = next
    }

    fun isSourceModelDownloaded(): Boolean = vosk.isModelDownloaded(sourceLang.code)

    /** Real download path, deliberately never invoked by this pass's own automated verification - see class doc / spec's honest-gaps section. */
    fun downloadSourceModel(onDone: (Boolean, String?) -> Unit) {
        val dir = vosk.modelRootDir(sourceLang.code)
        com.retroid.translator.wear.engine.DownloadManager.downloadAndUnzip(
            context, sourceLang.voskUrl, dir, requireWifi = true, onDone = onDone
        )
    }

    fun toggleListening() {
        if (state == ListenState.IDLE) startListening() else stopListening()
    }

    private fun startListening() {
        if (!vosk.isModelDownloaded(sourceLang.code)) {
            statusMessage = "${sourceLang.displayName} speech pack not downloaded"
            return
        }
        state = ListenState.LOADING_MODEL
        statusMessage = "Loading ${sourceLang.displayName} model..."
        vosk.loadModelAsync(sourceLang.code) { success, error ->
            if (!success) {
                state = ListenState.IDLE
                statusMessage = error ?: "Failed to load model"
                return@loadModelAsync
            }
            transcript = ""
            translated = ""
            state = ListenState.LISTENING
            statusMessage = "Listening..."
            mic.startContinuousListening(object : MicPipeline.ContinuousListener {
                override fun onListeningStarted() {}
                override fun onListeningStopped() {
                    if (state != ListenState.IDLE) {
                        state = ListenState.IDLE
                        statusMessage = "Tap to start listening"
                    }
                }
                override fun onSpeechStart() {
                    activeRecognizer = vosk.newRecognizer()
                    mainHandler.post {
                        state = ListenState.SPEECH_ACTIVE
                        statusMessage = "Hearing you..."
                    }
                }
                override fun onAudioChunk(buffer: ByteArray, length: Int) {
                    // Same pattern MicPipeline's own tap-to-talk path uses -
                    // a direct, synchronous acceptWaveForm call from the
                    // capture thread (see MicPipeline doc). One recognizer,
                    // not Conversations' dual-recognizer race, so there is
                    // no need for ContinuousConversationController's
                    // per-recognizer worker-thread/queue machinery here.
                    try {
                        activeRecognizer?.acceptWaveForm(buffer, length)
                    } catch (e: Exception) {
                        Log.e(TAG, "acceptWaveForm failed", e)
                    }
                }
                override fun onSpeechEnd() {
                    val rec = activeRecognizer
                    activeRecognizer = null
                    val finalJson = try { rec?.finalResult } catch (e: Exception) { null }
                    try { rec?.close() } catch (e: Exception) { /* ignore */ }
                    val text = VoskResultParsing.extractText(finalJson ?: "")
                    mainHandler.post {
                        if (state == ListenState.IDLE) return@post // stopped mid-utterance
                        if (text.isBlank()) {
                            state = ListenState.LISTENING
                            statusMessage = "Didn't catch that - listening..."
                            return@post
                        }
                        transcript = text
                        state = ListenState.TRANSLATING
                        statusMessage = "Translating..."
                        TranslationEngine.translate(
                            sourceLang.code, targetLang.code, text,
                            onResult = { result ->
                                translated = result
                                state = ListenState.LISTENING
                                statusMessage = "Listening..."
                                ttsSpeaker.speak(result, targetLang.code, onDone = {}, onError = { err ->
                                    Log.w(TAG, "TTS speak failed: $err")
                                })
                            },
                            onError = { err ->
                                statusMessage = "Translate failed: $err"
                                state = ListenState.LISTENING
                            }
                        )
                    }
                }
                override fun onError(message: String) {
                    mainHandler.post {
                        state = ListenState.IDLE
                        statusMessage = message
                    }
                }
            })
        }
    }

    private fun stopListening() {
        mic.stop()
        state = ListenState.IDLE
        statusMessage = "Tap to start listening"
    }

    fun release() {
        mic.stop()
        vosk.release()
        ttsSpeaker.release()
    }

    companion object {
        private const val TAG = "TranslateController"
    }
}
