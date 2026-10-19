package com.kita.agent

import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan

// Tampilan diff file (hijau = tambah, merah = hapus).
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

/** Hasilkan tampilan diff berwarna dan ringkasannya ("+a −d"). */
internal fun MainActivity.buildDiff(oldText: String, newText: String): Pair<CharSequence, String> {
    val a = if (oldText.isEmpty()) emptyList<String>() else oldText.removeSuffix("\n").split("\n")
    val b = if (newText.isEmpty()) emptyList<String>() else newText.removeSuffix("\n").split("\n")
    var pre = 0
    while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
    var suf = 0
    while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
    val am = a.subList(pre, a.size - suf)
    val bm = b.subList(pre, b.size - suf)
    val ops = ArrayList<Pair<Char, String>>()
    for (i in 0 until pre) ops.add(Pair(' ', a[i]))
    val n = am.size
    val m = bm.size
    if (n.toLong() * m <= 1_000_000L) {
        val w = m + 1
        val dp = IntArray((n + 1) * w)
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            dp[i * w + j] = if (am[i] == bm[j]) dp[(i + 1) * w + j + 1] + 1
            else maxOf(dp[(i + 1) * w + j], dp[i * w + j + 1])
        }
        var i = 0
        var j = 0
        while (i < n && j < m) {
            if (am[i] == bm[j]) { ops.add(Pair(' ', am[i])); i++; j++ }
            else if (dp[(i + 1) * w + j] >= dp[i * w + j + 1]) { ops.add(Pair('-', am[i])); i++ }
            else { ops.add(Pair('+', bm[j])); j++ }
        }
        while (i < n) { ops.add(Pair('-', am[i])); i++ }
        while (j < m) { ops.add(Pair('+', bm[j])); j++ }
    } else {
        for (x in am) ops.add(Pair('-', x))
        for (x in bm) ops.add(Pair('+', x))
    }
    for (i in a.size - suf until a.size) ops.add(Pair(' ', a[i]))

    val added = ops.count { it.first == '+' }
    val removed = ops.count { it.first == '-' }
    // Tampilkan baris yang berubah + 2 baris konteks; sisanya diringkas.
    val show = BooleanArray(ops.size)
    for ((k, o) in ops.withIndex()) if (o.first != ' ') {
        for (d in -2..2) if (k + d in ops.indices) show[k + d] = true
    }
    val out = SpannableStringBuilder()
    var skipped = 0
    var lines = 0
    fun flushSkip() {
        if (skipped > 0) {
            val st = out.length
            out.append("  ⋯ $skipped baris sama\n")
            out.setSpan(ForegroundColorSpan(Palette.TEXT_SOFT), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            skipped = 0
        }
    }
    var cut = 0
    for ((k, o) in ops.withIndex()) {
        if (!show[k]) { skipped++; continue }
        if (lines >= 300) { cut++; continue }
        flushSkip()
        val st = out.length
        out.append(o.first).append(' ').append(o.second).append('\n')
        when (o.first) {
            '+' -> {
                out.setSpan(DiffBg(Color.parseColor("#DCFCE7")), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(ForegroundColorSpan(Color.parseColor("#166534")), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            '-' -> {
                out.setSpan(DiffBg(Color.parseColor("#FEE2E2")), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(ForegroundColorSpan(Color.parseColor("#991B1B")), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            else -> out.setSpan(ForegroundColorSpan(Palette.TEXT_SOFT), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        lines++
    }
    flushSkip()
    if (cut > 0) out.append("  ⋯ dan $cut baris lagi\n")
    if (added == 0 && removed == 0) return Pair("(isi file tidak berubah)", "tanpa perubahan")
    return Pair(out, "+$added −$removed")
}
