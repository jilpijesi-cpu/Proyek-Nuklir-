package com.kita.agent

// Logika murni (tanpa android.*) untuk terminal live: pewarnaan perintah bash, kode, dan keluaran perintah,
// penyusunan baris untuk satu langkah agen, dan pencocokan baris baru dari keluaran yang bergeser (tail).
// Dipisah dari tampilan (LiveStatusLine.kt) agar bisa diuji sebagai unit test JVM (TerminalLexTest).

internal enum class TermKind {
    PLAIN, DIM, CMD, FLAG, STR, OP, VAR, KEYWORD, NUM, PATH, TAG, OK, ERR, WARN, TASK, ADD, DEL, PROMPT
}

/** Rentang [start, end) di dalam satu baris yang diberi warna sesuai [kind]. */
internal class TermSpan(val start: Int, val end: Int, val kind: TermKind)

/** Satu baris terminal: teks polos, warna per bagian, dan warna dasar seluruh baris ([whole]). */
internal class TermLine(
    val text: String,
    val spans: List<TermSpan> = emptyList(),
    val whole: TermKind = TermKind.PLAIN
)

// ------------------------------------------------------------------ Bash

private val BASH_TOKEN = Regex(
    """'[^']*'|"(?:[^"\\]|\\.)*"|&&|\|\||2>&1|>>|[|;<>&]|[$]\{?\w+\}?|--?[A-Za-z][\w.=-]*|#.*|\S+"""
)

/** Warnai satu baris perintah bash: perintah (cyan), opsi, string, operator, variabel, komentar. */
internal fun lexBash(line: String): List<TermSpan> {
    val out = ArrayList<TermSpan>()
    var cmdPos = true
    for (m in BASH_TOKEN.findAll(line)) {
        val t = m.value
        val kind: TermKind = when {
            t.startsWith("#") -> TermKind.DIM
            t == "&&" || t == "||" || t == "|" || t == ";" -> {
                cmdPos = true
                TermKind.OP
            }
            t == ">" || t == ">>" || t == "<" || t == "2>&1" || t == "&" -> TermKind.OP
            t.startsWith("'") || t.startsWith("\"") -> {
                cmdPos = false
                TermKind.STR
            }
            t.first() == '$' -> {
                cmdPos = false
                TermKind.VAR
            }
            t.first() == '-' && !cmdPos -> TermKind.FLAG
            cmdPos -> {
                cmdPos = false
                TermKind.CMD
            }
            t.contains('/') -> TermKind.PATH
            else -> TermKind.PLAIN
        }
        if (kind != TermKind.PLAIN) out.add(TermSpan(m.range.first, m.range.last + 1, kind))
    }
    return out
}

// ------------------------------------------------------------------ Kode (Kotlin/Java/XML/Gradle/Python/sh)

private val CODE_TOKEN = Regex(
    """//.*|"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'|@[A-Za-z]\w*|</?[A-Za-z][\w.:-]*|/?>|\b\d+(?:\.\d+)?\b|\b[A-Za-z_]\w*\b"""
)

private val KEYWORDS = setOf(
    "fun", "val", "var", "class", "object", "interface", "import", "package", "return", "if", "else", "for", "while",
    "when", "do", "try", "catch", "finally", "throw", "private", "public", "protected", "internal", "override",
    "const", "lateinit", "data", "enum", "sealed", "abstract", "open", "companion", "init", "is", "in", "as",
    "true", "false", "null", "this", "super", "new", "static", "final", "void", "int", "def", "from", "echo",
    "plugins", "dependencies", "implementation", "android", "fi", "then", "done", "function", "const", "let"
)

/** Warnai satu baris kode: komentar, string, angka, kata kunci, anotasi, tag XML. */
internal fun lexCode(line: String): List<TermSpan> {
    val t = line.trimStart()
    if (t.startsWith("#") || t.startsWith("/*") || t.startsWith("*") || t.startsWith("<!--")) {
        return listOf(TermSpan(0, line.length, TermKind.DIM))
    }
    val out = ArrayList<TermSpan>()
    for (m in CODE_TOKEN.findAll(line)) {
        val s = m.value
        val kind: TermKind? = when {
            s.startsWith("//") -> TermKind.DIM
            s.startsWith("\"") || s.startsWith("'") -> TermKind.STR
            s.startsWith("@") -> TermKind.KEYWORD
            s.startsWith("<") || s == "/>" || s == ">" -> TermKind.TAG
            s.first().isDigit() -> TermKind.NUM
            s in KEYWORDS -> TermKind.KEYWORD
            else -> null
        }
        if (kind != null) out.add(TermSpan(m.range.first, m.range.last + 1, kind))
    }
    return out
}

