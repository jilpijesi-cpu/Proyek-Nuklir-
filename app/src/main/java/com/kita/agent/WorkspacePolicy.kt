package com.kita.agent

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/**
 * Kebijakan dan pengelolaan fitur "Ruang Kerja" (Workspace).
 *
 * Logika murni (validasi nama, pembuatan perintah inisialisasi, dan pengecekan canonical path
 * di dalam ruang kerja) dipisahkan dari fungsi ekstensi UI MainActivity agar dapat diuji penuh
 * di JVM unit test.
 */

const val DEFAULT_WORKSPACE_NAME = "default"
const val WORKSPACES_DIR_NAME = "workspaces"
const val PREF_WORKSPACE_NAME = "workspace_name"

private val WORKSPACE_NAME_REGEX = Regex("^[A-Za-z0-9_-]{1,32}$")

data class WorkspaceNameValidation(
    val valid: Boolean,
    val cleanedName: String,
    val error: String? = null
)

/**
 * Validasi nama ruang kerja sesuai aturan:
 * - Wajib cocok dengan regex ^[A-Za-z0-9_-]{1,32}$
 * - Menolak spasi, titik (.), garis miring (/ atau \), dan ".."
 */
fun validateWorkspaceName(rawName: String): WorkspaceNameValidation {
    val name = rawName
    if (name.isEmpty()) {
        return WorkspaceNameValidation(false, name, "Nama ruang kerja tidak boleh kosong.")
    }
    if (name.contains("..")) {
        return WorkspaceNameValidation(false, name, "Nama ruang kerja tidak boleh mengandung '..'.")
    }
    if (name.contains('.')) {
        return WorkspaceNameValidation(false, name, "Nama ruang kerja tidak boleh mengandung titik (.).")
    }
    if (name.contains('/') || name.contains('\\')) {
        return WorkspaceNameValidation(false, name, "Nama ruang kerja tidak boleh mengandung garis miring.")
    }
    if (name.any { it.isWhitespace() }) {
        return WorkspaceNameValidation(false, name, "Nama ruang kerja tidak boleh mengandung spasi.")
    }
    if (name.length > 32) {
        return WorkspaceNameValidation(false, name, "Nama ruang kerja maksimal 32 karakter.")
    }
    if (!WORKSPACE_NAME_REGEX.matches(name)) {
        return WorkspaceNameValidation(
            false,
            name,
            "Nama hanya boleh berisi huruf (A-Z, a-z), angka (0-9), garis bawah (_), atau tanda hubung (-)."
        )
    }
    return WorkspaceNameValidation(true, name, null)
}

fun isValidWorkspaceName(name: String): Boolean = validateWorkspaceName(name).valid

/** Path relatif ruang kerja dari home Termux, mis. "workspaces/default". */
fun workspaceRelativePath(name: String): String {
    val validName = if (isValidWorkspaceName(name)) name else DEFAULT_WORKSPACE_NAME
    return "$WORKSPACES_DIR_NAME/$validName"
}

/** Path absolut ruang kerja di Termux, mis. "/data/data/com.termux/files/home/workspaces/default". */
fun workspaceAbsolutePath(termuxHome: String, name: String): String {
    val validName = if (isValidWorkspaceName(name)) name else DEFAULT_WORKSPACE_NAME
    return "${termuxHome.trimEnd('/')}/$WORKSPACES_DIR_NAME/$validName"
}

private fun shqWorkspace(s: String): String = "'" + s.replace("'", "'\\''") + "'"

/**
 * Susun perintah Termux untuk membuat folder ~/workspaces/<nama> (mkdir -p)
 * lalu menjalankan git init jika belum ada.
 */
fun buildCreateWorkspaceCommand(
    name: String,
    termuxHome: String = "/data/data/com.termux/files/home"
): String {
    val v = validateWorkspaceName(name)
    require(v.valid) { v.error ?: "Nama ruang kerja tidak valid" }
    val dir = workspaceAbsolutePath(termuxHome, v.cleanedName)
    val qDir = shqWorkspace(dir)
    return "mkdir -p $qDir && cd $qDir && ( [ -d .git ] || git init )"
}

/**
 * Periksa apakah perintah pembuatan ruang kerja di Termux terbukti berhasil (exit=0).
 */
fun didWorkspaceCommandSucceed(termuxResult: String): Boolean {
    val (exitCode, _) = parseTermuxResult(termuxResult)
    return exitCode == 0
}

/**
 * Periksa apakah token path adalah referensi mentah ke ~ atau $HOME yang keluar dari ruang kerja.
 */
