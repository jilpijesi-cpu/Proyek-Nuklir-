package com.kita.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextCleanTest {
    @Test fun teksBiasaTidakBerubah() {
        assertEquals("Halo! Apa kabar?", stripModelTags("Halo! Apa kabar?"))
    }

    @Test fun blokBerpikirDibuangDanResponseDibukaBungkusnya() {
        val raw = "<thinking> Pengguna hanya menyapa. </thinking>\n<response>Halo! Ada yang bisa saya bantu?</response>"
        assertEquals("Halo! Ada yang bisa saya bantu?", stripModelTags(raw))
    }

    @Test fun blokBerpikirBelumDitutupTidakTampil() {
        assertEquals("", stripModelTags("<thinking> Pengguna hanya menya"))
        assertEquals("Halo", stripModelTags("<thinking>x</thinking><response>Halo"))
    }

    @Test fun awalanTagSetengahDatangDipotong() {
        assertEquals("Halo", stripModelTags("Halo <thi"))
        assertEquals("Halo", stripModelTags("Halo</resp"))
        assertEquals("a < b", stripModelTags("a < b"))
    }

    @Test fun isiBlokKodeTidakDisentuh() {
        val raw = "Contoh:\n```xml\n<response>ok</response>\n```"
        assertEquals(raw, stripModelTags(raw))
    }

    @Test fun hasilStreamSelaluAwalanDariHasilBerikutnya() {
        val full = "<thinking>a</thinking><response>Halo dunia</response>"
        var prev = ""
        for (n in 1..full.length) {
            val cur = stripModelTags(full.take(n))
            assertTrue("n=$n prev='$prev' cur='$cur'", cur.startsWith(prev))
            prev = cur
        }
        assertEquals("Halo dunia", prev)
    }
}
