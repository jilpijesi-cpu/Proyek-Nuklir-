package com.kita.agent

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.sin

// Baris status AI "live" bergaya Claude: satu baris berisi ikon yang berganti sesuai pekerjaan AI, teks berkilau
// yang memudar saat berganti, dan panah. Diketuk -> terbuka terminal gelap yang menampilkan perintah + keluarannya
// secara live (diwarnai, bisa dipilih per langkah, bisa disalin). Data berasal dari AgentStepStore (renderSteps).
// Logika murni pewarnaan ada di TerminalLex.kt (teruji); berkas ini hanya tampilan.

internal object Live {
    // Ukuran teks status live AI diperbesar (18sp) agar lebih mencolok dibanding teks chat (16sp) dan daftar langkah (15sp).
    const val TEXT_SIZE_SP = 18f

    // Baris status
    val ORANGE = 0xFFD97757.toInt()      // aksen hangat (titik "berpikir" dan ikon kerja)
    val TEXT_SHINE = 0xFF3F3E3B.toInt()  // pita gelap yang menyapu ke kanan (judul header status)

    // Baris status di feed: tanpa kartu/pengurung, langsung di atas latar biru chat.
    // Warna dasar perak-biru terang (90%) berpadu dengan pita kilau putih penuh (100%) sehingga sapuan cahaya tampak hidup dan kontras.
    val LINE_BASE = 0xE6C6E4FF.toInt()   // perak-biru lembut: tegas di atas bayangan namun kontras saat disapu kilau putih
    val LINE_SHINE = 0xFFFFFFFF.toInt()  // pita putih murni 100% yang menyapu ke kanan
    val LINE_TEXT = 0xFFFFFFFF.toInt()   // warna teks dasar (kilau bergerak di atasnya)
    val LINE_CHEVRON = 0xF2FFFFFF.toInt()
    val LINE_HALO = 0x59001E42.toInt()   // bayangan biru tua di bawah teks agar tepi huruf tajam dan menonjol

    // Terminal
    val BG = 0xFF0F172A.toInt()
    val BAR = 0xFF162033.toInt()
    val LINE = 0xFF1E293B.toInt()
    val FG = 0xFFE2E8F0.toInt()
    val DIM = 0xFF64748B.toInt()
    val DIM_HI = 0xFF94A3B8.toInt()
    val GREEN = 0xFF4ADE80.toInt()
    val RED = 0xFFF87171.toInt()
    val AMBER = 0xFFFBBF24.toInt()
    val CYAN = 0xFF67E8F9.toInt()
    val VIOLET = 0xFFC4B5FD.toInt()
    val ORANGE_SOFT = 0xFFFDBA74.toInt()
    val ROSE = 0xFFFDA4AF.toInt()
    val ADD_BG = 0x2216A34A
    val DEL_BG = 0x22EF4444

    fun colorOf(k: TermKind): Int = when (k) {
        TermKind.PLAIN -> FG
        TermKind.DIM -> DIM
        TermKind.CMD -> CYAN
        TermKind.FLAG -> ORANGE_SOFT
        TermKind.STR -> GREEN
        TermKind.OP -> VIOLET
        TermKind.VAR -> AMBER
        TermKind.KEYWORD -> VIOLET
        TermKind.NUM -> ORANGE_SOFT
        TermKind.PATH -> VIOLET
        TermKind.TAG -> CYAN
        TermKind.OK -> GREEN
        TermKind.ERR -> RED
        TermKind.WARN -> AMBER
        TermKind.TASK -> DIM_HI
        TermKind.ADD -> GREEN
        TermKind.DEL -> ROSE
        TermKind.PROMPT -> GREEN
    }
}

private const val SPAN_FLAGS = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

// Pergantian teks status: teks lama hilang cepat dulu, teks baru menyusul sesaat kemudian (tidak bertumpuk jadi bayangan kabur).
private const val FADE_OUT_MS = 110L
private const val FADE_IN_DELAY_MS = 60L
private const val FADE_IN_MS = 230L

// ------------------------------------------------------------------ Ikon "berpikir": tiga titik mengorbit

