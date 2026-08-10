package com.retroid.translator.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * A single circular progress ring, drawn (not composed of child views) -
 * the entire visual for the Learn tab's "progress_ring" cover-screen
 * variant, which per its spec shows NO streak digit or due-card text
 * anywhere. Fills clockwise from 12 o'clock as [progress] increases.
 *
 * Deliberately a draw-only `View`, not a `ProgressBar` (Android's built-in
 * circular `ProgressBar` is either indeterminate-spinner styling or a small
 * platform-themed widget, not a large clean single ring matching "one large
 * focal circle" from this project's existing "single_circle" Translate
 * variant) and not a new XML shape/layer-list (a layer-list can't animate a
 * sweep angle).
 */
class ProgressRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 0f..1f. Setting this directly jumps with no animation - see [animateProgressTo] for the animated path used on every real state change. */
    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var trackColor: Int = Color.parseColor("#332196F3")
    var fillColor: Int = Color.parseColor("#2196F3")
    var strokeWidthDp: Float = 18f

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val arcRect = RectF()
    private var animator: ValueAnimator? = null

    fun animateProgressTo(target: Float, durationMs: Long = 450L) {
        animator?.cancel()
        val start = progress
        val end = target.coerceIn(0f, 1f)
        animator = ValueAnimator.ofFloat(start, end).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { progress = it.animatedValue as Float }
            start()
        }
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val strokePx = strokeWidthDp * resources.displayMetrics.density
        trackPaint.strokeWidth = strokePx
        trackPaint.color = trackColor
        fillPaint.strokeWidth = strokePx
        fillPaint.color = fillColor

        val inset = strokePx / 2f + paddingLeft.coerceAtLeast(paddingTop).toFloat()
        arcRect.set(inset, inset, width - inset, height - inset)
        canvas.drawArc(arcRect, 0f, 360f, false, trackPaint)
        if (progress > 0f) {
            canvas.drawArc(arcRect, -90f, 360f * progress, false, fillPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}
