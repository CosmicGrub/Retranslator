package com.retroid.translator.fold

import androidx.window.layout.FoldingFeature

/**
 * The five distinct physical postures a Galaxy Z Fold 5 (or any similar
 * book-style foldable) can present, per docs/specs/fold5-adaptation.md §2.
 * Everything except [CLOSED_COVER] is derived purely from
 * [androidx.window.layout.FoldingFeature] — never from raw screen
 * width/height (a wide phone and a flat-laid fold can report similar
 * dimensions, per spec).
 */
enum class FoldPosture {
    /**
     * Folded closed; the app (if running at all) is on the cover display.
     * [androidx.window.layout.WindowInfoTracker] / [FoldingFeature] cannot
     * observe this directly — there is no separating fold to report once the
     * inner screen isn't showing anything (spec §2: "not observable via
     * FoldingFeature — this only applies once folded state is otherwise
     * known"). [FoldPostureProvider] never produces this value on its own;
     * it exists here so the posture matrix is complete and so a future
     * cover-screen-launch detector (spec §3 / implementation-order step 4,
     * out of scope for this pass) has a value to report into once it exists.
     */
    CLOSED_COVER,

    /** VERTICAL hinge, FLAT — the default "just unfolded" pose. Fallback (single-column) layout. */
    BOOK_PORTRAIT_FLAT,

    /** VERTICAL hinge, HALF_OPENED — propped like a mini laptop, portrait. Fallback (single-column) layout. */
    BOOK_PORTRAIT_ANGLED,

    /** HORIZONTAL hinge, FLAT, isSeparating — laid flat on a table, 180°. Mirrored face-to-face layout. */
    TABLETOP_LANDSCAPE_FLAT,

    /** HORIZONTAL hinge, HALF_OPENED, isSeparating — tented/propped, ~75-115°. Mirrored face-to-face layout. */
    TABLETOP_LANDSCAPE_ANGLED,

    /**
     * No [FoldingFeature] reported at all — either a non-foldable device, or
     * a foldable with no currently-separating fold (spec §2 fallback: "a
     * VERTICAL hinge, or no separating fold at all"). Same fallback layout
     * as book-portrait.
     */
    NO_FOLDING_FEATURE;

    /** The two HORIZONTAL rows share one implementation per spec §2 — combined trigger. */
    val isMirroredTabletop: Boolean
        get() = this == TABLETOP_LANDSCAPE_FLAT || this == TABLETOP_LANDSCAPE_ANGLED
}

/**
 * A classified posture plus the [FoldingFeature] it was derived from (present
 * for the two book-portrait and two tabletop-landscape postures; null for
 * [FoldPosture.CLOSED_COVER] and [FoldPosture.NO_FOLDING_FEATURE], which have
 * no feature to report). Consumers that need [FoldingFeature.bounds] /
 * [FoldingFeature.occlusionType] to size a split precisely (spec §2 layout)
 * read [feature] directly rather than re-deriving posture-specific geometry.
 */
data class FoldState(
    val posture: FoldPosture,
    val feature: FoldingFeature?
) {
    companion object {
        val UNKNOWN = FoldState(FoldPosture.NO_FOLDING_FEATURE, null)
    }
}
