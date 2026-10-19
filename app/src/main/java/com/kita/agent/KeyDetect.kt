package com.kita.agent

// Mengenali penyedia AI dari bentuk API key (awalan dan panjang).
// Murni Kotlin tanpa Android, jadi bisa di-unit-test (lihat KeyDetectTest).
// Nama yang dikembalikan harus sama dengan Preset.name di MainActivity.presets.
// Untuk menambah penyedia baru: tambahkan satu baris di when(...) di detectProvider().

/** Key lebih pendek dari ini tidak dianggap (mencegah salah deteksi saat baru mengetik). */
internal const val KEY_MIN_LEN = 20

/** Membersihkan hasil tempel: spasi, awalan "Bearer ", dan tanda kutip di sekeliling key. */
internal fun normalizeKey(raw: String): String {
    var k = raw.trim()
    if (k.startsWith("Bearer ", ignoreCase = true)) k = k.substring(7).trim()
    return k.trim('"', '\'', '`').trim()
}

private val DEEPSEEK_KEY = Regex("^sk-[a-f0-9]{32}$")

/** Nama preset yang cocok dengan key ini, atau null bila bentuknya tidak dikenali. */
internal fun detectProvider(raw: String): String? {
    val k = normalizeKey(raw)
    if (k.length < KEY_MIN_LEN || k.any { it.isWhitespace() }) return null
    return when {
        k.startsWith("sk-ant-") -> "Anthropic"
        k.startsWith("sk-or-") -> "OpenRouter"
        k.startsWith("gsk_") -> "Groq"
        k.startsWith("AIza") -> "Gemini"
        DEEPSEEK_KEY.matches(k) -> "DeepSeek"
        k.startsWith("sk-") -> "OpenAI"
        else -> null
    }
}
