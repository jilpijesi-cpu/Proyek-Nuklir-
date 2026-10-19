package com.kita.agent

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// Live Agent Activity Feed: wadah kartu bertinggi TETAP di bawah header, memuat paling banyak 2 kartu.
// Kartu baru masuk dari bawah, kartu lama bergeser ke atas, kartu tertua keluar ke atas (animasi 350-500 ms).
// Kartu yang sudah keluar dari tampilan tetap menjalankan tugasnya (baris tetap diperbarui, hanya tidak digambar).
// Kartu jenis unduhan / instalasi / build menampilkan persentase + bar progres HANYA dari keluaran nyata perintah.
// Logika murni (status, progres, antrean) ada di ActivityModel.kt supaya bisa diuji; berkas ini hanya tampilan.

private fun markColorOf(state: StepState, accent: Int): Int = when (state) {
    StepState.RUNNING -> accent
    StepState.WAITING, StepState.DENIED -> Palette.AMBER
    StepState.OK -> Palette.GREEN
    StepState.FAIL -> Palette.RED
    StepState.STOPPED -> Palette.SLATE
}

private fun noteColorOf(state: StepState): Int = when (state) {
    StepState.WAITING, StepState.DENIED -> Palette.BRAND_DEEP
    StepState.FAIL -> Palette.RED
    else -> Palette.TEXT_SOFT
}

private fun roundRect(color: Int, radiusPx: Int, strokeColor: Int? = null, strokePx: Int = 0) =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx.toFloat()
        if (strokeColor != null) setStroke(strokePx, strokeColor)
    }

/** Ikon status. Selesai = centang hijau, gagal = silang merah (tanpa lingkaran); yang lain tetap berlingkar. Berputar saat berjalan. */
internal class StatusMark(ctx: Context) : View(ctx) {
    var kind: StepState = StepState.RUNNING
        set(v) {
            field = v
            if (v == StepState.RUNNING) { if (isAttachedToWindow && !spin.isStarted) spin.start() } else spin.cancel()
            invalidate()
        }
    var color: Int = 0xFF5B5BD6.toInt()
        set(v) { field = v; invalidate() }

    private var angle = 0f
    private val spin = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { angle = it.animatedValue as Float; invalidate() }
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (kind == StepState.RUNNING && !spin.isStarted) spin.start()
    }

    override fun onDetachedFromWindow() {
        spin.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val s = minOf(w, h)
        val cx = w / 2f; val cy = h / 2f
        val r = s * 0.42f
        paint.style = Paint.Style.STROKE
        paint.color = color
        paint.strokeWidth = 2f * resources.displayMetrics.density
        val ring = RectF(cx - r, cy - r, cx + r, cy + r)
        when (kind) {
            StepState.RUNNING -> {
                c.save()
                c.rotate(angle, cx, cy)
                c.drawArc(ring, 0f, 270f, false, paint)
                c.restore()
            }
            StepState.WAITING -> {
                c.drawArc(ring, 0f, 360f, false, paint)
                c.drawLine(cx, cy - r * 0.45f, cx, cy + r * 0.05f, paint)
                c.drawPoint(cx, cy + r * 0.4f, paint)
            }
            StepState.OK -> { // centang polos
                paint.strokeWidth = 2.4f * resources.displayMetrics.density
                c.drawLine(cx - s * 0.36f, cy + s * 0.02f, cx - s * 0.1f, cy + s * 0.3f, paint)
                c.drawLine(cx - s * 0.1f, cy + s * 0.3f, cx + s * 0.4f, cy - s * 0.3f, paint)
            }
            StepState.FAIL -> { // silang polos
                paint.strokeWidth = 2.4f * resources.displayMetrics.density
                c.drawLine(cx - s * 0.3f, cy - s * 0.3f, cx + s * 0.3f, cy + s * 0.3f, paint)
                c.drawLine(cx + s * 0.3f, cy - s * 0.3f, cx - s * 0.3f, cy + s * 0.3f, paint)
            }
            StepState.DENIED, StepState.STOPPED -> {
                c.drawArc(ring, 0f, 360f, false, paint)
                paint.style = Paint.Style.FILL
                c.drawRect(cx - r * 0.3f, cy - r * 0.3f, cx + r * 0.3f, cy + r * 0.3f, paint)
                paint.style = Paint.Style.STROKE
            }
        }
    }
}

