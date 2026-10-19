package com.kita.agent

import org.json.JSONObject

// Memilih ikon langkah agen sesuai apa yang sedang dikerjakan (baca, cari, tulis, build, ...).
// iconForCommand() murni Kotlin sehingga bisa di-unit-test (ActionIconsTest).
// Untuk menambah jenis aksi: tambahkan satu baris di COMMAND_RULES (urutan = prioritas, yang pertama cocok menang).
// Urutan: deskripsi AI (DESC_RULES) lebih dulu agar sejalan dengan judul langkah; bila tidak cocok, perintah (COMMAND_RULES); bila tetap tidak ada -> ikon terminal.

private class IconRule(val icon: Int, val pattern: Regex)

private val COMMAND_RULES = listOf(
    IconRule(IconView.GEAR, Regex("""\b(gradlew|gradle|assemble\w*|make|cmake|ninja)\b""")),
    IconRule(IconView.ARCHIVE, Regex("""\b(unzip|zip|tar|gunzip|gzip|7z|unrar)\b""")),
    IconRule(IconView.TRASH, Regex("""\brm\b""")),
    IconRule(IconView.GLOBE, Regex("""\b(curl|wget|ping|nc|ssh|scp)\b""")),
    IconRule(
        IconView.DOWNLOAD,
        Regex("""\b(pkg|apt|apt-get|pip|pip3|npm|yarn)\s+(install|add|update|upgrade)\b|\bgit\s+(clone|pull|fetch)\b""")
    ),
    IconRule(IconView.CHECK, Regex("""\b(test\w*|lint\w*)\b""")),
    // menulis/mengubah: sed -i, tee, atau pengalihan > / >> (bukan 2>&1 atau > /dev/null)
    IconRule(IconView.NEW, Regex("""\bsed\s+(-\w*i\b|--in-place)|\btee\b|(?<![\d&>=-])>{1,2}\s*(?!/dev/null)[^\s&>]""")),
    IconRule(IconView.GRID, Regex("""\b(grep|egrep|rg|find|locate|which|whereis)\b""")),
    IconRule(IconView.FILE, Regex("""\b(cat|head|tail|less|more|sed|awk|wc|nl|stat|file|diff|xxd)\b""")),
    IconRule(IconView.FOLDER, Regex("""\b(ls|tree|cd|pwd|du|mkdir|mv|cp|touch|chmod)\b"""))
)

private val DESC_RULES = listOf(
    IconRule(IconView.GEAR, Regex("""build|membangun|kompil""")),
    IconRule(IconView.ARCHIVE, Regex("""zip|arsip""")),
    IconRule(IconView.GRID, Regex("""cari|mencari|menelusuri|informasi""")),
    IconRule(IconView.FILE, Regex("""baca|membaca|periksa|memeriksa|lihat|melihat""")),
    IconRule(IconView.NEW, Regex("""tulis|menulis|menyusun|mengubah|memperbaiki|membuat|edit""")),
    IconRule(IconView.DOWNLOAD, Regex("""unduh|mengunduh|memasang|pasang|install""")),
    IconRule(IconView.CHECK, Regex("""\b(uji|menguji|verifikasi|memastikan|tes|mengetes)\b"""))
)

/** Ikon (konstanta IconView.*) untuk satu langkah. [tool] = nama alat, [command] = perintah bash bila ada. */
internal fun iconForCommand(tool: String, command: String, desc: String = ""): Int {
    if (tool == "write_file") return IconView.NEW
    // Utamakan kalimat deskripsi AI: itulah judul yang dibaca pengguna, jadi ikon dan judul selalu sejalan.
    val d = desc.lowercase()
    if (d.isNotBlank()) DESC_RULES.firstOrNull { it.pattern.containsMatchIn(d) }?.let { return it.icon }
    val c = command.lowercase()
    COMMAND_RULES.firstOrNull { it.pattern.containsMatchIn(c) }?.let { return it.icon }
    return IconView.BLOB_TERM
}

internal fun iconFor(name: String, args: JSONObject, desc: String = ""): Int =
    iconForCommand(name, args.optString("command"), desc)
