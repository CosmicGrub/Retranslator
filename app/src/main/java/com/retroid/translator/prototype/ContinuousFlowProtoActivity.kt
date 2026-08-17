package com.retroid.translator.prototype

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.ScrollView
import android.widget.TextView
import com.retroid.translator.TranslatorApp
import com.retroid.translator.conversation.ContinuousConversationController
import com.retroid.translator.engine.TranslationEngine
import com.retroid.translator.engine.VoiceGender
import com.retroid.translator.engine.VoskEngine
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Throwaway, NON-exported-in-spirit (see [DualRecognizerProtoActivity]'s doc
 * comment for why it's `exported=true` anyway on this OS) debug entry point
 * validating the CONTINUOUS listening pipeline end-to-end
 * ([ContinuousConversationController] + [TranslationEngine] +
 * [com.retroid.translator.engine.TtsRouter]) against real recorded human
 * speech, the same way [DualRecognizerProtoActivity] validated the STT-only
 * dual-recognizer decode. Trigger:
 *
 *   adb shell am start -n com.retroid.translator/.prototype.ContinuousFlowProtoActivity
 *
 * then `adb logcat -s ContFlowProto`.
 *
 * What this proves, concretely: [RealSpeechCorpus] clips are fed into
 * [ContinuousConversationController.micListener] chunk-by-chunk, PACED AT
 * REAL TIME (see [feedClipRealTime] - each chunk is held for the same
 * wall-clock duration a live 16kHz [android.media.AudioRecord] would take to
 * fill it), not dumped in as fast as the CPU can go. This matters for the
 * latency number this produces to mean anything: if every chunk were fed
 * instantly, both recognizers would already be fully caught up by the time
 * `onSpeechEnd()` fires, making "decode wall time after speech end" measure
 * ~0ms regardless of how fast decoding actually is - an artificially
 * favorable number that wouldn't reflect a real conversation, where decode
 * work races the live audio arriving in real time and only the LAST bit of
 * lag (after the person stops talking) is on the critical path.
 *
 * This is still not a live human speaking into the device's microphone -
 * see [RealSpeechCorpus]'s doc comment for exactly what gap that leaves.
 * What IS real here: the recorded speech audio itself, the actual on-device
 * Vosk decode, the actual ML Kit translation call, and the actual TTS
 * synthesis + `AudioTrack`/native-audio-track write that produces real
 * audible output - only the "how did this audio reach the pipeline" step is
 * simulated (pre-recorded file instead of a live mic), exactly like
 * [DualRecognizerProtoActivity] already established as this project's
 * offline validation method.
 */
class ContinuousFlowProtoActivity : Activity() {
    private lateinit var logView: TextView
    private val logBuilder = StringBuilder()

    companion object {
        private const val TAG = "ContFlowProto"
        private const val LANG_A = "en"
        private const val LANG_B = "es"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logView = TextView(this).apply {
            gravity = Gravity.START
            textSize = 11f
            setPadding(24, 24, 24, 24)
            text = "Starting continuous-flow prototype…\n"
        }
        setContentView(ScrollView(this).apply { addView(logView) })
        Thread({ runExperiment() }, "ContFlowProto-Main").start()
    }

    private fun appendLog(line: String) {
        Log.i(TAG, line)
        runOnUiThread {
            logBuilder.append(line).append('\n')
            logView.text = logBuilder.toString()
        }
    }

    private fun runExperiment() {
        try {
            val app = application as TranslatorApp

            appendLog("Ensuring eSpeak (TTS) is initialized…")
            if (!app.espeak.ready && !app.espeak.initFailed) app.espeak.initBlocking()
            if (!app.espeak.ready) {
                appendLog("FATAL: eSpeak failed to initialize (initFailed=${app.espeak.initFailed}) - TTS latency step can't run.")
                return
            }

            appendLog("Loading Vosk models for '$LANG_A' and '$LANG_B' into two dedicated VoskEngine instances (same pattern as DualRecognizerPrototype, NOT the shared app.vosk engine)…")
            val latch = CountDownLatch(1)
            var engineA: VoskEngine? = null
            var engineB: VoskEngine? = null
            var loadOk = false
            var loadErr: String? = null
            DualRecognizerPrototype.loadEngines(applicationContext, LANG_A, LANG_B) { eA, eB, ok, err ->
                engineA = eA; engineB = eB; loadOk = ok; loadErr = err
                latch.countDown()
            }
            latch.await()
            if (!loadOk || engineA == null || engineB == null) {
                appendLog("FATAL: model load failed: $loadErr")
                appendLog("Make sure Vosk STT packs for English and Spanish are downloaded via the Translate tab first.")
                return
            }
            appendLog("Both models resident.")

            appendLog("Pre-warming ML Kit translation models ($LANG_A<->$LANG_B) so the timed loop below isn't measuring a one-time download…")
            prewarmTranslation()

            appendLog("Loading REAL human-speech corpus clips (see assets/real_speech_corpus/ATTRIBUTION.txt)…")
            val clips = RealSpeechCorpus.loadAll(applicationContext, File(cacheDir, "cont_flow_proto_audio"))
            appendLog("Loaded ${clips.size} clips: ${clips.joinToString { it.label }}")

            val chunkSize = DualRecognizerPrototype.chunkSizeBytes()
            val chunkDurationMs = (chunkSize / 2).toLong() * 1000L / DualRecognizerPrototype.SAMPLE_RATE
            appendLog("Chunk size=${chunkSize} bytes (=${chunkDurationMs}ms @16kHz mono) - same cadence MicPipeline uses live.")

            var hits = 0
            val speechEndToAudioStartMsList = mutableListOf<Long>()
            val decodeWallTimeMsList = mutableListOf<Long>()
            val earlyGuessLeadMsList = mutableListOf<Long>()
            var earlyGuessMatches = 0
            var earlyGuessFired = 0

            for (clip in clips) {
                appendLog("===== ${clip.label} [${clip.langCode}]: \"${clip.text}\" =====")
                val pcm = DualRecognizerPrototype.readWavPcm(clip.file)
                val audioDurationMs = DualRecognizerPrototype.audioDurationMs(pcm)

                val doneLatch = CountDownLatch(1)
                var audioStartNanosCapture = -1L
                var speechEndNanosCapture = -1L
                var utteranceLangResult: ContinuousConversationController.UtteranceResult? = null

                val controller = ContinuousConversationController(
                    engineA!!, LANG_A, engineB!!, LANG_B,
                    object : ContinuousConversationController.Listener {
                        override fun onEarlyLanguageGuess(guessedLang: String, partialText: String, elapsedSinceSpeechStartMs: Long) {
                            earlyGuessFired++
                            appendLog("  early guess: $guessedLang (\"$partialText\") at +${elapsedSinceSpeechStartMs}ms since speech start")
                        }

                        override fun onEmptyUtterance() {
                            appendLog("  EMPTY (neither recognizer produced text - VAD/decode issue on this clip)")
                            doneLatch.countDown()
                        }

                        override fun onError(message: String) {
                            appendLog("  ERROR: $message")
                            doneLatch.countDown()
                        }

                        override fun onUtteranceFinal(result: ContinuousConversationController.UtteranceResult) {
                            utteranceLangResult = result
                            speechEndNanosCapture = result.speechEndNanos
                            val correct = result.pickedLang == clip.langCode
                            if (correct) hits++
                            decodeWallTimeMsList.add(result.decodeWallTimeMs)
                            if (result.earlyGuessLang != null) {
                                earlyGuessLeadMsList.add(result.speechDurationMs - (result.earlyGuessElapsedMs ?: 0L))
                                if (result.earlyGuessLang == result.pickedLang) earlyGuessMatches++
                            }
                            appendLog(
                                "  picked=${result.pickedLang} (actual=${clip.langCode}) correct=$correct basis=\"${result.decisionBasis}\" " +
                                    "text=\"${result.text}\" speechDurationMs=${result.speechDurationMs} decodeWallTimeMs=${result.decodeWallTimeMs} " +
                                    "earlyGuess=${result.earlyGuessLang} earlyGuessElapsedMs=${result.earlyGuessElapsedMs}"
                            )

                            val translateStartNanos = System.nanoTime()
                            TranslationEngine.translate(this@ContinuousFlowProtoActivity, result.pickedLang, result.otherLang, result.text,
                                onResult = { translated ->
                                    val translateDoneNanos = System.nanoTime()
                                    val translateMs = (translateDoneNanos - translateStartNanos) / 1_000_000
                                    appendLog("  translated (${translateMs}ms): \"$translated\"")
                                    app.tts.speak(
                                        translated, result.otherLang, VoiceGender.FEMALE,
                                        onDone = { doneLatch.countDown() },
                                        onError = { err -> appendLog("  TTS error: $err"); doneLatch.countDown() },
                                        onAudioStart = {
                                            audioStartNanosCapture = System.nanoTime()
                                            val speechEndToAudioStartMs = (audioStartNanosCapture - speechEndNanosCapture) / 1_000_000
                                            speechEndToAudioStartMsList.add(speechEndToAudioStartMs)
                                            appendLog("  LATENCY speechEndToTtsAudioStartMs=$speechEndToAudioStartMs")
                                        }
                                    )
                                },
                                onError = { err -> appendLog("  translation FAILED: $err"); doneLatch.countDown() }
                            )
                        }
                    }
                )

                feedClipRealTime(controller, pcm, chunkSize, chunkDurationMs)
                val gotIt = doneLatch.await(30, TimeUnit.SECONDS)
                if (!gotIt) appendLog("  WARNING: timed out waiting for this clip's pipeline to finish (30s)")
                if (utteranceLangResult == null && gotIt) {
                    // onEmptyUtterance/onError path already logged+counted-done above.
                }
                appendLog("  clip audio duration=${audioDurationMs}ms")
            }

            val avgLatency = speechEndToAudioStartMsList.average()
            val avgDecodeWall = decodeWallTimeMsList.averageOrNaN()
            val avgEarlyLead = earlyGuessLeadMsList.averageOrNaN()
            val summary =
                "SUMMARY[REAL HUMAN SPEECH, continuous flow]: languagePick %d/%d correct. ".format(hits, clips.size) +
                    "avgSpeechEndToTtsAudioStartMs=%.1f (n=%d) ".format(avgLatency, speechEndToAudioStartMsList.size) +
                    "avgSttDecodeWallTimeMs=%.1f ".format(avgDecodeWall) +
                    "earlyGuessFired=%d/%d earlyGuessMatchedFinal=%d avgEarlyGuessLeadMs=%.1f"
                        .format(earlyGuessFired, clips.size, earlyGuessMatches, avgEarlyLead)
            Log.i(TAG, summary)
            appendLog(summary)
            appendLog("DONE.")
        } catch (e: Throwable) {
            Log.e(TAG, "Continuous-flow prototype run failed", e)
            appendLog("FATAL ERROR: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Feeds [pcm] into [controller]'s continuous-listener contract as one utterance, chunk-by-chunk, sleeping between chunks to match real 16kHz mic arrival cadence - see class doc for why this matters to the latency number. */
    private fun feedClipRealTime(controller: ContinuousConversationController, pcm: ByteArray, chunkSize: Int, chunkDurationMs: Long) {
        controller.micListener.onSpeechStart()
        var offset = 0
        while (offset < pcm.size) {
            val len = minOf(chunkSize, pcm.size - offset)
            val chunk = pcm.copyOfRange(offset, offset + len)
            controller.micListener.onAudioChunk(chunk, len)
            offset += len
            if (offset < pcm.size) {
                try { Thread.sleep(chunkDurationMs) } catch (e: InterruptedException) { /* ignore */ }
            }
        }
        controller.micListener.onSpeechEnd()
    }

    /** Blocking; downloads/confirms both translation directions are ready before the timed loop, mirroring what a real session would already have done via the Translate tab. */
    private fun prewarmTranslation() {
        val latch = CountDownLatch(2)
        TranslationEngine.translate(this, LANG_A, LANG_B, "hello", onResult = { latch.countDown() }, onError = { latch.countDown() })
        TranslationEngine.translate(this, LANG_B, LANG_A, "hola", onResult = { latch.countDown() }, onError = { latch.countDown() })
        latch.await(60, TimeUnit.SECONDS)
    }

    private fun List<Long>.averageOrNaN(): Double = if (isEmpty()) Double.NaN else average()
}