/** Bar progres tipis. Determinate = lebar mengikuti angka NYATA; tak-tentu = segmen bergerak tanpa angka; selesai = penuh lalu memudar. */
internal class ProgressBarView(ctx: Context) : View(ctx) {
    private var spec = BarSpec(BarMode.HIDDEN)
    private var color = Palette.BRAND
    private var shown = 0f
    private var sweep = 0f
    private val rect = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val fillAnim = ValueAnimator().apply {
        duration = 260
        addUpdateListener { shown = it.animatedValue as Float; invalidate() }
    }
    private val sweepAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1200
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { sweep = it.animatedValue as Float; invalidate() }
    }

    fun apply(s: BarSpec, accent: Int) {
        val prev = spec
        spec = s
        color = accent
        when (s.mode) {
            BarMode.HIDDEN -> {
                stopSweep(); animate().cancel(); alpha = 0f
            }
            BarMode.INDETERMINATE -> {
                animate().cancel(); alpha = 1f
                if (isAttachedToWindow && !sweepAnim.isStarted) sweepAnim.start()
            }
            BarMode.DETERMINATE -> {
                stopSweep()
                if (prev.mode == BarMode.HIDDEN || prev.complete != s.complete || alpha < 1f) { animate().cancel(); alpha = 1f }
                fillAnim.cancel()
                fillAnim.setFloatValues(shown, s.fraction)
                fillAnim.start()
                if (s.complete) animate().alpha(0f).setStartDelay(900).setDuration(400).start()
            }
        }
        invalidate()
    }

    private fun stopSweep() { if (sweepAnim.isStarted) sweepAnim.cancel() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (spec.mode == BarMode.INDETERMINATE && !sweepAnim.isStarted) sweepAnim.start()
    }

    override fun onDetachedFromWindow() {
        sweepAnim.cancel(); fillAnim.cancel(); animate().cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        if (spec.mode == BarMode.HIDDEN) return
        val w = width.toFloat(); val h = height.toFloat()
        val r = h / 2f
        paint.color = tint(color, 0.82f)
        rect.set(0f, 0f, w, h)
        c.drawRoundRect(rect, r, r, paint)
        paint.color = color
        if (spec.mode == BarMode.DETERMINATE) {
            val fw = (w * shown).coerceAtLeast(if (shown > 0f) h else 0f)
            if (fw > 0f) { rect.set(0f, 0f, fw.coerceAtMost(w), h); c.drawRoundRect(rect, r, r, paint) }
        } else {
            val seg = w * 0.32f
            val x = -seg + (w + seg) * sweep
            c.save(); c.clipRect(0f, 0f, w, h)
            rect.set(x, 0f, x + seg, h); c.drawRoundRect(rect, r, r, paint)
            c.restore()
        }
    }
}

