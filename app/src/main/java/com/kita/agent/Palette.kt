package com.kita.agent

/**
 * Palet berwarna: dasar netral lembut + aksen jelas per jenis aksi.
 * Semua warna aksi dipilih agar kontras di atas latar putih maupun versi pastelnya (lihat [tint]).
 * Murni Kotlin (tanpa android.*) supaya bisa diuji sebagai unit test JVM.
 */
internal object Palette {
    // Merek
    val BRAND = 0xFF0A74D0.toInt()      // biru
    val BRAND_2 = 0xFF00A0E8.toInt()    // biru langit (pasangan gradasi)
    val BRAND_DEEP = 0xFF065AA8.toInt() // teks di atas latar pastel merek

    // Aksen semantik
    val BLUE = 0xFF3B82F6.toInt()
    val VIOLET = 0xFF8B5CF6.toInt()
    val TEAL = 0xFF0D9488.toInt()
    val GREEN = 0xFF16A34A.toInt()
    val AMBER = 0xFFF59E0B.toInt()
    val ORANGE = 0xFFF97316.toInt()
    val RED = 0xFFEF4444.toInt()
    val CYAN = 0xFF0891B2.toInt()
    val PINK = 0xFFEC4899.toInt()
    val INDIGO = 0xFF6366F1.toInt()
    val SLATE = 0xFF475569.toInt()

    // Kompatibilitas nama lama

    // Netral
    val TEXT_MAIN = 0xFF1E2433.toInt()
    val TEXT_SOFT = 0xFF6B7385.toInt()
    val BG = 0xFFEEF6FD.toInt()
    val SURFACE = 0xFFFFFFFF.toInt()
    val BORDER = 0xFFE4E7F0.toInt()

    // Tampilan chat gaya Gemini
    val INK = 0xFF1F1F1F.toInt()        // ikon dan teks utama
    val INK_SOFT = 0xFF6B7280.toInt()   // petunjuk, teks sekunder, panah
    val BUBBLE = 0xFFE8F0FE.toInt()     // gelembung pesan pengguna (biru sangat muda)
    val FIELD = 0xFFF3F5F9.toInt()      // latar kolom isian, pil model, pil status
    val DIVIDER = 0xFFE8EAEF.toInt()    // garis pemisah halus

    /** Latar chat: gradasi biru tua (atas) ke biru langit (bawah), diambil dari latar myBCA. */
    val SKY_TOP = 0xFF085DAD.toInt()
    val SKY_MID = 0xFF0092D7.toInt()
    val SKY_BOTTOM = 0xFF00B6F7.toInt()
    val SKY_COLORS = intArrayOf(SKY_TOP, 0xFF0272BE.toInt(), SKY_MID, 0xFF00A8E6.toInt(), SKY_BOTTOM)
    val SKY_STOPS = floatArrayOf(0f, 0.2f, 0.5f, 0.8f, 1f)

    // Tampilan chat gaya messenger di atas latar biru
    val BAR = 0xFFDDF0FD.toInt()          // bilah atas pucat
    val NAV = 0xFFF5F5F5.toInt()          // bilah navigasi sistem
    val BUBBLE_ME = 0xFFFFFFFF.toInt()    // gelembung pengguna (putih)
    val AI_TEXT = 0xFFFFFFFF.toInt()      // teks balasan AI, langsung di atas latar biru (tanpa kotak)
    val ON_BLUE = 0xFF07121C.toInt()      // teks di atas latar biru
    val ON_BLUE_SOFT = 0xCCFFFFFF.toInt() // teks sekunder putih di atas latar biru
    val TIME_ME = 0xFF8A93A0.toInt()      // jam pada gelembung pengguna (putih)
    val TIME_AI = 0xFF8A93A0.toInt()      // jam pada gelembung putih
    val TICK = 0xFF29B6F6.toInt()         // centang ganda terkirim
    val BALL_CORE = 0xFFFFD27A.toInt()    // bola oranye: pusat terang
    val BALL = 0xFFFF9A1F.toInt()         // bola oranye: badan
    val BALL_EDGE = 0xFFF26B00.toInt()    // bola oranye: tepi
    val CODE_HEAD = 0xFF182935.toInt()    // judul kotak kode
    val CODE_BODY = 0xFF0C171E.toInt()    // isi kotak kode
    val CODE_GUTTER = 0xFF111F2A.toInt()  // kolom nomor baris
    val CODE_NUM = 0xFF5F7384.toInt()     // nomor baris
    val CARD = 0xFFF5F7FB.toInt()         // kartu file di dalam gelembung
}

/** Warna aksen per jenis langkah agen: tiap aksi (baca, cari, tulis, build, ...) punya warna sendiri. */
internal fun accentFor(icon: Int): Int = when (icon) {
    IconView.FILE -> Palette.BLUE
    IconView.GRID -> Palette.VIOLET
    IconView.FOLDER -> Palette.AMBER
    IconView.NEW -> Palette.TEAL
    IconView.GEAR -> Palette.ORANGE
    IconView.TRASH, IconView.STOP -> Palette.RED
    IconView.GLOBE -> Palette.CYAN
    IconView.DOWNLOAD -> Palette.INDIGO
    IconView.CHECK -> Palette.GREEN
    IconView.ARCHIVE -> Palette.PINK
    IconView.CLOCK -> Palette.AMBER
    IconView.TERMINAL, IconView.BLOB_TERM -> Palette.SLATE
    else -> Palette.BRAND
}

/** Campur [color] dengan putih: amount 0 = warna asli, 1 = putih. Alpha tetap penuh. */
internal fun tint(color: Int, amount: Float): Int {
    fun mix(ch: Int) = (ch + (255 - ch) * amount.coerceIn(0f, 1f) + 0.5f).toInt()
    val r = mix((color shr 16) and 0xFF)
    val g = mix((color shr 8) and 0xFF)
    val b = mix(color and 0xFF)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

/** Warna teks yang terbaca di atas latar [bg]: putih untuk latar gelap, hampir hitam untuk latar terang. */
internal fun onColor(bg: Int): Int {
    val r = (bg shr 16) and 0xFF
    val g = (bg shr 8) and 0xFF
    val b = bg and 0xFF
    val lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
    return if (lum > 0.62) 0xFF1E2433.toInt() else 0xFFFFFFFF.toInt()
}

/** Buang emoji dari teks yang tampil (jawaban AI). Simbol teks biasa tidak diubah. */
internal fun stripIcons(s: String): String = s.replace(ICON_RE, "")

private val ICON_RE = Regex("[\\u2705\\u274C\\u2753\\u26A0\\u23F3\\u231B\\uFE0F\\u200D\\x{1F300}-\\x{1FAFF}]")
