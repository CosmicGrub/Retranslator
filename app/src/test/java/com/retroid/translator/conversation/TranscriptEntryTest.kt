package com.retroid.translator.conversation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [TranscriptEntry.paneIsA] is a pure boolean transform over [TranscriptEntry.own]/
 * [TranscriptEntry.speakerIsA] - see the class doc comment for why [speakerIsA]
 * is deliberately `val` (in-place `var` mutation broke the tap-to-fix reassign
 * affordance's DiffUtil re-bind on real Fold 5 hardware, per
 * docs/specs/fold5-adaptation.md §4).
 */
class TranscriptEntryTest {

    private fun entry(own: Boolean, speakerIsA: Boolean) = TranscriptEntry(
        id = 1L,
        turnId = 1L,
        speakerIsA = speakerIsA,
        own = own,
        text = "hola",
        langCode = "es",
        auto = true,
    )

    @Test
    fun `an own entry renders in the speaker's own pane`() {
        assertEquals(true, entry(own = true, speakerIsA = true).paneIsA)
        assertEquals(false, entry(own = true, speakerIsA = false).paneIsA)
    }

    @Test
    fun `a translation entry renders in the opposite pane from the speaker`() {
        assertEquals(false, entry(own = false, speakerIsA = true).paneIsA)
        assertEquals(true, entry(own = false, speakerIsA = false).paneIsA)
    }

    @Test
    fun `reassigning speakerIsA flips both the own and translation bubble to the other pane`() {
        val original = entry(own = true, speakerIsA = true)
        val translation = entry(own = false, speakerIsA = true)
        assertEquals(true, original.paneIsA)
        assertEquals(false, translation.paneIsA)

        // Mirrors ConversationsFragment.reassignTurn: replace via .copy(), same
        // new speakerIsA value applied to every entry sharing a turnId.
        val reassignedOriginal = original.copy(speakerIsA = false)
        val reassignedTranslation = translation.copy(speakerIsA = false)

        assertEquals(false, reassignedOriginal.paneIsA)
        assertEquals(true, reassignedTranslation.paneIsA)
    }
}
