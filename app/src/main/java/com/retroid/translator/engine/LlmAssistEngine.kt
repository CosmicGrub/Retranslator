package com.retroid.translator.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File
import java.util.concurrent.Executors

/**
 * On-device LLM assist - Qwen2.5 1.5B Instruct (int8, ~1524MB) via
 * MediaPipe's LLM Inference API (see app/build.gradle.kts's dependency
 * comment for why that API over the newer LiteRT-LM one).
 * docs/specs/engines-upgrade-plan.md's "rudimentary, fully wired and
 * cost-free AI" scoping: this is a bounded, single-shot assist action
 * (e.g. "explain this translation"), NOT a general chat interface -
 * [generate] takes one fixed, app-built prompt per call and returns one
 * response, mirroring [PiperTtsEngine]/[VoskEngine]'s own "one focused
 * job, not an open-ended API surface" shape. The caller (TranslateFragment's
 * "Explain this translation" button) is responsible for building a bounded
 * prompt and for disclosing the real caveat this class doesn't hide from
 * itself: even a real language model run entirely on a phone CAN be wrong
 * or nonsensical, same disclosure standard already applied to Vosk's
 * pronunciation-confidence heuristic and this screen's OCR "no text
 * detected" outcome.
 *
 * Model swapped from the originally-scoped Gemma 3 1B after live on-device
 * verification found `litert-community/Gemma3-1B-IT` is a genuinely gated
 * Hugging Face repo (confirmed via a direct unauthenticated request: real
 * `401 Unauthorized`, `X-Error-Code: GatedRepo`) - a plain download can
 * never succeed for any user without an HF login and accepted license,
 * which conflicts with this app's "no accounts, no logins" design
 * everywhere else. `litert-community/Qwen2.5-1.5B-Instruct` is confirmed
 * genuinely ungated (`"gated":false`, Apache-2.0, verified via a direct
 * HTTP request returning a real 302 to a working, Range-capable download,
 * not a 401) and, at 1.5B parameters, is actually larger/more capable than
 * the original pick - the real cost is size (~1.5GB vs. the original
 * ~529MB), a disclosed trade-off, not a compromise nobody chose.
 *
 * Unlike [VoskEngine]/[PiperTtsEngine] (kept loaded for the whole tab
 * session - cheap enough, and reloading per-utterance would be too slow for
 * live speech), this model is far larger than any other pack in this app
 * and "bounded, single-shot" by design (class doc above), so the intended
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

    /**
     * docs/specs/engineering-systems-pitch.md system #3: a device can pass
     * every other check here and still be under real memory pressure at the
     * exact moment this ~529MB model load is requested - this surfaces that
     * as the same [onResult] error path a missing download already uses,
     * instead of a native-level OOM with no user-facing explanation at all.
     * [DeviceCapabilities.hasHeadroomForLlm]'s own doc comment is explicit
     * that its multiplier is engineering judgment, not a citation - no real
     * on-device memory-footprint measurement exists for this model yet.
     */
    fun loadAsync(onResult: (success: Boolean, error: String?) -> Unit) {
        if (llmInference != null) {
            onResult(true, null)
            return
        }
        if (!DeviceCapabilities.hasHeadroomForLlm(appContext, APPROX_SIZE_MIB)) {
            onResult(false, "Not enough free memory on this device to load the on-device AI model right now")
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
                Log.i(TAG, "Qwen2.5 1.5B loaded from ${path.path}")
                mainHandler.post { onResult(true, null) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load on-device AI model", e)
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
                Log.e(TAG, "On-device AI generation failed", e)
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

        private const val MODEL_FILE_NAME = "qwen2.5-1.5b-instruct-q8.task"

        /**
         * litert-community/Qwen2.5-1.5B-Instruct on Hugging Face - the
         * community pre-converted-model repository for MediaPipe/LiteRT
         * (huggingface.co/litert-community/Qwen2.5-1.5B-Instruct). Real,
         * verified facts, not assumed from the repo listing alone:
         * - Genuinely ungated: the repo's own metadata API
         *   (huggingface.co/api/models/litert-community/Qwen2.5-1.5B-Instruct)
         *   reports `"gated":false`, and a direct unauthenticated HEAD
         *   request to this exact URL returns a real `302` to a working,
         *   Range-capable download - not the `401 Unauthorized`/
         *   `GatedRepo` this app's original Gemma 3 1B pick returned for
         *   every unauthenticated request, confirmed the same way.
         * - License: Apache-2.0 (from the repo's own cardData), the same
         *   license class already used for Vosk/sherpa-onnx elsewhere in
         *   this app.
         * - `q8` (int8 post-training quantized) variant, `ekv1280` context:
         *   the smaller of the two real quantization options this repo
         *   offers (the `f32` variant is unquantized and far larger) - the
         *   same "cheapest tier that's a real, disclosed trade-off, not the
         *   largest available" reasoning already applied to the Vosk lgraph
         *   pick, just landing at a larger absolute size here because this
         *   is a fundamentally bigger model class (1.5B params) than the
         *   originally-scoped Gemma 3 1B.
         * - Real measured size: `X-Linked-Size: 1597913616` bytes from a
         *   direct HEAD request (1597913616 / 1024 / 1024 ≈ 1524 MiB) - not
         *   a number copied from documentation.
         */
        const val MODEL_URL =
            "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
        const val APPROX_SIZE_MIB = 1524
    }
}