fun isRawHomeReference(rawPath: String): Boolean {
    val t = rawPath.trim()
    if (t == "~" || t == "~/" || t.startsWith("~/")) return true
    if (t == "\$HOME" || t.startsWith("\$HOME/") || t == "\${HOME}" || t.startsWith("\${HOME}/")) return true
    if (t.startsWith("~")) return true
    return false
}

/**
 * Periksa apakah path menyentuh folder terlarang ~/kita.
 */
fun touchesForbiddenKitaDir(
    rawPath: String,
    termuxHome: String = "/data/data/com.termux/files/home"
): Boolean {
    val t = rawPath.trim().replace('\\', '/')
    val homeClean = termuxHome.trimEnd('/')
    if (t == "~/kita" || t.startsWith("~/kita/")) return true
    if (t == "\$HOME/kita" || t.startsWith("\$HOME/kita/")) return true
    if (t == "\${HOME}/kita" || t.startsWith("\${HOME}/kita/")) return true
    if (t == "$homeClean/kita" || t.startsWith("$homeClean/kita/")) return true
    return try {
        val canon = if (t.startsWith("/")) File(t).canonicalPath else null
        val kitaCanon = File("$homeClean/kita").canonicalPath
        canon != null && (canon == kitaCanon || canon.startsWith("$kitaCanon/"))
    } catch (_: Exception) {
        false
    }
}

/**
 * Resolusi path memakai canonical path (File.canonicalPath), bukan pencocokan string biasa.
 * Mengembalikan null bila path memakai variabel shell ($), substitusi (`), atau ~ mentah.
 */
fun resolveCanonicalWorkspacePath(
    rawPath: String,
    workspaceDir: File,
    homeDir: File = File("/data/data/com.termux/files/home")
): String? {
    val trimmed = rawPath.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.contains('$') || trimmed.contains('`')) return null
    if (isRawHomeReference(trimmed)) return null
    return try {
        val wsCanon = workspaceDir.canonicalFile
        val targetFile = if (trimmed.startsWith("/")) {
            File(trimmed)
        } else {
            File(wsCanon, trimmed)
        }
        targetFile.canonicalPath
    } catch (_: Exception) {
        null
    }
}

/**
 * Periksa apakah [rawPath] berada di dalam [workspaceDir] menggunakan canonical path.
 * Menolak:
 * - Path absolut di luar ruang kerja
 * - Traversal "../" yang keluar dari ruang kerja
 * - Symlink di dalam ruang kerja yang mengarah ke luar ruang kerja
 * - Referensi ~ atau $HOME mentah
 * - Akses ke ~/kita, .env, atau keystore
 */
fun isPathInsideWorkspace(
    rawPath: String,
    workspaceDir: File,
    homeDir: File = File("/data/data/com.termux/files/home")
): Boolean {
    if (touchesForbiddenKitaDir(rawPath, homeDir.path)) return false
    if (isBlockedSecretOrKeystoreFile(rawPath)) return false
    val resolvedCanon = resolveCanonicalWorkspacePath(rawPath, workspaceDir, homeDir) ?: return false
    return try {
        val wsCanon = workspaceDir.canonicalPath.trimEnd(File.separatorChar)
        val homeKitaCanon = File(homeDir.canonicalFile, "kita").canonicalPath
        if (resolvedCanon == homeKitaCanon || resolvedCanon.startsWith("$homeKitaCanon${File.separator}")) {
            return false
        }
        if (isBlockedSecretOrKeystoreFile(resolvedCanon)) {
            return false
        }
        resolvedCanon == wsCanon || resolvedCanon.startsWith("$wsCanon${File.separator}")
    } catch (_: Exception) {
        false
    }
}

// =========================================================================================
// Integrasi Ruang Kerja dengan MainActivity
// =========================================================================================

/** Nama ruang kerja yang sudah tersimpan dan valid di SharedPreferences, atau null jika belum ada. */
internal fun MainActivity.savedWorkspaceName(): String? {
    val raw = prefs.getString(PREF_WORKSPACE_NAME, null)?.trim() ?: return null
    return if (isValidWorkspaceName(raw)) raw else null
}

/** Nama ruang kerja aktif (selalu jatuh ke "default" jika belum disetel, tidak pernah ke "kita"). */
internal fun MainActivity.activeWorkspaceName(): String =
    savedWorkspaceName() ?: DEFAULT_WORKSPACE_NAME

/** Path absolut ruang kerja aktif di Termux: /data/data/com.termux/files/home/workspaces/<nama>. */
internal fun MainActivity.workspaceAbsPath(): String =
    workspaceAbsolutePath(termuxHome, activeWorkspaceName())

