package com.kita.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDialogTest {

    @Test
    fun riskConfirmSpecCoversAllRiskySettings() {
        val riskyKeys = listOf("autonomous", "sandbox", "autoread", "apk_re")
        for (key in riskyKeys) {
            val spec = riskConfirmSpecFor(key)
            assertNotNull("Expected confirmation spec for $key", spec)
            assertTrue(spec!!.title.isNotBlank())
            assertTrue(spec.message.isNotBlank())
        }
        assertNull(riskConfirmSpecFor("draft"))
        assertNull(riskConfirmSpecFor("understand"))
        assertNull(riskConfirmSpecFor("readonly"))
    }

    @Test
    fun sanitizeConnErrorRedactsApiKeys() {
        val secretKey = "AIzaSySecretKeyValue1234567890"
        val rawError = "Invalid key provided: $secretKey at line 1\nSecond line details"
        val sanitized = sanitizeConnError(rawError, secretKey)
        assertFalse("Sanitized message must never contain raw API key", sanitized.contains(secretKey))
        assertTrue(sanitized.contains("***"))
        assertFalse(sanitized.contains("Second line"))
    }

    @Test
    fun sanitizeConnErrorRedactsKnownProviderKeyPatterns() {
        val raw = "Auth failed for token sk-ant-api03-abcdef1234567890"
        val sanitized = sanitizeConnError(raw, "")
        assertFalse(sanitized.contains("sk-ant-api03-abcdef1234567890"))
        assertTrue(sanitized.contains("***"))
    }

    @Test
    fun initialConnectionStatusReflectsConfiguration() {
        assertEquals(
            "Mode CLI Termux aktif",
            initialConnectionStatus(isCli = true, needsKey = false, hasKey = false, modelName = "", probedCount = 0)
        )
        assertEquals(
            "API key belum diisi",
            initialConnectionStatus(isCli = false, needsKey = true, hasKey = false, modelName = "gemini-2.5-flash", probedCount = 0)
        )
        assertEquals(
            "Siap • Model: gemini-2.5-flash (4 lolos uji)",
            initialConnectionStatus(isCli = false, needsKey = true, hasKey = true, modelName = "models/gemini-2.5-flash", probedCount = 4)
        )
        assertEquals(
            "Model aktif: gpt-4o • Ketuk Uji untuk verifikasi",
            initialConnectionStatus(isCli = false, needsKey = true, hasKey = true, modelName = "gpt-4o", probedCount = 0)
        )
    }
}
