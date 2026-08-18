package com.retroid.translator.prototype

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.ScrollView
import android.widget.TextView
import com.retroid.translator.TranslatorApp
import com.retroid.translator.engine.LlmAssistEngine

/**
 * Throwaway, NON-shipped debug entry point for the Translate tab's AI
 * phrase helper (docs/specs/fold5-adaptation.md's dated "On-device AI
 * assistant" section) - same "prototype" pattern as
 * [DualRecognizerProtoActivity]/[ContinuousFlowProtoActivity]/
 * [OcrTestActivity]/[WifiGateTestActivity] already in this package. Trigger
 * via:
 *
 *   adb shell am start -n com.retroid.translator.fold5/com.retroid.translator.prototype.LlmAssistTestActivity
 *
 * then watch `adb logcat -s LlmAssistTest`.
 *
 * Why this exists, honestly: the real UI flow (`TranslateFragment`'s
 * "Download AI assistant"/"Get AI insight" buttons) exercises the exact
 * same [TranslatorApp.llmAssist] singleton this harness does - this isn't a
 * parallel/mocked implementation, it's the same production
 * [LlmAssistEngine] instance, same download/load/generate calls. This
 * harness exists because this project's own house style prefers a direct,
 * scriptable, logcat-verifiable path for exercising an engine end-to-end
 * (real download progress, real model load, real on-device inference, real
 * generated text) over blind UI-automation taps, which this session's own
 * real-device testing found unreliable on this specific shared hardware
 * (this device also runs concurrently-installed builds from sibling agent
 * worktrees this same session - see docs/specs/fold5-adaptation.md's other
 * dated sections for this project's established, repeated precedent of that
 * exact multi-agent/shared-device risk).
 */
class LlmAssistTestActivity : Activity() {
    private lateinit var logView: TextView
    private val logBuilder = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logView = TextView(this).apply {
            gravity = Gravity.START
            textSize = 12f
            setPadding(24, 24, 24, 24)
            text = "Starting LLM assist engine test…\n"
        }
        setContentView(ScrollView(this).apply { addView(logView) })
        runTest()
    }

    private fun appendLog(line: String) {
        Log.i(TAG, line)
        runOnUiThread {
            logBuilder.append(line).append('\n')
            logView.text = logBuilder.toString()
        }
    }

    private fun runTest() {
        val app = application as TranslatorApp
        val engine = app.llmAssist
        appendLog("Model file present on disk already: ${engine.isModelDownloaded()}")
        if (engine.isModelDownloaded()) {
            proceedToLoadAndGenerate(engine)
            return
        }
        appendLog("Downloading model from ${LlmAssistEngine.MODEL_URL} (~${LlmAssistEngine.APPROX_SIZE_MIB}MB)...")
        engine.downloadModel(
            this,
            onProgress = { pct -> if (pct % 10 == 0) appendLog("download progress: $pct%") }
        ) { success, error ->
            if (!success) {
                appendLog("DOWNLOAD FAILED: $error")
                return@downloadModel
            }
            appendLog("Download complete. isModelDownloaded()=${engine.isModelDownloaded()}")
            proceedToLoadAndGenerate(engine)
        }
    }

    private fun proceedToLoadAndGenerate(engine: LlmAssistEngine) {
        appendLog("Loading model...")
        val loadStart = System.nanoTime()
        engine.loadAsync { loaded, loadError ->
            val loadMs = (System.nanoTime() - loadStart) / 1_000_000
            if (!loaded) {
                appendLog("LOAD FAILED after ${loadMs}ms: $loadError")
                return@loadAsync
            }
            appendLog("Model loaded in ${loadMs}ms. Generating a real response for a real translation-insight prompt...")
            val prompt = "You are a translation assistant. A user translated this text from English to Spanish:\n\n" +
                "Source (English): \"Good morning, how are you today?\"\n" +
                "Translation (Spanish): \"Buenos días, ¿cómo estás hoy?\"\n\n" +
                "In English, in 2-3 short sentences and under 80 words total: " +
                "(1) say whether the translation sounds natural, and if not, suggest a more natural phrasing in Spanish; " +
                "(2) give one brief, useful grammar or usage note about it."
            appendLog("PROMPT:\n$prompt")
            val genStart = System.nanoTime()
            engine.generate(
                prompt,
                onResult = { text ->
                    val genMs = (System.nanoTime() - genStart) / 1_000_000
                    appendLog("REAL LLM RESPONSE (${genMs}ms, ${text.length} chars):\n$text")
                    appendLog("TEST COMPLETE - real on-device generation succeeded.")
                },
                onError = { err ->
                    val genMs = (System.nanoTime() - genStart) / 1_000_000
                    appendLog("GENERATE FAILED after ${genMs}ms: $err")
                }
            )
        }
    }

    companion object {
        private const val TAG = "LlmAssistTest"
    }
}
