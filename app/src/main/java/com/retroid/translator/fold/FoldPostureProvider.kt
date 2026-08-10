package com.retroid.translator.fold

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Classifies the device into the full 5-posture matrix from
 * docs/specs/fold5-adaptation.md §2, driven by
 * [androidx.window.layout.WindowInfoTracker] / [FoldingFeature] — never by
 * raw screen dimensions.
 *
 * [postureFlow] is the source of truth for layout decisions (§2/§3); a
 * caller collects it (e.g. via `viewLifecycleOwner.lifecycleScope` +
 * `repeatOnLifecycle`) and re-lays-out on every emission, no Activity
 * restart required — `AndroidManifest.xml`'s existing
 * `configChanges="orientation|screenSize|keyboardHidden"` keeps the Activity
 * (and this Flow's collector) alive across a fold/unfold.
 *
 * [hingeAngleFlow] is a progressive-enhancement layer (spec §2 "Transition
 * polish"): continuous [android.hardware.Sensor.TYPE_HINGE_ANGLE] degrees
 * where available, for smooth cross-fade transitions, with nothing emitted
 * (not an error — an intentionally silent Flow) when the sensor is absent.
 * Callers must not depend on it for correctness, only for animation polish;
 * [postureFlow] alone is always sufficient to pick the right layout.
 */
class FoldPostureProvider(private val activity: Activity) {

    private val windowInfoTracker by lazy { WindowInfoTracker.getOrCreate(activity) }
    private val hingeAngleSensor by lazy { HingeAngleSensor(activity.applicationContext) }

    /** True on this device/API level iff a continuous hinge-angle sensor is present. */
    val hasHingeAngleSensor: Boolean get() = hingeAngleSensor.isAvailable

    /** Live posture, recomputed on every window-layout change (fold, unfold, rotate). */
    fun postureFlow(): Flow<FoldState> =
        windowInfoTracker.windowLayoutInfo(activity)
            .map { classify(it) }
            .distinctUntilChanged()

    /**
     * Continuous hinge angle in degrees (0 = closed, ~180 = flat), when
     * available. Emits nothing at all — not even a completion signal beyond
     * Flow's normal cancellation — on devices/API levels without
     * `TYPE_HINGE_ANGLE`; see [HingeAngleSensor] for the verified fallback
     * behavior.
     */
    fun hingeAngleFlow(): Flow<Float> = callbackFlow {
        val started = hingeAngleSensor.start { degrees -> trySend(degrees) }
        if (!started) {
            // No hinge-angle sensor on this device/API level (or registration
            // failed) — leave the flow open but silent; do not close it, so a
            // collector using it purely as an optional polish signal doesn't
            // need special-case handling for "sensor absent" vs "no angle
            // update yet". awaitClose still runs stop() defensively below.
            Log.i(TAG, "hingeAngleFlow: no TYPE_HINGE_ANGLE sensor, flow will stay silent")
        }
        awaitClose { hingeAngleSensor.stop() }
    }

    private fun classify(layoutInfo: WindowLayoutInfo): FoldState {
        val feature = layoutInfo.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
            ?: return FoldState(FoldPosture.NO_FOLDING_FEATURE, null)

        val posture = when (feature.orientation) {
            FoldingFeature.Orientation.HORIZONTAL -> {
                // Mirrored trigger (spec §2): both HORIZONTAL rows are meant
                // to share one implementation, gated on
                // "orientation == HORIZONTAL && isSeparating" per the spec's
                // literal wording. Verified on real Galaxy Z Fold 5 hardware
                // (serial RFCW80CK2RW) that wording does not hold: at FLAT
                // (180°, laid perfectly flat) the hinge presents a
                // zero-height bounds rect and isSeparating is reported
                // FALSE - there is no physical step/gap at that angle - so
                // gating on isSeparating would silently drop the FLAT half
                // of the tabletop pairing the spec explicitly lists as a
                // mirrored posture. Both FLAT and HALF_OPENED are treated
                // identically here on orientation alone, matching the
                // spec's stated intent (a horizontal hinge always means two
                // people facing each other across it) rather than its
                // isSeparating wording, which assumed hardware behavior
                // this device doesn't exhibit.
                if (feature.state == FoldingFeature.State.FLAT) {
                    FoldPosture.TABLETOP_LANDSCAPE_FLAT
                } else {
                    FoldPosture.TABLETOP_LANDSCAPE_ANGLED
                }
            }
            FoldingFeature.Orientation.VERTICAL ->
                // Spec §2 fallback: every VERTICAL-hinge posture (FLAT or
                // HALF_OPENED, isSeparating or not) falls back to the
                // single-column layout, so isSeparating doesn't change the
                // outcome here either way.
                if (feature.state == FoldingFeature.State.FLAT) {
                    FoldPosture.BOOK_PORTRAIT_FLAT
                } else {
                    FoldPosture.BOOK_PORTRAIT_ANGLED
                }
            else -> FoldPosture.NO_FOLDING_FEATURE
        }
        return FoldState(posture, feature)
    }

    companion object {
        private const val TAG = "FoldPostureProvider"
    }
}
