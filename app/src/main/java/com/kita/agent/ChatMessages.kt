package com.kita.agent

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

// Menambah gelembung pesan, catatan, kartu pasang-APK dan kartu undo ke layar chat.
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

internal fun MainActivity.insertBeforeStatus(v: View, lp: LinearLayout.LayoutParams) {
    val idx = if (statusRoot.parent === chatBox) chatBox.indexOfChild(statusRoot) else -1
    val zoneIdx = if (zone.parent === chatBox) chatBox.indexOfChild(zone) else -1
    val insertAt = listOf(idx, zoneIdx).filter { it >= 0 }.minOrNull()
    if (insertAt != null) chatBox.addView(v, insertAt, lp) else chatBox.addView(v, lp)
}

private fun MainActivity.updateStreamingText(box: LinearLayout, text: String) {
    val tv = box.findViewWithTag<ShimmerText>("ai_stream_text") ?: ShimmerText(this).apply {
        tag = "ai_stream_text"
        styleAiText()
        setShimmer(false)
        box.addView(this, LinearLayout.LayoutParams(MATCH, WRAP))
    }
    box.visibility = View.VISIBLE
    tv.text = mdToSpannable(text)
}

/** Gaya teks balasan AI: putih di atas biru, huruf nyaman dibaca, pemenggalan baris tidak berubah-ubah saat teks bertambah. */
internal fun TextView.styleAiText() {
    textSize = 16f
    setTextColor(Palette.AI_TEXT)
    setLineSpacing(0f, 1.35f)
    breakStrategy = android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE
    hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
}

internal fun nowStamp(): String =
    java.text.SimpleDateFormat("HH.mm", java.util.Locale.getDefault()).format(java.util.Date())

/** Baris jam di pojok kanan bawah gelembung; pesan pengguna ditambah centang ganda biru. */
internal fun MainActivity.stampRow(time: String, mine: Boolean): View = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL or Gravity.END
    addView(TextView(context).apply {
        text = time
        textSize = 11f
        setTextColor(if (mine) Palette.TIME_ME else Palette.ON_BLUE_SOFT)
    })
    if (mine) addView(
        IconView(context, IconView.DOUBLE_CHECK, Palette.TICK),
        LinearLayout.LayoutParams(dp(16), dp(14)).apply { marginStart = dp(3) }
    )
}

internal fun MainActivity.addMsg(kind: Int, text: String) = runOnUiThread {
    emptyState.visibility = View.GONE
    if (kind == USER) {
        val stamp = if (restoring) "" else nowStamp()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = MsgMeta(USER, text, stamp)
            background = BubbleDrawable(Palette.BUBBLE_ME, dp(16).toFloat(), dp(8).toFloat(), true)
            setPadding(dp(14), dp(10), dp(14) + dp(8), dp(7))
        }
        box.setOnLongClickListener { showMsgMenu(box); true }
        // Lampiran tampil sebagai gambar mini dan kartu file; baris "terlampir di <path>" hanya untuk AI, tidak ikut ditampilkan.
        val (shown, atts) = splitAttachments(text)
        attachmentViews(atts).forEach { box.addView(it) }
        if (shown.isNotEmpty()) box.addView(TextView(this).apply {
            this.text = shown
            textSize = 16f
            setTextColor(Palette.INK)
            setLineSpacing(0f, 1.2f)
            setOnLongClickListener { showMsgMenu(box); true }
        }, LinearLayout.LayoutParams(WRAP, WRAP))
        if (stamp.isNotEmpty()) box.addView(stampRow(stamp, true), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(2); gravity = Gravity.END })
        val lp = LinearLayout.LayoutParams(WRAP, WRAP).apply {
            topMargin = dp(14)
            gravity = Gravity.END
            marginStart = dp(48)
        }
        insertBeforeStatus(box, lp)
    } else {
        val box = newAiBox()
        fillMarkdown(box, text)
    }
    scrollDown()
}

internal fun MainActivity.newAiBox(): LinearLayout {
    // Balasan teks menutup kartu aktivitas yang sedang berjalan; langkah berikutnya membuka kartu baru.
    zone.feed.seal()
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        tag = MsgMeta(AI, "", if (restoring) "" else nowStamp())
        // Tanpa kotak/gelembung: teks langsung di atas latar chat, selebar layar, jadi lebarnya tidak bergantung pada isi.
        setPadding(dp(4), dp(2), dp(4), dp(2))
        visibility = View.GONE // tampil setelah ada isi; kotak kosong tidak boleh tertinggal
    }
    box.setOnLongClickListener { showMsgMenu(box); true }
    insertBeforeStatus(box, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12); marginStart = dp(10); marginEnd = dp(10) })
    return box
}

