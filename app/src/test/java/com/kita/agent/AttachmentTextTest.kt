package com.kita.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentTextTest {
    @Test fun pesanTanpaLampiranTidakBerubah() {
        val (shown, list) = splitAttachments("Halo, apa kabar?")
        assertEquals("Halo, apa kabar?", shown)
        assertTrue(list.isEmpty())
    }

    @Test fun barisLampiranDipisahDariTeks() {
        val raw = "Tolong periksa\nGambar terlampir di \"/sdcard/Download/ClaudeMythos/a.jpg\"" +
            "\nFile terlampir di \"/sdcard/Download/ClaudeMythos/Main.kt\""
        val (shown, list) = splitAttachments(raw)
        assertEquals("Tolong periksa", shown)
        assertEquals(2, list.size)
        assertTrue(list[0].isImage)
        assertEquals("a.jpg", list[0].name)
        assertFalse(list[1].isImage)
        assertEquals("Main.kt", list[1].name)
    }

    @Test fun teksBawaanDisembunyikanBilaHanyaLampiran() {
        val raw = DEFAULT_ATTACH_TEXT + "\nFile terlampir di \"/sdcard/Download/ClaudeMythos/x.zip\""
        val (shown, list) = splitAttachments(raw)
        assertEquals("", shown)
        assertEquals(1, list.size)
    }

    @Test fun namaDenganSpasiTetapTerbaca() {
        val (_, list) = splitAttachments("\nFile terlampir di \"/sdcard/Download/ClaudeMythos/Laporan akhir.pdf\"")
        assertEquals("Laporan akhir.pdf", list[0].name)
    }

    @Test fun ukuranFileMudahDibaca() {
        assertEquals("500 B", formatSize(500))
        assertEquals("2.0 kB", formatSize(2048))
        assertEquals("3.0 MB", formatSize(3L * 1048576L))
    }
}
