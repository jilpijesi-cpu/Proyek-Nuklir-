package com.kita.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit test pewarnaan terminal live dan pencocokan baris keluaran. Murni JVM: ./gradlew testDebugUnitTest */
class TerminalLexTest {

    private fun kindsOf(line: String, spans: List<TermSpan>): List<Pair<String, TermKind>> =
        spans.map { line.substring(it.start, it.end) to it.kind }

    @Test
    fun bashPerintahOpsiStringOperator() {
        val line = "cd ~/kita && ./gradlew assembleDebug --stacktrace > out.txt"
        val k = kindsOf(line, lexBash(line))
        assertTrue(k.contains("cd" to TermKind.CMD))
        assertTrue(k.contains("&&" to TermKind.OP))
        assertTrue(k.contains("./gradlew" to TermKind.CMD))
        assertTrue(k.contains("--stacktrace" to TermKind.FLAG))
        assertTrue(k.contains(">" to TermKind.OP))
    }

    @Test
    fun bashStringDanVariabel() {
        val line = "echo \"halo dunia\" \$HOME"
        val k = kindsOf(line, lexBash(line))
        assertTrue(k.contains("echo" to TermKind.CMD))
        assertTrue(k.contains("\"halo dunia\"" to TermKind.STR))
        assertTrue(k.contains("\$HOME" to TermKind.VAR))
    }

    @Test
    fun pipaMembukaPerintahBaru() {
        val line = "cat a.txt | grep -n foo"
        val k = kindsOf(line, lexBash(line))
        assertTrue(k.contains("cat" to TermKind.CMD))
        assertTrue(k.contains("grep" to TermKind.CMD))
        assertTrue(k.contains("-n" to TermKind.FLAG))
    }

    @Test
    fun kodeKataKunciStringKomentar() {
        val line = "val x = \"hi\" // catatan"
        val k = kindsOf(line, lexCode(line))
        assertTrue(k.contains("val" to TermKind.KEYWORD))
        assertTrue(k.contains("\"hi\"" to TermKind.STR))
        assertTrue(k.any { it.second == TermKind.DIM && it.first.startsWith("//") })
    }

    @Test
    fun urlDalamStringBukanKomentar() {
        val line = "val u = \"https://a.b/c\""
        val k = kindsOf(line, lexCode(line))
        assertTrue(k.none { it.second == TermKind.DIM })
    }

    @Test
    fun keluaranGradleDanError() {
        assertEquals(TermKind.TASK, kindOfOutput("> Task :app:compileDebugKotlin"))
        assertEquals(TermKind.OK, kindOfOutput("BUILD SUCCESSFUL in 41s"))
        assertEquals(TermKind.ERR, kindOfOutput("BUILD FAILED in 3s"))
        assertEquals(TermKind.ERR, kindOfOutput("e: File.kt: (12, 3): Unresolved reference: foo"))
        assertEquals(TermKind.WARN, kindOfOutput("w: deprecated API"))
        assertEquals(TermKind.PLAIN, kindOfOutput("3 tests, 0 failed"))
    }

    @Test
    fun namaFileBerisiKataErrorBukanError() {
        assertEquals(TermKind.PLAIN, kindOfOutput("app/src/main/ErrorHandler.kt"))
        assertEquals(TermKind.PLAIN, kindOfOutput("exception_log.txt"))
    }

    @Test
    fun diffHanyaDiwarnaiBilaDiff() {
        assertEquals(TermKind.ADD, kindOfOutput("+ baris baru", diff = true))
        assertEquals(TermKind.DEL, kindOfOutput("- baris lama", diff = true))
        assertEquals(TermKind.DIM, kindOfOutput("---------"))
        assertEquals(TermKind.PLAIN, kindOfOutput("- item daftar", diff = false))
    }

    @Test
    fun deteksiDiff() {
        assertTrue(looksLikeDiff("  a\n+ b\n- c\n  ⋯ 4 baris sama"))
        assertTrue(!looksLikeDiff("package x\nfun f() {}"))
        assertTrue(!looksLikeDiff(""))
    }

