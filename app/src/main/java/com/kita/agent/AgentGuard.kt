package com.kita.agent

// Pengaman keberhasilan agen (logika murni, tanpa android.*, diuji di AgentGuardTest):
// 1) verifikasi: agen tidak boleh berhenti bila build terakhir gagal / kode baru diubah tanpa build yang berhasil sesudahnya;
// 2) error berulang: error yang sama muncul lagi dan lagi -> peringatan ganti pendekatan, lalu berhenti;
// 3) ringkasan error: keluaran gagal yang panjang dipadatkan ke bagian errornya sebelum dikirim ke model.
// Semua keputusan memakai fakta nyata dari RunLog / keluaran perintah, bukan tebakan.

/** Awalan pesan sistem di riwayat: tidak ditampilkan sebagai gelembung pengguna saat riwayat dipulihkan. */
internal const val SYS_PREFIX = "[SISTEM] "

/** Berapa kali agen boleh diminta memverifikasi dalam satu tugas (cegah putaran tanpa akhir dan boros kuota). */
internal const val MAX_VERIFY_NUDGES = 2

/** Error yang sama: peringatan di kemunculan ke-2 dan ke-3, berhenti di ke-4. */
internal const val WARN_SAME_ERROR_AT = 2
internal const val STOP_SAME_ERROR_AT = 4

private val CODE_EXT = setOf("kt", "java", "kts", "gradle")
private val SHELL_WRITE_HINT = Regex("""(>>?|\btee\b|\bsed\b.*\s-i|\bcp\b|\bmv\b|\bcat\b.*<<)""")

internal fun isCodeFile(path: String): Boolean {
    val clean = path.trim().trim('"', '\'', '`', ';', ')', '(')
    val name = clean.substringAfterLast('/')
    if (name.isEmpty() || !name.contains('.')) return false
    return name.substringAfterLast('.', "").lowercase() in CODE_EXT
}

/**
 * Ekstrak path berkas kode yang dimodifikasi oleh sebuah langkah, baik lewat `write_file`
 * maupun lewat `run_command` (mis. saat mode Sandbox aktif dan `write_file` dinonaktifkan).
 */
internal fun modifiedCodeTarget(step: StepRecord): String? {
    if (step.state != StepState.OK) return null
    if (step.tool == "write_file") {
        return step.target.takeIf { isCodeFile(it) }
    }
    if (step.tool == "run_command" && step.kind != ActivityKind.BUILD) {
        val raw = step.target.trim()
        if (isCodeFile(raw) && !raw.contains(' ')) return raw
        if (SHELL_WRITE_HINT.containsMatchIn(raw)) {
            val tokens = raw.split(Regex("""[\s><|;&()]+"""))
                .map { it.trim('"', '\'', '`') }
                .filter { it.isNotEmpty() }
            return tokens.lastOrNull { isCodeFile(it) }
        }
    }
    return null
}

/**
 * Pesan pengingat bila agen belum boleh dianggap selesai, atau null bila boleh.
 * (a) build terakhir gagal dan belum ada build berhasil sesudahnya; atau
 * (b) file kode (kt/java/kts/gradle) DI DALAM proyek Gradle berhasil ditulis (via write_file atau run_command)
 *     dan belum ada build berhasil sesudahnya.
 *
 * [inGradleProject] memberi tahu apakah path file berada di proyek Gradle (ada gradlew / settings.gradle di folder
 * induknya). Bawaan true agar perilaku lama dan tes tetap sama; MainActivity memberi pemeriksaan nyata.
 */
