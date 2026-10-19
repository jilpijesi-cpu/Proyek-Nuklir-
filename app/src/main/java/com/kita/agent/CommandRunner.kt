package com.kita.agent

// Antarmuka penjalan perintah. UI dan alur persetujuan hanya mengenal CommandRunner,
// jadi runner lokal (SandboxRunner) bisa diganti runner remote (Docker/VPS) tanpa mengubah UI.
// Murni JVM (tanpa Android) agar bisa diuji.

/** Batas per perintah. Output dihitung dalam BYTE UTF-8. */
data class RunLimits(
    val timeoutSec: Long = DEFAULT_TIMEOUT_SEC,
    val maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES
) {
    init {
        require(timeoutSec in 1..7200) { "timeoutSec harus 1..7200" }
        require(maxOutputBytes in 100..500_000) { "maxOutputBytes harus 100..500000" }
    }

    companion object {
        const val DEFAULT_TIMEOUT_SEC = 120L
        const val DEFAULT_MAX_OUTPUT_BYTES = 20_000
    }
}

data class RunRequest(
    val command: String,
    val limits: RunLimits = RunLimits(),
    /** File di sisi Termux tempat keluaran juga ditulis selama berjalan (untuk tampilan live). */
    val liveFile: String? = null
)

data class RunResult(
    val exitCode: Int,
    /** Sudah termasuk catatan "dipotong" / "timeout" / petunjuk bila ada. */
    val output: String,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
    val durationMs: Long = 0,
    val sandboxed: Boolean = false
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut

    /** Format lama "exit=N\n<output>" yang dipahami runTool, summarizeResult dan AgentGuard. */
    fun toLegacyString(): String = "exit=$exitCode\n$output"
}

interface CommandRunner {
    /** Blokir sampai selesai. Pemanggil bertanggung jawab atas persetujuan SEBELUM memanggil ini. */
    fun run(request: RunRequest): RunResult
}

object OutputLimiter {
    const val MARK = "…(dipotong: keluaran melewati batas ukuran)"

    /** Potong [raw] ke [maxBytes] byte UTF-8. Mengembalikan teks dan apakah ada yang dibuang. */
    fun limit(raw: String, maxBytes: Int): Pair<String, Boolean> {
        val bytes = raw.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return raw to false
        return (String(bytes, 0, maxBytes, Charsets.UTF_8) + "\n" + MARK) to true
    }
}

private val EXIT_RE = Regex("""(?m)^exit=(-?\d+)[^\n]*\n?""")

/**
 * Pisahkan hasil termux() ("[ERR: ...\n]exit=N\n<isi>") menjadi kode keluar dan isi.
 * Kode null bila tidak ada baris exit= (mis. "Gagal memanggil Termux: ...").
 */
internal fun parseTermuxResult(raw: String): Pair<Int?, String> {
    val m = EXIT_RE.find(raw) ?: return null to raw
    val code = m.groupValues[1].toIntOrNull()
    return code to (raw.substring(0, m.range.first) + raw.substring(m.range.last + 1))
}
