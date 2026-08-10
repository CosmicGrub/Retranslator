package com.retroid.translator.prototype

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Debug
import android.util.Log
import com.retroid.translator.engine.VoskEngine
import com.retroid.translator.engine.VoskResultParsing
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * Throwaway spike for docs/specs/fold5-adaptation.md §4: can two Vosk
 * `Recognizer`s (one per language, from two independently-resident
 * [VoskEngine] instances) run against the same audio and have the app pick
 * the correct one, with no manual "whose turn" input?
 *
 * Deliberately does NOT touch [com.retroid.translator.engine.VoskEngine] —
 * it keeps only one model resident by design (see its class doc), and this
 * prototype gets two concurrently-resident models "for free" by simply
 * constructing two independent VoskEngine instances, each of which manages
 * its own resident model unaware of the other.
 */
object DualRecognizerPrototype {
    private const val TAG = "DualRecoProto"
    const val SAMPLE_RATE = 16000

    // ---------------------------------------------------------------------
    // Result types
    // ---------------------------------------------------------------------

    data class ChunkTiming(val chunkIndex: Int, val bytes: Int, val acceptWaveFormNanos: Long)

    data class RunResult(
        val langCode: String,
        val finalText: String,
        val finalJson: String,
        val avgWordConf: Double?,   // null if no word-level "conf" field was present in the JSON
        val wordCount: Int,
        val wallTimeMs: Long,
        val chunkTimings: List<ChunkTiming>
    )

    data class DualDecision(
        val clipLabel: String,
        val actualLang: String,
        val audioDurationMs: Long,
        val baselineSolo: RunResult,       // actual-language recognizer run alone, nothing else competing for CPU
        val concurrentA: RunResult,        // langA recognizer, run concurrently with langB below
        val concurrentB: RunResult,        // langB recognizer, run concurrently with langA above
        val dualWallTimeMs: Long,          // wall-clock for both concurrent recognizers together
        val chosenLang: String,
        val decisionBasis: String,         // e.g. "avgWordConf" or "wordCount fallback (no conf field present)"
        val correct: Boolean
    )

    data class MemorySnapshot(val label: String, val nativeHeapAllocBytes: Long, val nativeHeapSizeBytes: Long)

    // ---------------------------------------------------------------------
    // Model loading — two independent VoskEngine instances, per the spec's
    // proposed approach. Neither is aware the other exists.
    // ---------------------------------------------------------------------

    fun loadEngines(
        context: Context,
        langA: String,
        langB: String,
        onDone: (engineA: VoskEngine, engineB: VoskEngine, success: Boolean, error: String?) -> Unit
    ) {
        val engineA = VoskEngine(context)
        val engineB = VoskEngine(context)
        Log.i(TAG, "loadEngines: loading langA=$langA into engineA")
        engineA.loadModelAsync(langA) { okA, errA ->
            if (!okA) {
                Log.e(TAG, "loadEngines: engineA failed for $langA: $errA")
                onDone(engineA, engineB, false, "engineA($langA): $errA")
                return@loadModelAsync
            }
            Log.i(TAG, "loadEngines: engineA ready for $langA. Now loading langB=$langB into engineB")
            engineB.loadModelAsync(langB) inner@{ okB, errB ->
                if (!okB) {
                    Log.e(TAG, "loadEngines: engineB failed for $langB: $errB")
                    onDone(engineA, engineB, false, "engineB($langB): $errB")
                    return@inner
                }
                Log.i(TAG, "loadEngines: both models resident (langA=$langA, langB=$langB)")
                onDone(engineA, engineB, true, null)
            }
        }
    }

    fun memorySnapshot(label: String): MemorySnapshot {
        val alloc = Debug.getNativeHeapAllocatedSize()
        val size = Debug.getNativeHeapSize()
        val snap = MemorySnapshot(label, alloc, size)
        Log.i(TAG, "MEMORY[$label]: nativeHeapAllocated=${alloc / 1024}KB nativeHeapSize=${size / 1024}KB")
        return snap
    }

    // ---------------------------------------------------------------------
    // Audio I/O — matches MicPipeline's chunk cadence exactly.
    // ---------------------------------------------------------------------

    /** Same buffer-sizing logic MicPipeline.start() uses for live mic capture. */
    fun chunkSizeBytes(): Int {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return minBuf.coerceAtLeast(4096)
    }

    /** Strips WavFileWriter's fixed 44-byte header, returning raw PCM16 mono bytes. */
    fun readWavPcm(file: File): ByteArray {
        val bytes = file.readBytes()
        if (bytes.size <= 44) return ByteArray(0)
        return bytes.copyOfRange(44, bytes.size)
    }

    fun audioDurationMs(pcmBytes: ByteArray): Long =
        (pcmBytes.size / 2).toLong() * 1000L / SAMPLE_RATE

    // ---------------------------------------------------------------------
    // Single-recognizer run (used both as the solo baseline and as one half
    // of a concurrent dual run).
    // ---------------------------------------------------------------------

