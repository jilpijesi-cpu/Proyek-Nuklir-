package com.kita.agent

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/** Panel terminal-only; tidak menampilkan layar VM atau pratinjau komputer. */
internal fun MainActivity.showComputerPanel(groups: List<AgentStepGroup>, selectedId: String) {
    val steps = groups.flatMap { it.steps }
    if (steps.isEmpty()) {
        toast("Belum ada langkah terminal")
        return
    }
    val dialog = Dialog(this)
    val root = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(8), dp(18), dp(18))
        background = GradientDrawable().apply {
            setColor(Palette.SURFACE)
            cornerRadii = floatArrayOf(dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), 0f, 0f, 0f, 0f)
        }
    }
    val handle = View(this).apply {
        setBackgroundColor(Palette.BORDER)
    }
    root.addView(handle, LinearLayout.LayoutParams(dp(42), dp(4)).apply {
        gravity = Gravity.CENTER
        bottomMargin = dp(10)
    })
    val titleRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    titleRow.addView(TextView(this).apply {
        text = "Komputer"
        textSize = 18f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Palette.TEXT_MAIN)
    }, LinearLayout.LayoutParams(0, WRAP, 1f))
    titleRow.addView(TextView(this).apply {
        text = "Terminal · AgentApp"
        textSize = 12f
        setTextColor(Palette.TEXT_SOFT)
    })
    root.addView(titleRow)

    val active = TextView(this).apply {
        textSize = 12f
        setTextColor(Palette.BRAND_DEEP)
        setPadding(0, dp(9), 0, dp(7))
    }
    root.addView(active)

    val command = TextView(this).apply {
        typeface = Typeface.MONOSPACE
        textSize = 12f
        setTextColor(Palette.TEXT_MAIN)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), Palette.BORDER)
        }
        setTextIsSelectable(true)
    }
    root.addView(command, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })

    val output = TextView(this).apply {
        typeface = Typeface.MONOSPACE
        textSize = 11.5f
        setTextColor(0xFFE9EEF5.toInt())
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setTextIsSelectable(true)
        background = GradientDrawable().apply {
            setColor(0xFF111827.toInt())
            cornerRadius = dp(10).toFloat()
        }
    }
    root.addView(output, LinearLayout.LayoutParams(MATCH, dp(150)))

    val position = TextView(this).apply {
        textSize = 11.5f
        setTextColor(Palette.TEXT_SOFT)
        gravity = Gravity.CENTER
        setPadding(0, dp(7), 0, dp(2))
    }
    val seek = SeekBar(this)
    root.addView(position, LinearLayout.LayoutParams(MATCH, WRAP))
    root.addView(seek, LinearLayout.LayoutParams(MATCH, WRAP))

    val previous = Button(this).apply { text = "‹ Sebelumnya" }
    val next = Button(this).apply { text = "Berikutnya ›" }
    val controls = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        addView(previous, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(next, LinearLayout.LayoutParams(0, WRAP, 1f))
    }
    root.addView(controls)

    fun render(index: Int) {
        val safe = index.coerceIn(0, steps.lastIndex)
        val step = steps[safe]
        val isLive = safe == steps.lastIndex && step.status == AgentStepStatus.RUNNING
        active.text = if (isLive) "● Live · Sedang menggunakan Terminal" else "Replay langkah ${safe + 1} dari ${steps.size}"
        command.text = if (step.fullCommand.isBlank()) step.title else "$ ${step.fullCommand.removePrefix("$ ")}"
        output.text = step.output.ifBlank { "(belum ada keluaran)" }
        position.text = if (isLive) "● Live" else "Langkah ${safe + 1}/${steps.size} · ${step.status.name.lowercase()}"
        seek.progress = safe
        previous.isEnabled = safe > 0
        next.isEnabled = safe < steps.lastIndex
    }

    val initial = steps.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: steps.lastIndex
    seek.max = (steps.size - 1).coerceAtLeast(0)
    seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) { if (fromUser) render(value) }
        override fun onStartTrackingTouch(bar: SeekBar?) {}
        override fun onStopTrackingTouch(bar: SeekBar?) {}
    })
    previous.setOnClickListener { render((seek.progress - 1).coerceAtLeast(0)) }
    next.setOnClickListener { render((seek.progress + 1).coerceAtMost(steps.lastIndex)) }
    dialog.setContentView(root)
    dialog.setOnShowListener {
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.28f }
        }
        render(initial)
    }
    dialog.show()
}
