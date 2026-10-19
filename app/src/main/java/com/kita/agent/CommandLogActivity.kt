package com.kita.agent

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Layar "Catatan perintah": perintah yang pernah disetujui, kapan, bagaimana, dan hasilnya. */
class CommandLogActivity : Activity() {

    private lateinit var list: LinearLayout
    private lateinit var count: TextView
    private val fmt = SimpleDateFormat("d MMM yyyy, HH:mm:ss", Locale("id", "ID"))

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun col(s: String) = Color.parseColor(s)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Palette.BAR
        window.navigationBarColor = Palette.NAV
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val back = TextView(this).apply {
            text = "‹"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(col("#1F1F1F"))
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = "Catatan perintah"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(col("#1F1F1F"))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        val clear = TextView(this).apply {
            text = "Hapus"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(col("#555555"))
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { confirmClear() }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Palette.BAR)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            addView(back, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(title, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(clear, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)))
        }
        count = TextView(this).apply {
            textSize = 12f
            setTextColor(col("#757575"))
            setPadding(dp(20), 0, dp(20), dp(8))
        }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(24))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(count, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(ScrollView(context).apply { addView(list) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        render()   // segarkan: agen bisa mencatat perintah baru saat layar ini ditinggalkan
    }

    private fun render() {
        val entries = CommandLog.load(this)
        list.removeAllViews()
        count.text = if (entries.isEmpty()) "" else "${entries.size} catatan terakhir (maks ${CommandLog.MAX_KEEP}). Ketuk untuk melihat selengkapnya."
        if (entries.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Belum ada perintah yang disetujui."
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(col("#757575"))
                setPadding(0, dp(64), 0, 0)
            })
            return
        }
        for (e in entries) list.addView(card(e), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) })
    }

    private fun chip(text: String, bg: Int, fg: Int) = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(fg)
        setPadding(dp(8), dp(2), dp(8), dp(2))
        background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(bg) }
    }

    private fun card(e: LogEntry): View {
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        val match = LinearLayout.LayoutParams.MATCH_PARENT
        val cmd = TextView(this).apply {
            text = e.command
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(col("#1F1F1F"))
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
        val result = TextView(this).apply {
            text = (if (e.ok) "✓ " else "✗ ") + e.result.ifBlank { if (e.ok) "Berhasil" else "Gagal" }
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(if (e.ok) Palette.GREEN else Palette.RED)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = fmt.format(Date(e.time))
                textSize = 12f
                setTextColor(col("#757575"))
            }, LinearLayout.LayoutParams(0, wrap, 1f))
            addView(chip(e.risk.name, e.risk.color, onColor(e.risk.color)))
        }
        val how = TextView(this).apply {
            text = e.mode.label + if (e.kind == "perintah") "" else " · ${e.kind}"
            textSize = 12f
            setTextColor(col("#757575"))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.WHITE); setStroke(dp(1), col("#E3E3E0")) }
            addView(meta, LinearLayout.LayoutParams(match, wrap))
            addView(cmd, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(8) })
            addView(how, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(6) })
            addView(result, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(6) })
        }
        if (e.description.isNotBlank()) {
            // keterangan dari model: hanya info, jadi diberi label supaya tidak dikira penilaian aplikasi
            box.addView(TextView(this).apply {
                text = "Kata model: ${e.description}"
                textSize = 12f
                setTextColor(col("#757575"))
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, 2, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dp(6) })
        }
        var open = false
        box.setOnClickListener {
            open = !open
            val n = if (open) Int.MAX_VALUE else 3
            cmd.maxLines = n
            result.maxLines = n
            cmd.setTextIsSelectable(open)
        }
        return box
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage("Hapus semua catatan perintah? Ini tidak bisa dibatalkan.")
            .setPositiveButton("Hapus") { _, _ -> CommandLog.clear(this); render() }
            .setNegativeButton("Batal", null)
            .show()
    }
}
