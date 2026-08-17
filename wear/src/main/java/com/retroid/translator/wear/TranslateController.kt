package com.retroid.translator.wear

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.retroid.translator.wear.audio.ContinuousListeningService
import com.retroid.translator.wear.audio.MicPipeline
import com.retroid.translator.wear.engine.TranslationEngine
import com.retroid.translator.wear.engine.VoskEngine
import com.retroid.translator.wear.engine.VoskResultParsing
import com.retroid.translator.wear.engine.WearLanguage
import com.retroid.translator.wear.engine.WearLanguages
import com.retroid.translator.wear.tts.SystemTtsSpeaker
import com.retroid.translator.wear.tts.WearEspeakEngine
import org.vosk.Recognizer

enum class ListenState { IDLE, LOADING_MODEL, LISTENING, SPEECH_ACTIVE, TRANSLATING }

/**
 * Owns the watch's standalone translate flow end to end: continuous/ambient
 * mic listening (MicPipeline) -> offline STT (VoskEngine) -> on-device
 * translation (TranslationEngine) -> speech output ([WearEspeakEngine],
 * this app's own offline eSpeak NG voice, preferred; [SystemTtsSpeaker] as
 * fallback when eSpeak isn't ready yet or doesn't cover the target
 * language - see `WearEspeakEngine`'s doc comment for why an armeabi-v7a
 * eSpeak build is now real, not the "not ported" state this controller
 * originally shipped with). Not a port of anything from the phone app - Conversations' two-way,
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
 *
 * **Wake-lock / foreground-service backed**: [startListeningService] /
 * [stopListeningService] wrap [ContinuousListeningService] around the
 * [mic]'s continuous-listening session so it survives a screen lock -
 * without it, on a form factor whose screen sleeps far more aggressively
 * than a phone's, continuous listening would very likely die the instant
 * the watch face went dark. See that class's doc comment for the full
 * mechanism and how it deliberately differs from the phone app's own
 * `ContinuousListeningService` fix.
 */
