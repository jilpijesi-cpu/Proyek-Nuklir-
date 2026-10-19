package com.kita.agent

import java.util.Locale

// Logika murni (tanpa android.*) untuk UI aktivitas AI: status langkah, progres nyata, teks tengah, laporan akhir.
// Dipisah dari tampilan supaya bisa diuji sebagai unit test JVM (ActivityFeedTest, ActivityModelTest).

internal enum class StepState { RUNNING, WAITING, OK, FAIL, DENIED, STOPPED }

/** Format durasi untuk manusia: "850 md", "1,2 dtk", "2 mnt 5 dtk". Murni Kotlin (bisa diuji). */
internal fun fmtDur(ms: Long): String = when {
    ms < 0 -> ""
    ms < 1000 -> "$ms md"
    ms < 60_000 -> String.format(Locale("id", "ID"), "%.1f dtk", ms / 1000.0)
    else -> "${ms / 60_000} mnt ${(ms % 60_000) / 1000} dtk"
}

/** Tebak status langkah dari keluaran alat yang tersimpan di riwayat (untuk memulihkan feed setelah aplikasi dibuka ulang). */
internal fun stateOfResult(out: String?): StepState = when {
    out == null -> StepState.STOPPED
    out.startsWith("Ditolak") -> StepState.DENIED
    out.startsWith("exit=0") || out.trim().endsWith("OK") -> StepState.OK
    else -> StepState.FAIL
}

/**
 * Teks kecil di kanan kartu: persentase NYATA bila keluaran perintah melaporkannya, kalau tidak waktu berjalan/selesai,
 * atau alasan (gagal, ditolak, dihentikan).
 */
internal fun noteFor(state: StepState, note: String, elapsedMs: Long, percent: Int? = null): String = when (state) {
    StepState.RUNNING -> if (percent != null) "$percent%" else fmtDur(elapsedMs)
    StepState.OK -> fmtDur(elapsedMs)
    StepState.WAITING -> "menunggu izin"
    StepState.FAIL -> note.trim().ifBlank { "gagal" }
    StepState.DENIED -> note.trim().ifBlank { "ditolak" }
    StepState.STOPPED -> note.trim().ifBlank { "dihentikan" }
}

// ------------------------------------------------------------------ Jenis aktivitas + progres NYATA

internal enum class ActivityKind { DOWNLOAD, INSTALL, BUILD, OTHER }

// Awal sebuah perintah dalam rangkaian "a && b | c; d": setelah ; & | ( atau baris baru, boleh didahului sudo/time/nohup dan path ./
private const val SEG = """(?:^|[;&|(\n]\s*)(?:(?:sudo|time|nohup)\s+)*(?:\./|[\w./~-]*/)?"""
private val BUILD_RE = Regex(
    SEG + """(?:(?:gradlew|gradle)\b[^;&|\n]*\b(?:assemble\w*|build|bundle\w*|install\w*|compile\w*|test\w*|check|lint\w*)\b|(?:cmake|ninja|make)\b|apktool\s+b\b|apksigner\s+verify\b|aapt2?\s+dump\b|zipalign\b)"""
)
private val INSTALL_RE = Regex(
    SEG + """(?:(?:pkg|apt|apt-get|pip3?|npm|yarn|pnpm|gem|cargo)\s+(?:-\S+\s+)*(?:install|add|i)\b|npm\s+ci\b)"""
)
private val DOWNLOAD_RE = Regex(
    SEG + """(?:(?:curl|wget|aria2c)\b|git\s+(?:clone|pull|fetch)\b|(?:pkg|apt|apt-get)\s+(?:-\S+\s+)*(?:update|upgrade)\b|sdkmanager\b)"""
)

/** Jenis aktivitas dari satu perintah shell. Urutan prioritas: build, instalasi, unduhan. */
internal fun activityKindOf(command: String): ActivityKind = when {
    BUILD_RE.containsMatchIn(command) -> ActivityKind.BUILD
    INSTALL_RE.containsMatchIn(command) -> ActivityKind.INSTALL
    DOWNLOAD_RE.containsMatchIn(command) -> ActivityKind.DOWNLOAD
    else -> ActivityKind.OTHER
}

/** Jenis aktivitas dari teks detail langkah: perintah berawalan "$ ", sedangkan tulis file = OTHER. */
internal fun activityKindOfDetail(detail: String): ActivityKind =
    if (detail.startsWith("$ ")) activityKindOf(detail.removePrefix("$ ")) else ActivityKind.OTHER