/** Dipanggil saat teks balasan mengalir masuk; menggambar ulang pesan yang sedang ditulis. */
internal fun MainActivity.streamUpdate(textRaw: String) = runOnUiThread {
    val text = stripModelTags(textRaw)
    if (text.isBlank()) return@runOnUiThread // baru blok berpikir: belum ada yang layak tampil
    emptyState.visibility = View.GONE
    // Balasan sebelumnya yang masih mengejar ketikan diselesaikan dulu; balasan baru selalu di gelembung baru.
    val old = streamBox
    if (old != null && streamEnded && !streamCopyAdded) finishStream(old)
    val box = streamBox ?: newAiBox().also { streamBox = it; typewriterShown = "" }
    streamText = text
    streamEnded = false
    streamCopyAdded = false
    typewriterTarget = text
    if (!text.startsWith(typewriterShown)) { // percobaan ulang: teks dimulai lagi dari awal
        typewriterShown = ""
        box.removeAllViews()
    }
    startTypewriter(box)
}

/** Efek ketik: mengejar teks target, makin tertinggal makin cepat, dan menutup gelembung begitu stream selesai. */
private fun MainActivity.startTypewriter(box: LinearLayout) {
    typewriterTick?.let { typewriterHandler.removeCallbacks(it) }
    val tick = object : Runnable {
        override fun run() {
            val target = typewriterTarget
            val lag = target.length - typewriterShown.length
            if (lag > 0) {
                typewriterShown = target.take(typewriterShown.length + maxOf(1, lag / 25))
                try {
                    updateStreamingText(box, typewriterShown)
                } catch (e: Exception) {
                    typewriterShown = target // gagal menggambar: lompat ke akhir, jangan macet di tengah
                }
                if (typewriterShown.length % 3 == 0 || typewriterShown.length == target.length) scrollDown()
                typewriterHandler.postDelayed(this, 26L)
            } else if (streamEnded && !streamCopyAdded) {
                finishStream(box)
            }
        }
    }
    typewriterTick = tick
    typewriterHandler.post(tick)
}

/** Ganti teks ketik dengan tampilan akhir (markdown, blok kode, jam) dan lepaskan kotak stream. */
private fun MainActivity.finishStream(box: LinearLayout) {
    typewriterTick?.let { typewriterHandler.removeCallbacks(it) }
    typewriterTick = null
    streamCopyAdded = true
    fillMarkdown(box, typewriterTarget, withCopy = false, streaming = false)
    addCopyRow(box)
    streamBox = null
    streamText = ""
    typewriterShown = ""
    typewriterTarget = ""
}

internal fun MainActivity.streamEnd() = runOnUiThread {
    streamEnded = true
    val b = streamBox
    if (b == null || streamText.isBlank()) {
        if (b != null && b.childCount == 0) (b.parent as? android.view.ViewGroup)?.removeView(b)
        streamBox = null
        streamText = ""
        typewriterShown = ""
        typewriterTarget = ""
        return@runOnUiThread
    }
    startTypewriter(b) // kejar sisa ketikan lalu finalisasi
    // Pengaman: bila karena apa pun tik terhenti, teks lengkap tetap ditampilkan.
    typewriterHandler.postDelayed({ if (streamBox === b && !streamCopyAdded) finishStream(b) }, 4000L)
}

internal fun MainActivity.addNote(t: String) = runOnUiThread {
    val tv = TextView(this).apply {
        text = t
        textSize = 13.5f
        setTextColor(Palette.ON_BLUE_SOFT)
        setLineSpacing(0f, 1.25f)
        setPadding(dp(14), dp(8), dp(10), dp(2)) // rata kiri dengan teks balasan AI dan daftar langkah
    }
    insertBeforeStatus(tv, LinearLayout.LayoutParams(MATCH, WRAP))
    scrollDown()
}

internal fun MainActivity.addInstallCard(path: String) = runOnUiThread {
    val btn = TextView(this).apply {
        text = "Pasang APK"
        textSize = 15f
        setTextColor(Palette.BRAND_DEEP)
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = pressable(rounded(Color.WHITE, 24))
        setPadding(dp(24), dp(12), dp(24), dp(12))
    }
    btn.setOnClickListener { installApk(path, btn) }
    insertBeforeStatus(btn, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(14) })
    scrollDown()
}

internal fun MainActivity.addUndoCard(path: String, bak: String) = runOnUiThread {
    val label = "Urungkan: " + path.substringAfterLast('/')
    val btn = TextView(this).apply {
        text = label
        textSize = 14f
        setTextColor(darkOf(Palette.AMBER))
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setColor(tint(Palette.AMBER, 0.86f))
            cornerRadius = dp(20).toFloat()
        }
        setPadding(dp(16), dp(8), dp(16), dp(8))
    }
    btn.setOnClickListener { undoWrite(path, bak, btn, label) }
    insertBeforeStatus(btn, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(12) })
    scrollDown()
}

/** Versi lebih gelap dari [c] untuk teks di atas latar pastel. */
internal fun darkOf(c: Int): Int {
    val r = ((c shr 16) and 0xFF) * 6 / 10
    val g = ((c shr 8) and 0xFF) * 6 / 10
    val b = (c and 0xFF) * 6 / 10
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
