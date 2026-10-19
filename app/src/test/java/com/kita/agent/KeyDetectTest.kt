package com.kita.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit test pengenal API key. Murni JVM: ./gradlew testDebugUnitTest */
class KeyDetectTest {

    @Test
    fun gemini() = assertEquals("Gemini", detectProvider("AIzaSyA1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q"))

    @Test
    fun anthropic() = assertEquals("Anthropic", detectProvider("sk-ant-api03-abcdefghijklmnopqrstuvwxyz"))

    @Test
    fun openRouter() = assertEquals("OpenRouter", detectProvider("sk-or-v1-0123456789abcdef0123456789abcdef"))

    @Test
    fun groq() = assertEquals("Groq", detectProvider("gsk_abcdefghijklmnopqrstuvwxyz123456"))

    @Test
    fun deepSeek() = assertEquals("DeepSeek", detectProvider("sk-0123456789abcdef0123456789abcdef"))

    @Test
    fun openAiBaruDanLama() {
        assertEquals("OpenAI", detectProvider("sk-proj-abcdefghijklmnopqrstuvwxyz0123456789"))
        assertEquals("OpenAI", detectProvider("sk-abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUV"))
    }

    @Test
    fun terlaluPendekAtauTidakDikenali() {
        assertNull(detectProvider(""))
        assertNull(detectProvider("sk-ant-abc"))
        assertNull(detectProvider("kuncirahasia1234567890abcdef"))
        assertNull(detectProvider("sk-ant-api03 abcdefghijklmnop"))
    }

    @Test
    fun hasilTempelDibersihkan() {
        assertEquals("Anthropic", detectProvider("  \"sk-ant-api03-abcdefghijklmnopqrstuvwxyz\"\n"))
        assertEquals("Groq", detectProvider("Bearer gsk_abcdefghijklmnopqrstuvwxyz123456"))
        assertEquals("sk-abc", normalizeKey(" 'sk-abc' "))
    }
}