class TranslateController(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mic = MicPipeline()
    private val vosk = VoskEngine(context)
    private val ttsSpeaker = SystemTtsSpeaker(context)
    private val espeak = WearEspeakEngine(context)

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
        // eSpeak init is a blocking asset-unpack (18MB espeak-ng-data,
        // first run only) + native synth init - always off the main
        // thread, same as the phone app's own EspeakEngine.initAsync usage.
        // SystemTtsSpeaker stays the fallback if this fails or a language
        // isn't covered (see speakTranslated below), so a slow/failed
        // eSpeak init never blocks the flow.
        espeak.initAsync { success ->
            Log.i(TAG, "WearEspeakEngine init: success=$success")
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

    /**
     * Downloads the current [sourceLang]'s Vosk STT pack. Requires explicit
     * user consent per language before being called - see this class's
     * call site in `MainActivity`'s Compose UI (the "Download X" button
     * only exists because the user directly authorized these specific
     * downloads; do not wire this to auto-trigger on language selection).
     * Manages [state]/[statusMessage] itself, same pattern [startListening]
     * already uses, so the Compose UI's recomposition picks up progress and
     * completion without the caller needing its own callback.
     */
    fun downloadSourceModel() {
        if (state == ListenState.LOADING_MODEL) return
        state = ListenState.LOADING_MODEL
        statusMessage = "Downloading ${sourceLang.displayName} pack..."
        val dir = vosk.modelRootDir(sourceLang.code)
        com.retroid.translator.wear.engine.DownloadManager.downloadAndUnzip(
            context, sourceLang.voskUrl, dir, requireWifi = true,
            onDone = { success, error ->
                mainHandler.post {
                    state = ListenState.IDLE
                    statusMessage = if (success) "Tap to start listening" else (error ?: "Download failed")
                }
            }
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
            // Start the wake-lock/foreground-service backing (see
            // ContinuousListeningService's class doc) BEFORE the mic
            // pipeline itself, so the CPU-wake guarantee and Android 14's
            // mic-typed foreground-service requirement are both in place
            // before the first audio chunk can arrive, not raced against
            // it - same ordering the phone app's ConversationsFragment
            // uses for its own identical fix.
            startListeningService()
            mic.startContinuousListening(object : MicPipeline.ContinuousListener {
                override fun onListeningStarted() {}
                override fun onListeningStopped() {
                    // Safe to call even if already stopped (no-op) - see
                    // stopListeningService's own doc. This is the one path
                    // every continuous-listening stop (manual or the mic
                    // pipeline's own internal error/loop-exit) funnels
                    // through once the capture thread actually exits.
                    stopListeningService()
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
                                speakTranslated(result)
                            },
                            onError = { err ->
                                statusMessage = "Translate failed: $err"
                                state = ListenState.LISTENING
                            }
                        )
                    }
                }
                override fun onError(message: String) {
                    // MicPipeline can fail before ever spawning its capture
                    // thread (permission missing, mic unavailable, etc.) -
                    // on that path onListeningStopped above is never
                    // called, so this is a second, necessary release point
                    // for whatever startListeningService() just acquired,
                    // not a redundant duplicate of it.
                    stopListeningService()
                    mainHandler.post {
                        state = ListenState.IDLE
                        statusMessage = message
                    }
                }
            })
        }
    }

    /**
     * Speaks [text] in [targetLang], preferring this app's own offline
     * eSpeak NG voice ([espeak]) and transparently falling back to the
     * platform [ttsSpeaker] when eSpeak isn't ready or doesn't cover this
     * language - same fallback shape as the phone app's `TtsRouter`
     * (Piper -> eSpeak there; eSpeak -> system TTS here, one tier
     * shallower since `:wear` has no Piper/sherpa-onnx voice wired in yet).
     */
    private fun speakTranslated(text: String) {
        if (espeak.supportsLanguage(targetLang.code)) {
            espeak.speak(text, targetLang.code, onDone = {}, onError = { err ->
                Log.w(TAG, "eSpeak speak failed, falling back to system TTS: $err")
                ttsSpeaker.speak(text, targetLang.code, onDone = {}, onError = { err2 ->
                    Log.w(TAG, "TTS speak failed: $err2")
                })
            })
        } else {
            ttsSpeaker.speak(text, targetLang.code, onDone = {}, onError = { err ->
                Log.w(TAG, "TTS speak failed: $err")
            })
        }
    }

    private fun stopListening() {
        mic.stop()
        // Called directly here too, not left to the async
        // onListeningStopped callback alone - mic.stop() only requests the
        // capture thread exit (it finishes its current AudioRecord.read()
        // first), so stopping the service synchronously on the explicit
        // manual-stop path releases the wake lock/notification immediately
        // rather than waiting on that thread's next loop iteration. Same
        // "call it unconditionally on every stop path, it's a safe no-op if
        // already stopped" pattern the phone app's ConversationsFragment
        // uses for its own identical fix.
        stopListeningService()
        state = ListenState.IDLE
        statusMessage = "Tap to start listening"
    }

    fun release() {
        mic.stop()
        stopListeningService()
        vosk.release()
        ttsSpeaker.release()
        espeak.release()
    }

    /** See ContinuousListeningService's class doc for the full lifecycle contract this pairs with. */
    private fun startListeningService() {
        ContinuousListeningService.onTaskRemovedListener = { mic.stop() }
        ContextCompat.startForegroundService(context, Intent(context, ContinuousListeningService::class.java))
    }

    /** Safe to call even if the service was never started (no-op) - every stop path above calls this unconditionally rather than tracking "did we start it" separately. */
    private fun stopListeningService() {
        ContinuousListeningService.onTaskRemovedListener = null
        context.stopService(Intent(context, ContinuousListeningService::class.java))
    }

    companion object {
        private const val TAG = "TranslateController"
    }
}
