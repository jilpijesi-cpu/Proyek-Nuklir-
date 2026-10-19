package com.kita.agent

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.TextView
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID

// Mencari, mengambil dari Termux, dan memasang APK hasil build.
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

internal fun MainActivity.findApk(): String? {
    val r = termux("find \"\$PWD\" -maxdepth 8 \\( -name .gradle -o -name node_modules -o -name .git \\) -prune -o -path '*/build/outputs/apk/*' -name '*.apk' -mmin -30 -printf '%T@ %p\\n' 2>/dev/null | sort -nr | head -1", timeoutSec = 60)
    val line = r.lines().drop(1).firstOrNull { it.isNotBlank() } ?: return null
    val p = line.substringAfter(' ').trim()
    return if (p.endsWith(".apk")) p else null
}

internal fun MainActivity.installApk(path: String, btn: TextView) {
    if (!packageManager.canRequestPackageInstalls()) {
        toast("Izinkan aplikasi ini memasang aplikasi, lalu ketuk Pasang APK lagi")
        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        return
    }
    btn.isEnabled = false
    btn.text = "Mengambil APK..."
    Thread {
        try {
            val f = fetchApk(path)
            runOnUiThread {
                btn.isEnabled = true
                btn.text = "Pasang APK"
                launchInstaller(f)
            }
        } catch (e: Exception) {
            runOnUiThread {
                btn.isEnabled = true
                btn.text = "Pasang APK"
                toast("Gagal mengambil APK: " + (e.message ?: "").take(100))
            }
        }
    }.start()
}

/**
 * Ambil APK dari Termux lewat soket lokal (127.0.0.1), tanpa izin penyimpanan.
 * Baris pertama aliran adalah token acak supaya aplikasi lain tidak bisa menyusup.
 */
internal fun MainActivity.fetchApk(termuxPath: String): File {
    val dir = File(cacheDir, "apk")
    dir.mkdirs()
    dir.listFiles()?.forEach { it.delete() }
    val dest = File(dir, "app.apk")
    val expected = termux("stat -c %s ${sh(termuxPath)}").lines().drop(1)
        .firstOrNull { it.isNotBlank() }?.trim()?.toLongOrNull() ?: -1L
    val token = UUID.randomUUID().toString()
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val err = arrayOfNulls<Exception>(1)
    val rx = Thread {
        try {
            server.soTimeout = 90000
            server.accept().use { sock ->
                val ins = sock.getInputStream().buffered()
                val sb = StringBuilder()
                while (true) {
                    val b = ins.read()
                    if (b < 0 || b == '\n'.code) break
                    sb.append(b.toChar())
                    if (sb.length > 100) break
                }
                if (sb.toString() != token) throw RuntimeException("Token tidak cocok")
                dest.outputStream().use { o -> ins.copyTo(o) }
            }
        } catch (e: Exception) {
            err[0] = e
        }
    }
    rx.start()
    val r = try {
        termux("{ printf '%s\\n' ${sh(token)}; cat ${sh(termuxPath)}; } > /dev/tcp/127.0.0.1/${server.localPort}")
    } catch (e: Exception) {
        server.close()
        throw e
    }
    if (!r.startsWith("exit=0")) {
        server.close()
        rx.join(2000)
        throw RuntimeException(r.take(120))
    }
    rx.join(60000)
    val stillRunning = rx.isAlive
    server.close()
    if (stillRunning) throw RuntimeException("Pengunduhan APK belum selesai (timeout)")
    err[0]?.let { throw it }
    if (dest.length() < 1000) throw RuntimeException("File APK kosong")
    if (expected > 0 && dest.length() != expected) {
        throw RuntimeException("Ukuran APK tidak cocok (${dest.length()} dari $expected byte)")
    }
    return dest
}

internal fun MainActivity.launchInstaller(f: File) {
    val uri = Uri.parse("content://com.kita.agent.apk/" + f.name)
    val i = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(i)
    } catch (e: Exception) {
        toast("Pemasang tidak bisa dibuka: " + (e.message ?: "").take(80))
    }
}
