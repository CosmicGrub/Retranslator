package com.retroid.translator.ui

import com.retroid.translator.settings.LayoutPreferences

/**
 * Variant IDs for the Translate tab's cover-screen and Flex-Mode (tabletop)
 * layouts - the per-tab piece of docs/specs/fold5-adaptation.md's
 * layout-variant system, built out here for Translate only (Practice/Learn
 * define their own sets independently, per [LayoutPreferences]'s doc
 * comment - plain strings on purpose, no shared enum to edit).
 *
 * [LayoutPreferences.DEFAULT_VARIANT] ("default") is reused rather than
 * redefined, since [LayoutPreferences.getVariant] already falls back to it
 * for both [com.retroid.translator.settings.ScreenMode] values.
 *
 * This single file is the one place [com.retroid.translator.ui.TranslateFragment]
 * and [com.retroid.translator.settings.TranslateLayoutSettingsFragment] both
 * read the id/title/subtitle for each option from, so the picker screen and
 * the rendering logic can never drift out of sync with each other.
 */
data class LayoutVariantOption(val id: String, val title: String, val subtitle: String)

object TranslateCoverVariant {
    const val DEFAULT = LayoutPreferences.DEFAULT_VARIANT
    const val SINGLE_CIRCLE = "single_circle"
    const val LIVE_TRANSCRIPT = "live_transcript"
    const val FACE_TO_FACE = "face_to_face"

    val OPTIONS = listOf(
        LayoutVariantOption(
            DEFAULT, "Default",
            "The existing full Translate screen (language pickers, packs, input, result) on the cover display."
        ),
        LayoutVariantOption(
            SINGLE_CIRCLE, "Single Circle",
            "One focal circle does everything: hold to speak, release to see the translation fill it, tap to hear it, swipe to flip direction."
        ),
        LayoutVariantOption(
            LIVE_TRANSCRIPT, "Live Session Transcript",
            "Scrollable chat-style transcript of this sitting, recent language-pair chips up top, input pinned at the bottom."
        ),
        LayoutVariantOption(
            FACE_TO_FACE, "Face-to-Face Mode",
            "Splits the screen top/bottom for two people sharing the phone - the top half renders upside-down for whoever's across from you."
        )
    )
}

object TranslateFlexVariant {
    const val DEFAULT = LayoutPreferences.DEFAULT_VARIANT
    const val ACROSS_TABLE = "across_table"
    const val MULTI_BROADCAST = "multi_broadcast"
    const val MIRROR_PANES = "mirror_panes"

    val OPTIONS = listOf(
        LayoutVariantOption(
            DEFAULT, "Default",
            "Viewing pane above the hinge shows the result, control pane below holds the language pickers, input and mic."
        ),
        LayoutVariantOption(
            ACROSS_TABLE, "Across the Table",
            "The hinge separates two people, not viewing/control - each half gets its own mic and a translated-text zone near the hinge."
        ),
        LayoutVariantOption(
            MULTI_BROADCAST, "Multi-Target Broadcast",
            "Translate one phrase into a roster of target languages at once; each has its own play button. Manage the roster below the hinge."
        ),
        LayoutVariantOption(
            MIRROR_PANES, "Mirror Panes",
            "Two matching cards - source above the hinge, target below - with a swap button straddling the seam."
        )
    )
}
