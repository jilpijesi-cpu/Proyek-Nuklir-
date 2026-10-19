package com.kita.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePayloadTest {
    @Test
    fun ekstensiGambarDikenaliTanpaPekaHurufBesarKecil() {
        assertTrue(isImageName("screenshot.PNG"))
        assertTrue(isImageName("photo.jpeg"))
        assertTrue(isImageName("camera.webp"))
        assertFalse(isImageName("source.zip"))
        assertFalse(isImageName("notes.txt"))
    }
}
