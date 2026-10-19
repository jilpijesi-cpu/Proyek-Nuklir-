package com.kita.agent

// Eksekusi terisolasi: perintah dijalankan di dalam proot-distro (Ubuntu default; Debian/Alpine juga bisa) di Termux, bukan di shell Termux utama.
// Murni JVM: yang berbicara ke Termux hanya ShellExecutor (diisi MainActivity.termux di SandboxIntegration.kt).
//
// BATAS YANG JUJUR: proot BUKAN sandbox keamanan seperti container atau VM. Ia membatasi apa yang terlihat
// (tanpa mount penyimpanan bersama / home Termux) dan membuang environment, tetapi tidak memblokir jaringan
// dan tidak menjamin proses tak bisa keluar dari workdir di dalam sistem file tamu. Pembatasan workdir dan
// jaringan ditegakkan oleh CommandPolicy (analisis teks) + persetujuan pengguna.

data class SandboxConfig(
    val distro: String = "ubuntu",
    /** Workdir di DALAM sistem file tamu. Dibuat otomatis. */
    val workdir: String = DEFAULT_SANDBOX_WORKDIR,
    val guestHome: String = DEFAULT_GUEST_HOME,
    val termuxPrefix: String = "/data/data/com.termux/files/usr",
    val termuxHome: String = "/data/data/com.termux/files/home"
) {
    init {
        require(Regex("^[a-z0-9][a-z0-9_-]{0,40}$").matches(distro)) { "nama distro tidak valid" }
        require(isSafeAbsPath(workdir) && workdir.trimEnd('/').isNotEmpty()) { "workdir tidak valid" }
        require(isSafeAbsPath(guestHome)) { "guestHome tidak valid" }
        require(isSafeAbsPath(termuxPrefix)) { "termuxPrefix tidak valid" }
        require(isSafeAbsPath(termuxHome)) { "termuxHome tidak valid" }
    }

    private fun isSafeAbsPath(p: String) =
        Regex("^/[A-Za-z0-9_./-]*$").matches(p) && p.split('/').none { it == ".." || it == "." }
}

/** Jembatan ke Termux. Mengembalikan format lama "exit=N\n<isi>". */
fun interface ShellExecutor {
    fun exec(command: String, timeoutSec: Long, limit: Int, liveFile: String?): String
}

object SandboxCommand {
    /** Kutip satu argumen untuk shell POSIX. */
    fun shq(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** Satu-satunya variabel yang diteruskan ke luar-sandbox. Allowlist, bukan blocklist: token/API key tidak ikut. */
    internal fun envAssignments(c: SandboxConfig): List<String> = listOf(
        "PATH=${c.termuxPrefix}/bin",
        "HOME=${c.termuxHome}",
        "TMPDIR=${c.termuxPrefix}/tmp",
        "PREFIX=${c.termuxPrefix}",
        "TERM=xterm-256color",
        "LANG=C.UTF-8",
        "ANDROID_ROOT=/system",
        "ANDROID_DATA=/data"
    )

    /** Skrip di dalam tamu: masuk ke workdir (dibuat bila belum ada), lalu perintah pengguna. Gagal masuk = exit 97. */
    internal fun innerScript(userCmd: String, c: SandboxConfig): String =
        "mkdir -p ${shq(c.workdir)} && cd ${shq(c.workdir)} || exit 97\n$userCmd"

    /**
     * Susun perintah untuk shell Termux:
     * env -i (lingkungan bersih) -> timeout (hentikan seluruh grup proses) -> proot-distro --isolated -> head -c (batas output).
     * Tanpa --bind, tanpa --shared-tmp, tanpa --termux-home, dan tanpa termux-setup-storage.
     */
    fun build(userCmd: String, c: SandboxConfig, limits: RunLimits): String {
        val bin = c.termuxPrefix + "/bin"
        val env = envAssignments(c).joinToString(" ") { shq(it) }
        return "set -o pipefail\n" +
            "${shq("$bin/env")} -i $env " +
            "${shq("$bin/timeout")} -s KILL ${limits.timeoutSec} " +
            "${shq("$bin/proot-distro")} login ${c.distro} --isolated -- /bin/sh -c ${shq(innerScript(userCmd, c))} 2>&1 | " +
            "${shq("$bin/head")} -c ${limits.maxOutputBytes + 1}"
    }
}

class SandboxRunner(
    private val shell: ShellExecutor,
    private val config: SandboxConfig = SandboxConfig(),
    private val clock: () -> Long = { System.currentTimeMillis() }
) : CommandRunner {

    override fun run(request: RunRequest): RunResult {
        val start = clock()
        val script = SandboxCommand.build(request.command, config, request.limits)
        // Batas waktu utama dipasang di dalam skrip (timeout). Batas di sisi aplikasi hanya cadangan, makanya diberi kelonggaran.
        val raw = shell.exec(
            script,
            request.limits.timeoutSec + GRACE_SEC,
            request.limits.maxOutputBytes + 512,
            request.liveFile
        )
        return interpret(raw, request.limits, clock() - start)
    }

    internal fun interpret(raw: String, limits: RunLimits, durationMs: Long): RunResult {
        val (code, body) = parseTermuxResult(raw)
        val exit = code ?: -1
        val timedOut = exit == 124
        val (limited, truncated) = OutputLimiter.limit(body, limits.maxOutputBytes)
        val notes = ArrayList<String>()
        if (timedOut) {
            notes.add("Timeout: perintah melewati batas ${limits.timeoutSec} detik dan dihentikan. Jangan langsung mengulang perintah yang sama; pecah menjadi langkah lebih kecil.")
        }
        if (exit == 97) notes.add("Sandbox gagal masuk ke workdir ${config.workdir}.")
        if (exit == 127 && body.contains("proot-distro")) {
            notes.add("proot-distro belum terpasang di Termux. Pasang dulu: pkg install proot-distro")
        }
        if (body.contains("not installed", ignoreCase = true) && body.contains(config.distro, ignoreCase = true)) {
            notes.add("Distro '${config.distro}' belum dipasang. Pasang dulu: proot-distro install ${config.distro}")
        }
        val out = if (notes.isEmpty()) limited else notes.joinToString("\n") + "\n" + limited
        return RunResult(exit, out, timedOut, truncated, durationMs, sandboxed = true)
    }

    companion object {
        const val GRACE_SEC = 15L
    }
}
