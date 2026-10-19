package com.kita.agent

private val thinkBlock = Regex("<(think|thinking)>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE)
private val thinkOpen = Regex("<(think|thinking)>[\\s\\S]*$", RegexOption.IGNORE_CASE)
private val responseTag = Regex("</?response>", RegexOption.IGNORE_CASE)
private val tagTail = Regex("<[/a-zA-Z]{0,10}$")
private val knownTags = listOf("<thinking>", "<think>", "</thinking>", "</think>", "<response>", "</response>")

/**
 * Sebagian model menulis proses berpikirnya sebagai teks: <thinking>...</thinking> lalu <response>...</response>.
 * Fungsi ini membuang blok berpikir (juga yang belum ditutup saat teks masih mengalir), membuka bungkus <response>,
 * dan memotong awalan tag yang baru setengah datang ("<thi"). Isi blok kode (```) tidak disentuh.
 * Hasilnya selalu berupa awalan dari hasil teks yang lebih lengkap, jadi aman dipakai untuk efek ketik.
 */
internal fun stripModelTags(raw: String): String {
    if (!raw.contains('<')) return raw
    val parts = raw.split("```")
    val out = parts.mapIndexed { i, p ->
        if (i % 2 == 1) p else cleanSegment(p, last = i == parts.lastIndex)
    }
    return out.joinToString("```").trim()
}

private fun cleanSegment(p: String, last: Boolean): String {
    var s = thinkBlock.replace(p, "")
    s = thinkOpen.replace(s, "")
    s = responseTag.replace(s, "")
    if (last) {
        val tail = tagTail.find(s)
        if (tail != null) {
            val t = tail.value.lowercase()
            if (knownTags.any { it.startsWith(t) }) s = s.removeRange(tail.range)
        }
    }
    return s
}
