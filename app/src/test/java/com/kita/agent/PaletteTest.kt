package com.kita.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Unit test palet warna. Murni JVM: ./gradlew testDebugUnitTest */
class PaletteTest {

    @Test
    fun tintMencampurDenganPutih() {
        assertEquals(0xFFFFFFFF.toInt(), tint(0xFF000000.toInt(), 1f))
        assertEquals(Palette.BLUE, tint(Palette.BLUE, 0f))
        assertEquals(0xFF808080.toInt(), tint(0xFF000000.toInt(), 0.5f))
    }

    @Test
    fun aksenSesuaiJenisAksi() {
        // tiap jenis aksi punya warna sendiri (bukan satu warna untuk semua)
        assertEquals(Palette.BLUE, accentFor(IconView.FILE))
        assertEquals(Palette.VIOLET, accentFor(IconView.GRID))
        assertEquals(Palette.TEAL, accentFor(IconView.NEW))
        assertEquals(Palette.ORANGE, accentFor(IconView.GEAR))
        assertEquals(Palette.RED, accentFor(IconView.TRASH))
        assertEquals(Palette.GREEN, accentFor(IconView.CHECK))
        assertEquals(Palette.BRAND, accentFor(KIND_DOTS))
        assertNotEquals(accentFor(IconView.GRID), accentFor(IconView.GEAR))
        assertNotEquals(accentFor(IconView.FILE), accentFor(IconView.FOLDER))
    }

    @Test
    fun teksTerbacaDiAtasLatar() {
        assertEquals(0xFFFFFFFF.toInt(), onColor(Palette.BRAND))
        assertEquals(0xFF1E2433.toInt(), onColor(0xFFFFFFFF.toInt()))
        // label risiko selalu punya teks yang kontras (dulu READ = putih di atas abu-abu muda)
        for (r in Risk.values()) assertNotEquals(r.color, onColor(r.color))
        assertEquals(0xFFFFFFFF.toInt(), onColor(Risk.READ.color))
        assertEquals(0xFF1E2433.toInt(), onColor(Risk.WRITE.color))
    }

    @Test
    fun tingkatRisikoBerbedaWarna() {
        assertNotEquals(Risk.READ.color, Risk.WRITE.color)
        assertNotEquals(Risk.WRITE.color, Risk.DELETE.color)
    }
}
