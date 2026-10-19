package com.kita.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build

// Laporan masalah satu ketukan: kumpulkan info penting lalu salin ke papan klip, tinggal ditempel.
// Tidak memuat API key atau isi berkas kredensial (hanya cek ada atau tidaknya berkas).
// Fungsi-fungsi di sini adalah ekstensi MainActivity.

private const val DIAG_SCRIPT =
    "echo \"node: \$(node -v 2>&1)\"; " +
        "for c in codex gemini claude; do p=\$(command -v \$c) && echo \"\$c: \$(\$c --version 2>&1 | head -1)\" || echo \"\$c: belum terpasang\"; done; " +
        "for f in ~/.codex/auth.json ~/.gemini/oauth_creds.json ~/.claude/.credentials.json ~/.claude/credentials.json; do [ -e \"\$f\" ] && echo \"ada: \$f\"; done; " +
        "echo \"isi ~/.gemini: \$(ls -A ~/.gemini 2>&1 | tr '\\n' ' ')\"; " +
        "echo \"isi ~/.claude: \$(ls -A ~/.claude 2>&1 | tr '\\n' ' ')\""

/** Kumpulkan info aplikasi, Termux, dan perintah terakhir, lalu salin ke papan klip. */
internal fun MainActivity.copyReport() {
    toast("Menyiapkan laporan…")
    Thread {
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        val diag = termux(DIAG_SCRIPT, timeoutSec = 20, useWorkdir = false, killable = false)
        val logs = CommandLog.load(this).take(5).joinToString("\n") { e ->
            "- " + (if (e.ok) "OK" else "GAGAL") + ": " + e.command.take(120) + " -> " +
                e.result.lineSequence().firstOrNull().orEmpty().take(120)
        }
        val text = buildString {
            appendLine("Laporan Cloud")
            appendLine("Aplikasi: $ver | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) | ${Build.MODEL}")
            appendLine("Penyedia: $prov | model: ${cfgModel().ifBlank { "-" }} | folder kerja: ${workdir().ifBlank { "~" }}")
            appendLine("Hanya baca: ${prefs.getBoolean("readonly", false)}")
            appendLine("--- Termux ---")
            appendLine(diag.trim())
            appendLine("--- 5 perintah terakhir ---")
            appendLine(if (logs.isBlank()) "(kosong)" else logs)
        }
        runOnUiThread {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Laporan Cloud", text))
            toast("Laporan disalin. Tinggal tempel.")
        }
    }.start()
}
