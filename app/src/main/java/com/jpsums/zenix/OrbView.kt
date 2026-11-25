package com.jpsums.zenix

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SweepGradient
import android.view.View
import android.view.animation.LinearInterpolator

/** Zenix's floating neon ring (same look as the ring inside the app). Pulses when she speaks, breathes when listening. */
class OrbView(context: Context) : View(context) {

    private val violet = Color.parseColor("#B266FF")
    private val pink = Color.parseColor("#FF3FD2")
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB30D0B17.toInt() }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val pulse = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = pink }
    private var sweep: SweepGradient? = null
    private var sweepFor = 0
    private var scale = 1f
    private var ring = 0f
    private var spin = 0f
    private var speaking = false

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1600
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = LinearInterpolator()
        addUpdateListener {
            val t = it.animatedValue as Float
            ring = t
            spin = (spin + if (speaking) 4f else 1.2f) % 360f
            scale = if (speaking) 0.9f + 0.1f * kotlin.math.abs(kotlin.math.sin(t * Math.PI * 4).toFloat())
                    else 0.94f + 0.04f * kotlin.math.sin(t * Math.PI * 2).toFloat()
            invalidate()
        }
    }

    init { alpha = 0.85f }

    fun setSpeaking(on: Boolean) {
        speaking = on
        alpha = if (on) 0.96f else 0.8f
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val maxR = minOf(width, height) / 2f
        val r = maxR * 0.6f * scale

        // dark see-through middle, so the ring reads on any wallpaper or app
        canvas.drawCircle(cx, cy, r, fill)

        // the ring: a few wide faint strokes (the glow) under a bright narrow one
        if (sweep == null || sweepFor != width) {
            sweep = SweepGradient(cx, cy, intArrayOf(violet, pink, violet), null); sweepFor = width
        }
        glow.shader = sweep
        canvas.save()
        canvas.rotate(spin, cx, cy)
        val boost = if (speaking) 1.25f else 1f
        glow.strokeWidth = maxR * 0.34f * boost; glow.alpha = 28; canvas.drawCircle(cx, cy, r, glow)
        glow.strokeWidth = maxR * 0.2f * boost;  glow.alpha = 60; canvas.drawCircle(cx, cy, r, glow)
        glow.strokeWidth = maxR * 0.1f;          glow.alpha = 255; canvas.drawCircle(cx, cy, r, glow)
        canvas.restore()
        core.strokeWidth = maxR * 0.028f; core.alpha = 215
        canvas.drawCircle(cx, cy, r, core)

        // a ripple moving outward
        pulse.strokeWidth = maxR * 0.03f
        pulse.alpha = ((1f - ring) * 130).toInt()
        canvas.drawCircle(cx, cy, r + (maxR - r) * ring, pulse)
    }
}
