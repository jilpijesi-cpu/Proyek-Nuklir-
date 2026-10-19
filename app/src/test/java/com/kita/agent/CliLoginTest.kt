package com.kita.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit test daftar CLI login. Murni JVM: ./gradlew testDebugUnitTest */
class CliLoginTest {

    @Test
    fun templatChatSemuaCliMemuatPrompt() {
        for (c in CLI_LOGINS) assertTrue(c.name, c.run.contains("{prompt}"))
    }

    @Test
    fun namaUnikDanBisaDicari() {
        assertEquals(CLI_LOGINS.size, CLI_LOGINS.map { it.name }.toSet().size)
        assertEquals("codex", cliLoginOf("Codex (ChatGPT)")?.bin)
        assertNull(cliLoginOf("tidak ada"))
    }
}
