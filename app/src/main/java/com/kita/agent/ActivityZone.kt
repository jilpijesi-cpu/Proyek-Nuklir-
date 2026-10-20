package com.kita.agent

import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

// Panel aktivitas AI: header ringkas, timeline yang bisa digulir, status aktif, dan laporan akhir.
// Teks status tetap berasal dari event nyata dan ditahan TextGate agar tidak berkedip.

internal class CenterPanel(private val act: MainActivity) : FrameLayout(act) {
    private var gate = TextGate()
    private var reportShown = false

    // Teks berkilau (ShimmerText) + ikon yang berganti sesuai aktivitas AI; keduanya memudar bersama di tempat yang sama.
    private val lineTv = ShimmerText(act).apply {
        textSize = 18f
        setTextColor(Palette.INK)
        shimmerBase = 0xFF000000.toInt()
        shimmerHi = 0xFF9E9E9E.toInt()
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
        maxWidth = (act.resources.displayMetrics.widthPixels * 0.6f).toInt()
        setShimmer(false)
    }
    private val iconSlot = FrameLayout(act)
    private val lineRow = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        alpha = 0f
        addView(iconSlot, LinearLayout.LayoutParams(act.dp(44), act.dp(44)))
        addView(lineTv, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = act.dp(12) })
    }
    private val kindByText = HashMap<String, Int>()
    private var iconKind = -99

    private fun setIcon(kind: Int) {
        if (kind == iconKind) return
        iconKind = kind
        iconSlot.removeAllViews()
        // Ikon mengambang tanpa lencana/latar; semua ikon memakai hitam pekat yang seragam.
        iconSlot.background = null
        val ink = 0xFF000000.toInt()
        if (kind == KIND_DOTS) {
            iconSlot.addView(
                DotsView(act),
                FrameLayout.LayoutParams(act.dp(32), act.dp(32), Gravity.CENTER)
            )
        } else {
            iconSlot.addView(
                IconView(act, kind, ink).apply { working = kind != IconView.CLOCK },
                FrameLayout.LayoutParams(act.dp(28), act.dp(28), Gravity.CENTER)
            )
        }
    }

    private val chipTv = TextView(act).apply {
        textSize = 12.5f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(act.dp(10), act.dp(3), act.dp(10), act.dp(3))
    }
    private val statsTv = TextView(act).apply {
        textSize = 12.5f
        setTextColor(Palette.TEXT_SOFT)
        setSingleLine(true)
    }
    private val summaryTv = TextView(act).apply {
        textSize = 14f
        setTextColor(Palette.TEXT_MAIN)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val changesTv = TextView(act).apply {
        textSize = 12.5f
        setTextColor(Palette.TEXT_SOFT)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val reportBox = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        alpha = 0f
        background = GradientDrawable().apply {
            setColor(Palette.SURFACE)
            cornerRadius = act.dp(20).toFloat()
            setStroke(act.dp(1), Palette.BORDER)
        }
        setPadding(act.dp(16), act.dp(8), act.dp(16), act.dp(8))
        val top = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(chipTv, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(View(act), LinearLayout.LayoutParams(0, 1, 1f))
            addView(statsTv, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        addView(top, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(summaryTv, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = act.dp(6) })
        addView(changesTv, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = act.dp(3) })
    }

    private val release = Runnable {
        gate.release(SystemClock.uptimeMillis())?.let { applyLine(it) }
        scheduleRelease()
    }

    init {
        addView(lineRow, LayoutParams(MATCH, MATCH))
        addView(reportBox, LayoutParams(MATCH, MATCH).apply { topMargin = act.dp(2); bottomMargin = act.dp(4) })
        lineRow.setOnClickListener {
            val d = act.curDetail
            if (d.isNotEmpty()) act.showDetailDialog(lineTv.text.toString(), d)
        }
    }

    private fun scheduleRelease() {
        removeCallbacks(release)
        gate.nextDelay(SystemClock.uptimeMillis())?.let { postDelayed(release, it) }
    }

    /** Ganti teks dengan pudar-silang di tempat yang sama: teks tidak bergeser. */
    private fun applyLine(t: String) {
        val kind = kindByText[t] ?: KIND_DOTS
        lineRow.animate().cancel()
        val show = {
            lineTv.text = t
            setIcon(kind)
            lineTv.setShimmer(kind != IconView.CLOCK)
            lineRow.animate().alpha(1f).setDuration(200).start()
        }
        if (lineRow.alpha < 0.05f || lineTv.text.isEmpty()) {
            show()
        } else {
            lineRow.animate().alpha(0f).setDuration(140).withEndAction { show() }.start()
        }
    }

    /** Laporan akhir: ditampilkan setelah tugas benar-benar selesai, menggantikan baris penjelasan. */
    fun showReport(r: Report) {
        removeCallbacks(release)
        lineRow.animate().cancel()
        lineRow.animate().alpha(0f).setDuration(160).start()
        lineTv.setShimmer(false)
        val c = when (r.status) {
            ReportStatus.SUCCESS -> Palette.GREEN
            ReportStatus.FAILED -> Palette.RED
            ReportStatus.STOPPED -> Palette.SLATE
            ReportStatus.UNVERIFIED -> Palette.AMBER
        }
        chipTv.text = r.title
        chipTv.setTextColor(darkOf(c))
        chipTv.background = GradientDrawable().apply {
            setColor(tint(c, 0.84f))
            cornerRadius = act.dp(12).toFloat()
        }
        statsTv.text = r.stats
        summaryTv.text = r.summary
        changesTv.text = r.changes.joinToString(" · ")
        reportShown = true
        reportBox.animate().cancel()
        reportBox.visibility = View.VISIBLE
        reportBox.animate().alpha(1f).setStartDelay(120).setDuration(260).start()
    }

    /** Siapkan untuk putaran baru. */
    fun reset() {
        removeCallbacks(release)
        gate = TextGate()
        reportShown = false
        reportBox.animate().cancel()
        reportBox.alpha = 0f
        reportBox.visibility = View.GONE
        lineRow.animate().cancel()
        lineTv.setShimmer(false)
        lineTv.text = ""
        lineRow.alpha = 0f
        kindByText.clear()
        iconKind = -99
        iconSlot.removeAllViews()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(release)
        super.onDetachedFromWindow()
    }
}

internal class ActivityZone(private val act: MainActivity) : LinearLayout(act) {
    /** Feed tetap menyimpan detail untuk dialog, tetapi tidak lagi mengambil ruang layar utama. */
    val feed = ActivityFeed(act)
    val center = CenterPanel(act)

    // Satu baris status live (ikon + teks berkilau + panah). Diketuk -> terminal live terbuka di bawahnya.
    private val live = LiveStatusLine(act).apply {
        terminal.onApprove = { this@ActivityZone.act.confirmDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.performClick() }
        terminal.onReject = { this@ActivityZone.act.confirmDialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.performClick() }
        terminal.onOpenFull = { step ->
            val a = this@ActivityZone.act
            a.showComputerPanel(a.agentSteps.groups.value, step.id)
        }
    }

    // Daftar langkah polos (tanpa kotak) di bawah baris status.
    private val checklist = StepChecklist(act)

    init {
        orientation = VERTICAL
        visibility = View.GONE
        addView(live, LayoutParams(MATCH, WRAP).apply {
            setMargins(0, act.dp(10), 0, act.dp(4))
        })
        addView(checklist, LayoutParams(MATCH, WRAP).apply {
            setMargins(0, 0, 0, act.dp(4))
        })
    }

    /** Perbarui terminal live dengan daftar langkah terbaru (perintah + keluaran yang mengalir). */
    fun renderSteps(groups: List<AgentStepGroup>) {
        live.render(groups)
        checklist.render(groups)
    }

    /** Ganti teks status; [kind] menentukan ikon (KIND_DOTS = titik berpikir, selain itu ikon IconView.*). */
    fun showCompactLine(raw: String, kind: Int = KIND_DOTS) {
        live.setLine(raw, kind)
    }

    fun refreshHistory() {
        // Riwayat kini hidup di terminal live; tidak ada penghitung terpisah.
    }

    fun reveal() {
        // Sengaja tanpa animasi: posisi aktivitas stabil dan tidak mengganggu teks chat.
        alpha = 1f
        visibility = View.VISIBLE
    }

    fun beginRun() {
        attachInline()
        feed.clear()
        center.reset()
        live.begin("")
        checklist.render(emptyList())
        reveal()
    }

    private fun attachInline() {
        if (parent !== act.chatBox) {
            (parent as? android.view.ViewGroup)?.removeView(this)
            act.chatBox.addView(this)
        }
    }

    fun showReport(r: Report) {
        // Ringkas: "Berhasil · 3 langkah" (ringkasan panjang sudah ada di jawaban AI). Hasilnya terbaca dari teks;
        // titik dan kilau tetap hidup.
        val text = if (r.stats.isBlank()) r.title else "${r.title} · ${r.stats}"
        live.finish(text)
    }

    fun reset() {
        feed.clear()
        center.reset()
        live.reset()
        checklist.render(emptyList())
        visibility = View.GONE
        (parent as? android.view.ViewGroup)?.removeView(this)
    }

    fun collapseIfEmpty() {
        if (feed.cardCount == 0) visibility = View.GONE
    }
}

/** Tampilkan laporan akhir dari fakta nyata putaran yang baru selesai. Panggil di thread UI setelah semua langkah selesai. */
internal fun MainActivity.showRunReport() {
    val r = buildReport(runLog, System.currentTimeMillis() - runLog.startedAt)
    if (r == null) {
        zone.collapseIfEmpty()
        return
    }
    zone.reveal()
    zone.showReport(r)
}