/** Path tampilan singkat untuk UI, mis. ~/workspaces/default. */
internal fun MainActivity.workspaceDisplayPath(name: String = activeWorkspaceName()): String =
    "~/$WORKSPACES_DIR_NAME/$name"

/** Simpan ruang kerja ke SharedPreferences HANYA setelah folder dan git init terbukti berhasil di Termux. */
internal fun MainActivity.saveVerifiedWorkspace(name: String) {
    val v = validateWorkspaceName(name)
    if (!v.valid) return
    prefs.edit()
        .putString(PREF_WORKSPACE_NAME, v.cleanedName)
        .putString("workdir", workspaceRelativePath(v.cleanedName))
        .apply()
}

@Volatile
private var workspaceDialogShowing = false

/**
 * Dipanggil setiap kali aplikasi dibuka dan saat sesi chat baru dibuat:
 * 1. Jika belum ada ruang kerja tersimpan di SharedPreferences, tampilkan dialog wajib "Buat ruang kerja".
 * 2. Jika sudah ada, periksa ke Termux apakah foldernya (~/workspaces/<nama>) masih ada.
 *    Bila foldernya sudah hilang, tampilkan kembali dialog wajib "Buat ruang kerja".
 */
internal fun MainActivity.ensureWorkspaceReady(onReady: (() -> Unit)? = null) {
    val saved = savedWorkspaceName()
    if (saved == null) {
        showCreateWorkspaceDialog(mandatory = true, initialName = DEFAULT_WORKSPACE_NAME, onSuccess = onReady)
        return
    }
    // Sinkronkan key workdir lama agar tidak pernah kembali ke "kita"
    val expectedRel = workspaceRelativePath(saved)
    if (prefs.getString("workdir", "") != expectedRel) {
        prefs.edit().putString("workdir", expectedRel).apply()
    }
    Thread {
        val targetDir = workspaceAbsolutePath(termuxHome, saved)
        val hasTermuxPerm = try {
            packageManager.getPackageInfo("com.termux", 0)
            checkSelfPermission("com.termux.permission.RUN_COMMAND") ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
        if (!hasTermuxPerm) {
            // Bila Termux belum siap/diizinkan, pengguna tetap wajib menyelesaikan penyiapan ruang kerja.
            runOnUiThread {
                showCreateWorkspaceDialog(mandatory = true, initialName = saved, onSuccess = onReady)
            }
            return@Thread
        }
        val check = try {
            termux("test -d ${sh(targetDir)} && test -d ${sh("$targetDir/.git")}", timeoutSec = 10, useWorkdir = false, silentRetry = true)
        } catch (_: Exception) {
            "exit=1"
        }
        runOnUiThread {
            if (didWorkspaceCommandSucceed(check)) {
                onReady?.invoke()
            } else {
                showCreateWorkspaceDialog(mandatory = true, initialName = saved, onSuccess = onReady)
            }
        }
    }.start()
}

/**
 * Dialog "Buat ruang kerja" (wajib saat pertama kali dibuka / saat folder hilang; tidak bisa ditutup atau dilewati).
 * Juga dipakai saat mengganti ruang kerja dari Pengaturan (mandatory = false).
 */
internal fun MainActivity.showCreateWorkspaceDialog(
    mandatory: Boolean = true,
    initialName: String = savedWorkspaceName() ?: DEFAULT_WORKSPACE_NAME,
    onSuccess: (() -> Unit)? = null
) {
    if (isFinishing) return
    if (mandatory && workspaceDialogShowing) return
    if (mandatory) workspaceDialogShowing = true

    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(12), dp(20), dp(4))
    }

    box.addView(TextView(this).apply {
        text = if (mandatory) {
            "Agen hanya bekerja di dalam satu folder ruang kerja miliknya sendiri (~/workspaces/<nama>). " +
                "Buat ruang kerja terlebih dahulu untuk memulai."
        } else {
            "Masukkan nama ruang kerja baru atau yang sudah ada di ~/workspaces/<nama>. " +
                "Folder akan dibuat otomatis beserta repositori Git jika belum ada."
        }
        textSize = 13.5f
        setTextColor(Palette.INK_SOFT)
        setLineSpacing(0f, 1.25f)
    })

    val nameInput = EditText(this).apply {
        setText(initialName.ifBlank { DEFAULT_WORKSPACE_NAME })
        setSelection(text.length)
        hint = DEFAULT_WORKSPACE_NAME
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        textSize = 15f
        setTextColor(Palette.INK)
        background = rounded(Palette.FIELD, 12, Palette.DIVIDER)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }
    box.addView(nameInput, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })

    val pathPreview = TextView(this).apply {
        text = "Lokasi: ~/workspaces/${nameInput.text.toString().trim().ifEmpty { DEFAULT_WORKSPACE_NAME }}"
        textSize = 12f
        typeface = Typeface.MONOSPACE
        setTextColor(Palette.INK_SOFT)
        setPadding(dp(4), dp(6), dp(4), 0)
    }
    box.addView(pathPreview, LinearLayout.LayoutParams(MATCH, WRAP))

    val errorView = TextView(this).apply {
        textSize = 13f
        setTextColor(Palette.RED)
        setPadding(dp(4), dp(8), dp(4), 0)
        visibility = View.GONE
    }
    box.addView(errorView, LinearLayout.LayoutParams(MATCH, WRAP))

    nameInput.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            val raw = s?.toString() ?: ""
            val v = validateWorkspaceName(raw)
            pathPreview.text = "Lokasi: ~/workspaces/${raw.ifEmpty { DEFAULT_WORKSPACE_NAME }}"
            if (raw.isNotEmpty() && !v.valid) {
                errorView.text = v.error
                errorView.visibility = View.VISIBLE
            } else {
                errorView.visibility = View.GONE
            }
        }
    })

    val builder = AlertDialog.Builder(this)
        .setTitle(if (mandatory) "Buat ruang kerja" else "Ganti ruang kerja")
        .setView(box)
        .setCancelable(!mandatory)
        .setPositiveButton("Buat & Gunakan", null)
        .setNeutralButton("Siapkan Termux", null)

    if (!mandatory) {
        builder.setNegativeButton("Batal", null)
    }

    val dlg = builder.create()
    if (mandatory) {
        dlg.setCanceledOnTouchOutside(false)
    }
    dlg.setOnDismissListener {
        if (mandatory) workspaceDialogShowing = false
    }
    dlg.show()

    val posBtn = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
    val neuBtn = dlg.getButton(AlertDialog.BUTTON_NEUTRAL)

    neuBtn.setOnClickListener {
        showSetup()
    }

    posBtn.setOnClickListener {
        val rawName = nameInput.text.toString()
        val validation = validateWorkspaceName(rawName)
        if (!validation.valid) {
            errorView.text = validation.error ?: "Nama ruang kerja tidak valid."
            errorView.visibility = View.VISIBLE
            return@setOnClickListener
        }
        val cleanName = validation.cleanedName
        errorView.visibility = View.GONE
        posBtn.isEnabled = false
        posBtn.text = "Membuat..."
        nameInput.isEnabled = false

        val cmd = buildCreateWorkspaceCommand(cleanName, termuxHome)
        Thread {
            val res = try {
                termux(cmd, timeoutSec = 30, useWorkdir = false)
            } catch (e: Exception) {
                "Gagal memanggil Termux: ${e.message}"
            }
            val ok = didWorkspaceCommandSucceed(res)
            runOnUiThread {
                posBtn.isEnabled = true
                posBtn.text = "Buat & Gunakan"
                nameInput.isEnabled = true
                if (ok) {
                    saveVerifiedWorkspace(cleanName)
                    toast("Ruang kerja '$cleanName' aktif (~/workspaces/$cleanName)")
                    if (mandatory) workspaceDialogShowing = false
                    dlg.dismiss()
                    onSuccess?.invoke()
                } else {
                    val detail = parseTermuxResult(res).second.trim().ifEmpty { res.take(160) }
                    errorView.text = "Gagal menyiapkan ~/workspaces/$cleanName di Termux:\n$detail\nPastikan Termux & Git sudah siap lalu coba lagi."
                    errorView.visibility = View.VISIBLE
                }
            }
        }.start()
    }
}

/**
 * Dialog konfirmasi sebelum mengganti ruang kerja dari Pengaturan.
 */
internal fun MainActivity.confirmSwitchWorkspaceDialog(onSwitched: (() -> Unit)? = null) {
    val current = activeWorkspaceName()
    AlertDialog.Builder(this)
        .setTitle("Ganti ruang kerja?")
        .setMessage(
            "Ruang kerja saat ini adalah '$current' (~/workspaces/$current).\n\n" +
                "Mengganti ruang kerja akan mengarahkan seluruh perintah dan operasi file agen ke folder ruang kerja yang baru."
        )
        .setNegativeButton("Batal", null)
        .setPositiveButton("Lanjutkan") { _, _ ->
            showCreateWorkspaceDialog(
                mandatory = false,
                initialName = current,
                onSuccess = onSwitched
            )
        }
        .show()
}
