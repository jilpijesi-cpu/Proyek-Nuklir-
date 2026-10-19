package com.kita.agent

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.text.style.LineBackgroundSpan
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.sin

/** Latar penuh selebar baris untuk tampilan diff. */
internal class DiffBg(private val color: Int) : LineBackgroundSpan {
    override fun drawBackground(
        c: Canvas, p: Paint, left: Int, right: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, lnum: Int
    ) {
        val old = p.color
        p.color = color
        c.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), p)
        p.color = old
    }
}

/** Ikon sederhana yang digambar lewat kode (tanpa file gambar). */
class IconView(ctx: Context, t: Int, c: Int = Palette.TEXT_MAIN) : View(ctx) {
    var type: Int = t
        set(v) { field = v; invalidate() }
    var color: Int = c
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    companion object {
        const val MENU = 0
        const val PLUS = 1
        const val MIC = 2
        const val SEND = 3
        const val WAVE = 4
        const val NEW = 5
        const val CHEVRON = 6
        const val TERMINAL = 7
        const val CODE = 8
        const val CLOCK = 9
        const val TRASH = 10
        const val STOP = 11
        const val GRID = 12 // empat lingkaran: mencari / mengumpulkan informasi
        const val BLOB_TERM = 13 // awan berisi >_ : perintah terminal umum
        const val FILE = 14 // lembar dokumen: membaca file
        const val FOLDER = 15 // folder: melihat/mengatur isi folder
        const val GEAR = 16 // roda gigi: build (berputar saat berjalan)
        const val ARCHIVE = 17 // kotak arsip: zip/tar
        const val DOWNLOAD = 18 // panah ke bawah: pasang paket / unduh
        const val GLOBE = 19 // bola dunia: jaringan (curl, wget)
        const val CHECK = 20 // centang: uji / verifikasi
        const val CROSS = 21 // silang: gagal
        const val NEW_CHAT = 22 // lingkaran putus-putus + kapsul miring: chat baru
        const val CHEVRON_DOWN = 23 // panah kecil ke bawah
        const val BRAIN = 24 // analisis / berpikir
        const val BOOK = 25 // membaca dan memahami file
        const val WRITE = 26 // menulis atau mengubah file
        const val BACK = 27 // panah kiri
        const val PHONE = 28 // gagang telepon
        const val DOTS_V = 29 // tiga titik vertikal (menu)
        const val SMILEY = 30 // wajah tersenyum (stiker / templat)
        const val CLIP = 31 // penjepit kertas (lampiran)
        const val DOUBLE_CHECK = 32 // centang ganda (terkirim)
        const val RING = 33 // lingkaran kosong (langkah menunggu)
    }

    /** true = ikon "sedang bekerja": berdenyut (roda gigi: berputar). Berhenti sendiri saat dilepas dari layar. */
    var working: Boolean = false
        set(v) { field = v; if (v) startWorking() else stopWorking() }
    private var workAnim: ValueAnimator? = null

