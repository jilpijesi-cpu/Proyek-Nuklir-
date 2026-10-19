package com.kita.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Typeface
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

// Lampiran di gelembung chat: gambar tampil sebagai gambar mini, file lain sebagai kartu (ikon, nama, ukuran).

private const val ATTACH_WIDTH_DP = 200

/** Lokasi gambar mini di penyimpanan privat aplikasi; namanya diturunkan dari path file asli. */
internal fun MainActivity.thumbFile(path: String): File {
    val dir = File(filesDir, "thumbs")
    dir.mkdirs()
    val h = java.security.MessageDigest.getInstance("SHA-1").digest(path.toByteArray())
        .joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
    return File(dir, "$h.jpg")
}

/**
 * Buat gambar mini dari Uri asli (selalu terbaca, tidak bergantung izin penyimpanan) dan simpan untuk gelembung chat.
 * Orientasi EXIF diterapkan agar foto kamera tidak tampil miring. Gagal diam-diam: gelembung jatuh ke kartu file.
 */
internal fun MainActivity.saveThumb(uri: Uri, path: String) {
    try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 900) sample *= 2
        val bmp = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return
        val orientation = try {
            contentResolver.openInputStream(uri)?.use {
                android.media.ExifInterface(it).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL
                )
            } ?: android.media.ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            android.media.ExifInterface.ORIENTATION_NORMAL
        }
        val degrees = when (orientation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val out = if (degrees != 0f) {
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
        } else bmp
        thumbFile(path).outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        if (out !== bmp) out.recycle()
        bmp.recycle()
    } catch (e: Exception) {
    }
}

private fun decodeSampled(path: String, maxSide: Int): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
} catch (e: Exception) {
    null
}

/** Satu kartu atau gambar mini per lampiran, siap ditambahkan ke gelembung pesan pengguna. */
internal fun MainActivity.attachmentViews(list: List<ChatAttachment>): List<View> =
    list.map { a -> (if (a.isImage) imageThumb(a) else null) ?: fileCard(a) }

private fun MainActivity.imageThumb(a: ChatAttachment): View? {
    val tf = thumbFile(a.path)
    val original = File(a.path)
    val src = when {
        tf.isFile -> tf.absolutePath
        original.isFile && original.canRead() -> original.absolutePath
        else -> return null
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(src, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val wPx = dp(ATTACH_WIDTH_DP)
    val hPx = (wPx * bounds.outHeight.toFloat() / bounds.outWidth).toInt().coerceIn(dp(90), dp(280))
    val iv = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(Palette.FIELD)
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
            }
        }
        layoutParams = LinearLayout.LayoutParams(wPx, hPx).apply { bottomMargin = dp(6) }
    }
    Thread {
        val bmp = decodeSampled(src, dp(ATTACH_WIDTH_DP) * 2)
        if (bmp != null) runOnUiThread { iv.setImageBitmap(bmp) }
    }.start()
    // Ketuk untuk melihat layar penuh (pakai file asli bila terbaca, kalau tidak gambar mini).
    val full = if (original.isFile && original.canRead()) original.absolutePath else src
    iv.setOnClickListener { showImageFull(full) }
    return iv
}

private fun MainActivity.showImageFull(path: String) {
    Thread {
        val bmp = decodeSampled(path, 1600)
        runOnUiThread {
            if (bmp == null) return@runOnUiThread
            val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            val view = ImageView(this).apply {
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.BLACK)
                setOnClickListener { dialog.dismiss() }
            }
            dialog.setContentView(view)
            dialog.show()
        }
    }.start()
}

private fun MainActivity.fileCard(a: ChatAttachment): View {
    val f = File(a.path)
    val ext = a.name.substringAfterLast('.', "").uppercase().take(5)
    val size = if (f.isFile) formatSize(f.length()) else ""
    val meta = listOf(ext, size).filter { it.isNotEmpty() }.joinToString(" · ")
    val nameTv = TextView(this).apply {
        text = a.name
        textSize = 14.5f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Palette.INK)
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.MIDDLE
    }
    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(nameTv, LinearLayout.LayoutParams(MATCH, WRAP))
        if (meta.isNotEmpty()) addView(TextView(context).apply {
            text = meta
            textSize = 12f
            setTextColor(Palette.TIME_AI)
        }, LinearLayout.LayoutParams(MATCH, WRAP))
    }
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(Palette.FIELD, 12)
        setPadding(dp(10), dp(8), dp(12), dp(8))
        addView(IconView(context, IconView.FILE, Palette.BRAND), LinearLayout.LayoutParams(dp(32), dp(32)))
        addView(col, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(8) })
        layoutParams = LinearLayout.LayoutParams(dp(ATTACH_WIDTH_DP), WRAP).apply { bottomMargin = dp(6) }
    }
}