/** Satu langkah timeline: status, tindakan teramati, ringkasan output, durasi, dan detail saat diketuk. */
internal class FeedRow(
    private val act: MainActivity,
    val title: String,
    detail: CharSequence,
    icon: Int,
    val kind: ActivityKind
) : LinearLayout(act) {

    var state = StepState.RUNNING
        private set
    val startedAt = System.currentTimeMillis()
    val isLive: Boolean get() = state == StepState.RUNNING || state == StepState.WAITING

    /** Persentase NYATA terakhir yang terbaca dari keluaran perintah; null bila keluaran tidak pernah melaporkannya. */
    var percent: Int? = null
        private set

    private var durMs = -1L
    private var note = ""
    private val accent = accentFor(icon)
    private val headDetail = detail.toString()
    private var detailCs: CharSequence = detail

    private val mark = StatusMark(act)
    private val titleTv = TextView(act)
    private val noteTv = TextView(act)
    private val detailTv = TextView(act)
    private val bar = ProgressBarView(act)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        background = roundRect(Palette.SURFACE, act.dp(16), Palette.BORDER, act.dp(1))
        setPadding(act.dp(14), act.dp(10), act.dp(14), act.dp(10))

        titleTv.apply {
            text = title
            textSize = 15f
            setTextColor(Palette.TEXT_MAIN)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        detailTv.apply {
            textSize = 12f
            setTextColor(Palette.TEXT_SOFT)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            maxLines = 1
        }
        noteTv.apply {
            textSize = 12.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = act.dp(110)
        }
        val head = LinearLayout(act).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(mark, LayoutParams(act.dp(20), act.dp(20)))
            addView(titleTv, LayoutParams(0, WRAP, 1f).apply { marginStart = act.dp(14); marginEnd = act.dp(8) })
            addView(noteTv, LayoutParams(WRAP, WRAP))
        }
        addView(head, LayoutParams(MATCH, WRAP))
        addView(detailTv, LayoutParams(MATCH, WRAP).apply { topMargin = act.dp(5); marginStart = act.dp(34) })
        addView(bar, LayoutParams(MATCH, act.dp(4)).apply { topMargin = act.dp(9) })
        // Jenis OTHER tidak punya bar sama sekali (tinggi tidak berubah selama kartu hidup); jenis lain menyisakan ruang bar.
        bar.visibility = if (kind == ActivityKind.OTHER) View.GONE else View.VISIBLE
        setOnClickListener { if (detailCs.isNotEmpty()) act.showDetailDialog(title, detailCs.toString()) }
        refresh()
    }

    private fun refresh() {
        mark.color = markColorOf(state, accent)
        mark.kind = state
        val now = System.currentTimeMillis()
        val ms = if (isLive) now - startedAt else durMs
        noteTv.text = if (state == StepState.OK && note.isNotBlank()) note else noteFor(state, note, ms, percent)
        noteTv.setTextColor(noteColorOf(state))
        val preview = detailCs.toString().lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()
        detailTv.text = preview.take(160)
        detailTv.visibility = if (preview.isBlank()) View.GONE else View.VISIBLE
        bar.apply(barSpec(kind, state, percent), accent)
    }

    /** Pembaruan real-time: penghitung waktu di kartu yang sedang berjalan dan belum punya persentase. */
    fun tick(now: Long) {
        if (state == StepState.RUNNING) noteTv.text = noteFor(state, note, now - startedAt, percent)
    }

    /** Keluaran sebagian dari perintah yang sedang berjalan: dipakai untuk detail dan untuk membaca progres NYATA. */
    fun setLive(text: String) {
        if (state != StepState.RUNNING) return
        val t = text.trimEnd()
        detailCs = if (t.isEmpty()) headDetail else headDetail + "\n\n" + t
        parseProgress(t, kind)?.let { percent = it }
        refresh()
    }

    fun setWaiting(w: Boolean) {
        if (!isLive) return
        state = if (w) StepState.WAITING else StepState.RUNNING
        refresh()
    }

    fun finish(s: StepState, noteText: String, body: CharSequence?) {
        state = s
        note = noteText
        durMs = System.currentTimeMillis() - startedAt
        detailCs = if (body != null && body.isNotEmpty()) (if (body.length > 4000) body.subSequence(0, 4000) else body) else headDetail
        refresh()
    }

}

