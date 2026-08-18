package com.retroid.translator.engine

import org.json.JSONObject

/**
 * Small shared helpers for parsing Vosk `Recognizer` JSON output
 * (`result()`/`finalResult()`/`partialResult()`), plus the plausibility
 * heuristic used to pick between two recognizers decoding the same audio in
 * different languages (docs/specs/fold5-adaptation.md §4).
 *
 * Factored out of [com.retroid.translator.prototype.DualRecognizerPrototype]
 * (which keeps using it, via a thin delegation — see that file) so
 * [com.retroid.translator.conversation.ContinuousConversationController] can
 * reuse the exact same parsing/decision logic for live streaming instead of
 * duplicating it. One implementation, two callers (the offline-clip prototype
 * harness and the real streaming controller) — same decision logic is used
 * whether the audio came from a WAV file or a live microphone.
 */
object VoskResultParsing {

    fun extractText(json: String): String = try {
        JSONObject(json).optString("text", "")
    } catch (e: Exception) {
        ""
    }

    fun extractPartialText(json: String): String = try {
        JSONObject(json).optString("partial", "")
    } catch (e: Exception) {
        ""
    }

    /** Returns (averageWordConfidence, wordCount). avgConf is null if the JSON has no per-word "conf" field (requires `recognizer.setWords(true)`). */
    fun extractWordConfStats(json: String): Pair<Double?, Int> {
        return try {
            val obj = JSONObject(json)
            val resultArr = obj.optJSONArray("result") ?: return null to 0
            var sum = 0.0
            var confCount = 0
            for (i in 0 until resultArr.length()) {
                val w = resultArr.optJSONObject(i) ?: continue
                if (w.has("conf")) {
                    sum += w.optDouble("conf", 0.0)
                    confCount++
                }
            }
            if (confCount == 0) null to resultArr.length() else (sum / confCount) to resultArr.length()
        } catch (e: Exception) {
            null to 0
        }
    }

    /**
     * One decoded word's confidence, for the fold5-device-version branch's
     * Practice-tab pronunciation-feedback feature
     * (docs/specs/fold5-adaptation.md's engines-upgrade-plan §"Practice-tab
     * pronunciation feedback from Vosk per-word confidence"). This is a
     * heuristic/statistical signal, not phonetic analysis against a native
     * speaker - see [com.retroid.translator.ui.PracticeFragment]'s doc
     * comment on the feature this feeds for the honest framing.
     */
    data class WordConfidence(val word: String, val conf: Double, val startSec: Double, val endSec: Double)

    /**
     * Per-word confidence breakdown, for surfacing which specific words a
     * Vosk decode was least confident on (Practice-tab pronunciation
     * feedback). Distinct from [extractWordConfStats], which collapses this
     * same "result" array down to one averaged number for the dual-recognizer
     * language-picking use case - this keeps the per-word breakdown instead.
     * Empty list if the JSON has no per-word "conf"/"word" data (requires
     * `recognizer.setWords(true)`, same requirement as [extractWordConfStats]).
     */
    fun extractPerWordConf(json: String): List<WordConfidence> {
        return try {
            val obj = JSONObject(json)
            val resultArr = obj.optJSONArray("result") ?: return emptyList()
            val out = mutableListOf<WordConfidence>()
            for (i in 0 until resultArr.length()) {
                val w = resultArr.optJSONObject(i) ?: continue
                if (w.has("conf") && w.has("word")) {
                    out.add(
                        WordConfidence(
                            word = w.optString("word", ""),
                            conf = w.optDouble("conf", 0.0),
                            startSec = w.optDouble("start", 0.0),
                            endSec = w.optDouble("end", 0.0)
                        )
                    )
                }
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    data class Candidate(val langCode: String, val avgWordConf: Double?, val wordCount: Int)

    /**
     * Primary signal: average per-word Vosk "conf" (verified present on-device
     * for vosk-model-small-en-us-0.15 and vosk-model-small-es-0.42 — see
     * docs/specs/fold5-adaptation.md §4). Fallback (only if BOTH candidates
     * lack a conf field): whichever recognizer decoded more words.
     */
    fun pickLanguage(a: Candidate, b: Candidate): Pair<String, String> {
        if (a.avgWordConf != null && b.avgWordConf != null) {
            val basis = "avgWordConf: ${a.langCode}=%.3f vs ${b.langCode}=%.3f".format(a.avgWordConf, b.avgWordConf)
            return (if (a.avgWordConf >= b.avgWordConf) a.langCode else b.langCode) to basis
        }
        val basis = "wordCount fallback (no conf field present): ${a.langCode}=${a.wordCount} vs ${b.langCode}=${b.wordCount}"
        return (if (a.wordCount >= b.wordCount) a.langCode else b.langCode) to basis
    }
}