private val PCT_RE = Regex("""(?<![\w.])(\d{1,3})(?:[.,]\d+)?\s?%""")
private val SIZE_RE = Regex("""(\d+(?:\.\d+)?)\s*/\s*(\d+(?:\.\d+)?)\s*[kKMGT]i?B\b""")
private val BRACKET_RE = Regex("""\[\s*(\d+)\s*/\s*(\d+)\s*]""")
// Baris tabel progres curl: "% Total  Total  % Received  Received  % Xferd  Xferd  speed ..." -> kolom pertama = % dari total
private val CURL_ROW_RE = Regex("""^\s*(\d{1,3})\s+[\d.]+[kMGTP]?\s+\d{1,3}\s+[\d.]+[kMGTP]?\s+\d+\s+\d+\s+[\d.]+[kMGTP]?\s+\d+\s+[\d:-]+\s+[\d:-]+\s+[\d:-]+""")
// Persentase yang BUKAN progres tugas (mis. dpkg membaca database paket, df/du).
private val NOISE_RE = Regex("""Reading database|Use%|Capacity|Memory|Mem:""", RegexOption.IGNORE_CASE)

/**
 * Persentase NYATA dari keluaran perintah yang sedang berjalan (0-100), atau null bila keluaran tidak melaporkannya.
 * Tidak pernah menebak: gradle tanpa konsol interaktif, misalnya, tidak mencetak persen, jadi hasilnya null.
 * Yang diambil adalah penanda terakhir di keluaran. Dikenali: "45%" (git, wget, curl -#, apt, pip, cmake, gradle rich),
 * tabel progres curl, "1.2/3.4 MB" (pip), dan "[12/340]" (ninja/make) untuk build/instalasi.
 */
internal fun parseProgress(text: CharSequence, kind: ActivityKind): Int? {
    if (kind == ActivityKind.OTHER) return null
    val segs = text.toString().split('\r', '\n')
    for (i in segs.indices.reversed()) {
        val s = segs[i]
        if (s.isBlank() || NOISE_RE.containsMatchIn(s)) continue
        CURL_ROW_RE.find(s)?.let { m -> m.groupValues[1].toIntOrNull()?.takeIf { it in 0..100 }?.let { return it } }
        PCT_RE.findAll(s).toList().asReversed().forEach { m ->
            m.groupValues[1].toIntOrNull()?.takeIf { it in 0..100 }?.let { return it }
        }
        if (kind != ActivityKind.DOWNLOAD) {
            BRACKET_RE.findAll(s).toList().asReversed().forEach { m ->
                val a = m.groupValues[1].toLongOrNull(); val b = m.groupValues[2].toLongOrNull()
                if (a != null && b != null && b > 0 && a <= b) return (a * 100 / b).toInt()
            }
        }
        if (kind != ActivityKind.BUILD) {
            SIZE_RE.findAll(s).toList().asReversed().forEach { m ->
                val a = m.groupValues[1].toDoubleOrNull(); val b = m.groupValues[2].toDoubleOrNull()
                if (a != null && b != null && b > 0 && a <= b) return (a * 100 / b).toInt()
            }
        }
    }
    return null
}

internal enum class BarMode { HIDDEN, INDETERMINATE, DETERMINATE }

/** [complete] = langkah benar-benar selesai OK (bar penuh lalu memudar). */
internal class BarSpec(val mode: BarMode, val fraction: Float = 0f, val complete: Boolean = false)

/**
 * Apa yang digambar bar progres kartu. Angka hanya dari [percent] nyata atau dari selesainya langkah (OK = penuh).
 * Tanpa angka nyata saat berjalan: bar tak-tentu (tanpa persen). Tanpa data dan tidak berjalan: bar disembunyikan.
 */
internal fun barSpec(kind: ActivityKind, state: StepState, percent: Int?): BarSpec {
    if (kind == ActivityKind.OTHER) return BarSpec(BarMode.HIDDEN)
    val frac = percent?.coerceIn(0, 100)?.div(100f)
    return when (state) {
        StepState.OK -> BarSpec(BarMode.DETERMINATE, 1f, complete = true)
        StepState.RUNNING -> if (frac != null) BarSpec(BarMode.DETERMINATE, frac) else BarSpec(BarMode.INDETERMINATE)
        StepState.WAITING, StepState.FAIL, StepState.STOPPED ->
            if (frac != null) BarSpec(BarMode.DETERMINATE, frac) else BarSpec(BarMode.HIDDEN)
        StepState.DENIED -> BarSpec(BarMode.HIDDEN)
    }
}

