package com.kita.agent

import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

// Daftar langkah di dalam gelembung putih (seperti acuan): centang hijau = selesai, roda gigi biru = berjalan,
// lingkaran hijau = menunggu izin, silang merah = gagal. Datanya dari AgentStepStore (langkah nyata, bukan karangan).

internal class StepChecklist(private val act: MainActivity) : LinearLayout(act) {
    private val head = TextView(act).apply {
        text = "Langkah yang dikerjakan"
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Palette.ON_BLUE_SOFT)
    }
    private val more = TextView(act).apply {
        textSize = 12.5f
        setTextColor(Palette.ON_BLUE_SOFT)
        visibility = View.GONE
    }
    private val rows = LinearLayout(act).apply { orientation = VERTICAL }
    private var signature = ""

    init {
        orientation = VERTICAL
        visibility = View.GONE
        // Tanpa kotak: daftar langsung di atas latar chat, rata kiri dengan teks balasan AI (14dp dari tepi isi chat).
        background = null
        setPadding(act.dp(14), act.dp(2), act.dp(10), act.dp(2))
        addView(head, LayoutParams(WRAP, WRAP))
        addView(more, LayoutParams(WRAP, WRAP).apply { topMargin = act.dp(2) })
        addView(rows, LayoutParams(WRAP, WRAP).apply { topMargin = act.dp(6) })
    }

    /** Gambar ulang hanya bila status/judul langkah berubah (keluaran yang mengalir tidak mengulang animasi). */
    fun render(groups: List<AgentStepGroup>) {
        val steps = groups.flatMap { it.steps }.sortedBy { it.startedAt ?: 0L }
        val shown = steps.takeLast(MAX_ROWS)
        val sig = shown.joinToString("|") { it.id + ":" + it.status + ":" + it.title } + "#" + steps.size
        if (sig == signature) return
        signature = sig
        rows.removeAllViews()
        if (steps.isEmpty()) {
            visibility = View.GONE
            return
        }
        visibility = View.VISIBLE
        val hidden = steps.size - shown.size
        more.visibility = if (hidden > 0) View.VISIBLE else View.GONE
        more.text = "+$hidden langkah sebelumnya"
        for (st in shown) rows.addView(row(st), LayoutParams(WRAP, WRAP).apply { topMargin = act.dp(6) })
        act.scrollDown()
    }

    private fun row(st: AgentStep): View {
        val active = st.status == AgentStepStatus.RUNNING || st.status == AgentStepStatus.WAITING
        val icon: View = when (st.status) {
            AgentStepStatus.SUCCESS -> IconView(act, IconView.CHECK, Live.GREEN)
            AgentStepStatus.RUNNING -> IconView(act, IconView.GEAR, Live.ORANGE).apply { working = true }
            AgentStepStatus.WAITING -> IconView(act, IconView.RING, Palette.ON_BLUE_SOFT)
            AgentStepStatus.FAILED -> IconView(act, IconView.CROSS, Live.RED)
        }
        val label = tidyLine(st.title, 60).let { if (active && !it.endsWith(".") && !it.endsWith("…")) "$it..." else it }
        val tv = TextView(act).apply {
            text = label
            textSize = 15f
            setTextColor(Palette.AI_TEXT)
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = (act.resources.displayMetrics.widthPixels * 0.72f).toInt()
        }
        return LinearLayout(act).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val slot = FrameLayout(act)
            slot.addView(icon, FrameLayout.LayoutParams(act.dp(20), act.dp(20), Gravity.CENTER))
            addView(slot, LayoutParams(act.dp(24), act.dp(24)))
            addView(tv, LayoutParams(WRAP, WRAP).apply { marginStart = act.dp(8) })
        }
    }

    private companion object {
        const val MAX_ROWS = 6
    }
}
