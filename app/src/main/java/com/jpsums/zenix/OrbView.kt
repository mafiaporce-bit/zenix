package com.jpsums.zenix

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * ZENIX's small floating orb over other apps: the same glass sphere of silver light as inside the app,
 * drawn simply so it costs almost nothing. It breathes while she listens and stirs while she speaks.
 */
class OrbView(context: Context) : View(context) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val loopPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    private var builtFor = 0
    private var t = 0f          // seconds, roughly
    private var energy = 0f     // eases toward 1 while she speaks
    private var speaking = false

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            t += 0.016f * (1f + energy * 1.6f)
            energy += ((if (speaking) 1f else 0f) - energy) * 0.06f
            invalidate()
        }
    }

    init { alpha = 0.9f }

    fun setSpeaking(on: Boolean) {
        speaking = on
        alpha = if (on) 0.98f else 0.86f
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val maxR = minOf(width, height) / 2f
        val talk = kotlin.math.abs(kotlin.math.sin(t * 6.2f)) * kotlin.math.abs(kotlin.math.sin(t * 2.3f + 1f))
        val r = maxR * 0.62f * (1f + 0.02f * kotlin.math.sin(t * 1.3f) + 0.05f * energy * talk)

        if (builtFor != width) {
            builtFor = width
            // smoked glass: nearly black, a touch lighter toward the rim, so it reads on any wallpaper or app
            fill.shader = RadialGradient(cx, cy, maxR * 0.66f, intArrayOf(0xE6000000.toInt(), 0xE6080808.toInt(), 0xD9242424.toInt()), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
            rim.shader = SweepGradient(cx, cy, intArrayOf(Color.WHITE, 0xFF606060.toInt(), 0xFFB8B8B8.toInt(), 0xFF484848.toInt(), Color.WHITE), null)
            core.shader = RadialGradient(cx, cy, maxR * 0.34f, intArrayOf(0x66FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(cx, cy, r, fill)

        // the core glows a little, more while she speaks
        core.alpha = (40 + 150 * energy * (0.4f + 0.6f * talk)).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, maxR * 0.34f, core)

        // two loops of light turning inside the glass
        for (k in 0..1) {
            canvas.save()
            canvas.rotate((if (k == 0) 1f else -1f) * t * 14f + k * 70f, cx, cy)
            val squash = 0.38f + 0.3f * (0.5f + 0.5f * kotlin.math.sin(t * 0.9f + k * 2f)) + 0.12f * energy * talk
            oval.set(cx - r * 0.9f, cy - r * 0.9f * squash, cx + r * 0.9f, cy + r * 0.9f * squash)
            loopPaint.strokeWidth = maxR * 0.022f
            loopPaint.alpha = (70 + 110 * energy).toInt().coerceIn(0, 255) - k * 25
            canvas.drawOval(oval, loopPaint)
            canvas.restore()
        }

        // the rim: a soft halo under a bright, slowly turning edge
        glow.strokeWidth = maxR * 0.16f; glow.alpha = (14 + 26 * energy).toInt(); canvas.drawCircle(cx, cy, r, glow)
        glow.strokeWidth = maxR * 0.08f; glow.alpha = (30 + 40 * energy).toInt(); canvas.drawCircle(cx, cy, r, glow)
        canvas.save()
        canvas.rotate(t * 22f, cx, cy)
        rim.strokeWidth = maxR * 0.045f
        canvas.drawCircle(cx, cy, r, rim)
        canvas.restore()

        // thin arcs of light around it
        arc.strokeWidth = maxR * 0.02f
        arc.alpha = (110 + 100 * energy).toInt().coerceIn(0, 255)
        val ar = r + maxR * 0.2f
        oval.set(cx - ar, cy - ar, cx + ar, cy + ar)
        val a0 = -t * 30f
        canvas.drawArc(oval, a0, 42f, false, arc)
        canvas.drawArc(oval, a0 + 180f, 42f, false, arc)
    }
}
