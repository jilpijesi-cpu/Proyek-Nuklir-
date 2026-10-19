package com.kita.agent

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

// Lampiran file: kartu di atas kolom input (badge tipe, nama, tombol ×), lalu keyboard terbuka untuk mengetik perintahnya.

/** Satu lampiran. path masih null selama file disalin ke Download/ClaudeMythos. */
internal class Attachment(val name: String, @Volatile var path: String? = null)

/** Gambar ulang deretan kartu; deretan disembunyikan kalau kosong. Panggil dari thread UI. */
internal fun MainActivity.refreshAttachments() {
    attachRow.removeAllViews()
    attachScroll.visibility = if (attachments.isEmpty()) View.GONE else View.VISIBLE
    for (a in attachments) {
        attachRow.addView(attachmentChip(a), LinearLayout.LayoutParams(dp(104), dp(104)).apply { marginEnd = dp(8) })
    }
    updateAction()
}

internal fun MainActivity.clearAttachments() {
    attachments.clear()
    refreshAttachments()
}

/** Fokus ke kolom input dan buka keyboard (agak ditunda karena baru kembali dari pemilih file). */
internal fun MainActivity.focusInput() {
    input.requestFocus()
    input.postDelayed({
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }, 250)
}

internal fun MainActivity.attachmentChip(a: Attachment): View {
    val ready = a.path != null
    val ext = a.name.substringAfterLast('.', "").uppercase().take(4).ifEmpty { "FILE" }
    val card = FrameLayout(this).apply {
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(tint(Palette.BRAND, 0.94f))
            setStroke(dp(2), tint(Palette.BRAND, 0.7f))
        }
        alpha = if (ready) 1f else 0.55f   // redup selama masih disalin
    }
    val badge = TextView(this).apply {
        text = if (ready) ext else "…"
        textSize = 12f
        setTextColor(Color.WHITE)
        setPadding(dp(10), dp(3), dp(10), dp(3))
        background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Palette.BRAND, Palette.BRAND_2)
        ).apply { cornerRadius = dp(12).toFloat() }
    }
    card.addView(badge, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply {
        setMargins(dp(10), dp(10), 0, 0)
    })
    val close = TextView(this).apply {
        text = "✕"
        textSize = 14f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.SLATE) }
        setOnClickListener { attachments.remove(a); refreshAttachments() }
    }
    card.addView(close, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP or Gravity.END).apply {
        setMargins(0, dp(8), dp(8), 0)
    })
    val nameView = TextView(this).apply {
        text = a.name
        textSize = 13f
        setTextColor(Palette.TEXT_MAIN)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    card.addView(nameView, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply {
        setMargins(dp(10), 0, dp(10), dp(10))
    })
    return card
}
