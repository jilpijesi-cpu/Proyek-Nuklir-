package com.kita.agent

private val thinkBlock = Regex("<(think|thinking)(?:\\s[^>]*)?>[\\s\\S]*?</(think|thinking)\\s*>", RegexOption.IGNORE_CASE)
private val thinkOpen = Regex("<(think|thinking)(?:\\s[^>]*)?>[\\s\\S]*$", RegexOption.IGNORE_CASE)
private val responseTag = Regex("</?response(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE)
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

private val thinkInnerRe = Regex("<(?:think|thinking)(?:\\s[^>]*)?>([\\s\\S]*?)(?:</(?:think|thinking)\\s*>|$)", RegexOption.IGNORE_CASE)
private val deskJsonRe = Regex(""""deskripsi"\s*:\s*"((?:\\.|[^"\\]){6,160})"""")

/**
 * Ambil satu kalimat status terbaru dari teks penalaran/berpikir model yang sedang mengalir
 * (baik dari field `thought`/`thinking`/`reasoning_content` maupun isi blok `<think>...</think>`).
 * Mengembalikan null bila belum ada kalimat utuh yang layak tampil (>= 8 karakter).
 */
internal fun extractThoughtStatus(raw: String): String? {
    if (raw.isBlank()) return null
    val source = if (raw.contains('<')) {
        val matches = thinkInnerRe.findAll(raw).toList()
        if (matches.isNotEmpty()) matches.last().groupValues[1] else return null
    } else {
        raw
    }
    val lines = source
        .replace(Regex("```[\\s\\S]*?(?:```|$)"), " ")
        .lines()
        .map { line ->
            line.trim()
                .replace(Regex("^[#*\\-•>\\d.)\\s]+"), "")
                .replace("**", "")
                .replace("__", "")
                .replace("`", "")
                .trim()
        }
        .filter { it.length >= 8 }
    if (lines.isEmpty()) return null
    val latest = lines.last()
    // Jika paragraf panjang terdiri dari beberapa kalimat, ambil kalimat terakhir yang sudah cukup jelas.
    val sentences = latest.split(Regex("(?<=[.!?])\\s+"))
        .map { it.trim().trimEnd('.', '!', '?', ':', ';', ',') }
        .filter { it.length >= 8 }
    val pick = sentences.lastOrNull() ?: latest
    return tidyLine(pick, 90).takeIf { it.length >= 8 }
}

/**
 * Ambil nilai `"deskripsi"` begitu selesai dikirim di dalam stream argumen JSON tool call,
 * sehingga baris status live langsung menampilkan langkah AI bahkan sebelum argumen lainnya selesai mengalir.
 */
internal fun extractStreamingToolDesc(partialJson: String): String? {
    val m = deskJsonRe.findAll(partialJson).lastOrNull() ?: return null
    val unescaped = m.groupValues[1]
        .replace("\\\"", "\"")
        .replace("\\n", " ")
        .replace("\\\\", "\\")
        .trim()
    return tidyLine(unescaped, 90).takeIf { it.length >= 6 }
}
