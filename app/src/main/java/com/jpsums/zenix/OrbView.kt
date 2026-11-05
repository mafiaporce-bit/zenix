package com.jpsums.zenix

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/** ZeniX's floating see-through orb. Pulses when she speaks, breathes softly when listening. */
class OrbView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#F472B6")
    }
    private var scale = 1f
    private var ring = 0f
    private var speaking = false

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1400
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            val t = it.animatedValue as Float
            ring = t
            scale = if (speaking) 0.9f + 0.12f * kotlin.math.abs(kotlin.math.sin(t * Math.PI * 4).toFloat())
                    else 0.94f + 0.04f * kotlin.math.sin(t * Math.PI * 2).toFloat()
            invalidate()
        }
    }

    init { alpha = 0.78f }

    fun setSpeaking(on: Boolean) {
        speaking = on
        alpha = if (on) 0.92f else 0.72f
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val maxR = minOf(width, height) / 2f
        val r = maxR * 0.72f * scale

        // listening ring
        ringPaint.strokeWidth = maxR * 0.05f
        ringPaint.alpha = ((1f - ring) * 160).toInt()
        canvas.drawCircle(cx, cy, r + (maxR - r) * ring, ringPaint)

        // orb body
        paint.shader = RadialGradient(
            cx - r * 0.3f, cy - r * 0.35f, r * 1.4f,
            intArrayOf(Color.parseColor("#FFE1F2"), Color.parseColor("#EC4899"), Color.parseColor("#8B5CF6"), Color.parseColor("#2A1761")),
            floatArrayOf(0f, 0.3f, 0.65f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, paint)
    }
}