/**
 * Tiga titik hangat yang mengorbit pada elips mendatar: yang di depan lebih besar, yang di belakang lebih kecil, kecepatannya
 * sedikit tidak rata sehingga kadang bertumpuk dan kadang merenggang (seperti ikon berpikir pada video acuan).
 * Berhenti sendiri saat lepas dari layar.
 */
internal class OrbitDotsView(ctx: Context, private val dotColor: Int = Live.ORANGE) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private var anim: ValueAnimator? = null

    private fun start() {
        if (anim != null) return
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        anim = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        p.color = dotColor
        val cx = width / 2f
        val cy = height / 2f
        val rx = width * 0.34f
        val ry = height * 0.20f
        val twoPi = (2.0 * Math.PI).toFloat()
        for (i in 0..2) {
            // Gelombang kedua membuat gerak tidak seragam, tetap berulang mulus tiap putaran.
            val a = phase * twoPi + i * twoPi / 3f + 0.7f * sin(phase * twoPi * 2f + i * 2.1f)
            val depth = 0.5f + 0.5f * sin(a) // 0 = di belakang, 1 = di depan
            val r = height * (0.085f + 0.075f * depth)
            c.drawCircle(cx + rx * cos(a), cy + ry * sin(a), r, p)
        }
    }
}

/** Kotak kursor yang berkedip. */
private class BlinkBlock(ctx: Context, color: Int) : View(ctx) {
    private var anim: ObjectAnimator? = null

    init {
        setBackgroundColor(color)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim?.cancel()
        anim = ObjectAnimator.ofFloat(this, View.ALPHA, 1f, 0.08f).apply {
            duration = 520
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        anim = null
        alpha = 1f
        super.onDetachedFromWindow()
    }
}

/** ScrollView dengan tinggi maksimum; di dalam ScrollView lain ia merebut gestur geser selama masih bisa digulir. */
private class CappedScroll(ctx: Context) : ScrollView(ctx) {
    var maxHeightPx: Int = 0
        set(v) {
            field = v
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = if (maxHeightPx > 0) MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST) else heightMeasureSpec
        super.onMeasure(widthMeasureSpec, spec)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && (canScrollVertically(1) || canScrollVertically(-1))) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return super.onInterceptTouchEvent(ev)
    }
}

// ------------------------------------------------------------------ Terminal live

internal class LiveTerminal(private val act: MainActivity) : LinearLayout(act) {
    var onApprove: (() -> Unit)? = null
    var onReject: (() -> Unit)? = null
    var onOpenFull: ((AgentStep) -> Unit)? = null

    private val ui = Handler(Looper.getMainLooper())
    private var steps: List<AgentStep> = emptyList()
    private var pinnedId: String? = null
    private var shownId = ""
    private var shownLines: List<String> = emptyList()
    private var chipSig = ""
    private var big = false
    private var renderedLines = 0
    private var lastH = 0
    private val queue = ArrayList<TermLine>()

    private fun dotView(color: Int): View = View(act).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    private fun pill(label: String, fg: Int, bg: Int, onClick: () -> Unit): TextView = TextView(act).apply {
        text = label
        textSize = 12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(fg)
        gravity = Gravity.CENTER
        background = pressable(act.rounded(bg, 12))
        setPadding(act.dp(11), act.dp(4), act.dp(11), act.dp(4))
        setOnClickListener { onClick() }
    }

    private val titleTv = TextView(act).apply {
        typeface = Typeface.MONOSPACE
        textSize = 11.5f
        setTextColor(Live.DIM_HI)
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
    }
    private val copyTv: TextView = pill("Salin", Live.FG, Live.LINE) { copyCurrent() }
    private val sizeTv: TextView = pill("Besar", Live.FG, Live.LINE) { toggleBig() }