    private fun startWorking() {
        workAnim?.cancel()
        val spin = type == GEAR
        workAnim = (if (spin) ValueAnimator.ofFloat(0f, 360f) else ValueAnimator.ofFloat(0.35f, 1f)).apply {
            duration = if (spin) 1800L else 800L
            repeatCount = ValueAnimator.INFINITE
            if (spin) interpolator = LinearInterpolator() else repeatMode = ValueAnimator.REVERSE
            addUpdateListener { if (spin) rotation = it.animatedValue as Float else alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun stopWorking() {
        workAnim?.cancel()
        workAnim = null
        alpha = 1f
        rotation = 0f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (working && workAnim == null) startWorking()
    }

    override fun onDetachedFromWindow() {
        workAnim?.cancel()
        workAnim = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val s = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val iconStroke = 1.8f * resources.displayMetrics.density // satu ketebalan untuk semua ikon
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.strokeWidth = iconStroke
        p.color = color
        when (type) {
            MENU -> {
                c.drawLine(cx - s * 0.22f, cy - s * 0.075f, cx + s * 0.22f, cy - s * 0.075f, p)
                c.drawLine(cx - s * 0.22f, cy + s * 0.075f, cx + s * 0.22f, cy + s * 0.075f, p)
            }
            PLUS -> {
                p.strokeWidth = iconStroke
                c.drawLine(cx - s * 0.28f, cy, cx + s * 0.28f, cy, p)
                c.drawLine(cx, cy - s * 0.28f, cx, cy + s * 0.28f, p)
            }
            MIC -> {
                p.strokeWidth = iconStroke
                c.drawRoundRect(RectF(cx - s * 0.1f, cy - s * 0.3f, cx + s * 0.1f, cy + s * 0.05f), s * 0.1f, s * 0.1f, p)
                c.drawArc(RectF(cx - s * 0.2f, cy - s * 0.16f, cx + s * 0.2f, cy + s * 0.2f), 0f, 180f, false, p)
                c.drawLine(cx, cy + s * 0.2f, cx, cy + s * 0.32f, p)
            }
            SEND -> {
                c.drawLine(cx, cy + s * 0.24f, cx, cy - s * 0.24f, p)
                c.drawLine(cx - s * 0.2f, cy - s * 0.04f, cx, cy - s * 0.24f, p)
                c.drawLine(cx + s * 0.2f, cy - s * 0.04f, cx, cy - s * 0.24f, p)
            }
            WAVE -> {
                p.strokeWidth = iconStroke
                c.drawLine(cx - s * 0.18f, cy - s * 0.1f, cx - s * 0.18f, cy + s * 0.1f, p)
                c.drawLine(cx, cy - s * 0.24f, cx, cy + s * 0.24f, p)
                c.drawLine(cx + s * 0.18f, cy - s * 0.14f, cx + s * 0.18f, cy + s * 0.14f, p)
            }
            NEW -> {
                c.drawRoundRect(RectF(cx - s * 0.26f, cy - s * 0.2f, cx + s * 0.2f, cy + s * 0.26f), s * 0.08f, s * 0.08f, p)
                p.strokeWidth = iconStroke
                c.drawLine(cx - s * 0.04f, cy + s * 0.04f, cx + s * 0.26f, cy - s * 0.26f, p)
            }
            CHEVRON -> {
                c.drawLine(cx - s * 0.1f, cy - s * 0.2f, cx + s * 0.1f, cy, p)
                c.drawLine(cx + s * 0.1f, cy, cx - s * 0.1f, cy + s * 0.2f, p)
            }
            TERMINAL -> {
                p.strokeWidth = iconStroke
                c.drawRoundRect(RectF(cx - s * 0.32f, cy - s * 0.24f, cx + s * 0.32f, cy + s * 0.24f), s * 0.06f, s * 0.06f, p)
                c.drawLine(cx - s * 0.18f, cy - s * 0.09f, cx - s * 0.08f, cy, p)
                c.drawLine(cx - s * 0.08f, cy, cx - s * 0.18f, cy + s * 0.09f, p)
                c.drawLine(cx, cy + s * 0.1f, cx + s * 0.16f, cy + s * 0.1f, p)
            }
            CODE -> {
                c.drawLine(cx - s * 0.12f, cy - s * 0.16f, cx - s * 0.28f, cy, p)
                c.drawLine(cx - s * 0.28f, cy, cx - s * 0.12f, cy + s * 0.16f, p)
                c.drawLine(cx + s * 0.12f, cy - s * 0.16f, cx + s * 0.28f, cy, p)
                c.drawLine(cx + s * 0.28f, cy, cx + s * 0.12f, cy + s * 0.16f, p)
                c.drawLine(cx + s * 0.04f, cy - s * 0.2f, cx - s * 0.04f, cy + s * 0.2f, p)
            }
            CLOCK -> {
                p.strokeWidth = iconStroke
                c.drawCircle(cx, cy, s * 0.3f, p)
                c.drawLine(cx, cy, cx, cy - s * 0.16f, p)
                c.drawLine(cx, cy, cx + s * 0.12f, cy + s * 0.06f, p)
            }
            TRASH -> {
                p.strokeWidth = iconStroke
                // tutup dan gagang
                c.drawLine(cx - s * 0.27f, cy - s * 0.17f, cx + s * 0.27f, cy - s * 0.17f, p)
                c.drawLine(cx - s * 0.08f, cy - s * 0.17f, cx - s * 0.08f, cy - s * 0.27f, p)
                c.drawLine(cx + s * 0.08f, cy - s * 0.17f, cx + s * 0.08f, cy - s * 0.27f, p)
                c.drawLine(cx - s * 0.08f, cy - s * 0.27f, cx + s * 0.08f, cy - s * 0.27f, p)
                // badan tempat sampah
                c.drawLine(cx - s * 0.2f, cy - s * 0.17f, cx - s * 0.16f, cy + s * 0.28f, p)
                c.drawLine(cx - s * 0.16f, cy + s * 0.28f, cx + s * 0.16f, cy + s * 0.28f, p)
                c.drawLine(cx + s * 0.16f, cy + s * 0.28f, cx + s * 0.2f, cy - s * 0.17f, p)
                // garis di dalam
                c.drawLine(cx - s * 0.06f, cy - s * 0.06f, cx - s * 0.06f, cy + s * 0.18f, p)
                c.drawLine(cx + s * 0.06f, cy - s * 0.06f, cx + s * 0.06f, cy + s * 0.18f, p)
            }
            GRID -> {
                p.strokeWidth = iconStroke
                val d = s * 0.17f
                val r = s * 0.1f
                c.drawCircle(cx - d, cy - d, r, p)
                c.drawCircle(cx + d, cy - d, r, p)
                c.drawCircle(cx - d, cy + d, r, p)
                c.drawCircle(cx + d, cy + d, r, p)
            }
            BLOB_TERM -> {
                // badan awan bergelombang (6 tonjolan) + tanda >_
                val path = Path()
                val n = 72
                for (i in 0..n) {
                    val a = 2.0 * Math.PI * i / n
                    val r = s * 0.36f * (1f + 0.07f * cos(6.0 * a).toFloat())
                    val x = cx + r * cos(a).toFloat()
                    val y = cy + r * sin(a).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                p.strokeWidth = iconStroke
                c.drawPath(path, p)
                c.drawLine(cx - s * 0.17f, cy - s * 0.1f, cx - s * 0.06f, cy, p)
                c.drawLine(cx - s * 0.06f, cy, cx - s * 0.17f, cy + s * 0.1f, p)
                c.drawLine(cx + s * 0.02f, cy + s * 0.1f, cx + s * 0.16f, cy + s * 0.1f, p)
            }
            FILE -> {
                p.strokeWidth = iconStroke
                val path = Path()
                path.moveTo(cx - s * 0.2f, cy - s * 0.3f)
                path.lineTo(cx + s * 0.08f, cy - s * 0.3f)
                path.lineTo(cx + s * 0.22f, cy - s * 0.16f)
                path.lineTo(cx + s * 0.22f, cy + s * 0.3f)
                path.lineTo(cx - s * 0.2f, cy + s * 0.3f)
                path.close()
                c.drawPath(path, p)
                c.drawLine(cx + s * 0.08f, cy - s * 0.3f, cx + s * 0.08f, cy - s * 0.16f, p)
                c.drawLine(cx + s * 0.08f, cy - s * 0.16f, cx + s * 0.22f, cy - s * 0.16f, p)
                c.drawLine(cx - s * 0.1f, cy + s * 0.02f, cx + s * 0.12f, cy + s * 0.02f, p)
                c.drawLine(cx - s * 0.1f, cy + s * 0.16f, cx + s * 0.12f, cy + s * 0.16f, p)
            }
            FOLDER -> {
                p.strokeWidth = iconStroke
                val path = Path()
                path.moveTo(cx - s * 0.3f, cy - s * 0.2f)
                path.lineTo(cx - s * 0.1f, cy - s * 0.2f)
                path.lineTo(cx, cy - s * 0.1f)
                path.lineTo(cx + s * 0.3f, cy - s * 0.1f)
                path.lineTo(cx + s * 0.3f, cy + s * 0.22f)
                path.lineTo(cx - s * 0.3f, cy + s * 0.22f)
                path.close()
                c.drawPath(path, p)
            }
            GEAR -> {
                p.strokeWidth = iconStroke
                c.drawCircle(cx, cy, s * 0.19f, p)
                c.drawCircle(cx, cy, s * 0.07f, p)
                for (i in 0..7) {
                    val a = i * Math.PI / 4
                    val ca = cos(a).toFloat()
                    val sa = sin(a).toFloat()
                    c.drawLine(cx + s * 0.22f * ca, cy + s * 0.22f * sa, cx + s * 0.31f * ca, cy + s * 0.31f * sa, p)
                }
            }
            ARCHIVE -> {
                p.strokeWidth = iconStroke
                c.drawRoundRect(RectF(cx - s * 0.3f, cy - s * 0.28f, cx + s * 0.3f, cy - s * 0.1f), s * 0.04f, s * 0.04f, p)
                c.drawRoundRect(RectF(cx - s * 0.24f, cy - s * 0.1f, cx + s * 0.24f, cy + s * 0.28f), s * 0.04f, s * 0.04f, p)
                c.drawLine(cx - s * 0.08f, cy + s * 0.04f, cx + s * 0.08f, cy + s * 0.04f, p)
            }
            DOWNLOAD -> {
                c.drawLine(cx, cy - s * 0.26f, cx, cy + s * 0.1f, p)
                c.drawLine(cx - s * 0.14f, cy - s * 0.04f, cx, cy + s * 0.1f, p)
                c.drawLine(cx + s * 0.14f, cy - s * 0.04f, cx, cy + s * 0.1f, p)
                c.drawLine(cx - s * 0.26f, cy + s * 0.12f, cx - s * 0.26f, cy + s * 0.27f, p)
                c.drawLine(cx - s * 0.26f, cy + s * 0.27f, cx + s * 0.26f, cy + s * 0.27f, p)
                c.drawLine(cx + s * 0.26f, cy + s * 0.27f, cx + s * 0.26f, cy + s * 0.12f, p)
            }
            GLOBE -> {
                p.strokeWidth = iconStroke
                c.drawCircle(cx, cy, s * 0.3f, p)
                c.drawOval(RectF(cx - s * 0.12f, cy - s * 0.3f, cx + s * 0.12f, cy + s * 0.3f), p)
                c.drawLine(cx - s * 0.3f, cy, cx + s * 0.3f, cy, p)
            }
            CHECK -> {
                p.strokeWidth = iconStroke
                c.drawLine(cx - s * 0.22f, cy + s * 0.02f, cx - s * 0.07f, cy + s * 0.17f, p)
                c.drawLine(cx - s * 0.07f, cy + s * 0.17f, cx + s * 0.24f, cy - s * 0.16f, p)
            }
            CROSS -> {
                p.strokeWidth = iconStroke
                c.drawLine(cx - s * 0.19f, cy - s * 0.19f, cx + s * 0.19f, cy + s * 0.19f, p)
                c.drawLine(cx + s * 0.19f, cy - s * 0.19f, cx - s * 0.19f, cy + s * 0.19f, p)
            }
            NEW_CHAT -> {
                val r = s * 0.36f
                val circ = (2.0 * Math.PI * r).toFloat()
                p.strokeWidth = iconStroke * 1.1f
                p.pathEffect = DashPathEffect(floatArrayOf(0.01f, circ / 14f - 0.01f), 0f)
                c.drawCircle(cx, cy, r, p)
                p.pathEffect = null
                p.strokeWidth = iconStroke
                c.save()
                c.rotate(-45f, cx, cy)
                c.drawRoundRect(RectF(cx - s * 0.2f, cy - s * 0.085f, cx + s * 0.2f, cy + s * 0.085f), s * 0.085f, s * 0.085f, p)
                c.restore()
            }
            CHEVRON_DOWN -> {
                c.drawLine(cx - s * 0.2f, cy - s * 0.1f, cx, cy + s * 0.1f, p)
                c.drawLine(cx, cy + s * 0.1f, cx + s * 0.2f, cy - s * 0.1f, p)
            }
            BRAIN -> {
                c.drawCircle(cx - s * 0.13f, cy, s * 0.2f, p)
                c.drawCircle(cx + s * 0.13f, cy, s * 0.2f, p)
                c.drawLine(cx, cy - s * 0.2f, cx, cy + s * 0.2f, p)
                c.drawLine(cx - s * 0.27f, cy - s * 0.03f, cx - s * 0.12f, cy - s * 0.03f, p)
                c.drawLine(cx + s * 0.12f, cy + s * 0.08f, cx + s * 0.27f, cy + s * 0.08f, p)
            }
            BOOK -> {
                c.drawLine(cx, cy - s * 0.25f, cx, cy + s * 0.27f, p)
                c.drawRoundRect(RectF(cx - s * 0.3f, cy - s * 0.22f, cx - s * 0.03f, cy + s * 0.22f), s * 0.04f, s * 0.04f, p)
                c.drawRoundRect(RectF(cx + s * 0.03f, cy - s * 0.22f, cx + s * 0.3f, cy + s * 0.22f), s * 0.04f, s * 0.04f, p)
            }
            WRITE -> {
                c.drawLine(cx - s * 0.22f, cy + s * 0.23f, cx + s * 0.22f, cy - s * 0.22f, p)
                c.drawLine(cx - s * 0.27f, cy + s * 0.28f, cx - s * 0.12f, cy + s * 0.23f, p)
                c.drawLine(cx + s * 0.15f, cy - s * 0.29f, cx + s * 0.27f, cy - s * 0.17f, p)
                c.drawLine(cx - s * 0.26f, cy + s * 0.29f, cx + s * 0.27f, cy + s * 0.29f, p)
            }
            BACK -> {
                c.drawLine(cx + s * 0.26f, cy, cx - s * 0.26f, cy, p)
                c.drawLine(cx - s * 0.26f, cy, cx - s * 0.02f, cy - s * 0.24f, p)
                c.drawLine(cx - s * 0.26f, cy, cx - s * 0.02f, cy + s * 0.24f, p)
            }
            PHONE -> {
                p.style = Paint.Style.FILL
                val path = Path()
                path.moveTo(cx - s * 0.26f, cy - s * 0.16f)
                path.lineTo(cx - s * 0.12f, cy - s * 0.28f)
                path.lineTo(cx - s * 0.02f, cy - s * 0.08f)
                path.lineTo(cx - s * 0.1f, cy)
                path.quadTo(cx - s * 0.02f, cy + s * 0.14f, cx + s * 0.1f, cy + s * 0.18f)
                path.lineTo(cx + s * 0.18f, cy + s * 0.1f)
                path.lineTo(cx + s * 0.28f, cy + s * 0.22f)
                path.lineTo(cx + s * 0.16f, cy + s * 0.3f)
                path.quadTo(cx - s * 0.08f, cy + s * 0.28f, cx - s * 0.22f, cy + s * 0.04f)
                path.quadTo(cx - s * 0.3f, cy - s * 0.1f, cx - s * 0.26f, cy - s * 0.16f)
                path.close()
                c.drawPath(path, p)
            }
            DOTS_V -> {
                p.style = Paint.Style.FILL
                for (k in -1..1) c.drawCircle(cx, cy + k * s * 0.2f, s * 0.06f, p)
            }
            SMILEY -> {
                c.drawCircle(cx, cy, s * 0.32f, p)
                p.style = Paint.Style.FILL
                c.drawCircle(cx - s * 0.11f, cy - s * 0.07f, s * 0.04f, p)
                c.drawCircle(cx + s * 0.11f, cy - s * 0.07f, s * 0.04f, p)
                p.style = Paint.Style.STROKE
                c.drawArc(RectF(cx - s * 0.17f, cy - s * 0.1f, cx + s * 0.17f, cy + s * 0.18f), 20f, 140f, false, p)
            }
            CLIP -> {
                c.save()
                c.rotate(35f, cx, cy)
                c.drawRoundRect(RectF(cx - s * 0.13f, cy - s * 0.32f, cx + s * 0.13f, cy + s * 0.32f), s * 0.13f, s * 0.13f, p)
                c.drawLine(cx, cy - s * 0.14f, cx, cy + s * 0.2f, p)
                c.restore()
            }
            DOUBLE_CHECK -> {
                for (dx in floatArrayOf(-0.1f, 0.12f)) {
                    c.drawLine(cx + s * (dx - 0.15f), cy + s * 0.02f, cx + s * (dx - 0.05f), cy + s * 0.13f, p)
                    c.drawLine(cx + s * (dx - 0.05f), cy + s * 0.13f, cx + s * (dx + 0.17f), cy - s * 0.12f, p)
                }
            }
            RING -> {
                c.drawCircle(cx, cy, s * 0.3f, p)
            }
            STOP -> {
                p.style = Paint.Style.FILL
                c.drawRoundRect(RectF(cx - s * 0.17f, cy - s * 0.17f, cx + s * 0.17f, cy + s * 0.17f), s * 0.04f, s * 0.04f, p)
            }
        }
    }
}

/** Tiga bola oranye yang melayang bebas: penanda "sedang bekerja" di bilah atas. Berhenti sendiri saat lepas dari layar. */
class DotsView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t0 = android.os.SystemClock.uptimeMillis()
    private var anim: ValueAnimator? = null

    private fun start() {
        if (anim != null) return
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { invalidate() }
            start()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); start() }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        anim = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        drawBalls(c, width.toFloat(), height.toFloat(), (android.os.SystemClock.uptimeMillis() - t0) / 1000f, p)
    }
}

// Kecepatan sudut (rad/detik) tiap bola; sengaja tidak berkelipatan bulat sehingga polanya nyaris tak pernah berulang.
private val BX1 = floatArrayOf(1.31f, 1.07f, 1.63f)
private val BX2 = floatArrayOf(2.17f, 2.53f, 1.89f)
private val BY1 = floatArrayOf(1.73f, 1.41f, 1.19f)
private val BY2 = floatArrayOf(2.71f, 2.09f, 2.39f)

/**
 * Posisi (x, y) bola ke-[i] pada detik [t] di area [w] x [h] dengan jarak tepi [margin].
 * Tiap sumbu adalah campuran dua gelombang sinus berfrekuensi tak sebanding: gerak mulus tanpa lompatan, terlihat acak,
 * dan tidak ada putaran yang jelas berulang. Tiap bola condong ke jalurnya sendiri agar tidak menumpuk di satu sisi.
 */
internal fun ballCenter(i: Int, t: Float, w: Float, h: Float, margin: Float): FloatArray {
    val mx = 0.6f * sin(BX1[i] * t + i * 2.1f) + 0.4f * sin(BX2[i] * t + i * 0.7f + 1f)
    val my = 0.6f * sin(BY1[i] * t + i * 1.3f + 0.5f) + 0.4f * sin(BY2[i] * t + i * 2.9f)
    val lane = (i + 0.5f) / 3f
    val ux = (0.5f + 0.5f * mx) * 0.6f + lane * 0.4f
    val uy = 0.5f + 0.5f * my
    return floatArrayOf(margin + (w - 2 * margin) * ux, margin + (h - 2 * margin) * uy)
}

/** Tiga bola oranye kecil yang melayang acak dan halus. [t] = detik berjalan. Dipakai DotsView (bilah atas). */
internal fun drawBalls(c: Canvas, w: Float, h: Float, t: Float, p: Paint) {
    if (w <= 0f || h <= 0f) return
    val base = minOf(h * 0.16f, w / 3f * 0.30f)
    for (i in 0..2) {
        val pos = ballCenter(i, t, w, h, base * 1.35f)
        val x = pos[0]
        val y = pos[1]
        val r = base * (1f + 0.14f * sin(t * (1.9f + 0.37f * i) + i))
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = Palette.BALL
        p.alpha = 45
        c.drawCircle(x, y, r * 1.15f, p)
        p.alpha = 255
        p.shader = RadialGradient(
            x - r * 0.25f, y - r * 0.25f, r * 1.15f,
            intArrayOf(Palette.BALL_CORE, Palette.BALL, Palette.BALL_EDGE),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(x, y, r, p)
        p.shader = null
    }
}

/** Teks dengan efek kilau yang bergerak (shimmer) saat aktif. */
class ShimmerText(ctx: Context) : TextView(ctx) {
    private var anim: ValueAnimator? = null
    private var shader: LinearGradient? = null
    private val m = Matrix()
    private var t = 0f
    private var active = false

    /** Warna dasar dan sorot kilau. Bawaan: warna merek. */
    var shimmerBase: Int = tint(Palette.BRAND, 0.5f)
        set(v) { field = v; shader = null; invalidate() }
    var shimmerHi: Int = Palette.BRAND
        set(v) { field = v; shader = null; invalidate() }

    /** Lama satu sapuan kilau (ms). */
    var shimmerMs: Long = 1500L

    /**
     * Bayangan tipis tepat di bawah teks supaya teks putih tetap tegas di atas latar biru muda (0 = tanpa bayangan).
     * Digambar sebagai salinan teks berwarna polos, bukan lewat setShadowLayer, karena bayangan bawaan ikut memakai
     * shader kilau sehingga warnanya jadi ikut berubah-ubah. [haloDy] = jarak turun bayangan (px).
     */
    var haloColor: Int = 0
        set(v) { field = v; haloShader = null; invalidate() }
    var haloDy: Float = 0f
        set(v) { field = v; invalidate() }
    private var haloShader: LinearGradient? = null

    fun setShimmer(v: Boolean) {
        active = v
        shader = null
        if (v && isAttachedToWindow) {
            startAnim()
        } else {
            stopAnim()
            paint.shader = null
            invalidate()
        }
    }

    private fun startAnim() {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = shimmerMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stopAnim() {
        anim?.cancel()
        anim = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (active) startAnim()
    }

    override fun onDetachedFromWindow() {
        stopAnim()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        if (haloColor != 0 && width > 0 && text.isNotEmpty()) {
            // Lintasan 1: bayangan polos (shader satu warna) turun sedikit; lintasan 2 di bawah menggambar teks kilau di atasnya.
            val hs = haloShader ?: LinearGradient(
                0f, 0f, 1f, 0f, haloColor, haloColor, Shader.TileMode.CLAMP
            ).also { haloShader = it }
            val keep = paint.shader
            paint.shader = hs
            c.save()
            c.translate(0f, haloDy)
            super.onDraw(c)
            c.restore()
            paint.shader = keep
        }
        if (active && width > 0) {
            val w = width.toFloat()
            val bandW = maxOf(w * 0.75f, 170f * resources.displayMetrics.density)
            val midColor = blendArgb(shimmerBase, shimmerHi, 0.55f)
            val sh = shader ?: LinearGradient(
                0f, 0f, bandW, 0f,
                intArrayOf(shimmerBase, midColor, shimmerHi, midColor, shimmerBase),
                floatArrayOf(0f, 0.28f, 0.5f, 0.72f, 1f),
                Shader.TileMode.CLAMP
            ).also { shader = it }
            m.setTranslate(-bandW + t * (w + bandW * 1.35f), 0f)
            sh.setLocalMatrix(m)
            paint.shader = sh
        }
        super.onDraw(c)
    }

    private fun blendArgb(c1: Int, c2: Int, ratio: Float): Int {
        val f = ratio.coerceIn(0f, 1f)
        val a = ((c1 ushr 24) + (((c2 ushr 24) - (c1 ushr 24)) * f)).toInt()
        val r = (((c1 shr 16) and 0xFF) + ((((c2 shr 16) and 0xFF) - ((c1 shr 16) and 0xFF)) * f)).toInt()
        val g = (((c1 shr 8) and 0xFF) + ((((c2 shr 8) and 0xFF) - ((c1 shr 8) and 0xFF)) * f)).toInt()
        val b = ((c1 and 0xFF) + (((c2 and 0xFF) - (c1 and 0xFF)) * f)).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}

/** Logo aplikasi (asterisk hitam; gambar dari res/drawable-nodpi/logo_mark.png). */
class LogoView(ctx: Context) : android.widget.ImageView(ctx) {
    init {
        setImageResource(R.drawable.logo_mark)
        scaleType = ScaleType.FIT_CENTER
    }
}