    @Test
    fun keluaranPlainMewarnaiAngkaDanPath() {
        val line = "8830  2026-10-14  /data/data/com.termux/files"
        val k = kindsOf(line, lexOutput(line))
        assertTrue(k.contains("8830" to TermKind.NUM))
        assertTrue(k.any { it.second == TermKind.PATH && it.first.startsWith("/data/data") })
    }

    @Test
    fun barisBaruSetelahJendelaBergeser() {
        val shown = listOf("a", "b", "c", "d")
        val latest = listOf("b", "c", "d", "e", "f")
        assertEquals(listOf("e", "f"), newLinesAfter(shown, latest))
    }

    @Test
    fun tidakAdaYangBaru() {
        val shown = listOf("a", "b", "c")
        assertEquals(emptyList<String>(), newLinesAfter(shown, listOf("a", "b", "c")))
    }

    @Test
    fun isiBerganti_totalNull() {
        assertNull(newLinesAfter(listOf("a", "b", "c"), listOf("x", "y", "z")))
    }

    @Test
    fun awalKosongMenampilkanSemua() {
        assertEquals(listOf("x", "y"), newLinesAfter(emptyList(), listOf("x", "y")))
    }

    @Test
    fun perintahBerjalanMenampilkanPromptDanKeluaranTail() {
        val step = AgentStep(
            id = "s1", type = AgentStepType.COMMAND, title = "Membangun", fullCommand = "$ ./gradlew assembleDebug",
            status = AgentStepStatus.RUNNING, output = "> Task :app:compileDebugKotlin\n> Task :app:dexBuilderDebug"
        )
        val lines = termLinesFor(step)
        assertEquals("$ ./gradlew assembleDebug", lines[0].text)
        assertEquals(TermKind.TASK, lines[1].whole)
        assertEquals(3, lines.size)
    }

    @Test
    fun keluaranSelesaiTidakMenggandakanPerintah() {
        val step = AgentStep(
            id = "s2", type = AgentStepType.COMMAND, title = "Daftar", fullCommand = "$ ls -la",
            status = AgentStepStatus.SUCCESS, output = "$ ls -la\n\ntotal 8\napp"
        )
        val lines = termLinesFor(step)
        assertEquals(listOf("$ ls -la", "total 8", "app"), lines.map { it.text })
    }

    @Test
    fun tulisFileMenampilkanPathDanKodeBernomor() {
        val step = AgentStep(
            id = "s3", type = AgentStepType.TOOL, title = "Menulis", fullCommand = "app/A.kt\n\nfun a() {}\nfun b() {}",
            status = AgentStepStatus.RUNNING
        )
        val lines = termLinesFor(step)
        assertEquals("✎ app/A.kt", lines[0].text)
        assertTrue(lines[1].text.contains("1 │ fun a() {}"))
        assertTrue(lines[2].text.contains("2 │ fun b() {}"))
    }

    @Test
    fun tulisFileSelesaiMenampilkanDiff() {
        val step = AgentStep(
            id = "s4", type = AgentStepType.TOOL, title = "Menulis", fullCommand = "app/A.kt\n\nx",
            status = AgentStepStatus.SUCCESS, output = "  a\n- lama\n+ baru\n"
        )
        val lines = termLinesFor(step)
        assertEquals(TermKind.DEL, lines[2].whole)
        assertEquals(TermKind.ADD, lines[3].whole)
    }

    @Test
    fun formatWaktuDanChip() {
        assertEquals("00:07", formatElapsed(7_400))
        assertEquals("01:32", formatElapsed(92_000))
        assertEquals("1:02:03", formatElapsed(3_723_000))
        assertEquals("1 · Membaca kode", chipLabel(0, "Membaca kode"))
        assertTrue(chipLabel(2, "Memeriksa isi arsip ZIP deteksi key", 12).endsWith("…"))
        assertNotNull(chipLabel(0, ""))
    }
}
