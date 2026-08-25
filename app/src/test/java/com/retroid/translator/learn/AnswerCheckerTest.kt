package com.retroid.translator.learn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AnswerChecker] is zero-import beyond `Regex`/`String` stdlib - same shape
 * as [com.retroid.translator.conversation.TranscriptEntry], the other
 * dependency-free class already covered by a unit test. First test
 * coverage for the Speaking exercise's actual answer-matching logic
 * (docs/specs/engineering-systems-pitch.md's CI test-gate system, Part B).
 */
class AnswerCheckerTest {

    @Test
    fun `exact match passes at default threshold`() {
        assertTrue(AnswerChecker.matches("good morning", "good morning"))
    }

    @Test
    fun `punctuation and case are ignored`() {
        assertTrue(AnswerChecker.matches("Good Morning!", "good morning"))
    }

    @Test
    fun `word order does not matter, by design`() {
        // AnswerChecker's own doc comment: "Word order isn't checked -
        // Vosk transcripts can reorder or drop small words."
        assertTrue(AnswerChecker.matches("good morning", "morning good"))
    }

    @Test
    fun `below-threshold overlap fails`() {
        assertFalse(AnswerChecker.matches("good morning to you", "good"))
    }

    @Test
    fun `overlap exactly at the default threshold passes`() {
        // "good morning to" -> 3 expected words, transcript hits 2/3 = 0.667 >= 0.6
        assertTrue(AnswerChecker.matches("good morning to", "good morning"))
    }

    @Test
    fun `a stricter caller-supplied threshold can reject the same overlap`() {
        assertFalse(AnswerChecker.matches("good morning to", "good morning", threshold = 0.9))
    }

    @Test
    fun `empty expected phrase never matches`() {
        assertFalse(AnswerChecker.matches("", "anything"))
    }

    @Test
    fun `blank transcript never satisfies a non-empty expected phrase`() {
        assertFalse(AnswerChecker.matches("good morning", ""))
    }

    @Test
    fun `overlapRatio reports the same fraction matches thresholds against`() {
        assertEquals(0.5, AnswerChecker.overlapRatio("good morning", "good"), 0.0001)
    }

    @Test
    fun `overlapRatio is zero for an empty expected phrase`() {
        assertEquals(0.0, AnswerChecker.overlapRatio("", "anything"), 0.0001)
    }
}
