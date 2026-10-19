package com.kita.agent

import android.content.Intent

// Login CLI berlangganan lewat Termux (data CLI ada di CliLogins.kt).
// Sesi login dibuka terlihat (foreground) supaya link login bisa dibuka atau disalin.
// Fungsi-fungsi di sini adalah ekstensi MainActivity.

/** Buka sesi Termux: pasang CLI kalau belum ada, lalu jalankan perintah login-nya. */
internal fun MainActivity.cliLogin(c: CliLogin) {
    val bin = "/data/data/com.termux/files/usr/bin"
    val home = "/data/data/com.termux/files/home"
    val script = "export BROWSER=termux-open-url; " +
        "command -v ${c.bin} >/dev/null 2>&1 || { ${c.install}; }; " +
        "${c.login}; echo; echo 'Selesai. Kembali ke aplikasi Cloud.'"
    try {
        val i = Intent().apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            this.action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", "$bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", "export PATH=$bin:\$PATH; $script"))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", home)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
        }
        startService(i)
        if (c.tip.isNotEmpty()) toast(c.tip)
    } catch (e: Exception) {
        toast("Gagal membuka Termux: " + (e.message ?: "").take(80))
    }
}

/** True kalau perintah cek login CLI ini sukses (exit 0). Jalankan di thread latar. */
internal fun MainActivity.cliLoggedIn(c: CliLogin): Boolean =
    termux(c.check, timeoutSec = 10, useWorkdir = false, killable = false).startsWith("exit=0")

/**
 * Login satu ketukan, tanpa pengguna perlu tahu Termux: cek Termux, pasang CLI kalau belum ada, lalu buka login.
 * Kabar tiap langkah dikirim lewat [say] (boleh dipanggil dari thread mana pun, tampilkan lewat runOnUiThread).
 */
internal fun MainActivity.cliSetup(c: CliLogin, say: (String) -> Unit) {
    say("1/3 Memeriksa Termux…")
    Thread {
        val ping = termux("echo ok", timeoutSec = 8, useWorkdir = false, killable = false)
        if (!(ping.startsWith("exit=0") && ping.contains("ok"))) {
            say("Termux belum siap. Ikuti langkah di layar Siapkan Termux.")
            runOnUiThread { showSetup() }
            return@Thread
        }
        val has = termux("command -v ${c.bin}", timeoutSec = 10, useWorkdir = false, killable = false).startsWith("exit=0")
        if (!has) {
            say("2/3 Memasang ${c.bin}… tunggu beberapa menit")
            val r = termux(c.install, timeoutSec = 1800, useWorkdir = false, killable = false)
            if (!r.startsWith("exit=0")) {
                val why = r.lineSequence().drop(1).firstOrNull { it.isNotBlank() }.orEmpty().take(80)
                say("Gagal memasang ${c.bin}: $why. Ketuk \"Salin laporan masalah\" di bawah untuk melaporkan.")
                return@Thread
            }
        }
        say("3/3 Selesaikan login di browser, lalu kembali ke sini")
        runOnUiThread { cliLogin(c) }
    }.start()
}
