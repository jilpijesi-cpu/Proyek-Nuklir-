package com.kita.agent

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

// Baris status ringkas dengan ikon monokrom dan detail opsional.
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

internal fun MainActivity.buildStatusRow() {
    statusLead = FrameLayout(this)
    statusTitle = ShimmerText(this).apply {
        textSize = 14f
        setTextColor(Palette.ON_BLUE)
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
        setShimmer(false)
    }
    statusChevron = IconView(this, IconView.CHEVRON, Palette.ON_BLUE)
    statusDetail = TextView(this).apply {
        typeface = Typeface.MONOSPACE
        textSize = 12f
        setTextColor(Palette.TEXT_MAIN)
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#F8FAFC"))
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), Palette.BORDER)
        }
        setPadding(dp(10), dp(8), dp(10), dp(8))
        visibility = View.GONE
    }
    val header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(12), 0, dp(12))
        addView(statusLead, LinearLayout.LayoutParams(dp(44), dp(26)))
        addView(statusTitle, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(statusChevron, LinearLayout.LayoutParams(dp(22), dp(22)))
        setOnClickListener { toggleDetail() }
    }
    statusRoot = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(header)
        addView(statusDetail, LinearLayout.LayoutParams(MATCH, WRAP))
    }
}

internal fun MainActivity.toggleDetail() {
    if (curDetail.isEmpty()) return
    detailOpen = !detailOpen
    statusDetail.visibility = if (detailOpen) View.VISIBLE else View.GONE
    statusChevron.rotation = if (detailOpen) 90f else 0f
    scrollDown()
}

/** Salinan keadaan status, untuk dipulihkan setelah status sementara (persetujuan, hitung mundur, dsb). */
internal class StatusSnap(val kind: Int, val title: String, val detail: String, val quiet: Boolean)

internal fun MainActivity.saveStatus() = StatusSnap(curKind, curTitle, curDetail, curQuiet)

internal fun MainActivity.restoreStatus(s: StatusSnap) = setStatus(s.kind, s.title, s.detail, s.quiet)

/**
 * Ubah ikon + teks status. Aman dipanggil dari thread mana pun.
 * [quiet] = hanya catat keadaan (dipakai pulihkan status) tanpa menampilkan baris status; dipakai saat
 * langkah alat berjalan, karena kartu Aktivitas Agen sudah menampilkannya langsung.
 */
internal fun MainActivity.setStatus(kind: Int, title: String, detail: String = "", quiet: Boolean = false) {
    curKind = kind
    curTitle = title
    curDetail = detail
    curQuiet = quiet
    runOnUiThread {
        emptyState.visibility = View.GONE
        // Aktivitas agen selalu diringkas di satu baris stabil paling atas; detail lengkap ada saat diketuk.
        if (kind != KIND_HIDE) zone.showCompactLine(title, kind)
        // Selama panel aktivitas tampil, ia yang menggantikan baris status di dalam chat (tidak ditampilkan dua kali).
        if (kind == KIND_HIDE || quiet || zone.isShown) {
            stopStatusTicker()
            statusTitle.setShimmer(false)
            (statusRoot.parent as? ViewGroup)?.removeView(statusRoot)
            leadKind = -2
            return@runOnUiThread
        }
        if (kind != leadKind) {
            statusLead.removeAllViews()
            statusTitle.shimmerBase = Palette.ON_BLUE
            statusTitle.shimmerHi = Live.TEXT_SHINE
            if (kind == KIND_DOTS) {
                statusLead.addView(DotsView(this), FrameLayout.LayoutParams(MATCH, MATCH))
            } else {
                statusLead.addView(
                    IconView(this, kind, accentFor(kind)).apply { working = kind != IconView.CLOCK },
                    FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER_VERTICAL)
                )
            }
            leadKind = kind
        }
        statusBaseTitle = title
        statusTitle.text = title
        statusTitle.setShimmer(kind != IconView.CLOCK)
        startStatusTicker(title)
        statusDetail.text = detail
        statusChevron.visibility = if (detail.isEmpty()) View.INVISIBLE else View.VISIBLE
        statusDetail.visibility = if (detail.isNotEmpty() && detailOpen) View.VISIBLE else View.GONE
        if (statusRoot.parent == null) chatBox.addView(statusRoot, LinearLayout.LayoutParams(MATCH, WRAP))
        scrollDown()
    }
}

internal fun MainActivity.hideStatus() = runOnUiThread {
    stopStatusTicker()
    statusTitle.setShimmer(false)
    (statusRoot.parent as? ViewGroup)?.removeView(statusRoot)
    detailOpen = false
    statusChevron.rotation = 0f
    leadKind = -2
}

internal fun MainActivity.scrollDown() {
    if (userScrolledUp) return
    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
}

internal fun MainActivity.forceScrollDown() {
    userScrolledUp = false
    scrollDownBtn.visibility = View.GONE
    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
}

/**
 * Penghitung waktu jujur: setelah 3 detik di fase yang sama, judul diberi lama tunggu nyata
 * ("Menunggu respons model AI · 12 dtk"). Tidak ada teks karangan tentang apa yang "sedang terjadi".
 */
internal fun MainActivity.startStatusTicker(title: String) {
    stopStatusTicker()
    val startMs = System.currentTimeMillis()
    val task = object : Runnable {
        override fun run() {
            if (!busy || curTitle != title || statusRoot.parent == null) return
            val sec = (System.currentTimeMillis() - startMs) / 1000
            if (sec >= 3) statusTitle.text = "$title · $sec dtk"
            statusTickerHandler.postDelayed(this, 1000)
        }
    }
    statusTickerTask = task
    statusTickerHandler.postDelayed(task, 1000)
}

internal fun MainActivity.stopStatusTicker() {
    statusTickerTask?.let { statusTickerHandler.removeCallbacks(it) }
    statusTickerTask = null
}
