package com.kita.agent

import org.junit.Assert.*
import org.junit.Test

class AgentGuardTest {
    private fun s(tool: String, target: String, kind: ActivityKind, st: StepState) =
        StepRecord(tool, "t", target, kind, st, 10)

    @Test fun tanpaLangkahBolehSelesai() = assertNull(verificationNudge(emptyList()))

    @Test fun buildGagalTerakhirTidakBolehSelesai() {
        val n = verificationNudge(listOf(s("run_command", "", ActivityKind.BUILD, StepState.FAIL)))
        assertNotNull(n); assertTrue(n!!.startsWith(SYS_PREFIX)); assertTrue(n.contains("GAGAL"))
    }

    @Test fun buildGagalLaluBerhasilBolehSelesai() {
        assertNull(verificationNudge(listOf(
            s("run_command", "", ActivityKind.BUILD, StepState.FAIL),
            s("write_file", "app/A.kt", ActivityKind.OTHER, StepState.OK),
            s("run_command", "", ActivityKind.BUILD, StepState.OK))))
    }

    @Test fun kodeDiubahTanpaBuildSesudahnyaDiingatkan() {
        assertNotNull(verificationNudge(listOf(
            s("run_command", "", ActivityKind.BUILD, StepState.OK),
            s("write_file", "app/A.kt", ActivityKind.OTHER, StepState.OK))))
        assertNotNull(verificationNudge(listOf(s("write_file", "x/B.java", ActivityKind.OTHER, StepState.OK))))
    }

    @Test fun fileNonKodeTidakMemaksaBuild() {
        assertNull(verificationNudge(listOf(s("write_file", "README.md", ActivityKind.OTHER, StepState.OK))))
        assertNull(verificationNudge(listOf(s("write_file", "A.kt", ActivityKind.OTHER, StepState.FAIL))))
    }

    @Test fun kodeDiLuarProyekGradleTidakMemaksaBuild() {
        val steps = listOf(s("write_file", "scripts/Tool.kt", ActivityKind.OTHER, StepState.OK))
        assertNull(verificationNudge(steps) { false })
        assertNotNull(verificationNudge(steps) { true })
    }

    @Test fun buildGagalTetapDiingatkanMeskiBukanProyekGradle() {
        assertNotNull(verificationNudge(listOf(s("run_command", "", ActivityKind.BUILD, StepState.FAIL))) { false })
    }

    @Test fun signatureNormalAngkaDanPath() {
        val a = errorSignature("exit=1\n/home/u/p/A.kt:12: error: unresolved reference: foo")
        val b = errorSignature("exit=1\n/home/u/p/B.kt:99: error: unresolved reference: foo")
        assertEquals(a, b)
        assertNull(errorSignature("exit=0\nok"))
        assertNull(errorSignature("Ditolak: mode hanya baca"))
    }

    @Test fun errorBerulangDihitung() {
        val t = ErrorTracker()
        val out = "exit=1\nerror: gagal 3"
        assertEquals(1, t.record(out)); assertEquals(2, t.record("exit=1\nerror: gagal 7"))
        assertEquals(0, t.record("exit=0\nOK"))
        assertEquals(1, t.record("exit=2\nlain: not found"))
    }

    @Test fun keluaranGagalPanjangDiringkasTapiSuksesTidak() {
        val noise = (1..500).joinToString("\n") { "> Task :app:step$it" }
        val fail = "exit=1\n$noise\ne: A.kt:5: error: unresolved reference: x\nFAILED\n" + (1..30).joinToString("\n") { "tail$it" }
        val c = condenseFailure(fail)
        assertTrue(c.length <= 3000 && c.length < fail.length)
        assertTrue(c.contains("unresolved reference: x")); assertTrue(c.contains("tail30")); assertTrue(c.startsWith("[Keluaran gagal diringkas"))
        val ok = "exit=0\n" + noise
        assertEquals(ok, condenseFailure(ok))
        assertEquals("exit=1\nerror pendek", condenseFailure("exit=1\nerror pendek"))
    }
}
