package com.kita.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit test susunan cadangan dan status kartu peralihan. Murni JVM: ./gradlew testDebugUnitTest */
class FailoverTest {

    private val gemini = ProviderPool("Gemini", "gemini-3.5-flash", listOf("gemini-3.5-flash", "gemini-2.5-flash", "gemini-2.5-flash-lite", "gemma-4"))
    private val openai = ProviderPool("OpenAI", "gpt-4o", listOf("gpt-4o", "gpt-4o-mini"))

    @Test
    fun modelYangGagalDilewatiDanPenyediaUtamaDidahulukan() {
        val chain = buildChain("Gemini", listOf(openai, gemini), setOf("Gemini/gemini-3.5-flash"))
        assertEquals(FoCandidate("Gemini", "gemini-2.5-flash"), chain.first())
        assertTrue(chain.none { it.id == "Gemini/gemini-3.5-flash" })
    }

    @Test
    fun penyediaLainDicobaSebelumModelKetigaDiPenyediaUtama() {
        val chain = buildChain("Gemini", listOf(gemini, openai), setOf("Gemini/gemini-3.5-flash"))
        val ids = chain.map { it.id }
        assertTrue(ids.indexOf("OpenAI/gpt-4o") < ids.indexOf("Gemini/gemma-4"))
    }

    @Test
    fun semuaDiblokirHasilnyaKosong() {
        val all = (gemini.models + "x").map { "Gemini/$it" }.toSet()
        assertTrue(buildChain("Gemini", listOf(ProviderPool("Gemini", "", gemini.models)), all).isEmpty())
    }

    @Test
    fun batasTotalDipatuhi() {
        val big = ProviderPool("Groq", "m0", (1..30).map { "m$it" })
        assertEquals(5, buildChain("Groq", listOf(big), emptySet(), total = 5).size)
    }
}