internal fun verificationNudge(
    steps: List<StepRecord>,
    inGradleProject: (String) -> Boolean = { true }
): String? {
    val lastBuildOk = steps.indexOfLast { it.kind == ActivityKind.BUILD && it.state == StepState.OK }
    val lastBuildFail = steps.indexOfLast { it.kind == ActivityKind.BUILD && it.state == StepState.FAIL }
    if (lastBuildFail > lastBuildOk) {
        return SYS_PREFIX + "Build terakhir GAGAL dan belum ada build yang berhasil sesudahnya. " +
            "Baca error-nya, perbaiki akar masalahnya, lalu jalankan build lagi. Jangan menyatakan selesai sebelum build berhasil, " +
            "atau jelaskan dengan jujur mengapa tidak bisa diperbaiki."
    }
    val lastCodeWrite = steps.indexOfLast { step ->
        val codeTarget = modifiedCodeTarget(step)
        codeTarget != null && inGradleProject(codeTarget)
    }
    if (lastCodeWrite >= 0 && lastCodeWrite > lastBuildOk) {
        return SYS_PREFIX + "Kamu mengubah file kode tetapi belum menjalankan build yang berhasil sesudahnya. " +
            "Jalankan build/tes proyek untuk membuktikan perubahan bekerja, lalu laporkan hasil sebenarnya."
    }
    return null
}

private val ERR_LINE = Regex("""error|exception|fail|cannot|can't|not found|no such|denied|unresolved|tidak ditemukan|gagal""", RegexOption.IGNORE_CASE)

/** Tanda tangan error dari keluaran perintah gagal (angka dan path dinormalkan), atau null bila perintah tidak gagal. */
internal fun errorSignature(out: String): String? {
    val lines = out.lines()
    val head = lines.firstOrNull()?.trim().orEmpty()
    if (!head.startsWith("exit=") || head == "exit=0" || head.startsWith("exit=0 ")) return null
    val key = lines.drop(1).map { it.trim() }.firstOrNull { it.isNotEmpty() && ERR_LINE.containsMatchIn(it) }
        ?: lines.drop(1).map { it.trim() }.lastOrNull { it.isNotEmpty() }
        ?: head
    return key.lowercase()
        .replace(Regex("""(/[\w.\-]+)+"""), "<p>")
        .replace(Regex("""\d+"""), "#")
        .take(120)
}

/** Menghitung kemunculan error yang sama dalam satu tugas. */
internal class ErrorTracker {
    private val counts = HashMap<String, Int>()

    /** Catat keluaran satu perintah; kembalikan berapa kali error ini sudah muncul (0 bila tidak gagal). */
    fun record(out: String): Int {
        val sig = errorSignature(out) ?: return 0
        val n = (counts[sig] ?: 0) + 1
        counts[sig] = n
        return n
    }
}

/** Peringatan yang ditempelkan ke hasil alat untuk model bila error yang sama muncul berulang. */
internal fun repeatWarning(n: Int): String =
    "\n\n[Peringatan sistem: error yang sama sudah muncul $n kali. Jangan ulangi perintah serupa. " +
        "Ganti pendekatan: baca file/log penyebabnya, atau periksa asumsi awalmu. Bila buntu, jelaskan kendalanya dengan jujur.]"

/**
 * Padatkan keluaran GAGAL yang panjang: baris judul + baris error beserta 2 baris sekitarnya + ekor keluaran.
 * Keluaran sukses atau yang pendek tidak diubah. Kartu di layar tetap menampilkan keluaran penuh.
 */
internal fun condenseFailure(out: String, maxChars: Int = 3000): String {
    if (out.length <= maxChars || errorSignature(out) == null) return out
    val lines = out.lines()
    val keep = java.util.TreeSet<Int>()
    keep.add(0)
    lines.forEachIndexed { i, l ->
        if (i > 0 && ERR_LINE.containsMatchIn(l)) for (j in (i - 2)..(i + 2)) if (j in 1 until lines.size) keep.add(j)
    }
    for (j in maxOf(1, lines.size - 25) until lines.size) keep.add(j)
    val sb = StringBuilder()
    sb.append("[Keluaran gagal diringkas: ").append(out.length).append(" karakter -> bagian error dan ekor. Jalankan ulang dengan grep/tail bila perlu detail lain.]\n")
    var prev = -1
    for (i in keep) {
        if (prev >= 0 && i > prev + 1) sb.append("...\n")
        sb.append(lines[i]).append('\n')
        prev = i
    }
    val s = sb.toString().trimEnd()
    return if (s.length > maxChars) s.take(maxChars / 2) + "\n...\n" + s.takeLast(maxChars / 2 - 5) else s
}

