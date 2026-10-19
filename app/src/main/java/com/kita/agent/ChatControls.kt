package com.kita.agent

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.speech.RecognizerIntent
import android.view.View

// Kontrol chat: hapus, chat baru, suara, tombol aksi kirim/suara/stop.
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

internal fun MainActivity.confirmDelete() {
    if (busy) { toast("Tunggu proses selesai"); return }
    if (hist.isEmpty() && chatBox.childCount == 0) { toast("Belum ada percakapan"); return }
    AlertDialog.Builder(this)
        .setMessage("Hapus percakapan ini?")
        .setPositiveButton("Hapus") { _, _ -> newChat() }
        .setNegativeButton("Batal", null)
        .show()
}

internal fun MainActivity.newChat() {
    if (busy) { toast("Tunggu proses selesai"); return }
    clearAttachments()
    hist.clear()
    clearSummary()
    cliAllowedTpl = null
    histFile().delete()
    zone.reset()
    runLog.reset(System.currentTimeMillis())
    chatBox.removeAllViews()
    emptyState.visibility = View.VISIBLE
}

internal fun MainActivity.voice() {
    voiceTurnPending = true
    val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
    try {
        startActivityForResult(i, 77)
    } catch (e: Exception) {
        voiceTurnPending = false
        toast("Input suara tidak tersedia")
    }
}

internal fun MainActivity.onAction() {
    if (busy) stopAgent() else if (input.text.isNullOrBlank() && attachments.isEmpty()) voice() else send()
}

internal fun MainActivity.updateAction() {
    val has = !input.text.isNullOrBlank() || attachments.isNotEmpty()
    when {
        busy -> {
            actionIcon.type = IconView.STOP
            actionIcon.color = Color.WHITE
            actionBg.setColor(Palette.INK)
        }
        has -> {
            actionIcon.type = IconView.SEND
            actionIcon.color = Color.WHITE
            actionBg.setColor(Palette.BRAND)
        }
        else -> {
            actionIcon.type = IconView.MIC
            actionIcon.color = Palette.INK
            actionBg.setColor(Color.TRANSPARENT)
        }
    }
    refreshHeader()
}

/** Contoh perintah cepat (ikon wajah di kolom input): mengisi kolom input, tidak langsung menjalankan. */
internal fun MainActivity.showQuickPrompts(anchor: View) {
    val items = listOf(
        "Lihat isi folder" to "Lihat isi folder kerja lalu jelaskan singkat.",
        "Cari dan perbaiki error" to "Cari error di kode lalu perbaiki.",
        "Build aplikasi" to "Build aplikasi dengan ./gradlew assembleDebug."
    )
    val menu = android.widget.PopupMenu(this, anchor)
    items.forEachIndexed { i, it -> menu.menu.add(0, i, i, it.first) }
    menu.setOnMenuItemClickListener { item ->
        input.setText(items[item.itemId].second)
        input.setSelection(input.text.length)
        input.requestFocus()
        true
    }
    menu.show()
}
