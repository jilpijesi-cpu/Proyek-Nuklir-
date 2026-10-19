package com.kita.agent

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.TextView

data class ApprovalRequest(
    val command: String,
    val description: String // dari model; hanya penjelasan, BUKAN dasar penilaian risiko
)

/**
 * Dialog persetujuan: penjelasan sederhana, label risiko berwarna, lalu perintah asli.
 * "Ya, semua" muncul untuk semua perintah dan berlaku untuk sisa tugas.
 * Panggil dari thread UI.
 */
fun showApprovalDialog(
    act: Activity,
    req: ApprovalRequest,
    onYes: () -> Unit,
    onYesAll: () -> Unit,
    onNo: () -> Unit,
    offerAll: Boolean? = null,          // null = tombol hanya untuk risiko READ
    allLabel: String = "Ya, semua",
    warning: String? = null             // peringatan area sensitif, tampil merah
): AlertDialog {
    val risk = classify(req.command)
    val d = act.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()

    val box = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(16), dp(24), dp(8))
    }
    // 1. Penjelasan sederhana
    box.addView(TextView(act).apply {
        text = req.description.ifBlank { "(Model tidak memberi penjelasan)" }
        textSize = 16f
        setTextColor(Palette.INK)
        setLineSpacing(0f, 1.2f)
    })
    // 2. Label risiko berwarna
    box.addView(TextView(act).apply {
        text = risk.label
        textSize = 13f
        setTextColor(darkOf(risk.color))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(12), dp(5), dp(12), dp(5))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(tint(risk.color, 0.82f))
        }
    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(12)
    })
    if (warning != null) {
        box.addView(TextView(act).apply {
            text = " $warning"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Palette.RED)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
    }
    // 3. Perintah asli, kecil, untuk yang mau memeriksa
    box.addView(TextView(act).apply {
        text = req.command
        typeface = Typeface.MONOSPACE
        textSize = 12.5f
        setTextColor(Palette.INK)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply {
            setColor(Palette.FIELD)
            cornerRadius = dp(12).toFloat()
        }
        setTextIsSelectable(true)
    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(12)
    })

    val b = AlertDialog.Builder(act)
        .setTitle("Jalankan di Termux?")
        .setView(box)
        .setCancelable(false)
        .setPositiveButton("Jalankan") { _, _ -> onYes() }
        .setNegativeButton("Tolak") { _, _ -> onNo() }
    if (offerAll ?: (risk == Risk.READ)) b.setNeutralButton(allLabel) { _, _ -> onYesAll() }
    return b.show()
}