/**
 * Hasil gagal untuk galat tak terduga pada satu alat. Diawali "exit=" agar dikenali [errorSignature]
 * dan dihitung [ErrorTracker] seperti galat perintah biasa.
 */
internal fun recoverableToolResult(e: Throwable): String =
    "exit=1 Alat gagal: ${(e.message ?: e.javaClass.simpleName).lineSequence().first().take(200)}\n" +
        "Baca penyebabnya, lalu coba pendekatan lain."

/** Hasil untuk perintah yang melewati batas waktu. Diawali "exit=" agar pengaman bisa mengenalinya. */
internal fun timeoutResult(timeoutSec: Long): String =
    "exit=124 Timeout: perintah melewati batas $timeoutSec detik dan dihentikan.\n" +
        "Jangan langsung mengulang perintah yang sama. Cek dulu apakah proses masih berjalan atau " +
        "hasilnya sudah ada di log, atau pecah pekerjaan menjadi langkah lebih kecil."

/**
 * Instruksi Inti — Persistensi Maksimal Kita Agent.
 * Memastikan agen selalu gigih, adaptif, mandiri, dan berorientasi pada hasil terverifikasi.
 */
internal fun buildPersistenceDirective(): String = buildString {
    append("INSTRUKSI INTI — PERSISTENSI MAKSIMAL KITA AGENT:\n")
    append("1. Jangan menyerah terlalu cepat. Jika metode pertama gagal, analisis penyebabnya, susun alternatif, dan coba pendekatan lain yang diizinkan.\n")
    append("2. Jangan menolak hanya karena tugas sulit atau kompleks. Pecah tugas menjadi langkah kecil, periksa kemampuan alat yang tersedia, lalu kerjakan secara bertahap.\n")
    append("3. Pulihkan kesalahan secara mandiri. Baca pesan error, identifikasi penyebab, perbaiki strategi, dan ulangi dengan batas percobaan yang wajar.\n")
    append("4. Jangan mengklaim tugas selesai tanpa bukti. Verifikasi perubahan berkas, hasil perintah, tes, dan keluaran sebelum melaporkan keberhasilan.\n")
    append("5. Jika informasi kurang, gunakan konteks yang tersedia. Ajukan pertanyaan hanya jika informasi tersebut benar-benar diperlukan untuk melanjutkan.\n")
    append("6. Jika alat atau layanan tidak tersedia, jelaskan keterbatasannya secara singkat dan tawarkan alternatif yang bisa dijalankan.\n")
    append("7. Jika membutuhkan persetujuan pengguna, jelaskan tindakan yang memerlukan izin dan lanjutkan pekerjaan lain yang tidak bergantung pada izin tersebut.\n")
    append("8. Jangan mengulang kegagalan tanpa tujuan. Batasi percobaan, gunakan jeda saat diperlukan, dan ubah strategi berdasarkan bukti.\n")
    append("9. Pertahankan konteks tugas. Catat tujuan, langkah yang sudah berhasil, masalah yang ditemukan, dan pekerjaan yang belum selesai agar tidak mengulang dari awal.\n")
    append("10. Prioritaskan penyelesaian. Hindari jawaban berupa teori saja jika tugas dapat dikerjakan langsung dengan alat yang tersedia dan diizinkan.\n")
    append("ATURAN KETIKA MENGHADAPI HAMBATAN:\n")
    append("- Tentukan minimal satu alternatif yang realistis.\n")
    append("- Jalankan alternatif yang paling aman dan diizinkan.\n")
    append("- Verifikasi hasilnya.\n")
    append("- Jika seluruh alternatif gagal, laporkan penyebab utama, apa yang sudah berhasil dilakukan, dan langkah konkret agar tugas dapat diselesaikan.")
}

