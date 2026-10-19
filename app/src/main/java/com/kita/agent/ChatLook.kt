package com.kita.agent

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View

// Latar layar chat: gradasi biru tua di atas menyapu ke biru langit di bawah (acuan: latar myBCA).
// Warna dan titik henti ada di Palette (SKY_COLORS, SKY_STOPS).
internal class ChatBackground(ctx: Context) : View(ctx) {
    private val paint = Paint()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        paint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(), Palette.SKY_COLORS, Palette.SKY_STOPS, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}

/**
 * Gelembung pesan berekor: persegi membulat dengan ujung runcing di sudut atas (kanan = pesan pengguna,
 * kiri = balasan AI). Lebar ekor ([tail]) harus ditambahkan ke padding sisi ekor oleh pemakai.
 */
internal class BubbleDrawable(
    private val color: Int,
    private val radius: Float,
    private val tail: Float,
    private val tailRight: Boolean
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@BubbleDrawable.color }
    private val path = Path()
    private val arc = RectF()

    override fun onBoundsChange(b: android.graphics.Rect) {
        super.onBoundsChange(b)
        build(b.width().toFloat(), b.height().toFloat())
    }

    private fun build(w: Float, h: Float) {
        path.reset()
        if (w <= 0f || h <= 0f) return
        val r = minOf(radius, h / 2f, (w - tail) / 2f)
        val right = w - tail
        // sisi atas menjulur ke ujung ekor di (w, 0), lalu melengkung kembali ke badan
        path.moveTo(r, 0f)
        path.lineTo(w, 0f)
        path.quadTo(right + tail * 0.1f, tail * 0.3f, right, tail * 1.25f)
        path.lineTo(right, h - r)
        arc.set(right - 2 * r, h - 2 * r, right, h)
        path.arcTo(arc, 0f, 90f)
        path.lineTo(r, h)
        arc.set(0f, h - 2 * r, 2 * r, h)
        path.arcTo(arc, 90f, 90f)
        path.lineTo(0f, r)
        arc.set(0f, 0f, 2 * r, 2 * r)
        path.arcTo(arc, 180f, 90f)
        path.close()
        if (!tailRight) {
            val m = Matrix()
            m.setScale(-1f, 1f, w / 2f, 0f)
            path.transform(m)
        }
    }

    override fun draw(c: Canvas) {
        c.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf; invalidateSelf() }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
