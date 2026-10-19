package com.kita.agent

// Perekat antara MainActivity dan lapisan sandbox (CommandPolicy, SandboxRunner).
// Fungsi-fungsi di sini adalah ekstensi MainActivity; logika sebenarnya ada di file murni agar bisa diuji.

internal val sandboxConfig = SandboxConfig()

/** Opt-in lewat Pengaturan. Default mati supaya perilaku lama tidak berubah. */
internal fun MainActivity.sandboxEnabled(): Boolean = prefs.getBoolean("sandbox", false)

/** Null bila mode sandbox mati. */
internal fun MainActivity.sandboxVerdict(cmd: String): Assessment? =
    if (sandboxEnabled()) assessCommand(cmd, sandboxConfig.workdir, sandboxConfig.guestHome) else null

/** Jalankan SETELAH persetujuan. Mengembalikan format lama "exit=N\n<isi>". */
internal fun MainActivity.runInSandbox(cmd: String, isBuild: Boolean, liveFile: String?): String {
    val shell = ShellExecutor { c, timeoutSec, limit, live ->
        termux(c, limit = limit, timeoutSec = timeoutSec, useWorkdir = false, liveFile = live)
    }
    val runner: CommandRunner = SandboxRunner(shell, sandboxConfig)
    val limits = RunLimits(timeoutSec = if (isBuild) 1800L else RunLimits.DEFAULT_TIMEOUT_SEC)
    val out = runner.run(RunRequest(cmd, limits, liveFile)).toLegacyString()
    if (out.contains("belum dipasang")) sandboxMissingHint()
    return out
}

@Volatile private var sandboxHintShown = false

/** Mode sandbox menyala tetapi distro belum ada: jelaskan sekali di chat cara keluar dari kebuntuan. */
private fun MainActivity.sandboxMissingHint() {
    if (sandboxHintShown) return
    sandboxHintShown = true
    addNote(
        "Mode sandbox aktif, tetapi Ubuntu belum terpasang sehingga perintah gagal. Matikan \"Mode sandbox\" di Pengaturan > Lanjutan, " +
            "atau pasang dengan: pkg install proot-distro && proot-distro install ubuntu"
    )
}

/** Catat perintah yang TIDAK dijalankan (diblokir atau ditolak pengguna). Tidak boleh mengganggu alur agen. */
internal fun MainActivity.logSandboxOutcome(cmd: String, desc: String, a: Assessment, decision: String) {
    try {
        CommandLog.append(
            this,
            LogEntry(
                time = System.currentTimeMillis(), kind = "perintah", command = cmd, description = desc,
                risk = a.risk, mode = ApprovalMode.MANUAL, ok = false,
                result = a.reasons.joinToString("; ").ifBlank { decision },
                exitCode = null, decision = decision, sandbox = true, network = a.network
            )
        )
    } catch (e: Exception) {
    }
}
