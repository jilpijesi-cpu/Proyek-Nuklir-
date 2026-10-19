package com.kita.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

// Render markdown dan blok kode di gelembung chat.
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

internal fun MainActivity.fillMarkdown(box: LinearLayout, textRaw: String, withCopy: Boolean = true, streaming: Boolean = false) {
    val text = stripModelTags(textRaw)
    box.removeAllViews()
    (box.tag as? MsgMeta)?.text = text
    var last = 0
    for (m in fenceRe.findAll(text)) {
        addTextBlock(box, text.substring(last, m.range.first), streaming)
        box.addView(
            codeBlock(m.groupValues[1], m.groupValues[2].trimEnd('\n')),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10); bottomMargin = dp(6) }
        )
        last = m.range.last + 1
    }
    if (last < text.length) addTextBlock(box, text.substring(last), streaming)
    box.visibility = if (box.childCount > 0) View.VISIBLE else View.GONE
    if (withCopy && text.isNotBlank()) addCopyRow(box)
}

internal fun MainActivity.copyText(label: String, content: String) {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, content))
}

/** Jam di pojok kanan bawah gelembung AI (menyalin lewat tekan-lama pesan). Tanpa jam (riwayat lama) tidak menambah apa pun. */
internal fun MainActivity.addCopyRow(box: LinearLayout) {
    val time = (box.tag as? MsgMeta)?.time ?: ""
    if (time.isEmpty()) return
    box.addView(stampRow(time, false), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4); gravity = Gravity.END })
}

internal fun MainActivity.addTextBlock(box: LinearLayout, seg: String, streaming: Boolean = false) {
    val t = seg.trim('\n')
    if (t.isBlank()) return
    val tv = (if (streaming) ShimmerText(this) else TextView(this)).apply {
        text = mdToSpannable(t)
        setOnLongClickListener { showMsgMenu(box); true }
        styleAiText()
        if (this is ShimmerText) setShimmer(false)
    }
    box.addView(tv, LinearLayout.LayoutParams(MATCH, WRAP))
}

