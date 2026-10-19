package com.kita.agent

import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File

// Lampirkan file dari penyimpanan HP ke folder Download/ClaudeMythos.
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

internal fun MainActivity.pickFile() {
    val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
    }
    try {
        startActivityForResult(i, 78)
    } catch (e: Exception) {
        toast("Pemilih file tidak tersedia")
    }
}

/** Pilih gambar dari galeri/pemilih dokumen tanpa meminta akses penyimpanan luas. */
internal fun MainActivity.pickImage() {
    val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "image/*"
    }
    try {
        startActivityForResult(i, 79)
    } catch (e: Exception) {
        toast("Pemilih gambar tidak tersedia")
    }
}

internal fun MainActivity.displayName(uri: Uri): String {
    var n = "file"
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val v = c.getString(0)
            if (!v.isNullOrBlank()) n = v
        }
    }
    return n.replace(Regex("[^A-Za-z0-9._ -]"), "_")
}

/** Salin file pilihan ke Download/ClaudeMythos agar bisa dibaca dari Termux. Mengembalikan path-nya. */
internal fun MainActivity.copyToDownload(uri: Uri, name: String): String {
    val src = contentResolver.openInputStream(uri) ?: throw RuntimeException("File tidak bisa dibaca")
    return src.use { ins ->
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/ClaudeMythos")
            }
            val dest = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                ?: throw RuntimeException("Gagal membuat file di Download")
            val out = contentResolver.openOutputStream(dest) ?: throw RuntimeException("Gagal menulis file")
            out.use { o -> ins.copyTo(o) }
            var finalName = name
            contentResolver.query(dest, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) finalName = c.getString(0)
            }
            "/sdcard/Download/ClaudeMythos/$finalName"
        } else {
            val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "ClaudeMythos")
            dir.mkdirs()
            val f = File(dir, name)
            f.outputStream().use { o -> ins.copyTo(o) }
            "/sdcard/Download/ClaudeMythos/$name"
        }
    }
}

internal fun MainActivity.importFile(uri: Uri) {
    if (Build.VERSION.SDK_INT < 29 &&
        checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
    ) {
        pendingImport = uri
        requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 2)
        return
    }
    val name = displayName(uri)
    val att = Attachment(name)
    attachments.add(att)
    refreshAttachments()   // kartu langsung tampil (redup selama disalin)
    focusInput()           // keyboard terbuka: ketik perintah untuk file ini
    Thread {
        try {
            val dest = copyToDownload(uri, name)
            if (isImageName(name)) saveThumb(uri, dest) // gambar mini untuk gelembung chat
            att.path = dest
            runOnUiThread { refreshAttachments() }
        } catch (e: Exception) {
            runOnUiThread {
                attachments.remove(att)
                refreshAttachments()
                toast("Gagal melampirkan: " + (e.message ?: "").take(80))
            }
        }
    }.start()
}
