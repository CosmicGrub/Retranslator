package com.retroid.translator.learn

/**
 * Lenient "did you say the right words" checker for the Speaking exercise -
 * NOT phonetic pronunciation scoring, consistent with the Practice tab's
 * existing "no automated pronunciation grading, listen-and-compare only"
 * boundary. This only judges whether Vosk's transcript contains roughly
 * the same words as the expected phrase, via normalized word overlap.
 */
object AnswerChecker {

    private fun normalize(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

    /**
     * True if the transcript's word overlap with the expected phrase meets
     * [threshold] (fraction of expected words found in the transcript).
     * Word order isn't checked - Vosk transcripts can reorder or drop small
     * words - this is intentionally forgiving.
     */
    fun matches(expected: String, transcript: String, threshold: Double = 0.6): Boolean {
        val expectedWords = normalize(expected)
        if (expectedWords.isEmpty()) return false
        val transcriptWords = normalize(transcript).toSet()
        val hits = expectedWords.count { it in transcriptWords }
        return hits.toDouble() / expectedWords.size >= threshold
    }

    /** Same overlap ratio as [matches], exposed for showing partial-credit feedback. */
    fun overlapRatio(expected: String, transcript: String): Double {
        val expectedWords = normalize(expected)
        if (expectedWords.isEmpty()) return 0.0
        val transcriptWords = normalize(transcript).toSet()
        val hits = expectedWords.count { it in transcriptWords }
        return hits.toDouble() / expectedWords.size
    }
}
