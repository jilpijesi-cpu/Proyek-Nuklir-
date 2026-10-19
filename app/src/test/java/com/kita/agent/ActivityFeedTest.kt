package com.kita.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit test logika murni Live Agent Activity Feed. Murni JVM: ./gradlew testDebugUnitTest */
class ActivityFeedTest {

    @Test
    fun durasiDitampilkanManusiawi() {
        assertEquals("850 md", fmtDur(850))
        assertEquals("2 mnt 5 dtk", fmtDur(125_000))
        val s = fmtDur(1200)
        assertTrue(s, s.startsWith("1") && s.endsWith("dtk"))
        assertEquals("", fmtDur(-1))
    }

    @Test
    fun statusDipulihkanDariKeluaranAlat() {
        assertEquals(StepState.OK, stateOfResult("exit=0\nhalo"))
        assertEquals(StepState.OK, stateOfResult("tulis ok\nOK"))
        assertEquals(StepState.FAIL, stateOfResult("exit=1\nerror"))
        assertEquals(StepState.FAIL, stateOfResult("Error: args rusak"))
        assertEquals(StepState.DENIED, stateOfResult("Ditolak pengguna."))
        assertEquals(StepState.STOPPED, stateOfResult(null))
    }

    @Test
    fun catatanBarisSesuaiKeadaan() {
        assertEquals("2 mnt 5 dtk", noteFor(StepState.RUNNING, "", 125_000))
        assertEquals("850 md", noteFor(StepState.OK, "", 850))
        assertEquals("", noteFor(StepState.OK, "", -1)) // riwayat tanpa durasi
        assertEquals("menunggu izin", noteFor(StepState.WAITING, "", 10))
        assertEquals("exit=1", noteFor(StepState.FAIL, " exit=1 ", 10))
        assertEquals("gagal", noteFor(StepState.FAIL, "", 10))
        assertEquals("ditolak", noteFor(StepState.DENIED, "", 10))
        assertEquals("dihentikan", noteFor(StepState.STOPPED, "", 10))
    }

}
