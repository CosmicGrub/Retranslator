package com.retroid.translator.ui

import com.retroid.translator.settings.LayoutPreferences

/**
 * Variant IDs for the Practice tab's cover-screen and Flex-Mode (tabletop)
 * layouts - the per-tab piece of docs/specs/fold5-adaptation.md's
 * layout-variant system, built out here for Practice only (Translate/Learn
 * define their own sets independently, per [LayoutPreferences]'s doc
 * comment - plain strings on purpose, no shared enum to edit).
 *
 * Reuses [LayoutVariantOption] (id/title/subtitle), already declared
 * top-level in this same `com.retroid.translator.ui` package by
 * `TranslateLayoutVariants.kt`, rather than redeclaring an identical data
 * class here - that file is read-only from this pass (not edited), and this
 * one small, general-purpose type is exactly the kind of thing worth sharing
 * without needing to touch its owning file.
 *
 * This single file is the one place [com.retroid.translator.ui.PracticeFragment]
 * and [com.retroid.translator.settings.PracticeLayoutSettingsFragment] both
 * read the id/title/subtitle for each option from, so the picker screen and
 * the rendering logic can never drift out of sync with each other.
 */
object PracticeCoverVariant {
    const val DEFAULT = LayoutPreferences.DEFAULT_VARIANT
    const val DRILL_DECK = "drill_deck"
    const val ECHO_DUET = "echo_duet"
    const val DRILL_CAROUSEL = "drill_carousel"

    val OPTIONS = listOf(
        LayoutVariantOption(
            DEFAULT, "Default",
            "The existing full Practice screen (language picker, phrase field, hear/record/play, past attempts) on the cover display."
        ),
        LayoutVariantOption(
            DRILL_DECK, "Drill Deck",
            "A scrollable queue of phrases (5-8 visible at a time), each with its own inline hear/record controls and an attempted-this-session marker. A recent-attempts waveform strip up top lets you replay any past take instantly."
        ),
        LayoutVariantOption(
            ECHO_DUET, "Echo Duet",
            "Two round tap targets side by side - Reference (blue) and You (orange) - each ring pulsing while its audio plays. No score, ever, per this app's design. A rep-count dot row builds toward a streak flame chip."
        ),
        LayoutVariantOption(
            DRILL_CAROUSEL, "Drill Carousel",
            "Swipe horizontally through one phrase card at a time. One big center button cycles hear-reference -> record -> hear-mine on repeated taps, resetting fresh on every new card. Pager dots at the bottom."
        )
    )
}

object PracticeFlexVariant {
    const val DEFAULT = LayoutPreferences.DEFAULT_VARIANT
    const val WAVEFORM_WALL = "waveform_wall"
    const val LOOP_COMPARE = "loop_compare"
    const val PHRASE_FEED = "phrase_feed"

    val OPTIONS = listOf(
        LayoutVariantOption(
            DEFAULT, "Default",
            "Viewing pane above the hinge holds the phrase and hear/play controls, control pane below holds the language picker, gender, phrase field and record button."
        ),
        LayoutVariantOption(
            WAVEFORM_WALL, "Waveform Comparison Wall",
            "Above the hinge: a reference row stacked above several real past-attempt mini-waveforms (tap any to replay), plus a phrase-set rail on the right with attempt-count badges. Below the hinge: record / play / gender / prev-next."
        ),
        LayoutVariantOption(
            LOOP_COMPARE, "Loop Compare",
            "Start it once and the reference and your recording auto-play back-to-back on a hands-free loop, so comparing them needs no re-tapping. Icon-first, built from the same hear-reference/play pieces as every other layout."
        ),
        LayoutVariantOption(
            PHRASE_FEED, "Phrase Feed",
            "One continuous vertical feed of phrase cards flows uninterrupted across the hinge; scrolling snaps the active card to the hinge. A record button stays pinned near the hinge and always acts on whichever card is active."
        )
    )
}