// ------------------------------------------------------------------ Keluaran perintah

private val NUM_OR_PATH = Regex("""(?:~|\.{1,2})?(?:/[\w.@+-]+)+/?|\b\d[\d.,:]*\b""")

/** Warna bagian dalam baris keluaran biasa: path (ungu) dan angka (oranye). */
internal fun lexOutput(line: String): List<TermSpan> {
    val out = ArrayList<TermSpan>()
    for (m in NUM_OR_PATH.findAll(line)) {
        val kind = if (m.value.first().isDigit()) TermKind.NUM else TermKind.PATH
        out.add(TermSpan(m.range.first, m.range.last + 1, kind))
    }
    return out
}

/** Warna dasar satu baris keluaran. [diff] = baris diff file (+ / -). */
internal fun kindOfOutput(line: String, diff: Boolean = false): TermKind {
    if (diff) {
        if (line.startsWith("+") && !line.startsWith("+++")) return TermKind.ADD
        if (line.startsWith("-") && !line.startsWith("---")) return TermKind.DEL
    }
    val s = line.trim()
    if (s.isEmpty()) return TermKind.PLAIN
    // Satu kata tanpa spasi yang berupa path/nama file (hasil ls, find) jangan ikut dianggap error.
    if (!s.contains(' ') && (s.contains('/') || s.contains('.')) && !s.endsWith(":")) return TermKind.PLAIN
    val low = s.lowercase()
    return when {
        s.startsWith("> Task") || s.startsWith("> Configure") || s.startsWith("> Transform") -> TermKind.TASK
        low.contains("build successful") || s.startsWith("✓") -> TermKind.OK
        low.contains("build failed") || low.startsWith("e: ") || low.contains("error:") ||
            low.contains("exception") || low.contains("no such file") || low.contains("permission denied") ||
            low.contains("command not found") || low.contains("fatal:") ||
            (low.contains("failed") && !low.contains("0 failed")) -> TermKind.ERR
        low.startsWith("w: ") || low.contains("warning") || low.contains("deprecated") -> TermKind.WARN
        s.length > 3 && s.all { it == '-' || it == '=' || it == ' ' || it == '+' } -> TermKind.DIM
        else -> TermKind.PLAIN
    }
}

/** Apakah teks tampak seperti diff file buatan buildDiff ("+ baris", "- baris", "  baris", "  ⋯ n baris sama"). */
internal fun looksLikeDiff(text: String): Boolean {
    val ls = text.lines().filter { it.isNotBlank() }
    if (ls.isEmpty()) return false
    val marked = ls.any { it.startsWith("+ ") || it.startsWith("- ") }
    return marked && ls.all { it.startsWith("+ ") || it.startsWith("- ") || it.startsWith("  ") }
}

// ------------------------------------------------------------------ Penyusunan baris satu langkah

private const val MAX_LINE_CHARS = 400
private const val MAX_CMD_LINES = 12

private fun shifted(spans: List<TermSpan>, by: Int): List<TermSpan> = spans.map { TermSpan(it.start + by, it.end + by, it.kind) }

private fun clip(s: String): String = if (s.length > MAX_LINE_CHARS) s.take(MAX_LINE_CHARS - 1) + "…" else s

/** Baris-baris perintah (dengan prompt "$ ") yang sudah diwarnai. */
private fun commandLines(raw: String): List<TermLine> {
    val all = raw.lines()
    val shown = all.take(MAX_CMD_LINES)
    val out = ArrayList<TermLine>()
    for ((i, l0) in shown.withIndex()) {
        val l = clip(l0)
        val prompt = if (i == 0) "$ " else "> "
        val spans = ArrayList<TermSpan>()
        spans.add(TermSpan(0, 1, if (i == 0) TermKind.PROMPT else TermKind.DIM))
        spans.addAll(shifted(lexBash(l), prompt.length))
        out.add(TermLine(prompt + l, spans))
    }
    if (all.size > shown.size) out.add(TermLine("  … +${all.size - shown.size} baris perintah", emptyList(), TermKind.DIM))
    return out
}

