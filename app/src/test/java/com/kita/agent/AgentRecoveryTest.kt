package com.kita.agent

import org.junit.Assert.*
import org.junit.Test

class AgentRecoveryTest {
    @Test fun galatAlatDikenaliSebagaiKegagalan() {
        val out = recoverableToolResult(IllegalStateException("file terkunci"))
        assertNotNull(errorSignature(out))
        assertTrue(out.startsWith("exit=1"))
    }

    @Test fun galatAlatBerulangDihitungOlehTracker() {
        val tracker = ErrorTracker()
        repeat(3) { tracker.record(recoverableToolResult(RuntimeException("sama"))) }
        assertEquals(4, tracker.record(recoverableToolResult(RuntimeException("sama"))))
    }

    @Test fun timeoutDikenaliSebagaiKegagalanDanMenyarankanCekDulu() {
        val out = timeoutResult(600)
        assertNotNull(errorSignature(out))
        assertTrue(out.contains("Cek dulu"))
    }

    @Test fun pesanGalatKosongTetapAman() {
        val out = recoverableToolResult(RuntimeException())
        assertTrue(out.startsWith("exit=1 Alat gagal: "))
    }
}
