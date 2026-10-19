package com.kita.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit test pemilih ikon langkah agen. Murni JVM: ./gradlew testDebugUnitTest */
class ActionIconsTest {

    private fun icon(cmd: String, desc: String = "") = iconForCommand("bash", cmd, desc)

    @Test
    fun perintahUmum() {
        assertEquals(IconView.GEAR, icon("./gradlew assembleDebug"))
        assertEquals(IconView.ARCHIVE, icon("unzip -l /sdcard/Download/x.zip"))
        assertEquals(IconView.GRID, icon("grep -rn foo ."))
        assertEquals(IconView.FILE, icon("cat a.kt | head -20"))
        assertEquals(IconView.FILE, icon("sed -n '1,20p' a.kt"))
        assertEquals(IconView.FOLDER, icon("ls -la"))
        assertEquals(IconView.TRASH, icon("rm -rf build"))
        assertEquals(IconView.GLOBE, icon("curl -s https://example.com"))
        assertEquals(IconView.DOWNLOAD, icon("pkg install git"))
    }

    @Test
    fun menulisBedaDariMembaca() {
        assertEquals(IconView.NEW, icon("sed -i 's/a/b/' f.kt"))
        assertEquals(IconView.NEW, icon("echo hi > out.txt"))
        assertEquals(IconView.NEW, iconForCommand("write_file", "", ""))
        // pengalihan stderr / ke /dev/null bukan menulis file
        assertEquals(IconView.FILE, icon("cat a 2>/dev/null"))
        assertEquals(IconView.FILE, icon("cat a 2>&1"))
    }

    @Test
    fun cadanganDariDeskripsi() {
        assertEquals(IconView.GRID, icon("zzz", "Mencari informasi relevan di berkas"))
        assertEquals(IconView.BLOB_TERM, icon("zzz", ""))
    }
}
