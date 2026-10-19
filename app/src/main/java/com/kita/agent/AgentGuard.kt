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

private fun isCodeFile(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in CODE_EXT

/**
 * Pesan pengingat bila agen belum boleh dianggap selesai, atau null bila boleh.
 * (a) build terakhir gagal dan belum ada build berhasil sesudahnya; atau
 * (b) file kode (kt/java/kts/gradle) DI DALAM proyek Gradle berhasil ditulis dan belum ada build berhasil sesudahnya.
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
    val lastCodeWrite = steps.indexOfLast {
        it.tool == "write_file" && it.state == StepState.OK && isCodeFile(it.target) && inGradleProject(it.target)
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
