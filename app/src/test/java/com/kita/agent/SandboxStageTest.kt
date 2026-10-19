package com.kita.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit test SandboxStage: penyalinan lampiran ke workdir sandbox. Murni JVM: ./gradlew testDebugUnitTest */
class SandboxStageTest {
    private val c = SandboxConfig()

    @Test fun guestPathDiWorkdir() {
        assertEquals("/root/sandbox/work/a.pdf", SandboxStage.guestPath(c, "a.pdf"))
    }

    @Test fun salinDariFolderLampiranKeRootfsSandbox() {
        val cmd = SandboxStage.copyCommand("/sdcard/Download/ClaudeMythos/a.pdf", c)
        assertNotNull(cmd)
        assertTrue(cmd!!.startsWith("mkdir -p '"))
        assertTrue(cmd.contains("cp '/sdcard/Download/ClaudeMythos/a.pdf' '"))
        assertTrue(cmd.contains("/installed-rootfs/ubuntu/root/sandbox/work/a.pdf'"))
    }

    @Test fun tolakSumberDiLuarFolderLampiran() {
        assertNull(SandboxStage.copyCommand("/data/data/com.termux/files/home/.ssh/id_rsa", c))
        assertNull(SandboxStage.copyCommand("/sdcard/DCIM/foto.jpg", c))
        assertNull(SandboxStage.copyCommand("/sdcard/Download/ClaudeMythosLain/a.zip", c))
    }

    @Test fun tolakPathTraversal() {
        assertNull(SandboxStage.copyCommand("/sdcard/Download/ClaudeMythos/../../x", c))
        assertNull(SandboxStage.copyCommand("/sdcard/Download/ClaudeMythos/./a.zip", c))
    }

    @Test fun tolakNamaKosong() {
        assertNull(SandboxStage.copyCommand("/sdcard/Download/ClaudeMythos/", c))
    }

    @Test fun namaTidakAman() {
        assertNull(SandboxStage.safeName(""))
        assertNull(SandboxStage.safeName(".."))
        assertNull(SandboxStage.safeName("a/b"))
        assertEquals("laporan.pdf", SandboxStage.safeName("laporan.pdf"))
    }

    @Test fun namaDenganSpasiDanKurungDikutip() {
        val cmd = SandboxStage.copyCommand("/sdcard/Download/ClaudeMythos/recovered_source_from_apk (3).zip", c)
        assertNotNull(cmd)
        assertTrue(cmd!!.contains("'/sdcard/Download/ClaudeMythos/recovered_source_from_apk (3).zip'"))
        assertTrue(cmd.endsWith("/root/sandbox/work/recovered_source_from_apk (3).zip'"))
    }

    @Test fun namaDenganKutipTunggalAman() {
        val cmd = SandboxStage.copyCommand("/sdcard/Download/ClaudeMythos/a'b.zip", c)
        assertNotNull(cmd)
        assertTrue(cmd!!.contains("'/sdcard/Download/ClaudeMythos/a'\\''b.zip'"))
    }
}
