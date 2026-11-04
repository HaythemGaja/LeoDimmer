package com.universal.tvdimmer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import kotlin.math.hypot
import kotlin.math.max

/**
 * LeoDimmer v2.4 -- what Sleep actually draws.
 *
 * A flat rectangle of colour is a lit screen. A lamp spills: bright where the
 * bulb is, falling off into the room. This view keeps the same opaque fill but
 * puts a hot spot in the middle of it -- a [RadialGradient] from the lamp colour
 * to black -- and can draw the time on top of it.
 *
 * The view is still the dimmer: `alpha` is the Day/Night mechanism, and
 * [setBackgroundColor] stays the fill entry point, so the colour fades in
 * OverlayService (which mix two fills frame by frame) need no changes.
 */
class LampView(context: Context) : View(context) {

    companion object {
        /**
         * Radius of the glow as a fraction of the half-diagonal. 0 means a flat
         * panel: the whole screen is the colour, nothing fades out.
         */
        private fun radiusFactor(glowPercent: Int): Float =
            if (glowPercent <= 0) 0f else 1.45f - 0.95f * (glowPercent.coerceIn(0, 100) / 100f)
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        color = 0xE6FFFFFF.toInt()
    }
    private val clockShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        color = 0x59000000
    }

    private var fill = Color.BLACK
    private var glow = 0
    private var clock: String? = null

    /** The lamp colour, already dimmed by the level. */
    override fun setBackgroundColor(color: Int) {
        if (color == fill) return
        fill = color
        invalidate()
    }

    /** 0 = flat panel, 100 = a tight hot spot. */
    fun setGlow(percent: Int) {
        if (percent == glow) return
        glow = percent
        invalidate()
    }

    /** The time to draw, or null for no clock. */
    fun setClock(text: String?) {
        if (text == clock) return
        clock = text
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val factor = radiusFactor(glow)
        if (factor <= 0f) {
            canvas.drawColor(fill)
        } else {
            canvas.drawColor(Color.BLACK)
            val centreX = width / 2f
            val centreY = height / 2f
            val radius = max(1f, hypot(width.toFloat(), height.toFloat()) / 2f * factor)
            paint.shader = RadialGradient(
                centreX,
                centreY,
                radius,
                intArrayOf(fill, dim(fill, 0.4f), Color.BLACK),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.shader = null
        }
        drawClock(canvas)
    }

    private fun drawClock(canvas: Canvas) {
        val text = clock ?: return
        val textSize = height * 0.11f
        clockPaint.textSize = textSize
        clockShadow.textSize = textSize
        val x = width / 2f
        val y = height / 2f + textSize / 3f
        canvas.drawText(text, x + 2f, y + 3f, clockShadow)
        canvas.drawText(text, x, y, clockPaint)
    }

    private fun dim(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * factor).toInt(),
        (Color.green(color) * factor).toInt(),
        (Color.blue(color) * factor).toInt()
    )
}
