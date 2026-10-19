package com.kita.agent

import android.content.pm.PackageManager

// Pemeriksaan dan penyiapan Termux (izin, paket, folder kerja).
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

internal fun MainActivity.openTermux() {
    val i = packageManager.getLaunchIntentForPackage("com.termux")
    if (i != null) startActivity(i) else toast("Termux tidak ditemukan")
}

/** Jalankan perintah panjang di Termux (pasang paket dll), lalu periksa ulang. */
internal fun MainActivity.setupRun(label: String, cmd: String, again: () -> Unit) {
    toast("$label... (bisa beberapa menit)")
    Thread {
        val r = try {
            termux(cmd, timeoutSec = 1800, useWorkdir = false)
        } catch (e: Exception) {
            "gagal: ${e.message}"
        }
        runOnUiThread {
            toast(if (r.startsWith("exit=0")) "$label selesai" else "$label gagal: " + r.take(100))
            again()
        }
    }.start()
}

internal fun MainActivity.setupChecks(again: () -> Unit): List<SetupStep> {
    val out = ArrayList<SetupStep>()
    val installed = try { packageManager.getPackageInfo("com.termux", 0); true } catch (e: Exception) { false }
    out.add(
        SetupStep(
            "Termux terpasang", installed,
            "Pasang Termux dari F-Droid atau GitHub (versi Play Store sudah usang).",
            listOf("Buka F-Droid" to { openUrl("https://f-droid.org/packages/com.termux/") })
        )
    )
    if (!installed) return out

    val perm = checkSelfPermission("com.termux.permission.RUN_COMMAND") == PackageManager.PERMISSION_GRANTED
    out.add(
        SetupStep(
            "Izin menjalankan perintah Termux", perm,
            "Kalau dialog tidak muncul: Info aplikasi, Izin, Tambahan, lalu izinkan \"Jalankan perintah di Termux\".",
            listOf(
                "Beri izin" to { requestPermissions(arrayOf("com.termux.permission.RUN_COMMAND"), 1) },
                "Buka Info aplikasi" to { openAppInfo() }
            )
        )
    )
    if (!perm) return out

    val ping = termux("echo ok", timeoutSec = 8, useWorkdir = false)
    val linked = ping.startsWith("exit=0") && ping.contains("ok")
    out.add(
        SetupStep(
            "Termux menerima perintah dari aplikasi", linked,
            "Cukup sekali: salin perintah, buka Termux, tempel lalu tekan Enter. Setelah itu ketuk Periksa lagi.",
            listOf(
                "Salin perintah" to { copyText("termux", allowCmd); toast("Perintah disalin") },
                "Buka Termux" to { openTermux() }
            )
        )
    )
    if (!linked) return out

    val r = termux(toolCheckCmd, useWorkdir = false)
    fun has(k: String) = r.contains("$k=ok")
    out.add(
        SetupStep(
            "JDK 17 (untuk build)", has("java"), "",
            listOf("Pasang JDK" to { setupRun("Memasang JDK", "pkg update -y >/dev/null 2>&1; pkg install -y openjdk-17 >/dev/null 2>&1", again) })
        )
    )
    out.add(
        SetupStep(
            "Git", has("git"), "",
            listOf("Pasang Git" to { setupRun("Memasang Git", "pkg install -y git >/dev/null 2>&1", again) })
        )
    )
    out.add(
        SetupStep(
            "Akses penyimpanan (/sdcard)", has("storage"),
            "Di Termux ketik: termux-setup-storage, lalu izinkan. Perlu untuk melampirkan file.",
            listOf(
                "Salin perintah" to { copyText("termux", "termux-setup-storage"); toast("Perintah disalin") },
                "Buka Termux" to { openTermux() }
            )
        )
    )
    val t = wdTarget(termuxHome)
    if (t != null) {
        val d = termux("test -d ${sh(t)}", useWorkdir = false)
        out.add(
            SetupStep(
                "Folder kerja: $t", d.startsWith("exit=0"), "Folder ini belum ada.",
                listOf("Buat folder" to { setupRun("Membuat folder", "mkdir -p ${sh(t)}", again) })
            )
        )
    }
    return out
}