/** Timeline terbuka: semua langkah tetap terlihat, panel menggulir sendiri, dan langkah terbaru disorot. */
internal class ActivityFeed(private val act: MainActivity) : ScrollView(act) {
    private val timeline = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(act.dp(4), act.dp(4), act.dp(4), act.dp(8))
    }
    private val all = ArrayList<FeedRow>()
    private var sealed = false
    private val handler = Handler(Looper.getMainLooper())
    private var ticking = false
    val cardCount: Int get() = all.size

    init {
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        addView(timeline, LayoutParams(MATCH, WRAP))
    }

    private val ticker = object : Runnable {
        override fun run() {
            ticking = false
            if (sealed) return
            val now = System.currentTimeMillis()
            all.forEach { it.tick(now) }
            ticking = true
            handler.postDelayed(this, 1000)
        }
    }

    fun addStep(title: String, detail: CharSequence, icon: Int): FeedRow {
        sealed = false
        val row = FeedRow(act, title, detail, icon, activityKindOfDetail(detail.toString()))
        all.add(row)
        timeline.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            bottomMargin = act.dp(8)
        })
        row.alpha = 0f
        row.translationY = act.dp(10).toFloat()
        row.animate().alpha(1f).translationY(0f).setDuration(220).start()
        act.zone.refreshHistory()
        post { fullScroll(FOCUS_DOWN) }
        startLive()
        return row
    }

    private fun startLive() {
        if (!ticking) {
            ticking = true
            handler.postDelayed(ticker, 1000)
        }
    }

    private fun stopLive() {
        handler.removeCallbacks(ticker)
        ticking = false
    }

    fun seal() {
        if (sealed) return
        all.filter { it.isLive }.forEach { it.finish(StepState.STOPPED, "", null) }
        sealed = true
        stopLive()
    }

    fun clear() {
        stopLive()
        all.forEach { it.animate().cancel() }
        all.clear()
        timeline.removeAllViews()
        sealed = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!sealed && all.isNotEmpty()) startLive()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(ticker)
        ticking = false
        super.onDetachedFromWindow()
    }
}

// ---------------------------------------------------------------- Penghubung ke MainActivity

internal fun MainActivity.feedForNewStep(): ActivityFeed = zone.feed

/** Tutup feed yang sedang berjalan. Panggil di thread UI. */
internal fun MainActivity.sealFeed() {
    zone.feed.seal()
    activeStep = null
}

/** Dialog detail langkah (perintah + keluaran): teks bisa dipilih, tombol Salin. */
internal fun MainActivity.showDetailDialog(title: String, text: String) {
    val tv = TextView(this).apply {
        typeface = Typeface.MONOSPACE
        textSize = 12f
        setTextColor(Palette.TEXT_MAIN)
        setTextIsSelectable(true)
        setPadding(dp(20), dp(8), dp(20), dp(8))
        this.text = text.take(6000)
    }
    val sv = ScrollView(this).apply { addView(tv) }
    AlertDialog.Builder(this)
        .setTitle(title.take(80))
        .setView(sv)
        .setPositiveButton("Tutup", null)
        .setNeutralButton("Salin") { _, _ -> copyText("detail langkah", text); toast("Detail disalin") }
        .show()
}

/**
 * Pulihkan langkah dari riwayat tersimpan (satu giliran model + hasil alatnya) sebagai daftar "Langkah yang dikerjakan"
 * yang sama dengan tampilan saat tugas berjalan. Kartu aktivitas bergerak hanya untuk tugas yang sedang berjalan.
 */
internal fun MainActivity.restoreFeed(calls: List<Call>, results: List<Pair<Call, String>>) {
    if (calls.isEmpty()) return
    // Tampil sama seperti saat tugas berjalan: daftar polos "Langkah yang dikerjakan" dengan judul yang ditulis AI sendiri.
    val steps = calls.mapIndexed { i, c ->
        val out = results.firstOrNull { it.first.id == c.id && c.id.isNotEmpty() }?.second ?: results.getOrNull(i)?.second
        AgentStep(
            id = c.id.ifEmpty { "riwayat-$i" },
            type = AgentStepType.COMMAND,
            title = stepTitleFor(c.name, c.args),
            status = if (stateOfResult(out) == StepState.OK) AgentStepStatus.SUCCESS else AgentStepStatus.FAILED,
            startedAt = i.toLong()
        )
    }
    val card = StepChecklist(this)
    card.render(listOf(AgentStepGroup("Riwayat", "", steps)))
    insertBeforeStatus(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
}
