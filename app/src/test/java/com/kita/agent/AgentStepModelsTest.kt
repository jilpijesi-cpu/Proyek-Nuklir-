package com.kita.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentStepModelsTest {
    @Test
    fun langkahDikelompokkanDanRingkasanMengikutiJumlah() {
        val store = AgentStepStore()
        val first = AgentStep("1", AgentStepType.READ_FILE, "Membaca struktur proyek")
        val second = AgentStep("2", AgentStepType.COMMAND, "Menjalankan pemeriksaan")
        store.add(first)
        store.add(second)

        assertEquals(1, store.groups.value.size)
        assertEquals("Menjalankan perintah", store.groups.value.single().label)
        assertEquals("Menjalankan perintah · Menjalankan 2 perintah", store.groups.value.single().summary)
        assertEquals(2, store.groups.value.single().steps.size)
    }

    @Test
    fun transisiLangkahMenyimpanDurasiDanOutputTerpotong() {
        val store = AgentStepStore()
        val step = AgentStep("1", AgentStepType.COMMAND, "Build", "./gradlew test")
        store.add(step)
        store.start("1", now = 1_000)
        store.finish("1", success = true, output = "hasil", now = 4_500)

        val result = store.groups.value.single().steps.single()
        assertEquals(AgentStepStatus.SUCCESS, result.status)
        assertEquals(3_500L, result.durationMs)
        assertEquals("hasil", result.output)
    }

    @Test
    fun perintahMenungguApprovalDitampilkanPenuh() {
        val step = AgentStep(
            id = "1",
            type = AgentStepType.COMMAND,
            title = "Menjalankan command",
            fullCommand = "rm -rf folder-yang-sangat-panjang",
            status = AgentStepStatus.WAITING
        )
        assertTrue(step.isApprovalWaiting)
        assertEquals("rm -rf folder-yang-sangat-panjang", step.fullCommand)
    }

    @Test
    fun outputPanjangDipangkasDenganPenanda() {
        val result = AgentStepStore.truncateOutput("abcdef", maxChars = 3)
        assertEquals("abc\n…(output dipotong)…", result)
    }

    @Test
    fun grupDapatDilipatDanDibuka() {
        val store = AgentStepStore()
        store.add(AgentStep("1", AgentStepType.TOOL, "Tool"), "Menggunakan alat")
        assertEquals(false, store.groups.value.single().collapsed)
        store.toggle("Menggunakan alat")
        assertEquals(true, store.groups.value.single().collapsed)
    }
}