internal data class ObstacleRecoveryPlan(
    val errorKind: ErrorKind,
    val rootCause: String,
    val alternatives: List<String>,
    val requiresUserApproval: Boolean,
    val shouldContinueOtherWork: Boolean,
    val shouldStopRetryingSame: Boolean
)

/**
 * Analisis hambatan/kegagalan alat dan susun rencana pemulihan yang mencakup penyebab utama,
 * minimal satu alternatif realistis yang aman & diizinkan, serta penanganan izin.
 */
internal fun analyzeObstacle(
    tool: String,
    commandOrTarget: String,
    rawOutput: String,
    sameFailureCount: Int = 1
): ObstacleRecoveryPlan {
    val trimmed = rawOutput.trim()
    val lower = trimmed.lowercase()
    val parsed = parseToolResult(rawOutput)
    val userDenied = trimmed.startsWith("Ditolak pengguna", ignoreCase = true) ||
        parsed.errorKind == ErrorKind.USER_DENIED
    val sandboxBlocked = trimmed.startsWith("Diblokir oleh sandbox", ignoreCase = true) ||
        lower.contains("mode sandbox aktif, write_file")
    val readOnlyBlocked = lower.contains("mode hanya baca aktif")

    val effectiveKind = when {
        userDenied -> ErrorKind.USER_DENIED
        sandboxBlocked || readOnlyBlocked -> ErrorKind.INVALID_TOOL_ARGUMENT
        parsed.errorKind != ErrorKind.NONE -> parsed.errorKind
        else -> ErrorKind.COMMAND_FAILED
    }

    val rootCause: String
    val alternatives: List<String>

    when {
        userDenied -> {
            rootCause = "Tindakan pada '$tool' (${commandOrTarget.take(60)}) membutuhkan persetujuan pengguna dan belum disetujui."
            alternatives = listOf(
                "Jelaskan tindakan yang memerlukan izin pengguna secara singkat dan lanjutkan pekerjaan lain yang tidak bergantung pada izin tersebut",
                "Gunakan perintah baca/analisis aman di dalam direktori kerja yang diizinkan tanpa mengubah sistem",
                "Tanyakan preferensi pendekatan kepada pengguna melalui ask_user hanya bila keputusan mutlak diperlukan"
            )
        }
        sandboxBlocked -> {
            rootCause = "Operasi '$tool' (${commandOrTarget.take(60)}) dibatasi oleh kebijakan isolasi Sandbox."
            alternatives = listOf(
                "Jalankan operasi di dalam workdir sandbox ($DEFAULT_SANDBOX_WORKDIR) menggunakan perintah yang diizinkan",
                "Jika perlu menulis berkas di mode Sandbox, gunakan run_command (mis. printf atau cat << 'EOF' > file) di dalam workdir sandbox"
            )
        }
        readOnlyBlocked -> {
            rootCause = "Mode Hanya Baca aktif sehingga operasi tulis/ubah tidak diizinkan."
            alternatives = listOf(
                "Gunakan perintah baca (ls, cat, grep, find) untuk menyelesaikan analisis dan verifikasi",
                "Susun rincian perubahan kode yang terverifikasi agar siap diterapkan begitu mode Hanya Baca dinonaktifkan"
            )
        }
        effectiveKind == ErrorKind.COMMAND_NOT_FOUND -> {
            val missingCmd = commandOrTarget.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
            rootCause = "Alat atau binary '$missingCmd' tidak ditemukan di lingkungan saat ini (exit=126/127)."
            alternatives = listOf(
                "Gunakan utilitas standar POSIX/Termux yang setara (mis. ganti rg dengan grep -rn, ganti fd dengan find, ganti bat dengan cat)",
                "Periksa ketersediaan binary dengan which/command -v atau pasang paket yang dibutuhkan bila diizinkan"
            )
        }
        effectiveKind == ErrorKind.COMMAND_TIMEOUT -> {
            rootCause = "Perintah '${commandOrTarget.take(60)}' melewati batas waktu eksekusi."
            alternatives = listOf(
                "Pecah perintah menjadi langkah yang lebih kecil dan spesifik (mis. jalankan satu modul/tes tertentu)",
                "Periksa apakah hasil parsial sudah terbentuk di log/berkas sebelum menjalankan ulang"
            )
        }
        effectiveKind == ErrorKind.NETWORK_AUTH -> {
            rootCause = "Autentikasi jaringan/API ditolak (401/403)."
            alternatives = listOf(
                "Jangan mengulang permintaan dengan kredensial yang sama; periksa konfigurasi kunci/token",
                "Selesaikan bagian tugas lokal yang tidak bergantung pada layanan eksternal tersebut"
            )
        }
        effectiveKind == ErrorKind.NETWORK_TRANSIENT -> {
            rootCause = "Gangguan jaringan atau layanan sementara sibuk."
            alternatives = listOf(
                "Ulangi dengan jeda wajar dan cakupan permintaan yang lebih ringan",
                "Gunakan sumber/cache lokal di proyek bila tersedia"
            )
        }
        else -> {
            val firstErrLine = trimmed.lineSequence()
                .drop(1)
                .firstOrNull { ERR_LINE.containsMatchIn(it) }
                ?.trim()
                ?.take(120)
                ?: trimmed.lineSequence().firstOrNull()?.take(120).orEmpty()
            rootCause = "Eksekusi '$tool' gagal: $firstErrLine"
            alternatives = listOf(
                "Baca berkas/log terkait untuk memastikan penyebab utama sebelum mengubah kode atau perintah",
                "Susun dan jalankan pendekatan alternatif yang aman di dalam direktori kerja lalu verifikasi hasilnya"
            )
        }
    }

    val retryable = !userDenied && !sandboxBlocked && !readOnlyBlocked &&
        retryPolicy(tool, commandOrTarget, parsed)
    val stopSame = sameFailureCount >= WARN_SAME_ERROR_AT || !retryable

    return ObstacleRecoveryPlan(
        errorKind = effectiveKind,
        rootCause = rootCause,
        alternatives = alternatives,
        requiresUserApproval = userDenied,
        shouldContinueOtherWork = userDenied || readOnlyBlocked,
        shouldStopRetryingSame = stopSame
    )
}

