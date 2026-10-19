package com.kita.agent

// Pembantu teks murni (tanpa Android) untuk baris status: pemotongan rapi dan penanda nama file. Teruji di TextFitTest.

/**
 * Potong [text] di batas kata supaya muat dalam [maxWidth], lalu akhiri dengan "…". Lebar diukur lewat [measure]
 * (di aplikasi: lebar teks sebenarnya pada layar), jadi fungsi ini tidak bergantung pada Android.
 *
 * - Teks yang sudah muat dikembalikan apa adanya.
 * - Pemotongan jatuh di spasi, bukan di tengah kata ("Memeriksa isi…" dan bukan "Memeriksa is…").
 * - Bila satu kata saja sudah terlalu panjang, kata itu dipotong per huruf.
 * - Tanda baca yang menggantung sebelum "…" dibuang ("Menguji, lalu" -> "Menguji…" bukan "Menguji,…").
 */
internal fun fitWords(text: String, maxWidth: Float, measure: (String) -> Float): String {
    if (maxWidth <= 0f || measure(text) <= maxWidth) return text
    var cut = text.trimEnd('…', ' ')
    while (cut.isNotEmpty() && measure(cut + "…") > maxWidth) {
        val i = cut.lastIndexOf(' ')
        cut = if (i > 0) cut.substring(0, i).trimEnd() else cut.dropLast(1)
    }
    cut = cut.trimEnd(' ', ',', ';', ':', '-', '–', '—', '.')
    return if (cut.isEmpty()) "…" else cut + "…"
}

private val FILE_TOKEN = Regex(
    """[\w.\-]+\.(?:kt|kts|java|xml|gradle|json|md|txt|yml|yaml|py|js|ts|sh|zip|apk|png|jpg|properties)\b""",
    RegexOption.IGNORE_CASE
)

/** Rentang (awal..akhir, inklusif) nama file di dalam [text], mis. "ApkProvider.kt". Dipakai untuk huruf monospasi. */
internal fun fileTokenRanges(text: String): List<IntRange> = FILE_TOKEN.findAll(text).map { it.range }.toList()
