package com.kita.agent

import org.junit.Assert.*
import org.junit.Test

class ApkReModeTest {
    @Test
    fun promptMelarangPembobolanProteksi() {
        val t = APK_RE_SYSTEM_PROMPT.lowercase()
        assertTrue(t.contains("lisensi"))
        assertTrue(t.contains("drm"))
        assertTrue(t.contains("anti-tamper"))
        assertTrue(t.contains("bajak") || t.contains("pembajakan"))
    }

    @Test
    fun promptTidakMengizinkanSegalaCara() {
        val t = APK_RE_SYSTEM_PROMPT.lowercase()
        assertFalse(t.contains("apa pun caranya"))
        assertFalse(t.contains("segala cara"))
        assertTrue(t.contains("tidak diizinkan"))
    }
}
