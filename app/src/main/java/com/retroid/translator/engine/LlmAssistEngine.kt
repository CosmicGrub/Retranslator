package com.retroid.translator.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File
import java.util.concurrent.Executors

/**
 * On-device LLM assist - Gemma 3 1B (int4 QAT, ~529MB) via MediaPipe's LLM
 * Inference API (see app/build.gradle.kts's dependency comment for why that
 * API over the newer LiteRT-LM one). docs/specs/engines-upgrade-plan.md's
 * "rudimentary, fully wired and cost-free AI" scoping: this is a bounded,
 * single-shot assist action (e.g. "explain this translation"), NOT a
 * general chat interface - [generate] takes one fixed, app-built prompt per
 * call and returns one response, mirroring [PiperTtsEngine]/[VoskEngine]'s
 * own "one focused job, not an open-ended API surface" shape. The caller
 * (TranslateFragment's "Explain this translation" button) is responsible
 * for building a bounded prompt and for disclosing the real caveat this
 * class doesn't hide from itself: a 1B-parameter model run entirely on a
 * phone CAN be wrong or nonsensical, same disclosure standard already
 * applied to Vosk's pronunciation-confidence heuristic and this screen's
 * OCR "no text detected" outcome.
 *
 * Unlike [VoskEngine]/[PiperTtsEngine] (kept loaded for the whole tab
 * session - cheap enough, and reloading per-utterance would be too slow for
 * live speech), this model is ~10x the size of the largest Vosk pack and
 * "bounded, single-shot" by design (class doc above), so the intended
 * calling shape is load -> [generate] once -> [unload], not "load once,
 * keep resident." The caller (TranslateFragment's "Explain this
 * translation" button) drives that sequence explicitly per tap rather than
 * this class hiding it behind one convenience call, so a future caller that
 * genuinely wants several asks in a row (unlike today's single-shot use)
 * can still choose to skip the unload between them.
 */
class LlmAssistEngine(context: Context) {
    private val appContext = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var llmInference: LlmInference? = null
    val isLoaded: Boolean get() = llmInference != null

    fun modelFile(): File = File(appContext.filesDir, "llm-assist/$MODEL_FILE_NAME")

    fun isModelDownloaded(): Boolean = modelFile().let { it.exists() && it.length() > 0L }

    fun loadAsync(onResult: (success: Boolean, error: String?) -> Unit) {
        if (llmInference != null) {
            onResult(true, null)
            return
        }
        worker.execute {
            try {
                val path = modelFile()
                if (!path.exists()) {
                    mainHandler.post { onResult(false, "On-device AI model not downloaded yet") }
                    return@execute
                }
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(path.absolutePath)
                    .setMaxTokens(MAX_TOKENS)
                    .build()
                val instance = LlmInference.createFromOptions(appContext, options)
                llmInference = instance
                Log.i(TAG, "Gemma 3 1B loaded from ${path.path}")
                mainHandler.post { onResult(true, null) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load Gemma 3 1B model", e)
                mainHandler.post { onResult(false, e.message ?: "Failed to load on-device AI model") }
            }
        }
    }

    /**
     * One bounded prompt in, one response out. [prompt] is the caller's
     * responsibility to keep scoped (see class doc) - this function does not
     * inspect or restrict its content, the same "engine trusts its caller's
     * framing" boundary [TranslationEngine.translate] already draws for
     * arbitrary source text.
     */
    fun generate(prompt: String, onResult: (result: String?, error: String?) -> Unit) {
        val llm = llmInference
        if (llm == null) {
            onResult(null, "On-device AI model not loaded")
            return
        }
        worker.execute {
            try {
                val result = llm.generateResponse(prompt)
                mainHandler.post { onResult(result, null) }
            } catch (e: Exception) {
                Log.e(TAG, "Gemma 3 1B generation failed", e)
                mainHandler.post { onResult(null, e.message ?: "On-device AI generation failed") }
            }
        }
    }

    fun unload() {
        val llm = llmInference
        llmInference = null
        if (llm != null) worker.execute {
            try { llm.close() } catch (e: Exception) { Log.w(TAG, "close() failed (non-fatal)", e) }
        }
    }

    /** Mirrors [VoskEngine.deleteModel]/[PiperTtsEngine.deleteVoice]'s "unload synchronously first, then delete" pattern - same proven fix for the same class of bug (deleting a model's file while a native object still has it open). */
    fun deleteModel() {
        if (llmInference != null) unloadBlocking()
        val f = modelFile()
        if (f.exists()) f.delete()
    }

    private fun unloadBlocking() {
        val latch = java.util.concurrent.CountDownLatch(1)
        worker.execute {
            try { llmInference?.close() } catch (e: Exception) { Log.w(TAG, "close() failed (non-fatal)", e) }
            llmInference = null
            latch.countDown()
        }
        try { latch.await(5, java.util.concurrent.TimeUnit.SECONDS) } catch (e: InterruptedException) { /* ignore */ }
    }

    companion object {
        private const val TAG = "LlmAssistEngine"
        private const val MAX_TOKENS = 512

        private const val MODEL_FILE_NAME = "gemma3-1b-it-int4.task"

        /**
         * litert-community/Gemma3-1B-IT on Hugging Face - Google's own
         * pre-converted-model repository for MediaPipe/LiteRT
         * (huggingface.co/litert-community/Gemma3-1B-IT). "int4 QAT" variant:
         * quantization-aware-trained int4, the best size/quality balance of
         * the variants offered there (~529MB vs. ~657MB for post-training
         * dynamic_int4 or ~1GB for int8) - the same "cheapest tier that's a
         * real, disclosed trade-off, not the largest available" reasoning
         * already applied to the Vosk lgraph pick.
         */
        const val MODEL_URL =
            "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task"
        const val APPROX_SIZE_MIB = 529
    }
}