    private val topBar = LinearLayout(act).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(act.dp(12), act.dp(8), act.dp(10), act.dp(8))
        background = GradientDrawable().apply {
            setColor(Live.BAR)
            val r = act.dp(16).toFloat()
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        addView(dotView(0xFFF87171.toInt()), LinearLayout.LayoutParams(act.dp(8), act.dp(8)).apply { marginEnd = act.dp(5) })
        addView(dotView(0xFFFBBF24.toInt()), LinearLayout.LayoutParams(act.dp(8), act.dp(8)).apply { marginEnd = act.dp(5) })
        addView(dotView(0xFF4ADE80.toInt()), LinearLayout.LayoutParams(act.dp(8), act.dp(8)).apply { marginEnd = act.dp(10) })
        addView(titleTv, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(sizeTv, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = act.dp(8) })
        addView(copyTv, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = act.dp(6) })
    }

    private val chipsRow = LinearLayout(act).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(act.dp(10), act.dp(6), act.dp(10), act.dp(2))
    }
    private val chipsScroll = HorizontalScrollView(act).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        visibility = View.GONE
        addView(chipsRow, FrameLayout.LayoutParams(WRAP, WRAP))
    }

    private val codeTv = TextView(act).apply {
        typeface = Typeface.MONOSPACE
        textSize = 12f
        setTextColor(Live.FG)
        setLineSpacing(0f, 1.2f)
    }
    private val cursorRow = LinearLayout(act).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, act.dp(2), 0, 0)
        addView(TextView(act).apply {
            text = "❯"
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(Live.GREEN)
        }, LinearLayout.LayoutParams(WRAP, WRAP))
        addView(BlinkBlock(act, Live.GREEN), LinearLayout.LayoutParams(act.dp(7), act.dp(14)).apply { marginStart = act.dp(6) })
    }
    private val bodyBox = LinearLayout(act).apply {
        orientation = VERTICAL
        setPadding(act.dp(14), act.dp(10), act.dp(14), act.dp(10))
        addView(codeTv, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(cursorRow, LinearLayout.LayoutParams(WRAP, WRAP))
    }
    private val scroll = CappedScroll(act).apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        maxHeightPx = act.dp(250)
        addView(bodyBox, FrameLayout.LayoutParams(MATCH, WRAP))
    }

    private val stateTv = TextView(act).apply {
        typeface = Typeface.MONOSPACE
        textSize = 11.5f
        setTextColor(Live.DIM_HI)
        setSingleLine(true)
    }
    private val rejectTv: TextView = pill("Tolak", Live.FG, Live.LINE) { onReject?.invoke() }
    private val approveTv: TextView = pill("Setuju", 0xFF0F172A.toInt(), Live.GREEN) { onApprove?.invoke() }
    private val footer = LinearLayout(act).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(act.dp(14), act.dp(7), act.dp(10), act.dp(8))
        addView(stateTv, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(rejectTv, LinearLayout.LayoutParams(WRAP, WRAP))
        addView(approveTv, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = act.dp(6) })
    }

    private val drip = object : Runnable {
        override fun run() {
            if (queue.isEmpty()) return
            val follow = nearBottom()
            val n = minOf(queue.size, maxOf(1, (queue.size + 5) / 6))
            for (i in 0 until n) appendLine(queue.removeAt(0))
            trimOld()
            if (follow) scrollToBottom()
            notifyGrowth()
            if (queue.isNotEmpty()) ui.postDelayed(this, 45)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val st = current()
            if (st != null) updateState(st)
            ui.postDelayed(this, 1000)
        }
    }

    init {
        orientation = VERTICAL
        visibility = View.GONE
        background = act.rounded(Live.BG, 16)
        addView(topBar, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(chipsScroll, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(scroll, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(View(act).apply { setBackgroundColor(Live.LINE) }, LinearLayout.LayoutParams(MATCH, act.dp(1)))
        addView(footer, LinearLayout.LayoutParams(MATCH, WRAP))
        titleTv.setOnLongClickListener {
            current()?.let { s -> onOpenFull?.invoke(s) }
            true
        }
        showIdle()
    }

    // ---------------------------------------------------------- API

    /** Terima daftar langkah terbaru. Murah bila terminal sedang tertutup. */
    fun render(groups: List<AgentStepGroup>) {
        steps = groups.flatMap { it.steps }.sortedBy { it.startedAt ?: Long.MAX_VALUE }
        if (visibility == View.VISIBLE) refresh(animate = true)
    }

    fun onOpened() {
        shownId = ""
        refresh(animate = false)
        ui.removeCallbacks(ticker)
        ui.postDelayed(ticker, 1000)
    }

    fun onClosed() {
        ui.removeCallbacks(ticker)
        ui.removeCallbacks(drip)
        queue.clear()
        shownId = ""
    }

    fun reset() {
        ui.removeCallbacks(ticker)
        ui.removeCallbacks(drip)
        queue.clear()
        steps = emptyList()
        pinnedId = null
        shownId = ""
        shownLines = emptyList()
        chipSig = ""
        big = false
        sizeTv.text = "Besar"
        scroll.maxHeightPx = act.dp(250)
        chipsRow.removeAllViews()
        chipsScroll.visibility = View.GONE
        showIdle()
    }

    // ---------------------------------------------------------- Isi

    private fun current(): AgentStep? {
        val id = pinnedId
        if (id != null) {
            val s = steps.firstOrNull { it.id == id }
            if (s != null) return s
        }
        return steps.lastOrNull()
    }

    private fun refresh(animate: Boolean) {
        val st = current()
        updateChips(st)
        if (st == null) {
            showIdle()
            return
        }
        titleTv.text = st.title
        updateState(st)
        val lines = termLinesFor(st)
        val plain = lines.map { it.text }
        val add = if (animate && st.id == shownId) newLinesAfter(shownLines, plain) else null
        if (add != null) {
            if (add.isNotEmpty()) {
                queue.addAll(lines.subList(lines.size - add.size, lines.size))
                ui.removeCallbacks(drip)
                ui.post(drip)
            }
        } else {
            replaceAll(lines)
        }
        shownId = st.id
        shownLines = plain
    }

    private fun showIdle() {
        titleTv.text = "terminal"
        stateTv.text = ""
        rejectTv.visibility = View.GONE
        approveTv.visibility = View.GONE
        cursorRow.visibility = View.VISIBLE
        shownId = ""
        shownLines = emptyList()
        replaceAll(listOf(TermLine("Menunggu perintah dari AI…", emptyList(), TermKind.DIM)))
    }

    private fun updateState(st: AgentStep) {
        val now = System.currentTimeMillis()
        val running = st.status == AgentStepStatus.RUNNING
        val waiting = st.status == AgentStepStatus.WAITING
        cursorRow.visibility = if (running || waiting) View.VISIBLE else View.GONE
        val needApproval = st.isApprovalWaiting
        rejectTv.visibility = if (needApproval) View.VISIBLE else View.GONE
        approveTv.visibility = if (needApproval) View.VISIBLE else View.GONE
        when (st.status) {
            AgentStepStatus.RUNNING -> {
                stateTv.text = "● berjalan  " + formatElapsed(now - (st.startedAt ?: now))
                stateTv.setTextColor(Live.ORANGE)
            }
            AgentStepStatus.WAITING -> {
                stateTv.text = "○ menunggu izin"
                stateTv.setTextColor(Live.AMBER)
            }
            AgentStepStatus.SUCCESS -> {
                stateTv.text = "✓ selesai  " + formatElapsed(st.durationMs)
                stateTv.setTextColor(Live.GREEN)
            }
            AgentStepStatus.FAILED -> {
                stateTv.text = "✕ gagal  " + formatElapsed(st.durationMs)
                stateTv.setTextColor(Live.RED)
            }
        }
    }

    private fun updateChips(sel: AgentStep?) {
        val last = steps.takeLast(24)
        chipsScroll.visibility = if (steps.size >= 2) View.VISIBLE else View.GONE
        val sig = last.joinToString("|") { it.id + it.status.ordinal } + "#" + (sel?.id ?: "") + "#" + (pinnedId ?: "")
        if (sig == chipSig) return
        chipSig = sig
        val keepX = chipsScroll.scrollX
        chipsRow.removeAllViews()
        val base = steps.size - last.size
        for ((i, s) in last.withIndex()) {
            val on = s.id == sel?.id
            val glyph: String
            val gc: Int
            when (s.status) {
                AgentStepStatus.WAITING -> { glyph = "○"; gc = Live.AMBER }
                AgentStepStatus.RUNNING -> { glyph = "●"; gc = Live.ORANGE }
                AgentStepStatus.SUCCESS -> { glyph = "✓"; gc = Live.GREEN }
                AgentStepStatus.FAILED -> { glyph = "✕"; gc = Live.RED }
            }
            val label = SpannableStringBuilder(glyph + " " + chipLabel(base + i, s.title, 18))
            label.setSpan(ForegroundColorSpan(gc), 0, glyph.length, SPAN_FLAGS)
            val chip = TextView(act).apply {
                text = label
                textSize = 11.5f
                setSingleLine(true)
                setTextColor(if (on) Live.FG else Live.DIM_HI)
                background = pressable(act.rounded(if (on) Live.LINE else Color.TRANSPARENT, 12))
                setPadding(act.dp(10), act.dp(4), act.dp(10), act.dp(4))
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    pinnedId = if (s.id == steps.lastOrNull()?.id) null else s.id
                    refresh(animate = false)
                }
            }
            chipsRow.addView(chip, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = act.dp(4) })
        }
        if (pinnedId == null) chipsScroll.post { chipsScroll.fullScroll(View.FOCUS_RIGHT) }
        else chipsScroll.post { chipsScroll.scrollTo(keepX, 0) }
    }

    // ---------------------------------------------------------- Menggambar baris

    private fun styled(line: TermLine): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        sb.append(line.text).append('\n')
        val end = line.text.length
        if (end > 0) {
            sb.setSpan(ForegroundColorSpan(Live.colorOf(line.whole)), 0, end, SPAN_FLAGS)
            for (s in line.spans) {
                if (s.start >= 0 && s.end <= end && s.start < s.end) {
                    sb.setSpan(ForegroundColorSpan(Live.colorOf(s.kind)), s.start, s.end, SPAN_FLAGS)
                }
            }
        }
        when (line.whole) {
            TermKind.ADD -> sb.setSpan(DiffBg(Live.ADD_BG), 0, sb.length, SPAN_FLAGS)
            TermKind.DEL -> sb.setSpan(DiffBg(Live.DEL_BG), 0, sb.length, SPAN_FLAGS)
            else -> Unit
        }
        return sb
    }

    private fun appendLine(line: TermLine) {
        codeTv.append(styled(line))
        renderedLines++
    }

    private fun replaceAll(lines: List<TermLine>) {
        ui.removeCallbacks(drip)
        queue.clear()
        val all = SpannableStringBuilder()
        for (l in lines) all.append(styled(l))
        codeTv.setText(all, TextView.BufferType.EDITABLE)
        renderedLines = lines.size
        scrollToBottom()
        notifyGrowth()
    }

    /** Buang baris tertua agar tampilan tetap ringan saat keluaran sangat panjang. */
    private fun trimOld() {
        if (renderedLines <= 420) return
        val e = codeTv.editableText ?: return
        var pos = 0
        var removed = 0
        while (removed < 120) {
            val nl = e.indexOf('\n', pos)
            if (nl < 0) break
            pos = nl + 1
            removed++
        }
        if (pos > 0) {
            e.delete(0, pos)
            renderedLines -= removed
        }
    }

    private fun nearBottom(): Boolean {
        val c = scroll.getChildAt(0) ?: return true
        return scroll.scrollY + scroll.height >= c.height - act.dp(28)
    }

    private fun scrollToBottom() {
        scroll.post {
            val c = scroll.getChildAt(0)
            if (c != null) scroll.smoothScrollTo(0, (c.height - scroll.height).coerceAtLeast(0))
        }
    }

    /** Beri tahu chat agar ikut menggulir saat terminal bertambah tinggi. */
    private fun notifyGrowth() {
        scroll.post {
            if (scroll.height != lastH) {
                lastH = scroll.height
                act.scrollDown()
            }
        }
    }

    // ---------------------------------------------------------- Aksi

    private fun copyCurrent() {
        val text = shownLines.joinToString("\n")
        if (text.isBlank()) return
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        act.copyText("terminal", text)
        copyTv.text = "Tersalin ✓"
        ui.postDelayed({ copyTv.text = "Salin" }, 1400)
    }

    private fun toggleBig() {
        big = !big
        sizeTv.text = if (big) "Kecil" else "Besar"
        scroll.maxHeightPx = act.dp(if (big) 460 else 250)
        scrollToBottom()
        notifyGrowth()
    }

    override fun onDetachedFromWindow() {
        ui.removeCallbacks(ticker)
        ui.removeCallbacks(drip)
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == View.VISIBLE) {
            ui.removeCallbacks(ticker)
            ui.postDelayed(ticker, 1000)
        }
    }
}

