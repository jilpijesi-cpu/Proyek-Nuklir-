package com.kita.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

internal data class EncodedImage(val mimeType: String, val base64: String)

private const val IMAGE_MAX_SIDE = 1600
private const val IMAGE_MAX_BYTES = 3 * 1024 * 1024

internal fun isImageName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in setOf(
    "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "tif", "tiff", "avif"
)

/** Baca dan kecilkan gambar sekali; provider API menerima JPEG base64, file aslinya tetap utuh di Download. */
internal fun MainActivity.encodedImage(image: HistImage): EncodedImage {
    image.cachedPayload?.let { return it }
    synchronized(image) {
        image.cachedPayload?.let { return it }
        val file = File(image.path)
        require(file.isFile && file.canRead()) { "Gambar tidak bisa dibaca: ${file.name}" }
        require(file.length() <= 30L * 1024L * 1024L) { "Gambar melebihi batas 30 MB" }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0 &&
            bounds.outWidth.toLong() * bounds.outHeight.toLong() <= 500_000_000L) {
            "Format atau dimensi gambar tidak didukung. Gunakan JPG, PNG, atau WEBP."
        }

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > IMAGE_MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw IllegalArgumentException("Gambar gagal dibuka: ${file.name}")

        val longest = max(decoded.width, decoded.height)
        val scale = minOf(1f, IMAGE_MAX_SIDE.toFloat() / longest)
        val width = max(1, (decoded.width * scale).toInt())
        val height = max(1, (decoded.height * scale).toInt())
        val scaled = if (width != decoded.width || height != decoded.height)
            Bitmap.createScaledBitmap(decoded, width, height, true) else decoded
        val normalized = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(normalized).apply {
            drawColor(Color.WHITE)
            drawBitmap(scaled, 0f, 0f, null)
        }
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()

        var encoded: ByteArray? = null
        for (quality in listOf(84, 74, 64, 54)) {
            val out = ByteArrayOutputStream()
            val ok = normalized.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (ok && out.size() <= IMAGE_MAX_BYTES) {
                encoded = out.toByteArray()
                break
            }
        }
        normalized.recycle()
        val bytes = encoded ?: throw IllegalArgumentException("Gambar terlalu besar untuk dikirim ke AI")

        val payload = EncodedImage("image/jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP))
        image.cachedPayload = payload
        return payload
    }
}
