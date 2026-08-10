package com.retroid.translator.ui

import com.retroid.translator.settings.LayoutPreferences

/**
 * Variant IDs for the Learn tab's cover-screen and Flex-Mode (tabletop)
 * layouts - the per-tab piece of docs/specs/fold5-adaptation.md's
 * layout-variant system, built out here for Learn only (Translate/Practice
 * define their own sets independently in their own files, per
 * [LayoutPreferences]'s doc comment - plain strings on purpose, no shared
 * enum to edit).
 *
 * Reuses [LayoutVariantOption] (id/title/subtitle), already declared
 * top-level in this same `com.retroid.translator.ui` package by
 * `TranslateLayoutVariants.kt`, rather than redeclaring an identical data
 * class here - that file is read-only from this pass (not edited).
 *
 * This single file is the one place [com.retroid.translator.ui.LearnFragment]
 * and [com.retroid.translator.settings.LearnLayoutSettingsFragment] both read
 * the id/title/subtitle for each option from, so the picker screen and the
 * rendering logic can never drift out of sync with each other.
 */
object LearnCoverVariant {
    const val DEFAULT = LayoutPreferences.DEFAULT_VARIANT
    const val PROGRESS_RING = "progress_ring"
    const val COURSE_DASHBOARD = "course_dashboard"
    const val LISTEN_CHOOSE = "listen_choose"

    val OPTIONS = listOf(
        LayoutVariantOption(
            DEFAULT, "Default",
            "The existing full Learn screen (unit list -> lesson list -> one exercise at a time -> summary) on the cover display."
        ),
        LayoutVariantOption(
            PROGRESS_RING, "Progress Ring Only",
            "One large circular ring, no streak digit or due-card text anywhere. Fills clockwise as reviews complete; tap it to answer the next due exercise inline, then it closes back up fuller."
        ),
        LayoutVariantOption(
            COURSE_DASHBOARD, "Course Dashboard",
            "Three stacked bands: streak/XP/level, a lesson mini-map for the current unit, and a review-queue panel by Leitner box - tap a box to jump straight into that review."
        ),
        LayoutVariantOption(
            LISTEN_CHOOSE, "Listen & Choose",
            "Built for the listening exercise type: audio auto-plays on open (repeatable via a big speaker button), two oversized answer buttons, right/wrong shown by color and haptics, not text."
        )
    )
}

object LearnFlexVariant {
    const val DEFAULT = LayoutPreferences.DEFAULT_VARIANT
    const val FLIP_SORT = "flip_sort"
    const val ELASTIC_SPLIT = "elastic_split"
    const val SPEAKING_ARC = "speaking_arc"

    val OPTIONS = listOf(
        LayoutVariantOption(
            DEFAULT, "Default",
            "Viewing pane above the hinge holds the progress/prompt/feedback, control pane below holds the answer options, word bank, or mic and Continue."
        ),
        LayoutVariantOption(
            FLIP_SORT, "Flip & Sort",
            "Top pane: bare prompt only - tap to flip and reveal the answer like a flashcard. Bottom pane: two silent full-width tap zones (X / check) log the recall grade. All 4 exercise types funnel through this one gesture."
        ),
        LayoutVariantOption(
            ELASTIC_SPLIT, "Elastic Split",
            "The prompt/answer-area balance shifts per exercise type - roomier for word-bank tiles, taller prompt for multiple-choice/listening, balanced for speaking - animating on every exercise transition."
        ),
        LayoutVariantOption(
            SPEAKING_ARC, "Speaking Coach Arc",
            "Only the speaking exercise gets a bespoke control layout: replay-target/record/replay-mine/skip arranged along a shallow upward arc for one-thumb reach. Feedback above is icon-only, no score. Other exercise types use the Default control pane."
        )
    )
}