/**
 * Format panduan pemulihan hambatan yang ditempelkan pada hasil alat agar model mengikuti
 * aturan persistensi maksimal (analisis penyebab, minimal 1 alternatif realistis, verifikasi hasil).
 */
internal fun formatObstacleGuidance(plan: ObstacleRecoveryPlan, sameFailureCount: Int = 1): String = buildString {
    append("\n\n[Panduan Persistensi & Pemulihan Hambatan")
    if (sameFailureCount > 1) append(" (kemunculan ke-$sameFailureCount)")
    append("]\n")
    append("- Penyebab utama: ").append(plan.rootCause).append('\n')
    append("- Alternatif yang disarankan: ").append(plan.alternatives.joinToString(" | ")).append('\n')
    if (plan.requiresUserApproval) {
        append("- Penanganan izin: Jelaskan tindakan yang memerlukan izin pengguna dan lanjutkan pekerjaan lain yang tidak bergantung pada izin tersebut.\n")
    }
    if (plan.shouldStopRetryingSame) {
        append("- Aturan: Jangan mengulang perintah gagal yang persis sama. Pilih alternatif yang paling aman & diizinkan, lalu verifikasi hasilnya.")
    } else {
        append("- Aturan: Perbaiki strategi atau gunakan alternatif di atas, lalu verifikasi hasilnya.")
    }
}