/** Markdown ringan: judul (#), daftar (-, *), **tebal**, *miring*, dan `kode inline`. */
internal fun MainActivity.mdToSpannable(srcRaw: String): SpannableStringBuilder {
    val src = stripIcons(srcRaw)
    val out = SpannableStringBuilder()
    val lines = src.split("\n")
    for ((i, raw) in lines.withIndex()) {
        val start = out.length
        val h = headRe.find(raw)
        val b = bulletRe.find(raw)
        if (h != null) {
            out.append(inline(h.groupValues[2]))
            val size = when (h.groupValues[1].length) { 1 -> 1.22f; 2 -> 1.14f; else -> 1.06f }
            out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(RelativeSizeSpan(size), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else if (b != null) {
            out.append("    ".repeat(b.groupValues[1].length / 2))
            val bs = out.length
            out.append("•  ")
            out.setSpan(ForegroundColorSpan(Palette.ON_BLUE_SOFT), bs, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.append(inline(b.groupValues[2]))
        } else {
            out.append(inline(raw))
        }
        if (i < lines.size - 1) out.append("\n")
    }
    return out
}

internal fun MainActivity.inline(src: String): SpannableStringBuilder {
    val out = SpannableStringBuilder()
    var last = 0
    for (m in inlineRe.findAll(src)) {
        out.append(src, last, m.range.first)
        val st = out.length
        if (m.groups[1] != null) {
            out.append(m.groupValues[1])
            out.setSpan(TypefaceSpan("monospace"), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(BackgroundColorSpan(Palette.FIELD), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(ForegroundColorSpan(Palette.INK), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(RelativeSizeSpan(0.9f), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else if (m.groups[2] != null) {
            out.append(m.groupValues[2])
            out.setSpan(StyleSpan(Typeface.BOLD), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else {
            out.append(m.groupValues[3])
            out.setSpan(StyleSpan(Typeface.ITALIC), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        last = m.range.last + 1
    }
    out.append(src, last, src.length)
    return out
}

/** Pewarnaan sintaks sederhana (komentar, string, angka, kata kunci, fungsi, tipe). Tanpa pustaka. */
internal fun MainActivity.highlight(lang: String, code: String): CharSequence {
    if (code.length > 8000) return code
    val comment = if (lang.trim().lowercase() in hashLangs) "#[^\\n]*"
    else "//[^\\n]*|/\\*[\\s\\S]*?(?:\\*/|\\z)|<!--[\\s\\S]*?(?:-->|\\z)"
    val re = Regex("""($comment)|("(?:\\.|[^"\\\n])*"?|'(?:\\.|[^'\\\n])*'?)|(\b\d+(?:\.\d+)?\b)|([@A-Za-z_][A-Za-z0-9_]*)""")
    val out = SpannableStringBuilder(code)
    fun paint(color: String, a: Int, b: Int, italic: Boolean = false) {
        out.setSpan(ForegroundColorSpan(Color.parseColor(color)), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (italic) out.setSpan(StyleSpan(Typeface.ITALIC), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    for (m in re.findAll(code)) {
        val a = m.range.first
        val b = m.range.last + 1
        when {
            m.groups[1] != null -> paint("#7C8AA5", a, b, true)   // komentar
            m.groups[2] != null -> paint("#86EFAC", a, b)         // string
            m.groups[3] != null -> paint("#FDBA74", a, b)         // angka
            else -> {
                val w = m.value
                when {
                    w in kwSet -> paint("#C4B5FD", a, b)                                  // kata kunci
                    b < code.length && code[b] == '(' -> paint("#7DD3FC", a, b)           // fungsi
                    w[0].isUpperCase() || w[0] == '@' -> paint("#FCD34D", a, b)           // tipe / anotasi
                }
            }
        }
    }
    return out
}

/** Info pagar kode: bahasa dan (bila ada) nama file, mis. "kotlin LiveAgent_UI.kt" atau "kotlin:Main.kt". */
internal class FenceInfo(val lang: String, val file: String)

internal fun parseFence(info: String): FenceInfo {
    val parts = info.trim().split(Regex("[\\s:]+")).filter { it.isNotBlank() }
    val file = parts.firstOrNull { it.contains('.') && it.length > 2 }?.trim('"', '\'') ?: ""
    val lang = parts.firstOrNull { !it.contains('.') } ?: file.substringAfterLast('.', "")
    return FenceInfo(lang, file)
}

/** Nama bahasa yang rapi untuk label kotak kode. */
internal fun langLabel(lang: String): String = when (lang.trim().lowercase()) {
    "" -> "Kode"
    "kt", "kts", "kotlin" -> "Kotlin"
    "java" -> "Java"
    "py", "python" -> "Python"
    "js", "javascript" -> "JavaScript"
    "ts", "typescript" -> "TypeScript"
    "sh", "bash", "shell", "zsh" -> "Bash"
    "xml" -> "XML"
    "json" -> "JSON"
    "yml", "yaml" -> "YAML"
    "md", "markdown" -> "Markdown"
    "gradle" -> "Gradle"
    "html" -> "HTML"
    "css" -> "CSS"
    else -> lang.trim().replaceFirstChar { it.uppercase() }
}

private fun sizeLabel(code: String): String {
    val n = code.toByteArray().size
    return if (n < 1024) "$n B" else String.format(java.util.Locale.US, "%.1f KB", n / 1024.0)
}

/** Simpan potongan kode sebagai file di folder Download (Android 10+); di Android lama disalin ke papan klip. */
internal fun MainActivity.saveCodeFile(name: String, code: String) {
    try {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val cv = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            if (uri == null) { toast("Gagal menyimpan file"); return }
            contentResolver.openOutputStream(uri)?.use { it.write(code.toByteArray()) }
            toast("Tersimpan di Download/$name")
        } else {
            copyText("kode", code)
            toast("Kode disalin ke papan klip")
        }
    } catch (e: Exception) {
        toast("Gagal menyimpan: " + (e.message ?: "").take(60))
    }
}

/**
 * Kotak kode gelap: judul (ikon file + nama file/bahasa, label bahasa di kanan), kolom nomor baris,
 * kode berwarna yang bisa digeser ke samping. Bila ada nama file, kartu file (ukuran + unduh) tampil di bawahnya.
 * Ketuk judul = salin kode.
 */
internal fun MainActivity.codeBlock(info: String, code: String): View {
    val fi = parseFence(info)
    val langName = langLabel(fi.lang)
    val fileIcon = FrameLayout(this).apply {
        background = rounded(0xFFCDD5DC.toInt(), 6)
        addView(IconView(context, IconView.CROSS, 0xFF2A3A47.toInt()), FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
    }
    val title = TextView(this).apply {
        text = if (fi.file.isNotEmpty()) fi.file else langName
        textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Color.WHITE)
        setSingleLine(true)
        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
    }
    val langTv = TextView(this).apply {
        text = langName
        textSize = 13f
        setTextColor(0xFF9AA5B1.toInt())
    }
    val header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Palette.CODE_HEAD)
        setPadding(dp(12), dp(9), dp(12), dp(9))
        addView(fileIcon, LinearLayout.LayoutParams(dp(30), dp(30)))
        addView(title, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(10); marginEnd = dp(8) })
        addView(langTv, LinearLayout.LayoutParams(WRAP, WRAP))
        setOnClickListener {
            copyText("kode", code)
            langTv.text = "Tersalin ✓"
            postDelayed({ langTv.text = langName }, 1500)
        }
    }
    val lines = code.split("\n").size
    val gutter = TextView(this).apply {
        text = (1..lines).joinToString("\n")
        typeface = Typeface.MONOSPACE
        textSize = 13f
        setLineSpacing(0f, 1.15f)
        gravity = Gravity.END
        setTextColor(Palette.CODE_NUM)
        setBackgroundColor(Palette.CODE_GUTTER)
        setPadding(dp(10), dp(10), dp(8), dp(10))
    }
    val codeTv = TextView(this).apply {
        text = highlight(fi.lang, code)
        typeface = Typeface.MONOSPACE
        textSize = 13f
        setLineSpacing(0f, 1.15f)
        setTextColor(0xFFE2E8F0.toInt())
        setTextIsSelectable(true)
        setPadding(dp(12), dp(10), dp(14), dp(10))
    }
    val hs = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(codeTv)
    }
    val body = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(Palette.CODE_BODY)
        addView(gutter, LinearLayout.LayoutParams(WRAP, WRAP))
        addView(hs, LinearLayout.LayoutParams(0, WRAP, 1f))
    }
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Palette.CODE_BODY, 10)
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) {
                o.setRoundRect(0, 0, v.width, v.height, dp(10).toFloat())
            }
        }
        addView(header, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(body, LinearLayout.LayoutParams(MATCH, WRAP))
    }
    val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    wrap.addView(card, LinearLayout.LayoutParams(MATCH, WRAP))
    if (fi.file.isNotEmpty()) {
        val nameTv = TextView(this).apply {
            text = fi.file
            textSize = 14.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Palette.INK)
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        val metaTv = TextView(this).apply {
            text = sizeLabel(code) + " · " + langName.uppercase()
            textSize = 12f
            setTextColor(Palette.TIME_AI)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(nameTv, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(metaTv, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        val fileCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pressable(rounded(Palette.CARD, 10))
            setPadding(dp(10), dp(8), dp(12), dp(8))
            addView(IconView(context, IconView.FILE, 0xFF8A93A0.toInt()), LinearLayout.LayoutParams(dp(34), dp(34)))
            addView(col, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(8); marginEnd = dp(8) })
            addView(IconView(context, IconView.DOWNLOAD, 0xFF6B7380.toInt()), LinearLayout.LayoutParams(dp(24), dp(24)))
            setOnClickListener { saveCodeFile(fi.file, code) }
        }
        wrap.addView(fileCard, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
    }
    return wrap
}
