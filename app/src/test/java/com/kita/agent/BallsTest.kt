package com.kita.agent

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Gerak tiga bola di bilah atas: harus tetap di dalam area, mulus, dan tidak berulang dalam waktu dekat. */
class BallsTest {
    private val w = 100f
    private val h = 60f
    private val m = 5f

    @Test fun selaluDiDalamArea() {
        var t = 0f
        while (t < 120f) {
            for (i in 0..2) {
                val (x, y) = ballCenter(i, t, w, h, m).let { it[0] to it[1] }
                assertTrue("i=$i t=$t x=$x", x >= m - 0.01f && x <= w - m + 0.01f)
                assertTrue("i=$i t=$t y=$y", y >= m - 0.01f && y <= h - m + 0.01f)
            }
            t += 0.05f
        }
    }

    @Test fun gerakMulusTanpaLompatan() {
        var t = 0f
        while (t < 60f) {
            for (i in 0..2) {
                val a = ballCenter(i, t, w, h, m)
                val b = ballCenter(i, t + 1f / 60f, w, h, m)
                assertTrue("i=$i t=$t", abs(a[0] - b[0]) < 1.5f && abs(a[1] - b[1]) < 1.5f)
            }
            t += 0.1f
        }
    }

    @Test fun tidakBerulangDalamWaktuDekat() {
        for (i in 0..2) {
            val a = ballCenter(i, 3f, w, h, m)
            for (period in listOf(5f, 7f, 10f, 14f)) {
                val b = ballCenter(i, 3f + period, w, h, m)
                assertTrue("i=$i period=$period", abs(a[0] - b[0]) > 0.5f || abs(a[1] - b[1]) > 0.5f)
            }
        }
    }

    @Test fun tiapBolaPunyaJalurBerbeda() {
        val a = ballCenter(0, 4f, w, h, m)
        val b = ballCenter(1, 4f, w, h, m)
        val c = ballCenter(2, 4f, w, h, m)
        assertTrue(abs(a[0] - b[0]) > 0.1f || abs(a[1] - b[1]) > 0.1f)
        assertTrue(abs(b[0] - c[0]) > 0.1f || abs(b[1] - c[1]) > 0.1f)
    }
}
