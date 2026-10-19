package com.kita.agent

import android.content.Context
import java.io.File
import org.json.JSONObject

/** Cara sebuah perintah sampai disetujui. */
enum class ApprovalMode(val label: String) {
    MANUAL("Disetujui manual"),
    ALL_BUTTON("Tombol \"Ya, semua\""),
    ALL_SESSION("\"Ya, semua\" sedang aktif"),
    AUTO_READ("Otomatis (hanya baca)"),
    AUTO_DRAFT("Otomatis (susun kode)")
}

/** Satu catatan: perintah (atau penulisan file) yang disetujui, kapan, dan hasilnya. */
class LogEntry(
    val time: Long,
    val kind: String,           // "perintah" atau "tulis file"
    val command: String,
    val description: String,    // penjelasan dari model (hanya keterangan)
    val risk: Risk,             // dihitung aplikasi, bukan klaim model
    val mode: ApprovalMode,
    val ok: Boolean,
    val result: String,
    val exitCode: Int? = null,      // null bila perintah tidak pernah dijalankan
    val decision: String = "",      // "disetujui", "otomatis", "ditolak", "diblokir"
    val sandbox: Boolean = false,
    val network: Boolean = false
)

/**
 * Ringkas hasil dari termux() untuk dicatat. Isi output TIDAK disimpan untuk area sensitif
 * (kunci SSH, kredensial, dll.) supaya catatan di penyimpanan aplikasi tidak menjadi tempat rahasia menumpuk.
 */
fun summarizeResult(res: String, hideOutput: Boolean, maxChars: Int = 300): String {
    val first = res.lineSequence().firstOrNull().orEmpty().take(80)
    if (hideOutput) return "$first (isi output tidak disimpan: area sensitif)"
    val body = res.substringAfter("\n", "").trim()
    if (body.isEmpty()) return first
    val cut = if (body.length > maxChars) body.take(maxChars) + "…" else body
    return "$first\n$cut"
}

/** Penyimpanan catatan: satu entri JSON per baris di filesDir/command_log.jsonl, dibatasi MAX_KEEP terakhir. */
object CommandLog {
    const val MAX_KEEP = 500
    private const val TRIM_AT = MAX_KEEP + 100
    private val lock = Any()

    private fun file(ctx: Context) = File(ctx.filesDir, "command_log.jsonl")

    private fun toJson(e: LogEntry) = JSONObject()
        .put("t", e.time).put("k", e.kind).put("c", e.command).put("d", e.description)
        .put("r", e.risk.name).put("m", e.mode.name).put("ok", e.ok).put("res", e.result)
        .put("dec", e.decision).put("sbx", e.sandbox).put("net", e.network)
        .apply { e.exitCode?.let { put("x", it) } }

    private fun fromJson(o: JSONObject) = LogEntry(
        time = o.optLong("t"),
        kind = o.optString("k", "perintah"),
        command = o.optString("c"),
        description = o.optString("d"),
        risk = runCatching { Risk.valueOf(o.optString("r")) }.getOrDefault(Risk.WRITE),
        mode = runCatching { ApprovalMode.valueOf(o.optString("m")) }.getOrDefault(ApprovalMode.MANUAL),
        ok = o.optBoolean("ok"),
        result = o.optString("res"),
        exitCode = if (o.has("x")) o.optInt("x") else null,
        decision = o.optString("dec"),
        sandbox = o.optBoolean("sbx"),
        network = o.optBoolean("net")
    )

    fun append(ctx: Context, e: LogEntry) = synchronized(lock) {
        val f = file(ctx)
        f.appendText(toJson(e).toString() + "\n")
        val lines = f.readLines()
        if (lines.size > TRIM_AT) {
            val tmp = File(f.path + ".tmp")
            tmp.writeText(lines.takeLast(MAX_KEEP).joinToString("\n") + "\n")
            if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
        }
    }

    /** Terbaru di depan. Baris yang rusak dilewati. */
    fun load(ctx: Context): List<LogEntry> = synchronized(lock) {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        f.readLines().mapNotNull { line ->
            if (line.isBlank()) null else runCatching { fromJson(JSONObject(line)) }.getOrNull()
        }.asReversed()
    }

    fun clear(ctx: Context) = synchronized(lock) { file(ctx).delete(); Unit }
}
