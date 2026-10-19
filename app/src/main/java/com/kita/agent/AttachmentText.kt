package com.kita.agent

// Murni Kotlin (tanpa android.*) supaya bisa diuji sebagai unit test JVM.

private val attachLineRe = Regex("\n(Gambar|File) terlampir di \"([^\"]+)\"")

/** Teks bawaan saat pengguna hanya mengirim lampiran tanpa mengetik apa pun (lihat send()). */
internal const val DEFAULT_ATTACH_TEXT = "Periksa lampiran dan gunakan sebagai konteks tugas."

/** Satu lampiran pada pesan pengguna: gambar atau file biasa, dengan path di penyimpanan. */
internal class ChatAttachment(val isImage: Boolean, val path: String) {
    val name: String get() = path.substringAfterLast('/')
}

/**
 * Pisahkan pesan pengguna menjadi teks yang ditampilkan dan daftar lampiran.
 * Baris "Gambar/File terlampir di ..." tetap dikirim ke AI, tetapi di layar chat diganti kartu file dan gambar mini.
 */
internal fun splitAttachments(text: String): Pair<String, List<ChatAttachment>> {
    val list = attachLineRe.findAll(text).map { ChatAttachment(it.groupValues[1] == "Gambar", it.groupValues[2]) }.toList()
    if (list.isEmpty()) return text to emptyList()
    var shown = attachLineRe.replace(text, "").trim()
    if (shown == DEFAULT_ATTACH_TEXT) shown = ""
    return shown to list
}

/** Ukuran file yang mudah dibaca: "500 B", "2,0 kB", "3,0 MB" (titik desimal, agar sama di semua bahasa perangkat). */
internal fun formatSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f kB", bytes / 1024.0)
    else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
}
