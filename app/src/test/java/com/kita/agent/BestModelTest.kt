package com.kita.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit test pemilihan model otomatis. Murni JVM: ./gradlew testDebugUnitTest */
class BestModelTest {

    @Test
    fun flashTertinggiTanpaLiteDipilih() {
        val good = listOf("gemini-3.7-flash", "gemini-3.8-flash", "gemini-flash-lite-latest", "gemma-4-26b-a4b-it")
        assertEquals("gemini-3.8-flash", bestModel(good))
    }

    @Test
    fun liteDipakaiKalauHanyaItuYangAda() {
        assertEquals("gemini-flash-lite-latest", bestModel(listOf("gemini-flash-lite-latest", "gemma-4-26b-a4b-it")))
    }

    @Test
    fun sonnetDiutamakanDariHaiku() {
        assertEquals("claude-sonnet-4-6", bestModel(listOf("claude-haiku-4-5", "claude-sonnet-4-5", "claude-sonnet-4-6")))
    }

    @Test
    fun tanpaKecocokanAmbilYangPertama() {
        assertEquals("llama-3", bestModel(listOf("llama-3", "qwen-2")))
        assertNull(bestModel(emptyList()))
    }
}
