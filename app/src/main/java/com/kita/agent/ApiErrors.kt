package com.kita.agent

import java.io.IOException
import kotlin.math.ceil

// Klasifikasi error API model. Murni JVM (tanpa Android) supaya mudah diuji.

/** Error sementara dari server (503 "high demand", 429 per menit, overloaded, dll). Aman diulang. Turunan IOException agar ikut diulang oleh callModelRetry. */
internal class TransientApiException(message: String) : IOException(message)

/** Kuota harian/kredit habis. Menunggu beberapa detik tidak membantu; ganti model atau kunci API. */
internal class QuotaExhaustedException(message: String) : RuntimeException(message)

internal enum class ApiErrorKind { TRANSIENT, QUOTA, FATAL }

private val RETRYABLE_CODES = setOf(408, 429, 500, 502, 503, 504, 529)

private val QUOTA_MARKERS = listOf("perday", "per_day", "per day", "limit: 0", "insufficient_quota", "daily limit")

private val TRANSIENT_MARKERS = listOf(
    "high demand", "overloaded", "unavailable", "try again later", "temporarily",
    "resource_exhausted", "exceeded your current quota", "rate limit", "rate_limit",
    "internal error", "deadline exceeded", "timed out"
)

/**
 * Tentukan jenis error dari kode HTTP (atau -1 bila tidak ada), status teks (mis. "UNAVAILABLE", "overloaded_error") dan pesan.
 * Pesan 429 Gemini selalu memuat "check your plan and billing details", jadi kata "billing" TIDAK dipakai sebagai tanda kuota habis;
 * yang dipakai adalah penanda per-hari / limit 0 pada detailnya.
 */
internal fun classifyApiError(code: Int, status: String, message: String): ApiErrorKind {
    val t = (status + " " + message).lowercase()
    if (QUOTA_MARKERS.any { t.contains(it) }) return ApiErrorKind.QUOTA
    if (code in RETRYABLE_CODES) return ApiErrorKind.TRANSIENT
    if (TRANSIENT_MARKERS.any { t.contains(it) }) return ApiErrorKind.TRANSIENT
    return ApiErrorKind.FATAL
}

internal fun quotaMessage(code: Int, body: String): String =
    "Kuota API model ini habis" + (if (code > 0) " ($code)" else "") + ". Batas harian/kredit sudah terpakai, " +
        "menunggu tidak membantu. Ganti model atau kunci API, atau cek https://ai.dev/rate-limit. Detail: " + body.take(200)

/** Ubah error di dalam stream (setelah HTTP 200) menjadi exception yang tepat. */
internal fun apiStreamException(code: Int, status: String, message: String): Exception {
    val text = "API: " + message.take(300)
    return when (classifyApiError(code, status, message)) {
        ApiErrorKind.TRANSIENT -> TransientApiException(text)
        ApiErrorKind.QUOTA -> QuotaExhaustedException(quotaMessage(code, message))
        ApiErrorKind.FATAL -> RuntimeException(text)
    }
}

/** Ambil "retryDelay": "37s" dari badan error Gemini; dibulatkan ke atas. Null bila tidak ada. */
internal fun parseRetryDelaySec(body: String): Int? {
    val m = Regex("\"retryDelay\"\\s*:\\s*\"(\\d+(?:\\.\\d+)?)s\"").find(body) ?: return null
    return ceil(m.groupValues[1].toDouble()).toInt().takeIf { it > 0 }
}

/** Hanya error sementara atau kuota habis yang layak dicoba dengan model lain. */
internal fun isFallbackWorthy(e: Throwable): Boolean = e is TransientApiException || e is QuotaExhaustedException
