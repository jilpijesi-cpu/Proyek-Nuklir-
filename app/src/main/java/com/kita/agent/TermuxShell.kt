package com.kita.agent

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Menjalankan perintah di Termux (RUN_COMMAND) dan menghentikannya.
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

/** Hentikan proses perintah di Termux (PID dicatat di pidFile) beserta anak-anaknya; jalan di thread terpisah. */
internal fun MainActivity.killRemote(pidFile: String) {
    Thread {
        try {
            termux(
                "kt() { for c in \$(pgrep -P \"\$1\" 2>/dev/null); do kt \"\$c\" \"\$2\"; done; kill -\"\$2\" \"\$1\" 2>/dev/null; }; " +
                    "p=\$(cat ${sh(pidFile)} 2>/dev/null); " +
                    "if [ -n \"\$p\" ]; then kt \"\$p\" TERM; sleep 2; kt \"\$p\" KILL; fi; rm -f ${sh(pidFile)}",
                4000, 15, false, false
            )
        } catch (e: Exception) {
        }
    }.start()
}

// Jalankan perintah di Termux lewat RUN_COMMAND, lalu tunggu hasilnya (stdout/stderr/exit code).
internal fun MainActivity.termux(
    cmd: String,
    limit: Int = 4000,
    timeoutSec: Long = 600,
    useWorkdir: Boolean = true,
    killable: Boolean = true,
    liveFile: String? = null,
    silentRetry: Boolean = false
): String {
    // Jika liveFile diberikan, keluaran juga ditulis (tee) ke file itu agar bisa dibaca selama perintah berjalan.
    val runBody = if (liveFile != null) "mkdir -p ${sh(termuxTmp)}\nset -o pipefail\n(\n$cmd\n) 2>&1 | tee -a ${sh(liveFile)}" else cmd
    val action = "com.kita.agent.RESULT." + UUID.randomUUID()
    val pidFile = "$termuxTmp/kita_run_${UUID.randomUUID()}.pid"
    val pidPrefix = if (killable)
        "mkdir -p ${sh(termuxTmp)}; trap ${sh("rm -f $pidFile")} EXIT; echo \$\$ > ${sh(pidFile)}; "
    else ""
    val latch = CountDownLatch(1)
    var out = "Tidak ada hasil."
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val b = i.getBundleExtra("result")
            if (b != null) {
                val errmsg = b.getString("errmsg") ?: ""
                val body = (b.getString("stdout") ?: "") + (b.getString("stderr") ?: "")
                val shown = if (body.length > limit)
                    "…(dipotong, ${body.length - limit} karakter awal dibuang)\n" + body.takeLast(limit)
                else body
                out = (if (errmsg.isNotEmpty()) "ERR: $errmsg\n" else "") +
                    "exit=${b.getInt("exitCode")}\n" + shown
            }
            latch.countDown()
        }
    }
    val filter = IntentFilter(action)
    if (Build.VERSION.SDK_INT >= 33) {
        registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
        registerReceiver(receiver, filter)
    }
    try {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getBroadcast(
            this, reqCode++, Intent(action).setPackage(packageName), flags
        )
        val home = "/data/data/com.termux/files/home"
        val bin = "/data/data/com.termux/files/usr/bin"
        val i = Intent().apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            this.action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", "$bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", "export PATH=$bin:\$PATH; $pidPrefix${if (useWorkdir) cdPrefix(home) else ""}$runBody"))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", home)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi)
        }
        var tries = 0
        var savedStatus: StatusSnap? = null
        while (true) {
            try {
                startService(i)
                break
            } catch (e: IllegalStateException) {
                // Android 12+: aplikasi di latar belakang tidak boleh memulai service. Tunggu sampai dibuka lagi.
                if (++tries > 300) throw e
                if (savedStatus == null && !silentRetry) {
                    savedStatus = saveStatus()
                    setStatus(KIND_DOTS, "Buka aplikasi ini lagi agar perintah bisa jalan")
                }
                if (cancelled) throw InterruptedException()
                Thread.sleep(2000)
            }
        }
        savedStatus?.let { restoreStatus(it) }
        if (!latch.await(timeoutSec, TimeUnit.SECONDS)) {
            if (killable) killRemote(pidFile)
            return timeoutResult(timeoutSec)
        }
    } catch (e: InterruptedException) {
        if (killable) killRemote(pidFile)
        throw Cancelled()
    } catch (e: Exception) {
        return "Gagal memanggil Termux: ${e.message}"
    } finally {
        unregisterReceiver(receiver)
    }
    return out
}