// ------------------------------------------------------------------ Teks tengah (satu baris)

/** Rapikan satu baris penjelasan: satu baris, tanpa titik akhir, huruf awal kapital, dipotong rapi. */
internal fun tidyLine(raw: String, max: Int = 72): String {
    var s = raw.replace(Regex("""\s+"""), " ").trim().trimStart('-', '*', '•', ' ').trimEnd('.', ' ')
    if (s.isEmpty()) return ""
    s = s.replaceFirstChar { it.uppercase() }
    return if (s.length > max) s.take(max - 1).trimEnd() + "…" else s
}

/**
 * Penjaga teks tengah: teks yang sama tidak ditampilkan ulang, dan teks tidak boleh berganti lebih cepat dari [holdMs]
 * (perubahan yang datang lebih cepat ditahan; yang terbaru yang menang). Dengan begitu teks tidak berkedip.
 * Waktu disuntikkan lewat parameter [now] agar bisa diuji.
 */
internal class TextGate(private val holdMs: Long = 900L) {
    var shown: String = ""
        private set
    private var shownAt = Long.MIN_VALUE / 2
    private var pending: String? = null

    /** Teks yang harus tampil sekarang, atau null (sama dengan yang tampil / ditahan dulu). */
    fun submit(text: String, now: Long): String? {
        if (text.isEmpty()) return null
        if (text == shown) { pending = null; return null }
        if (now - shownAt >= holdMs) return show(text, now)
        pending = text
        return null
    }

    /** Sisa waktu (ms) sampai teks yang ditahan boleh tampil, atau null bila tidak ada yang ditahan. */
    fun nextDelay(now: Long): Long? = pending?.let { (shownAt + holdMs - now).coerceAtLeast(0L) }

    /** Tampilkan teks yang ditahan bila waktunya sudah tiba. */
    fun release(now: Long): String? {
        val p = pending ?: return null
        if (now - shownAt < holdMs) return null
        pending = null
        return if (p == shown) null else show(p, now)
    }

    fun reset() { shown = ""; shownAt = Long.MIN_VALUE / 2; pending = null }

    private fun show(text: String, now: Long): String { shown = text; shownAt = now; pending = null; return text }
}

// ------------------------------------------------------------------ Laporan akhir

internal enum class RunOutcome { COMPLETED, CANCELLED, ERROR, INCOMPLETE }
internal enum class ReportStatus { SUCCESS, UNVERIFIED, FAILED, STOPPED }

internal class StepRecord(
    val tool: String,
    val title: String,
    val target: String,
    val kind: ActivityKind,
    val state: StepState,
    val ms: Long
)

/** Fakta nyata satu putaran agen: dicatat saat langkah selesai, dibaca saat membuat laporan. Aman lintas-thread. */
internal class RunLog {
    private val list = ArrayList<StepRecord>()
    @Volatile var finalText = ""
    @Volatile var outcome = RunOutcome.COMPLETED
    @Volatile var note = ""
    @Volatile var apkPath: String? = null
    @Volatile var startedAt = 0L

    val steps: List<StepRecord> get() = synchronized(list) { ArrayList(list) }

    fun record(r: StepRecord) { synchronized(list) { list.add(r) } }

    fun reset(now: Long) {
        synchronized(list) { list.clear() }
        finalText = ""; outcome = RunOutcome.COMPLETED; note = ""; apkPath = null; startedAt = now
    }
}

internal class Report(
    val status: ReportStatus,
    val title: String,
    val summary: String,
    val changes: List<String>,
    val stats: String
)

/** Ambil ringkasan singkat dari jawaban akhir AI: tanpa blok kode dan tanda markdown, maksimal [max] karakter. */
internal fun summaryFromAnswer(text: String, max: Int = 150): String {
    val clean = text.replace(Regex("```.*?```", RegexOption.DOT_MATCHES_ALL), " ")
    val lines = clean.lines()
        .map { it.trim().trimStart('#', '>', '-', '*', '•', ' ').replace("**", "").replace("`", "").replace("__", "").trim() }
        .filter { it.isNotEmpty() }
    if (lines.isEmpty()) return ""
    var s = lines[0]
    if (s.length < 60 && lines.size > 1) s += " " + lines[1]
    return if (s.length > max) s.take(max - 1).trimEnd() + "…" else s
}