// ------------------------------------------------------------------ Baris status

internal class LiveStatusLine(private val act: MainActivity) : LinearLayout(act) {
    val terminal = LiveTerminal(act)

    private val ui = Handler(Looper.getMainLooper())
    private var gate = TextGate(700L)
    private var iconKind = Int.MIN_VALUE
    private var iconTint = 0
    private var finished = false
    private var expanded = false
    private var breathing = false
    private var breatheAnim: ObjectAnimator? = null
    private var heightAnim: ValueAnimator? = null
    private var availPx = 0 // lebar (px) yang tersedia untuk teks; dipakai memotong di batas kata

    private val iconSlot = FrameLayout(act)
    // Gaya huruf live AI: lebih besar (18sp) dan tegas (bold-medium) dengan sapuan kilau kontras tinggi agar lebih mencolok dari teks lain.
    private val label = ShimmerText(act).apply {
        textSize = Live.TEXT_SIZE_SP
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        letterSpacing = 0.014f
        shimmerBase = Live.LINE_BASE
        shimmerHi = Live.LINE_SHINE
        shimmerMs = 1750L
        setTextColor(Live.LINE_TEXT)
        haloColor = Live.LINE_HALO
        haloDy = act.dp(1).toFloat()
        setPadding(0, act.dp(2), 0, act.dp(2)) // ruang untuk bayangan; sama dengan oldLabel agar tidak bergeser saat pudar-silang
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
        alpha = 0f
        setShimmer(false)
    }

