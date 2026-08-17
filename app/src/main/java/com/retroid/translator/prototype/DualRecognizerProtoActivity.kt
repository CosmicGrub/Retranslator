package com.retroid.translator.prototype

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.ScrollView
import android.widget.TextView
import com.retroid.translator.engine.VoskEngine
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * Throwaway debug entry point for the dual-recognizer spike
 * (docs/specs/fold5-adaptation.md §4). Deliberately not registered in any
 * navigation flow — trigger directly via:
 *
 *   adb shell am start -n com.retroid.translator/.prototype.DualRecognizerProtoActivity
 *
 * then watch `adb logcat -s DualRecoProto`. UI is a single scrolling
 * TextView; this never needs to look good, it exists purely to prove (or
 * disprove) the dual-recognizer approach with real on-device numbers.
 *
 * Declared `android:exported="true"` in app/src/debug/AndroidManifest.xml
 * (a debug-only Gradle source-set overlay — this Activity does not exist at
 * all in a release build) purely so `adb shell am start` can reach it:
 * Android 16 blocks `adb shell am start` of a non-exported activity even in
 * a debuggable app, a hardening change from what used to be a standard
 * debug workflow. It stays out of the launcher and out of every shipped nav
 * flow regardless.
 *
 * Requires the "en" and "es" Vosk STT packs to already be downloaded (drive
 * the Translate tab's normal download UI first) — this prototype does not
 * duplicate that download flow.
 */
class DualRecognizerProtoActivity : Activity() {
    private lateinit var logView: TextView
    private val logBuilder = StringBuilder()

    companion object {
        private const val TAG = "DualRecoProto"
        private const val LANG_A = "en"
        private const val LANG_B = "es"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logView = TextView(this).apply {
            gravity = Gravity.START
            textSize = 11f
            setPadding(24, 24, 24, 24)
            text = "Starting dual-recognizer prototype…\n"
        }
        val scroll = ScrollView(this).apply { addView(logView) }
        setContentView(scroll)

        Thread({ runExperiment() }, "DualRecoProto-Main").start()
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
            val memStart = DualRecognizerPrototype.memorySnapshot("app_start")
            appendLog("Native heap at start: ${memStart.nativeHeapAllocBytes / 1024}KB")
            logProcMeminfo("app_start")

            appendLog("Loading Vosk models for '$LANG_A' and '$LANG_B' into two independent VoskEngine instances…")
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

            val memBoth = DualRecognizerPrototype.memorySnapshot("both_models_loaded")
            val deltaKb = (memBoth.nativeHeapAllocBytes - memStart.nativeHeapAllocBytes) / 1024
            appendLog("Native heap with BOTH models resident: ${memBoth.nativeHeapAllocBytes / 1024}KB (delta vs start: ${deltaKb}KB)")
            logProcMeminfo("both_models_loaded")
            appendLog("Holding 8s here for external `adb shell cat /proc/meminfo` capture at peak memory…")
            Log.i(TAG, "MEMORY_CHECKPOINT: both models loaded, holding 8s")
            Thread.sleep(8000)

            // -----------------------------------------------------------
            // Run 1: synthetic eSpeak-TTS clips (original §4 spike, kept
            // exactly as before so the numbers stay comparable).
            // -----------------------------------------------------------
            appendLog("Synthesizing test clips via eSpeak NG (no Piper download needed)…")
            val synthDir = File(cacheDir, "dual_reco_proto_audio")
            val synthClips = TestAudioSynth.synthesizeAll(applicationContext, synthDir)
            appendLog("Synthesized ${synthClips.size} clips: ${synthClips.joinToString { it.label }}")
            runBatch("SYNTHETIC (eSpeak TTS)", synthClips, engineA!!, engineB!!)

            // -----------------------------------------------------------
            // Run 2: real recorded human speech (docs/specs/fold5-adaptation.md
            // §4's stated prerequisite before wiring auto-detect into
            // ConversationsFragment - see RealSpeechCorpus's doc comment for
            // exactly what this is and is not).
            // -----------------------------------------------------------
            appendLog("Loading REAL human-speech corpus clips (see assets/real_speech_corpus/ATTRIBUTION.txt)…")
            val realDir = File(cacheDir, "real_speech_proto_audio")
            val realClips = RealSpeechCorpus.loadAll(applicationContext, realDir)
            appendLog("Loaded ${realClips.size} real-speech clips: ${realClips.joinToString { it.label }}")
            runBatch("REAL HUMAN SPEECH (OpenSLR SLR72/SLR83, CC BY-SA 4.0)", realClips, engineA!!, engineB!!)

            val memEnd = DualRecognizerPrototype.memorySnapshot("after_all_clips")
            appendLog("Native heap after all clips: ${memEnd.nativeHeapAllocBytes / 1024}KB")
            logProcMeminfo("after_all_clips")
            appendLog("DONE.")
        } catch (e: Throwable) {
            Log.e(TAG, "Prototype run failed", e)
            appendLog("FATAL ERROR: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Runs [DualRecognizerPrototype.evaluateClip] over [clips] and logs a SUMMARY line, identically for both the synthetic and real-speech runs so the two are directly comparable in logcat. */
    private fun runBatch(
        label: String,
        clips: List<TestAudioSynth.Clip>,
        engineA: VoskEngine,
        engineB: VoskEngine
    ) {
        appendLog("===== BATCH: $label =====")
        val results = mutableListOf<DualRecognizerPrototype.DualDecision>()
        var hits = 0
        for (clip in clips) {
            appendLog("Evaluating ${clip.label} [${clip.langCode}]: \"${clip.text}\"")
            val decision = DualRecognizerPrototype.evaluateClip(clip, engineA, LANG_A, engineB, LANG_B)
            results.add(decision)
            if (decision.correct) hits++
            appendLog(
                "  -> chosen=${decision.chosenLang} (actual=${decision.actualLang}) correct=${decision.correct}\n" +
                    "     solo=${decision.baselineSolo.wallTimeMs}ms dual=${decision.dualWallTimeMs}ms " +
                    "audioDur=${decision.audioDurationMs}ms basis=${decision.decisionBasis}"
            )
        }
        val avgSolo = results.map { it.baselineSolo.wallTimeMs }.average()
        val avgDual = results.map { it.dualWallTimeMs }.average()
        val ratio = if (avgSolo > 0) avgDual / avgSolo else -1.0
        val summary = "SUMMARY[$label]: %d/%d correct. avgSoloWallMs=%.1f avgDualWallMs=%.1f dual/solo ratio=%.2fx"
            .format(hits, results.size, avgSolo, avgDual, ratio)
        Log.i(TAG, summary)
        appendLog(summary)
    }

    /** Best-effort in-process corroboration of /proc/meminfo; the authoritative reading is external `adb shell`. */
    private fun logProcMeminfo(label: String) {
        try {
            val text = File("/proc/meminfo").readText()
            val memAvailable = text.lineSequence().firstOrNull { it.startsWith("MemAvailable:") }?.trim()
            val memFree = text.lineSequence().firstOrNull { it.startsWith("MemFree:") }?.trim()
            Log.i(TAG, "PROC_MEMINFO[$label] (in-app read): $memAvailable | $memFree")
        } catch (e: Exception) {
            Log.w(TAG, "PROC_MEMINFO[$label]: could not read /proc/meminfo from app process (${e.message})")
        }
    }
}
