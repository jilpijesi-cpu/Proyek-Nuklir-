package com.kita.agent

import org.junit.Assert.*
import org.junit.Test

class TaskBriefTest {
    @Test fun paham() {
        val b = parseBrief("PAHAM: membongkar APK | hasil: daftar izin | langkah awal: baca manifest")
        assertTrue(b is Brief.Paham)
    }

    @Test fun tanyaTidakMenahanTugas() {
        val b = parseBrief("TANYA: APK mana yang dimaksud?")
        assertTrue(b is Brief.Paham)
        assertTrue((b as Brief.Paham).text.contains("APK mana yang dimaksud?"))
    }

    @Test fun tanyaKosongDianggapKosong() = assertTrue(parseBrief("TANYA:   ") is Brief.Kosong)

    @Test fun formatLainDianggapKosong() = assertTrue(parseBrief("Tentu, saya kerjakan.") is Brief.Kosong)

    @Test fun barisPertamaYangDipakai() {
        val b = parseBrief("\n\nPAHAM: tujuan A\nPAHAM: tujuan B")
        assertEquals("PAHAM: tujuan A", (b as Brief.Paham).text)
    }
}
