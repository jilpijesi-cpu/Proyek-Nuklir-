package com.kita.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFitTest {
    // Pengukur sederhana: tiap karakter selebar 10 satuan.
    private val m: (String) -> Float = { it.length * 10f }

    @Test fun teksYangMuatTidakBerubah() {
        assertEquals("Membaca file", fitWords("Membaca file", 1000f, m))
        assertEquals("Membaca file", fitWords("Membaca file", 0f, m))
    }

    @Test fun dipotongDiBatasKataBukanDiTengahKata() {
        assertEquals("Memeriksa isi…", fitWords("Memeriksa isi arsip ZIP", 150f, m))
    }

    @Test fun elipsisLamaTidakDobel() {
        assertEquals("Memeriksa isi…", fitWords("Memeriksa isi arsip ZIP…", 150f, m))
    }

    @Test fun tandaBacaMenggantungDibuang() {
        assertEquals("Menguji…", fitWords("Menguji, lalu membangun", 130f, m))
    }

    @Test fun satuKataTerlaluPanjangDipotongPerHuruf() {
        assertEquals("Supercali…", fitWords("Supercalifragilistic", 100f, m))
    }

    @Test fun hasilTidakPernahMelebihiLebar() {
        val t = "Membaca ApkProvider.kt dan AgentService.kt untuk memeriksa celah keamanan"
        for (w in listOf(60f, 120f, 200f, 300f, 500f)) {
            val r = fitWords(t, w, m)
            assertTrue("lebar $w: \"$r\"", m(r) <= w || r == "…")
        }
    }

    @Test fun lebarSangatSempitMenyisakanElipsis() {
        assertEquals("…", fitWords("Panjang", 5f, m))
    }

    @Test fun namaFileDikenali() {
        val t = "Membaca ApkProvider.kt"
        val r = fileTokenRanges(t)
        assertEquals(1, r.size)
        assertEquals("ApkProvider.kt", t.substring(r[0].first, r[0].last + 1))
    }

    @Test fun beberapaNamaFileDalamSatuKalimat() {
        val t = "Membaca ApkProvider.kt dan build.gradle.kts"
        val r = fileTokenRanges(t).map { t.substring(it.first, it.last + 1) }
        assertEquals(listOf("ApkProvider.kt", "build.gradle.kts"), r)
    }

    @Test fun kalimatTanpaNamaFileKosong() {
        assertTrue(fileTokenRanges("Memeriksa isi arsip ZIP").isEmpty())
        assertTrue(fileTokenRanges("Versi v2.6 siap").isEmpty())
        assertTrue(fileTokenRanges("Mengubah file.ktx").isEmpty())
    }
}