internal data class TaskProgressContext(
    val goal: String,
    val succeededSteps: List<String>,
    val obstaclesFound: List<String>,
    val pendingWork: List<String>
)

/**
 * Catat konteks progres tugas (tujuan, langkah berhasil, masalah ditemukan, dan pekerjaan belum selesai)
 * agar agen tidak kehilangan konteks atau mengulang dari awal.
 */
internal fun buildTaskProgressContext(
    goal: String,
    steps: List<StepRecord>,
    inGradleProject: (String) -> Boolean = { true }
): TaskProgressContext {
    val succeeded = steps.filter { it.state == StepState.OK }.map { s ->
        val tgt = s.target.trim().take(50)
        if (tgt.isNotEmpty()) "${s.tool}($tgt)" else s.tool
    }.distinct()

    val obstacles = steps.filter { it.state == StepState.FAIL || it.state == StepState.DENIED }.map { s ->
        val tgt = s.target.trim().take(45)
        val statusLabel = if (s.state == StepState.DENIED) "ditolak/diblokir" else "gagal"
        if (tgt.isNotEmpty()) "${s.tool}($tgt): $statusLabel" else "${s.tool}: $statusLabel"
    }.distinct()

    val pending = ArrayList<String>()
    val nudge = verificationNudge(steps, inGradleProject)
    if (nudge != null) {
        val lastBuildFail = steps.indexOfLast { it.kind == ActivityKind.BUILD && it.state == StepState.FAIL }
        val lastBuildOk = steps.indexOfLast { it.kind == ActivityKind.BUILD && it.state == StepState.OK }
        if (lastBuildFail > lastBuildOk) {
            pending.add("Perbaiki penyebab build gagal dan jalankan ulang build/tes sampai berhasil")
        } else {
            val modifiedFiles = steps.mapNotNull { modifiedCodeTarget(it) }.distinct()
            pending.add("Verifikasi perubahan kode (${modifiedFiles.joinToString(", ").ifEmpty { "proyek" }}) dengan menjalankan build/tes")
        }
    }
    if (steps.isNotEmpty() && (steps.last().state == StepState.FAIL || steps.last().state == StepState.DENIED)) {
        pending.add("Selesaikan hambatan pada langkah terakhir (${steps.last().title}) dengan pendekatan alternatif")
    }

    return TaskProgressContext(
        goal = goal.trim(),
        succeededSteps = succeeded,
        obstaclesFound = obstacles,
        pendingWork = pending.distinct()
    )
}

internal fun formatTaskProgressContext(ctx: TaskProgressContext): String {
    if (ctx.goal.isBlank() && ctx.succeededSteps.isEmpty() && ctx.obstaclesFound.isEmpty() && ctx.pendingWork.isEmpty()) {
        return ""
    }
    return buildString {
        append("Catatan Konteks Tugas (pertahankan agar tidak mengulang dari awal):\n")
        if (ctx.goal.isNotBlank()) append("- Tujuan: ").append(ctx.goal.take(240)).append('\n')
        if (ctx.succeededSteps.isNotEmpty()) {
            append("- Langkah berhasil: ").append(ctx.succeededSteps.takeLast(8).joinToString(", ")).append('\n')
        }
        if (ctx.obstaclesFound.isNotEmpty()) {
            append("- Masalah ditemukan: ").append(ctx.obstaclesFound.takeLast(5).joinToString("; ")).append('\n')
        }
        if (ctx.pendingWork.isNotEmpty()) {
            append("- Pekerjaan belum selesai: ").append(ctx.pendingWork.joinToString("; "))
        } else if (ctx.succeededSteps.isNotEmpty()) {
            append("- Pekerjaan belum selesai: Pastikan seluruh hasil sudah terverifikasi sebelum melaporkan selesai")
        }
    }.trim()
}

