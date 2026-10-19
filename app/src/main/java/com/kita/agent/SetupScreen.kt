package com.kita.agent

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

// Layar "Siapkan Termux": daftar pemeriksaan dan tombol perbaikan.
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

internal fun MainActivity.openUrl(u: String) {
    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (e: Exception) { toast("Tidak bisa membuka tautan") }
}

internal fun MainActivity.openAppInfo() {
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
}

internal fun MainActivity.renderSetup(box: LinearLayout, steps: List<SetupStep>, note: String? = null) {
    box.removeAllViews()
    if (note != null) {
        box.addView(TextView(this).apply {
            text = note
            textSize = 14f
            setPadding(0, dp(8), 0, dp(8))
        })
        return
    }
    for (st in steps) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(2))
        }
        row.addView(TextView(this).apply {
            text = if (st.ok) "✓" else "✗"
            textSize = 16f
            setTextColor(if (st.ok) Palette.GREEN else Palette.RED)
            setPadding(0, 0, dp(10), 0)
        })
        row.addView(TextView(this).apply {
            text = st.label
            textSize = 15f
            setTextColor(col("#1F1F1F"))
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        box.addView(row)
        if (!st.ok) {
            if (st.help.isNotEmpty()) {
                box.addView(TextView(this).apply {
                    text = st.help
                    textSize = 13f
                    setTextColor(col("#757575"))
                    setPadding(dp(26), 0, 0, dp(2))
                })
            }
            for ((label, act) in st.acts) {
                box.addView(TextView(this).apply {
                    text = label
                    textSize = 14f
                    setTextColor(Palette.BRAND)
                    setPadding(dp(26), dp(6), dp(8), dp(6))
                    setOnClickListener { act() }
                })
            }
        }
    }
    if (steps.isNotEmpty() && steps.all { it.ok }) {
        box.addView(TextView(this).apply {
            text = "Semua siap. Termux sudah terhubung."
            textSize = 14f
            setTextColor(Palette.GREEN)
            setPadding(0, dp(14), 0, dp(4))
        })
    }
}

internal fun MainActivity.showSetup() {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), 0)
    }
    val dlg = AlertDialog.Builder(this)
        .setTitle("Siapkan Termux")
        .setView(ScrollView(this).apply { addView(box) })
        .setPositiveButton("Selesai") { _, _ ->
            if (needsKey() && cfgKey().isEmpty()) showSettings()
        }
        .setNeutralButton("Periksa lagi", null)
        .create()
    fun recheck() {
        renderSetup(box, emptyList(), "Memeriksa Termux...")
        Thread {
            val steps = try {
                setupChecks { recheck() }
            } catch (e: Exception) {
                listOf(SetupStep("Pemeriksaan gagal: ${(e.message ?: "").take(80)}", false))
            }
            runOnUiThread { renderSetup(box, steps) }
        }.start()
    }
    dlg.setOnDismissListener { setupRecheck = null }
    dlg.show()
    dlg.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener { recheck() }
    setupRecheck = { recheck() }
    recheck()
}
