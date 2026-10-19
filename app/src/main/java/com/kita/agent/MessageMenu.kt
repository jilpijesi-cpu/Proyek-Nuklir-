package com.kita.agent

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// Menu tekan-lama pada pesan: salin, bagikan, ulangi, edit; serta chip saran.
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

internal fun MainActivity.showMsgMenu(v: View) {
    val meta = v.tag as? MsgMeta ?: return
    if (meta.text.isBlank()) return
    val labels = ArrayList<String>()
    val acts = ArrayList<() -> Unit>()
    labels.add("Salin"); acts.add { copyText("pesan", meta.text); toast("Tersalin") }
    labels.add("Pilih teks"); acts.add { selectDialog(meta.text) }
    labels.add("Bagikan"); acts.add { shareText(meta.text) }
    if (!busy && preset(prov).fmt != "cli") {
        if (meta.role == AI && isLastAi(v)) { labels.add("Ulangi jawaban"); acts.add { regenerate() } }
        if (meta.role == USER) { labels.add("Edit & kirim ulang"); acts.add { editFrom(v) } }
    }
    AlertDialog.Builder(this)
        .setItems(labels.toTypedArray()) { _, i -> acts[i]() }
        .show()
}

internal fun MainActivity.selectDialog(text: String) {
    val tv = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextIsSelectable(true)
        setPadding(dp(20), dp(12), dp(20), dp(4))
    }
    AlertDialog.Builder(this)
        .setView(ScrollView(this).apply { addView(tv) })
        .setPositiveButton("Tutup", null)
        .show()
}

internal fun MainActivity.shareText(text: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    try { startActivity(Intent.createChooser(i, "Bagikan")) } catch (e: Exception) { toast("Tidak bisa membagikan") }
}

internal fun MainActivity.metaOf(i: Int): MsgMeta? = chatBox.getChildAt(i)?.tag as? MsgMeta

internal fun MainActivity.isLastAi(v: View): Boolean {
    for (i in chatBox.childCount - 1 downTo 0) {
        val m = metaOf(i) ?: continue
        if (m.role == AI) return chatBox.getChildAt(i) === v
    }
    return false
}

/** Hapus tampilan mulai indeks `from` (kecuali baris status). */
internal fun MainActivity.removeViewsFrom(from: Int) {
    clearChips()
    for (i in chatBox.childCount - 1 downTo from) {
        val c = chatBox.getChildAt(i)
        if (c !== statusRoot) chatBox.removeViewAt(i)
    }
    if (chatBox.childCount == 0) emptyState.visibility = View.VISIBLE
}

internal fun MainActivity.regenerate() {
    if (busy) return
    val key = cfgKey()
    if (needsKey() && key.isEmpty()) { showSettings(); return }
    val idx = synchronized(hist) { hist.indexOfLast { it.role == 0 } }
    if (idx < 0) { toast("Tidak ada pesan untuk diulang"); return }
    val msg = hist[idx].text
    synchronized(hist) { while (hist.size > idx + 1) hist.removeAt(hist.size - 1) }
    if (hist.size < sumUpto) clearSummary()
    var lastUser = -1
    for (i in chatBox.childCount - 1 downTo 0) {
        if (metaOf(i)?.role == USER) { lastUser = i; break }
    }
    removeViewsFrom(lastUser + 1)
    saveHist()
    busy = true
    startWorker(msg, key, addUser = false, images = hist[idx].images)
}

internal fun MainActivity.editFrom(v: View) {
    if (busy) return
    var k = 0
    for (i in 0 until chatBox.childCount) {
        val c = chatBox.getChildAt(i)
        if (c === v) break
        if (metaOf(i)?.role == USER) k++
    }
    var idx = -1
    var seen = 0
    synchronized(hist) {
        for ((i, h) in hist.withIndex()) {
            if (h.role == 0) {
                if (seen == k) { idx = i; break }
                seen++
            }
        }
    }
    if (idx < 0) { toast("Pesan ini tidak bisa diedit"); return }
    val text = (v.tag as MsgMeta).text
    synchronized(hist) { while (hist.size > idx) hist.removeAt(hist.size - 1) }
    if (hist.size < sumUpto) clearSummary()
    removeViewsFrom(chatBox.indexOfChild(v))
    saveHist()
    input.setText(text)
    input.setSelection(input.text.length)
    input.requestFocus()
}

internal fun MainActivity.clearChips() {
    chipsRow?.let { (it.parent as? ViewGroup)?.removeView(it) }
    chipsRow = null
}

internal fun MainActivity.showChips(text: String) = runOnUiThread {
    clearChips()
    val items = ArrayList<Pair<String, String>>()
    val asksQuestion = text.trimEnd().trimEnd('*', '_', '`').endsWith("?")
    if (sawFail) items.add("Perbaiki error" to "Perbaiki error tadi lalu coba lagi.")
    if (asksQuestion) items.add("Ya, lakukan" to "Ya, lakukan.")
    if (sawBuild) items.add("Build ulang" to "Build ulang dengan ./gradlew assembleDebug. Kalau gagal, baca error-nya dan perbaiki.")
    if (text.contains("```")) items.add("Jelaskan kodenya" to "Jelaskan kode tadi dengan singkat.")
    if (text.length > 400) items.add("Lebih singkat" to "Jelaskan lebih singkat.")
    if (loadAgentCheckpoint() != null) items.add("Lanjutkan tugas" to "Lanjutkan")
    // Jangan menampilkan tombol generik "Lanjutkan" pada setiap jawaban.
    // Jika tidak ada tindakan kontekstual, jangan tampilkan baris saran.
    if (items.isEmpty()) return@runOnUiThread
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    for ((label, prompt) in items.take(4)) {
        val chip = TextView(this).apply {
            this.text = label
            textSize = 14f
            setTextColor(Palette.INK)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            background = pressable(rounded(Color.WHITE, 20, Palette.DIVIDER))
            setPadding(dp(16), dp(9), dp(16), dp(9))
            setOnClickListener {
                if (busy) return@setOnClickListener
                clearChips()
                input.setText(prompt)
                send()
            }
        }
        row.addView(chip, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) })
    }
    val hs = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(row)
    }
    chipsRow = hs
    insertBeforeStatus(hs, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10); marginStart = dp(14) })
    scrollDown()
}
