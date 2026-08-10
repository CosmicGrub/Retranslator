package com.retroid.translator.conversation

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.retroid.translator.audio.MicPipeline
import com.retroid.translator.engine.VoskEngine
import com.retroid.translator.engine.VoskResultParsing
import org.vosk.Recognizer
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue

/**
 * Drives docs/specs/fold5-adaptation.md §4's dual-recognizer auto-detect as a
 * live streaming engine, on top of [MicPipeline.startContinuousListening]
 * (VAD-triggered, no tap). Deliberately mic-independent: it consumes audio
 * only through [micListener] ([MicPipeline.ContinuousListener]), which a
 * test harness can drive directly with WAV-file chunks - the exact same code
 * path a live [MicPipeline] session uses - instead of only via a real
 * [android.media.AudioRecord] session. This is what let this project's task
 * report validate the whole VAD -> dual-decode -> early-guess -> final-pick
 * pipeline against real recorded human speech without a live speaker.
 *
 * Two behaviors worth calling out explicitly (evidence-based house style -
 * no silent scope-narrowing):
 *
 * 1. **Early language guess vs. final pick are different signals, on
 *    purpose.** The early guess (via [Listener.onEarlyLanguageGuess]) uses
 *    Vosk streaming *partial* results - specifically, "this recognizer's
 *    partial text has stopped changing for [STABLE_CHUNKS_FOR_EARLY_GUESS]
 *    consecutive chunks and has at least [MIN_WORDS_FOR_EARLY_GUESS] words" -
 *    because `partialResult()` carries no per-word confidence (that only
 *    exists on `result()`/`finalResult()`, confirmed in this project's §4
 *    spike). Word-count-of-a-stable-partial is a real but weaker signal than
 *    the authoritative avgWordConf pick made once the utterance actually
 *    ends (docs/specs/fold5-adaptation.md §4's `pickLanguage`, reused
 *    verbatim via [VoskResultParsing]). This class does NOT act on the early
 *    guess by translating/speaking a partial sentence - it only surfaces it
 *    for UI responsiveness (e.g. "heard: English..." while the person is
 *    still talking). The actual translate+speak decision always waits for
 *    [Listener.onUtteranceFinal].
 *
 * 2. **No overlapping utterances.** While one utterance is still being
 *    finalized (busy = true, from [handleSpeechEnd] until its background
 *    finalize thread posts [Listener.onUtteranceFinal]), a new VAD-detected
 *    speech start is ignored (logged, not queued) rather than spinning up a
 *    third pair of recognizers concurrently with a translate/speak still in
 *    flight - real overlapping-utterance handling (a proper queue/barge-in
 *    model) was judged out of scope for this pass and out of what could be
 *    responsibly verified without a live speaker to actually test talking
 *    over the app's own TTS playback. This is a real, documented limitation,
 *    not a hidden one.
 */