/** Baris kode bernomor untuk isi file yang sedang ditulis. */
private fun numberedCode(content: String, limit: Int): List<TermLine> {
    val ls = content.lines()
    val out = ArrayList<TermLine>()
    for ((i, l0) in ls.take(limit).withIndex()) {
        val gutter = String.format(java.util.Locale.ROOT, "%3d │ ", i + 1)
        val l = clip(l0)
        val spans = ArrayList<TermSpan>()
        spans.add(TermSpan(0, gutter.length, TermKind.DIM))
        spans.addAll(shifted(lexCode(l), gutter.length))
        out.add(TermLine(gutter + l, spans))
    }
    if (ls.size > limit) out.add(TermLine("    … +${ls.size - limit} baris lagi", emptyList(), TermKind.DIM))
    return out
}

/**
 * Susun baris terminal untuk satu langkah agen.
 * - Perintah: "$ perintah" lalu keluarannya (live saat berjalan, lengkap saat selesai).
 * - Tulis file: judul path, lalu isi bernomor (saat berjalan) atau diff / isi akhir (saat selesai).
 */
internal fun termLinesFor(step: AgentStep, maxLines: Int = 160): List<TermLine> {
    val lines = ArrayList<TermLine>()
    val active = step.status == AgentStepStatus.RUNNING || step.status == AgentStepStatus.WAITING
    val cmd = step.fullCommand.trim()

    if (step.type != AgentStepType.TOOL) {
        if (cmd.isNotEmpty()) lines.addAll(commandLines(cmd.removePrefix("$ ")))
        var out = step.output
        if (cmd.isNotEmpty() && out.trimStart().startsWith(cmd)) out = out.trimStart().removePrefix(cmd)
        out = out.trim('\n', '\r')
        if (out.isNotEmpty()) {
            for (l0 in out.lines().takeLast(maxLines)) {
                val l = clip(l0.trimEnd())
                val kind = kindOfOutput(l)
                lines.add(TermLine(l, if (kind == TermKind.PLAIN) lexOutput(l) else emptyList(), kind))
            }
        } else if (!active) {
            lines.add(TermLine("(tanpa keluaran)", emptyList(), TermKind.DIM))
        }
        return lines
    }

    // write_file: baris pertama fullCommand = path, sisanya isi file (maks 1500 karakter)
    val path = cmd.lineSequence().firstOrNull().orEmpty()
    lines.add(TermLine("✎ $path", emptyList(), TermKind.CMD))
    if (active || step.output.isBlank()) {
        val content = cmd.substringAfter("\n", "").trim('\n')
        if (content.isNotEmpty()) lines.addAll(numberedCode(content, maxLines))
    } else {
        val body = step.output.trim('\n', '\r')
        if (looksLikeDiff(body)) {
            for (l0 in body.lines().takeLast(maxLines)) {
                val l = clip(l0.trimEnd())
                val kind = kindOfOutput(l, diff = true)
                lines.add(TermLine(l, emptyList(), if (kind == TermKind.PLAIN && l.trimStart().startsWith("⋯")) TermKind.DIM else kind))
            }
        } else {
            lines.addAll(numberedCode(body, maxLines))
        }
    }
    return lines
}

// ------------------------------------------------------------------ Keluaran live yang bergeser

/**
 * Keluaran live dibaca dengan `tail`, jadi jendelanya bergeser. Fungsi ini mencari ujung baris yang sudah tampil
 * ([shown]) di dalam [latest] dan mengembalikan baris yang benar-benar baru. Mengembalikan null bila tidak ada
 * kecocokan (isi berganti total): pemanggil harus menampilkan ulang dari awal.
 */
internal fun newLinesAfter(shown: List<String>, latest: List<String>): List<String>? {
    if (shown.isEmpty()) return latest
    val k = minOf(3, shown.size)
    val anchor = shown.takeLast(k)
    var j = latest.size - k
    while (j >= 0) {
        var same = true
        for (i in 0 until k) {
            if (latest[j + i] != anchor[i]) {
                same = false
                break
            }
        }
        if (same) return latest.subList(j + k, latest.size)
        j--
    }
    return null
}

/** "00:07", "01:32", "1:02:03" dari milidetik. */
internal fun formatElapsed(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0L)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(java.util.Locale.ROOT, "%02d:%02d", m, sec)
}

/** Judul pendek untuk chip langkah: nomor + judul dipotong. */
internal fun chipLabel(index: Int, title: String, max: Int = 20): String {
    val t = title.trim().replace(Regex("""\s+"""), " ")
    val cut = if (t.length > max) t.take(max - 1).trimEnd() + "…" else t
    return "${index + 1} · $cut"
}
