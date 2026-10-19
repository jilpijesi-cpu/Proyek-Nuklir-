package com.kita.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit test klasifikasi error API. Murni JVM: ./gradlew testDebugUnitTest */
class ApiErrorsTest {

    @Test
    fun highDemandDiDalamStreamDianggapSementara() {
        val e = apiStreamException(503, "UNAVAILABLE", "This model is currently experiencing high demand. Spikes in demand are usually temporary. Please try again later.")
        assertTrue(e is TransientApiException)
        assertTrue(e is java.io.IOException) // supaya callModelRetry mengulang
    }

    @Test
    fun kuotaHarianDianggapHabis() {
        val body = "You exceeded your current quota, please check your plan and billing details. quotaId: GenerateRequestsPerDayPerProjectPerModel-FreeTier"
        assertEquals(ApiErrorKind.QUOTA, classifyApiError(429, "RESOURCE_EXHAUSTED", body))
        assertTrue(apiStreamException(429, "RESOURCE_EXHAUSTED", body) is QuotaExhaustedException)
    }

    @Test
    fun limitNolDianggapKuotaHabis() {
        assertEquals(ApiErrorKind.QUOTA, classifyApiError(429, "", "Quota exceeded for metric ... limit: 0, model: gemini-x"))
    }

    @Test
    fun kuotaPerMenitTetapBisaDiulang() {
        val body = "You exceeded your current quota, please check your plan and billing details. quotaId: GenerateRequestsPerMinutePerProjectPerModel"
        assertEquals(ApiErrorKind.TRANSIENT, classifyApiError(429, "RESOURCE_EXHAUSTED", body))
    }

    @Test
    fun kodeSementaraDiulang() {
        for (c in listOf(408, 500, 502, 503, 504, 529)) assertEquals(ApiErrorKind.TRANSIENT, classifyApiError(c, "", "x"))
    }

    @Test
    fun anthropicOverloadedSementara() {
        assertEquals(ApiErrorKind.TRANSIENT, classifyApiError(-1, "overloaded_error", "Overloaded"))
    }

    @Test
    fun errorBiasaTidakDiulang() {
        assertEquals(ApiErrorKind.FATAL, classifyApiError(400, "INVALID_ARGUMENT", "API key not valid"))
        assertTrue(apiStreamException(400, "INVALID_ARGUMENT", "bad request") is RuntimeException)
        assertTrue(apiStreamException(400, "", "bad request") !is java.io.IOException)
    }

    @Test
    fun retryDelayDiparse() {
        assertEquals(37, parseRetryDelaySec("{\"retryDelay\": \"37s\"}"))
        assertEquals(2, parseRetryDelaySec("\"retryDelay\":\"1.2s\""))
        assertNull(parseRetryDelaySec("tanpa penanda"))
    }

    @Test
    fun hanyaTransienDanKuotaLayakFallback() {
        assertTrue(isFallbackWorthy(TransientApiException("x")))
        assertTrue(isFallbackWorthy(QuotaExhaustedException("x")))
        assertTrue(!isFallbackWorthy(RuntimeException("x")))
        assertTrue(!isFallbackWorthy(java.io.IOException("x")))
    }
}