class ContinuousConversationController(
    private val engineA: VoskEngine,
    private val langA: String,
    private val engineB: VoskEngine,
    private val langB: String,
    private val listener: Listener
) {
    interface Listener {
        fun onListeningStateChanged(listening: Boolean) {}
        fun onSpeechStart() {}
        /** Provisional, mid-utterance - see class doc §1. May never fire for a very short utterance. */
        fun onEarlyLanguageGuess(guessedLang: String, partialText: String, elapsedSinceSpeechStartMs: Long) {}
        fun onUtteranceFinal(result: UtteranceResult) {}
        /** Fired if VAD triggered but neither recognizer produced any text (noise / false trigger) - no [UtteranceResult] follows for this trigger. */
        fun onEmptyUtterance() {}
        fun onError(message: String) {}
    }

    data class UtteranceResult(
        val pickedLang: String,
        val otherLang: String,
        val text: String,
        val decisionBasis: String,
        val earlyGuessLang: String?,
        val earlyGuessElapsedMs: Long?,
        val speechDurationMs: Long,
        /** Wall time from VAD speech-end to both recognizers' finalResult() being ready - the STT half of end-to-end latency. */
        val decodeWallTimeMs: Long,
        /** System.nanoTime() at the moment VAD judged speech had ended - the caller's baseline for measuring speech-end -> translated-TTS-audio-start latency (see TtsRouter.speak's onAudioStart param). */
        val speechEndNanos: Long
    )

    private sealed class ChunkOrEnd {
        class Data(val buf: ByteArray, val len: Int) : ChunkOrEnd()
        object End : ChunkOrEnd()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val earlyGuessLock = Any()

    @Volatile private var busy = false
    private var queueA: BlockingQueue<ChunkOrEnd>? = null
    private var queueB: BlockingQueue<ChunkOrEnd>? = null
    private var threadA: Thread? = null
    private var threadB: Thread? = null
    @Volatile private var finalJsonA = ""
    @Volatile private var finalJsonB = ""
    private var speechStartNanos = 0L

    @Volatile private var lastPartialA = ""
    @Volatile private var stableCountA = 0
    @Volatile private var lastPartialB = ""
    @Volatile private var stableCountB = 0
    @Volatile private var earlyGuessLang: String? = null
    @Volatile private var earlyGuessElapsedMs: Long? = null

    /**
     * Wire this into [MicPipeline.startContinuousListening] for live mic
     * capture, or call its methods directly (onSpeechStart -> onAudioChunk*
     * -> onSpeechEnd) to feed pre-recorded WAV chunks for offline testing -
     * both drive the identical decode/decision path below.
     */
    val micListener: MicPipeline.ContinuousListener = object : MicPipeline.ContinuousListener {
        override fun onListeningStarted() { mainHandler.post { listener.onListeningStateChanged(true) } }
        override fun onListeningStopped() { mainHandler.post { listener.onListeningStateChanged(false) } }
        override fun onSpeechStart() = handleSpeechStart()
        override fun onAudioChunk(buffer: ByteArray, length: Int) = handleAudioChunk(buffer, length)
        override fun onSpeechEnd() = handleSpeechEnd()
        override fun onError(message: String) { mainHandler.post { listener.onError(message) } }
    }

    private fun handleSpeechStart() {
        if (busy) {
            Log.w(TAG, "handleSpeechStart: previous utterance still finalizing/speaking - new speech start ignored (see class doc §2)")
            return
        }
        val recA = engineA.newRecognizer(SAMPLE_RATE)
        val recB = engineB.newRecognizer(SAMPLE_RATE)
        if (recA == null || recB == null) {
            recA?.close(); recB?.close()
            mainHandler.post { listener.onError("Could not start recognizers - is a model loaded for both languages?") }
            return
        }
        runCatching { recA.setWords(true) }
        runCatching { recB.setWords(true) }

        busy = true
        finalJsonA = ""
        finalJsonB = ""
        lastPartialA = ""; stableCountA = 0
        lastPartialB = ""; stableCountB = 0
        earlyGuessLang = null; earlyGuessElapsedMs = null
        speechStartNanos = System.nanoTime()

        val qA = LinkedBlockingQueue<ChunkOrEnd>()
        val qB = LinkedBlockingQueue<ChunkOrEnd>()
        queueA = qA
        queueB = qB
        threadA = Thread({ workerLoop(qA, recA, langA) }, "ContConv-$langA").also { it.start() }
        threadB = Thread({ workerLoop(qB, recB, langB) }, "ContConv-$langB").also { it.start() }

        mainHandler.post { listener.onSpeechStart() }
    }

    private fun handleAudioChunk(buffer: ByteArray, length: Int) {
        if (!busy) return
        val copy = buffer.copyOf(length)
        // Same ByteArray instance handed to both queues - each worker only
        // reads it (acceptWaveForm treats it as input), never mutates it, so
        // sharing one copy across both recognizers is safe and avoids a
        // second allocation per chunk.
        queueA?.put(ChunkOrEnd.Data(copy, length))
        queueB?.put(ChunkOrEnd.Data(copy, length))
    }

    private fun handleSpeechEnd() {
        if (!busy) return
        val speechEndNanos = System.nanoTime()
        val speechDurationMs = (speechEndNanos - speechStartNanos) / 1_000_000
        val qA = queueA; val qB = queueB
        val tA = threadA; val tB = threadB
        // Detach immediately so MicPipeline's capture loop (which calls
        // onSpeechStart/onAudioChunk/onSpeechEnd synchronously - see
        // MicPipeline's ContinuousListener doc) is never blocked on decode -
        // finalization runs on its own thread below.
        queueA = null; queueB = null; threadA = null; threadB = null

        Thread({
            val finalizeStart = System.nanoTime()
            qA?.put(ChunkOrEnd.End)
            qB?.put(ChunkOrEnd.End)
            tA?.join()
            tB?.join()
            val decodeWallTimeMs = (System.nanoTime() - finalizeStart) / 1_000_000

            val textA = VoskResultParsing.extractText(finalJsonA)
            val textB = VoskResultParsing.extractText(finalJsonB)
            val (confA, wordsA) = VoskResultParsing.extractWordConfStats(finalJsonA)
            val (confB, wordsB) = VoskResultParsing.extractWordConfStats(finalJsonB)

            val guessAtStart = earlyGuessLang
            val guessElapsed = earlyGuessElapsedMs
            busy = false // ready for the next utterance before doing any UI/caller work

            if (textA.isBlank() && textB.isBlank()) {
                Log.i(TAG, "utterance end: both recognizers produced empty text (VAD false trigger / noise?) speechDurationMs=$speechDurationMs")
                mainHandler.post { listener.onEmptyUtterance() }
                return@Thread
            }

            val (picked, basis) = VoskResultParsing.pickLanguage(
                VoskResultParsing.Candidate(langA, confA, wordsA),
                VoskResultParsing.Candidate(langB, confB, wordsB)
            )
            val pickedText = if (picked == langA) textA else textB
            val otherLang = if (picked == langA) langB else langA

            Log.i(
                TAG,
                "utterance final: picked=$picked basis=\"$basis\" text=\"$pickedText\" " +
                    "speechDurationMs=$speechDurationMs decodeWallTimeMs=$decodeWallTimeMs " +
                    "earlyGuess=$guessAtStart earlyGuessElapsedMs=$guessElapsed earlyGuessMatchedFinal=${guessAtStart == picked}"
            )

            mainHandler.post {
                listener.onUtteranceFinal(
                    UtteranceResult(
                        pickedLang = picked,
                        otherLang = otherLang,
                        text = pickedText,
                        decisionBasis = basis,
                        earlyGuessLang = guessAtStart,
                        earlyGuessElapsedMs = guessElapsed,
                        speechDurationMs = speechDurationMs,
                        decodeWallTimeMs = decodeWallTimeMs,
                        speechEndNanos = speechEndNanos
                    )
                )
            }
        }, "ContConv-Finalize").start()
    }

    private fun workerLoop(queue: BlockingQueue<ChunkOrEnd>, recognizer: Recognizer, langCode: String) {
        try {
            while (true) {
                when (val item = queue.take()) {
                    is ChunkOrEnd.Data -> {
                        val isFinal = try {
                            recognizer.acceptWaveForm(item.buf, item.len)
                        } catch (e: Exception) {
                            Log.e(TAG, "acceptWaveForm failed ($langCode)", e)
                            false
                        }
                        if (isFinal) {
                            // Vosk found an internal sub-boundary mid-utterance
                            // (rare given our own VAD already segments speech) -
                            // capture it as the running "final so far" text;
                            // ChunkOrEnd.End below still asks for the true
                            // finalResult() once our own VAD says the utterance
                            // is actually over.
                            safe { recognizer.result }?.let { setFinalJson(langCode, it) }
                        } else {
                            val partialText = VoskResultParsing.extractPartialText(safe { recognizer.partialResult } ?: "")
                            if (partialText.isNotBlank()) handlePartial(langCode, partialText)
                        }
                    }
                    is ChunkOrEnd.End -> {
                        safe { recognizer.finalResult }?.let { setFinalJson(langCode, it) }
                        return
                    }
                }
            }
        } finally {
            try { recognizer.close() } catch (e: Exception) { /* ignore */ }
        }
    }

    private fun setFinalJson(langCode: String, json: String) {
        if (langCode == langA) finalJsonA = json else finalJsonB = json
    }

    /**
     * Streaming early-guess heuristic - see class doc §1 for why this is
     * word-count-of-a-stable-partial rather than word confidence (which
     * `partialResult()` does not carry). Called concurrently from both
     * worker threads, hence the lock - contention is negligible (this fires
     * at most once per utterance, then short-circuits via [earlyGuessLang]).
     */
    private fun handlePartial(langCode: String, text: String) {
        synchronized(earlyGuessLock) {
            if (earlyGuessLang != null) return

            if (langCode == langA) {
                if (text == lastPartialA) stableCountA++ else { lastPartialA = text; stableCountA = 1 }
            } else {
                if (text == lastPartialB) stableCountB++ else { lastPartialB = text; stableCountB = 1 }
            }

            val wordsA = wordCountOf(lastPartialA)
            val wordsB = wordCountOf(lastPartialB)
            val aConfident = stableCountA >= STABLE_CHUNKS_FOR_EARLY_GUESS && wordsA >= MIN_WORDS_FOR_EARLY_GUESS
            val bConfident = stableCountB >= STABLE_CHUNKS_FOR_EARLY_GUESS && wordsB >= MIN_WORDS_FOR_EARLY_GUESS
            if (!aConfident && !bConfident) return

            val guess = when {
                aConfident && bConfident -> if (wordsA >= wordsB) langA else langB
                aConfident -> langA
                else -> langB
            }
            val guessText = if (guess == langA) lastPartialA else lastPartialB
            val elapsed = (System.nanoTime() - speechStartNanos) / 1_000_000
            earlyGuessLang = guess
            earlyGuessElapsedMs = elapsed

            Log.i(TAG, "early language guess: $guess (\"$guessText\") at +${elapsed}ms since speech start (provisional, not acted on - see class doc §1)")
            mainHandler.post { listener.onEarlyLanguageGuess(guess, guessText, elapsed) }
        }
    }

    private fun wordCountOf(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotBlank() }

    private inline fun safe(block: () -> String?): String? = try { block() } catch (e: Exception) { null }

    /**
     * Best-effort cleanup for a Fragment going away mid-utterance (e.g. the
     * user navigates off Conversations while a phrase is still being
     * decoded). Does not wait for the worker threads - they'll drain their
     * queue's End sentinel and close their own recognizer independently;
     * [busy] is cleared immediately so a stale controller can't be mistaken
     * for still-listening by a caller that checks it.
     */
    fun reset() {
        busy = false
        queueA?.put(ChunkOrEnd.End)
        queueB?.put(ChunkOrEnd.End)
        queueA = null; queueB = null; threadA = null; threadB = null
    }

    companion object {
        private const val TAG = "ContConvController"
        private const val SAMPLE_RATE = 16000f

        // Early-guess tuning - see class doc §1. Deliberately conservative
        // (2 stable chunks ~= 256ms of unchanged partial text, 2+ words)
        // rather than tuned against real conversational speech timing, which
        // this agent could not do without a live speaker.
        private const val STABLE_CHUNKS_FOR_EARLY_GUESS = 2
        private const val MIN_WORDS_FOR_EARLY_GUESS = 2
    }
}