    // Teks lama yang memudar di tempat yang sama saat teks berganti (pudar-silang, tanpa bergeser).
    private val oldLabel = TextView(act).apply {
        textSize = Live.TEXT_SIZE_SP
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        letterSpacing = 0.014f
        setTextColor(Live.LINE_BASE)
        setPadding(0, act.dp(2), 0, act.dp(2))
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
        alpha = 0f
    }
    private val textBox = FrameLayout(act).apply {
        addView(oldLabel, FrameLayout.LayoutParams(WRAP, WRAP))
        addView(label, FrameLayout.LayoutParams(WRAP, WRAP))
    }
    private val chevron = IconView(act, IconView.CHEVRON, Live.LINE_CHEVRON)

    // Tanpa kartu, tanpa latar, tanpa garis: hanya ikon + teks + panah di atas latar chat. Panah menempel di ujung teks.
    private val head = LinearLayout(act).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = act.dp(44)
        setPadding(act.dp(14), act.dp(6), act.dp(12), act.dp(6))
        background = null
        addView(iconSlot, LinearLayout.LayoutParams(act.dp(36), act.dp(32)))
        addView(textBox, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = act.dp(8) })
        addView(chevron, LinearLayout.LayoutParams(act.dp(22), act.dp(22)).apply { marginStart = act.dp(6) })
        setOnClickListener { toggle() }
        addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            // Sisakan ruang untuk ikon dan panah; teks yang kepanjangan dipotong dengan "…".
            val avail = (r - l) - paddingLeft - paddingRight - act.dp(36) - act.dp(8) - act.dp(22) - act.dp(6)
            availPx = avail
            if (avail > 0 && label.maxWidth != avail) post {
                label.maxWidth = avail
                oldLabel.maxWidth = avail
            }
        }
    }

    private val release = Runnable {
        gate.release(SystemClock.uptimeMillis())?.let { applyLine(it) }
        scheduleRelease()
    }

    init {
        orientation = VERTICAL
        addView(head, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(terminal, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = act.dp(6); bottomMargin = act.dp(4) })
    }

    // ---------------------------------------------------------- Teks dan ikon

    /** Satu baris penjelasan aktivitas. Teks sama diabaikan; perubahan beruntun ditahan agar tidak berkedip. */
    fun setLine(raw: String, kind: Int = KIND_DOTS) {
        if (finished) return
        val t = tidyLine(raw, 90)
        if (t.isEmpty()) return
        gate.submit(t, SystemClock.uptimeMillis())?.let { applyLine(it) }
        scheduleRelease()
    }

    private fun scheduleRelease() {
        ui.removeCallbacks(release)
        gate.nextDelay(SystemClock.uptimeMillis())?.let { ui.postDelayed(release, it) }
    }

    private fun applyLine(t: String) = swapText(t)

    /**
     * Ganti teks dengan pudar-silang di tempat yang sama: teks lama memudar sambil teks baru muncul, tanpa bergeser.
     * Baris ini selalu "hidup": titik oranye terus mengorbit dan teks terus berkilau, juga setelah AI selesai atau berhenti.
     */
    private fun swapText(t: String) {
        label.animate().cancel()
        oldLabel.animate().cancel()
        val had = label.alpha > 0.05f && label.text.isNotEmpty()
        if (had) {
            oldLabel.text = label.text
            oldLabel.alpha = label.alpha
            // Teks lama hilang lebih dulu, teks baru menyusul sesaat kemudian: keduanya tidak sempat bertumpuk menjadi bayangan kabur.
            oldLabel.animate().alpha(0f).setStartDelay(0L).setDuration(FADE_OUT_MS).start()
        } else {
            oldLabel.alpha = 0f // animasi sebelumnya dibatalkan di tengah jalan: jangan biarkan teks lama menempel dan menumpuk
        }
        label.text = styled(fitToWidth(t))
        label.setShimmer(true)
        setIcon(KIND_DOTS, Live.ORANGE, true)
        label.alpha = 0f
        label.animate().alpha(1f).setStartDelay(if (had) FADE_IN_DELAY_MS else 0L).setDuration(FADE_IN_MS).start()
    }

    /** Nama file (mis. ApkProvider.kt) tampil dengan huruf monospasi agar mudah dikenali; sisanya tetap huruf biasa. */
    private fun styled(t: String): CharSequence {
        val ranges = fileTokenRanges(t)
        if (ranges.isEmpty()) return t
        val sp = SpannableString(t)
        for (r in ranges) {
            sp.setSpan(TypefaceSpan("monospace"), r.first, r.last + 1, SPAN_FLAGS)
            sp.setSpan(RelativeSizeSpan(0.95f), r.first, r.last + 1, SPAN_FLAGS)
        }
        return sp
    }

    /** Muat teks ke lebar yang tersedia: bila kepanjangan, dipotong di batas kata (bukan di tengah kata) dan diakhiri "…". */
    private fun fitToWidth(t: String): String {
        if (availPx <= 1) return t
        return fitWords(t, (availPx - 1).toFloat()) { s -> Layout.getDesiredWidth(styled(s), label.paint) }
    }

    private fun setIcon(kind: Int, tint: Int, breathe: Boolean) {
        if (kind == iconKind && tint == iconTint) return
        iconKind = kind
        iconTint = tint
        stopBreathe()
        iconSlot.removeAllViews()
        val v: View = if (kind == KIND_DOTS) {
            OrbitDotsView(act, tint)
        } else {
            IconView(act, kind, tint).apply { working = kind == IconView.GEAR && breathe }
        }
        val lp = if (kind == KIND_DOTS) FrameLayout.LayoutParams(act.dp(36), act.dp(24), Gravity.CENTER)
        else FrameLayout.LayoutParams(act.dp(26), act.dp(26), Gravity.CENTER)
        iconSlot.addView(v, lp)
        v.scaleX = 0.4f
        v.scaleY = 0.4f
        v.alpha = 0f
        v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(280)
            .setInterpolator(OvershootInterpolator(2f)).start()
        breathing = breathe && kind != KIND_DOTS && kind != IconView.GEAR
        if (breathing && isAttachedToWindow) startBreathe()
    }

    private fun startBreathe() {
        breatheAnim?.cancel()
        breatheAnim = ObjectAnimator.ofPropertyValuesHolder(
            iconSlot,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.9f, 1.08f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.9f, 1.08f)
        ).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun stopBreathe() {
        breatheAnim?.cancel()
        breatheAnim = null
        iconSlot.scaleX = 1f
        iconSlot.scaleY = 1f
    }

    // ---------------------------------------------------------- Siklus

    /** Putaran baru: kosongkan semuanya dan mulai dari "berpikir". */
    fun begin(firstText: String) {
        ui.removeCallbacks(release)
        gate = TextGate(700L)
        finished = false
        iconKind = Int.MIN_VALUE
        label.animate().cancel()
        oldLabel.animate().cancel()
        label.alpha = 0f
        oldLabel.alpha = 0f
        label.text = ""
        setExpanded(open = false, animate = false)
        terminal.reset()
        setLine(firstText, KIND_DOTS)
    }

    /** Hasil akhir: teks ringkasan menggantikan penjelasan, tetapi titik dan kilau tetap bergerak; panah tetap bisa dibuka. */
    fun finish(text: String) {
        ui.removeCallbacks(release)
        finished = true
        swapText(tidyLine(text, 90))
    }

    fun reset() {
        ui.removeCallbacks(release)
        gate = TextGate(700L)
        finished = false
        iconKind = Int.MIN_VALUE
        label.animate().cancel()
        oldLabel.animate().cancel()
        label.setShimmer(false)
        label.alpha = 0f
        oldLabel.alpha = 0f
        label.text = ""
        stopBreathe()
        iconSlot.removeAllViews()
        setExpanded(open = false, animate = false)
        terminal.reset()
    }

    fun render(groups: List<AgentStepGroup>) = terminal.render(groups)

    // ---------------------------------------------------------- Buka / tutup terminal

    private fun toggle() {
        head.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        setExpanded(open = !expanded, animate = true)
    }

    private fun setExpanded(open: Boolean, animate: Boolean) {
        if (open == expanded && !(open && terminal.visibility != View.VISIBLE)) {
            if (!animate && !open) terminal.visibility = View.GONE
            return
        }
        expanded = open
        chevron.animate().cancel()
        if (animate) chevron.animate().rotation(if (open) 90f else 0f).setDuration(220).start()
        else chevron.rotation = if (open) 90f else 0f
        heightAnim?.cancel()
        heightAnim = null
        terminal.animate().cancel()
        if (open) terminal.onOpened() else terminal.onClosed()
        if (!animate) {
            terminal.alpha = 1f
            terminal.layoutParams.height = WRAP
            terminal.visibility = if (open) View.VISIBLE else View.GONE
            terminal.requestLayout()
            return
        }
        val availW = (width - paddingLeft - paddingRight).coerceAtLeast(act.dp(200))
        terminal.visibility = View.VISIBLE
        terminal.measure(
            View.MeasureSpec.makeMeasureSpec(availW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val full = terminal.measuredHeight
        val from = if (open) 0 else terminal.height
        val to = if (open) full else 0
        terminal.alpha = if (open) 0f else 1f
        terminal.layoutParams.height = from
        terminal.requestLayout()
        terminal.animate().alpha(if (open) 1f else 0f).setDuration(240).start()
        val a = ValueAnimator.ofInt(from, to)
        heightAnim = a
        a.duration = 280
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener {
            terminal.layoutParams.height = it.animatedValue as Int
            terminal.requestLayout()
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (heightAnim !== a) return
                heightAnim = null
                terminal.layoutParams.height = WRAP
                if (open) {
                    terminal.alpha = 1f
                    terminal.requestLayout()
                    act.scrollDown()
                } else {
                    terminal.visibility = View.GONE
                    terminal.requestLayout()
                }
            }
        })
        a.start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (breathing) startBreathe()
    }

    override fun onDetachedFromWindow() {
        ui.removeCallbacks(release)
        breatheAnim?.cancel()
        breatheAnim = null
        super.onDetachedFromWindow()
    }
}