/**
 * True bila ada perubahan file yang belum diikuti verifikasi yang berhasil (build, apktool b, apksigner verify, dll).
 * Dipakai agar laporan tidak menyatakan "Berhasil" untuk perubahan yang belum dibuktikan.
 */
internal fun unverifiedChange(steps: List<StepRecord>): Boolean {
    val lastWrite = steps.indexOfLast { it.tool == "write_file" && it.state == StepState.OK }
    if (lastWrite < 0) return false
    val lastVerifyOk = steps.indexOfLast { it.kind == ActivityKind.BUILD && it.state == StepState.OK }
    return lastVerifyOk < lastWrite
}

private fun lastOf(steps: List<StepRecord>, kind: ActivityKind) = steps.lastOrNull { it.kind == kind }

/**
 * Laporan akhir dari fakta nyata putaran [log]. Null bila tidak ada yang perlu dilaporkan (jawaban biasa tanpa langkah).
 * Aturan status (COMPLETED): GAGAL bila build terakhir gagal atau langkah terakhir tidak OK; selain itu BERHASIL.
 * Dihentikan pengguna = DIHENTIKAN; galat atau batas putaran = GAGAL.
 */
internal fun buildReport(log: RunLog, totalMs: Long): Report? {
    val steps = log.steps
    if (steps.isEmpty() && log.outcome == RunOutcome.COMPLETED) return null
    val fails = steps.count { it.state == StepState.FAIL }
    val denied = steps.count { it.state == StepState.DENIED }
    val build = lastOf(steps, ActivityKind.BUILD)

    val status: ReportStatus
    val title: String
    when (log.outcome) {
        RunOutcome.CANCELLED -> { status = ReportStatus.STOPPED; title = "Dihentikan" }
        RunOutcome.ERROR -> { status = ReportStatus.FAILED; title = "Gagal" }
        RunOutcome.INCOMPLETE -> { status = ReportStatus.FAILED; title = "Tidak selesai" }
        RunOutcome.COMPLETED -> when {
            build?.state == StepState.FAIL -> { status = ReportStatus.FAILED; title = "Build gagal" }
            steps.last().state != StepState.OK -> { status = ReportStatus.FAILED; title = "Gagal" }
            unverifiedChange(steps) -> { status = ReportStatus.UNVERIFIED; title = "Belum terverifikasi" }
            else -> { status = ReportStatus.SUCCESS; title = "Berhasil" }
        }
    }

    val summary = when (log.outcome) {
        RunOutcome.ERROR, RunOutcome.INCOMPLETE -> tidyLine(log.note, 150)
        RunOutcome.CANCELLED ->
            if (steps.isEmpty()) "Tugas dihentikan sebelum langkah pertama" else "Tugas dihentikan setelah ${steps.size} langkah"
        RunOutcome.COMPLETED -> summaryFromAnswer(log.finalText).ifEmpty {
            if (status == ReportStatus.SUCCESS) "${steps.size} langkah selesai"
            else "Langkah terakhir tidak berhasil: " + tidyLine(steps.last().title, 60)
        }
    }

    val changes = ArrayList<String>()
    val files = steps.filter { it.tool == "write_file" && it.state == StepState.OK }
        .map { it.target.substringAfterLast('/') }.filter { it.isNotEmpty() }.distinct()
    if (files.isNotEmpty()) {
        val shown = files.take(3).joinToString(", ")
        changes.add("Mengubah ${files.size} file: $shown" + if (files.size > 3) " (+${files.size - 3} lagi)" else "")
    }
    fun outcomeOf(label: String, s: StepRecord?) {
        when (s?.state) {
            StepState.OK -> changes.add("$label berhasil")
            StepState.FAIL -> changes.add("$label gagal")
            else -> {}
        }
    }
    outcomeOf("Build", build)
    if (build?.state == StepState.OK && log.apkPath != null) changes.add("APK siap dipasang")
    outcomeOf("Instalasi", lastOf(steps, ActivityKind.INSTALL))
    outcomeOf("Unduhan", lastOf(steps, ActivityKind.DOWNLOAD))
    if (fails > 0) changes.add(if (status == ReportStatus.SUCCESS) "$fails langkah sempat gagal" else "$fails langkah gagal")
    if (denied > 0) changes.add("$denied langkah ditolak")
    if (changes.isEmpty()) changes.add("Tidak ada file yang diubah")

    val stats = listOfNotNull(if (steps.isEmpty()) null else "${steps.size} langkah", fmtDur(totalMs).ifEmpty { null }).joinToString(" · ")
    return Report(status, title, summary, changes, stats)
}