    private fun runRecognizer(pcm: ByteArray, chunkSize: Int, engine: VoskEngine, langCode: String): RunResult {
        val recognizer: Recognizer = engine.newRecognizer(SAMPLE_RATE.toFloat())
            ?: throw IllegalStateException("newRecognizer() returned null for $langCode — is its model loaded?")
        try {
            try {
                recognizer.setWords(true)
            } catch (e: Throwable) {
                Log.w(TAG, "runRecognizer($langCode): setWords(true) not available/failed: ${e.message}")
            }

            val timings = mutableListOf<ChunkTiming>()
            var offset = 0
            var chunkIndex = 0
            val t0 = System.nanoTime()
            var lastResultJson = ""
            while (offset < pcm.size) {
                val len = minOf(chunkSize, pcm.size - offset)
                val chunk = pcm.copyOfRange(offset, offset + len)
                val cT0 = System.nanoTime()
                val isFinal = try {
                    recognizer.acceptWaveForm(chunk, len)
                } catch (e: Exception) {
                    Log.e(TAG, "runRecognizer($langCode): acceptWaveForm failed at chunk $chunkIndex", e)
                    false
                }
                val cElapsed = System.nanoTime() - cT0
                timings.add(ChunkTiming(chunkIndex, len, cElapsed))
                if (isFinal) {
                    lastResultJson = safe { recognizer.result } ?: ""
                }
                offset += len
                chunkIndex++
            }
            val finalJson = safe { recognizer.finalResult } ?: lastResultJson
            val wallTimeMs = (System.nanoTime() - t0) / 1_000_000

            val text = extractText(finalJson)
            val (avgConf, wordCount) = extractWordConfStats(finalJson)

            Log.i(
                TAG,
                "runRecognizer($langCode): chunks=${timings.size} wallTimeMs=$wallTimeMs text=\"$text\" " +
                    "avgWordConf=${avgConf?.let { "%.3f".format(it) } ?: "N/A"} wordCount=$wordCount"
            )
            Log.i(TAG, "runRecognizer($langCode) RAW JSON: $finalJson")

            return RunResult(langCode, text, finalJson, avgConf, wordCount, wallTimeMs, timings)
        } finally {
            try { recognizer.close() } catch (e: Exception) { /* ignore */ }
        }
    }

    private inline fun safe(block: () -> String?): String? = try { block() } catch (e: Exception) { null }

    // JSON parsing delegates to VoskResultParsing (shared with
    // ContinuousConversationController) - see that object's doc comment.
    private fun extractText(json: String): String = VoskResultParsing.extractText(json)

    /** Returns (averageWordConfidence, wordCount). avgConf is null if the JSON has no per-word "conf" field. */
    private fun extractWordConfStats(json: String): Pair<Double?, Int> = VoskResultParsing.extractWordConfStats(json)

    // ---------------------------------------------------------------------
    // The actual experiment: solo baseline, then both recognizers concurrently
    // against the same clip, then a plausibility-based language pick.
    // ---------------------------------------------------------------------

    fun evaluateClip(
        clip: TestAudioSynth.Clip,
        engineA: VoskEngine,
        langA: String,
        engineB: VoskEngine,
        langB: String
    ): DualDecision {
        val pcm = readWavPcm(clip.file)
        val chunkSize = chunkSizeBytes()
        val durationMs = audioDurationMs(pcm)

        // 1) Solo baseline: only the recognizer matching the actual spoken language runs, alone.
        val soloEngine = if (clip.langCode == langA) engineA else engineB
        Log.i(TAG, "evaluateClip(${clip.label}): running SOLO baseline (lang=${clip.langCode})")
        val baseline = runRecognizer(pcm, chunkSize, soloEngine, clip.langCode)

        // 2) Dual/concurrent: both recognizers run at the same time on separate threads,
        //    each independently fed the identical chunk sequence — this is what the real
        //    Conversations implementation would do against one live mic buffer.
        var resultA: RunResult? = null
        var resultB: RunResult? = null
        val latch = CountDownLatch(2)
        val tA = Thread({
            try { resultA = runRecognizer(pcm, chunkSize, engineA, langA) } finally { latch.countDown() }
        }, "DualReco-$langA")
        val tB = Thread({
            try { resultB = runRecognizer(pcm, chunkSize, engineB, langB) } finally { latch.countDown() }
        }, "DualReco-$langB")
        Log.i(TAG, "evaluateClip(${clip.label}): running CONCURRENT dual decode ($langA + $langB)")
        val dualT0 = System.nanoTime()
        tA.start(); tB.start()
        latch.await()
        val dualWallTimeMs = (System.nanoTime() - dualT0) / 1_000_000

        val rA = resultA!!
        val rB = resultB!!

        // 3) Plausibility pick.
        val (chosen, basis) = pickLanguage(rA, rB)
        val correct = chosen == clip.langCode

        Log.i(
            TAG,
            "evaluateClip(${clip.label}): actual=${clip.langCode} chosen=$chosen basis=\"$basis\" " +
                "correct=$correct dualWallTimeMs=$dualWallTimeMs soloWallTimeMs=${baseline.wallTimeMs} " +
                "durationMs=$durationMs"
        )

        return DualDecision(
            clipLabel = clip.label,
            actualLang = clip.langCode,
            audioDurationMs = durationMs,
            baselineSolo = baseline,
            concurrentA = rA,
            concurrentB = rB,
            dualWallTimeMs = dualWallTimeMs,
            chosenLang = chosen,
            decisionBasis = basis,
            correct = correct
        )
    }

    /**
     * Primary signal: average per-word Vosk "conf" (only present if the JSON
     * actually carries it for these models — verified on-device, not assumed).
     * Fallback signal (used only if BOTH recognizers lack a conf field):
     * whichever recognizer produced more decoded words — a mismatched-language
     * recognizer decoding foreign phonemes tends to produce a shorter/emptier
     * guess against its own dictionary/grammar than the correct one.
     */
    private fun pickLanguage(a: RunResult, b: RunResult): Pair<String, String> = VoskResultParsing.pickLanguage(
        VoskResultParsing.Candidate(a.langCode, a.avgWordConf, a.wordCount),
        VoskResultParsing.Candidate(b.langCode, b.avgWordConf, b.wordCount)
    )
}
