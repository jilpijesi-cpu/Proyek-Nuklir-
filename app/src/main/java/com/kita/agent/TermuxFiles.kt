package com.kita.agent

import android.app.AlertDialog
import android.util.Base64
import android.widget.TextView
import java.io.File

// Path, cadangan, undo, dan penulisan file lewat Termux.
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

/** Isi file lama: "" kalau belum ada, null kalau tidak bisa dibaca penuh. */
internal fun MainActivity.readOld(path: String): String? {
    val r = termux("test -f ${sh(path)} && cat ${sh(path)}", 300000)
    return when {
        r.startsWith("exit=1") -> ""
        r.startsWith("exit=0\n…(dipotong") -> null
        r.startsWith("exit=0\n") -> r.removePrefix("exit=0\n")
        else -> null
    }
}

/** Perluas awalan ~ (tanda kutip pada perintah mencegah shell memperluasnya sendiri). */
internal fun MainActivity.normPath(p: String): String {
    val t = p.trim()
    return when {
        t == "~" -> termuxHome
        t.startsWith("~/") -> termuxHome + "/" + t.removePrefix("~/")
        else -> t
    }
}

internal fun MainActivity.sh(s: String) = "'" + s.replace("'", "'\\''") + "'"

internal fun MainActivity.absPath(p: String): String =
    if (p.startsWith("/")) p else (wdTarget(termuxHome) ?: termuxHome) + "/" + p

/** Salin file lama ke ~/.kita_bak sebelum ditimpa (simpan 50 terakhir). Null kalau file belum ada. */
internal fun MainActivity.backupFile(path: String): String? {
    val dir = "$termuxHome/.kita_bak"
    val bak = "$dir/${System.currentTimeMillis()}_" + path.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)
    val r = try {
        termux(
            "mkdir -p ${sh(dir)} && if [ -f ${sh(path)} ]; then cp -p ${sh(path)} ${sh(bak)} && echo HAD_FILE; else echo NO_FILE; fi; " +
                "(cd ${sh(dir)} && ls -1t | tail -n +51 | xargs -r rm -f --) 2>/dev/null"
        )
    } catch (e: Cancelled) {
        throw e
    } catch (e: Exception) {
        return null
    }
    return if (r.contains("HAD_FILE")) bak else null
}

internal fun MainActivity.undoWrite(path: String, bak: String, btn: TextView, label: String) {
    AlertDialog.Builder(this)
        .setMessage("Kembalikan $path ke versi sebelum diubah?")
        .setPositiveButton("Kembalikan") { _, _ ->
            btn.isEnabled = false
            btn.text = "Mengembalikan..."
            Thread {
                val r = try {
                    termux("cp -p ${sh(bak)} ${sh(path)} && echo OK")
                } catch (e: Exception) {
                    "gagal: ${e.message}"
                }
                val ok = r.startsWith("exit=0") && r.trim().endsWith("OK")
                runOnUiThread {
                    if (ok) {
                        btn.text = "Sudah dikembalikan"
                        toast("File dikembalikan")
                    } else {
                        btn.isEnabled = true
                        btn.text = label
                        toast("Gagal mengembalikan: " + r.take(80))
                    }
                }
            }.start()
        }
        .setNegativeButton("Batal", null)
        .show()
}

/** Path absolut folder kerja di Termux, atau null kalau memakai home. */
internal fun MainActivity.wdTarget(home: String): String? {
    val wd = workdir()
    return when {
        wd.isEmpty() || wd == "~" -> null
        wd.startsWith("/") -> wd
        wd.startsWith("~/") -> "$home/${wd.removePrefix("~/")}"
        else -> "$home/$wd"
    }
}

/** Awalan perintah yang pindah ke folder kerja yang dipilih di Pengaturan. */
internal fun MainActivity.cdPrefix(home: String): String {
    val target = wdTarget(home) ?: return ""
    return "cd ${sh(target)} 2>/dev/null || { echo ${sh("Folder kerja tidak ditemukan: $target")}; exit 1; }; "
}

/**
 * Tulis file lewat potongan base64 kecil (satu argumen perintah dibatasi ±128 KB), ke file sementara,
 * lalu ganti file tujuan dengan mv. File lama tidak terkosongkan kalau gagal; izin (chmod) file lama dipertahankan.
 * Hasil berakhiran "OK" kalau berhasil.
 */
internal fun MainActivity.writeFileChunked(path: String, content: String): String {
    val b64 = Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP)
    val tmpDir = "/data/data/com.termux/files/usr/tmp"
    val tmpB = "$tmpDir/kita_${System.nanoTime()}.b64"
    val tmpOut = path + ".kita-tmp"
    fun cleanup() { try { termux("rm -f ${sh(tmpB)} ${sh(tmpOut)}") } catch (e: Exception) { /* cleanup best-effort: kegagalan tidak berdampak */ } }
    var r = termux("mkdir -p \"\$(dirname ${sh(path)})\" && mkdir -p ${sh(tmpDir)} && : > ${sh(tmpB)}")
    if (!r.startsWith("exit=0")) return r
    val size = 60000
    var i = 0
    while (i < b64.length) {
        if (cancelled) { cleanup(); throw Cancelled() }
        val part = b64.substring(i, minOf(i + size, b64.length))
        r = termux("printf '%s' '$part' >> ${sh(tmpB)}")
        if (!r.startsWith("exit=0")) { cleanup(); return r }
        i += size
    }
    return termux(
        "base64 -d ${sh(tmpB)} > ${sh(tmpOut)} && " +
            "{ [ ! -e ${sh(path)} ] || chmod --reference=${sh(path)} ${sh(tmpOut)} 2>/dev/null; true; } && " +
            "mv -f ${sh(tmpOut)} ${sh(path)} && echo OK; rm -f ${sh(tmpB)} ${sh(tmpOut)}"
    )
}
