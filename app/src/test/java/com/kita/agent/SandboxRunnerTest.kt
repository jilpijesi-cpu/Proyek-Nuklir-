package com.kita.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Unit test SandboxRunner: susunan perintah, env bersih, timeout, batas output, validasi konfigurasi. */
class SandboxRunnerTest {

    private class FakeShell(var reply: String) : ShellExecutor {
        var lastCommand: String? = null
        var lastTimeout: Long = -1
        var lastLimit: Int = -1
        var lastLive: String? = "belum dipanggil"
        var calls = 0
        override fun exec(command: String, timeoutSec: Long, limit: Int, liveFile: String?): String {
            calls++
            lastCommand = command; lastTimeout = timeoutSec; lastLimit = limit; lastLive = liveFile
            return reply
        }
    }

    private val cfg = SandboxConfig()

    private fun runner(reply: String, shell: FakeShell = FakeShell(reply)) = SandboxRunner(shell, cfg, clock = { 1000L }) to shell

    // ---------- susunan perintah ----------

    @Test
    fun perintahDibungkusProotDistroIsolated() {
        val s = SandboxCommand.build("ls", cfg, RunLimits())
        assertTrue(s.startsWith("set -o pipefail\n"))
        assertTrue(s.contains("login ubuntu --isolated -- /bin/sh -c"))
        assertTrue(s.contains("/bin/timeout' -s KILL 120 "))
        assertTrue(s.contains("/bin/head' -c 20001"))
        assertTrue(s.contains(" -i "))
    }

    @Test
    fun tanpaMountPenyimpananBersamaDanTanpaSetupStorage() {
        val s = SandboxCommand.build("ls", cfg, RunLimits())
        for (bad in listOf("termux-setup-storage", "--bind", "--shared-tmp", "--termux-home", "/sdcard", "/storage", "-b ")) {
            assertFalse("tidak boleh ada: $bad", s.contains(bad))
        }
    }

    @Test
    fun envHanyaAllowlist_tanpaRahasia() {
        val names = SandboxCommand.envAssignments(cfg).map { it.substringBefore('=') }
        val allowed = setOf("PATH", "HOME", "TMPDIR", "PREFIX", "TERM", "LANG", "ANDROID_ROOT", "ANDROID_DATA")
        assertEquals(allowed, names.toSet())
        for (n in names) for (w in listOf("KEY", "TOKEN", "SECRET", "PASSWORD", "AUTH")) assertFalse("$n mengandung $w", n.contains(w))
        // nilai variabel lingkungan induk tidak pernah ikut ($VAR tidak ada di perintah rakitan di luar perintah pengguna)
        val s = SandboxCommand.build("true", cfg, RunLimits())
        assertEquals("tidak ada ekspansi variabel milik pembungkus", -1, s.substringBefore("--").indexOf('$'))
    }

    @Test
    fun skripDalamMasukWorkdirDulu() {
        val inner = SandboxCommand.innerScript("echo hi", cfg)
        assertEquals("mkdir -p '/root/sandbox/work' && cd '/root/sandbox/work' || exit 97\necho hi", inner)
    }

    @Test
    fun kutipanAman_perintahPenggunaTidakBisaMenutupKutip() {
        assertEquals("'it'\\''s'", SandboxCommand.shq("it's"))
        val evil = "echo 'x'; rm -rf / #"
        val s = SandboxCommand.build(evil, cfg, RunLimits())
        // kutip tunggal pengguna di-escape, jadi tidak ada kutip bebas yang menutup argumen -c lebih awal
        assertTrue(s.contains("'\\''x'\\''"))
    }

    // ---------- konfigurasi ----------

    private fun assertRejects(block: () -> Unit) {
        try { block(); fail("seharusnya ditolak") } catch (e: IllegalArgumentException) { /* ok */ }
    }

    @Test
    fun konfigurasiBerbahayaDitolak() {
        assertRejects { SandboxConfig(distro = "debian; rm -rf /") }
        assertRejects { SandboxConfig(distro = "") }
        assertRejects { SandboxConfig(distro = "Debian") }
        assertRejects { SandboxConfig(workdir = "/") }
        assertRejects { SandboxConfig(workdir = "relatif/dir") }
        assertRejects { SandboxConfig(workdir = "/root/../etc") }
        assertRejects { SandboxConfig(workdir = "/root/a'b") }
        assertRejects { SandboxConfig(workdir = "/root/a b") }
        assertRejects { SandboxConfig(workdir = "/root/\$HOME") }
    }

    @Test
    fun batasTidakMasukAkal_ditolak() {
        assertRejects { RunLimits(timeoutSec = 0) }
        assertRejects { RunLimits(timeoutSec = 99999) }
        assertRejects { RunLimits(maxOutputBytes = 10) }
        assertRejects { RunLimits(maxOutputBytes = 10_000_000) }
    }

    // ---------- run(): timeout dan batas output ----------

    @Test
    fun run_meneruskanBatasDanLiveFile() {
        val (r, shell) = runner("exit=0\nok\n")
        val res = r.run(RunRequest("ls", RunLimits(timeoutSec = 30, maxOutputBytes = 1000), liveFile = "/tmp/live"))
        assertTrue(res.ok)
        assertTrue(res.sandboxed)
        assertEquals(30 + SandboxRunner.GRACE_SEC, shell.lastTimeout)   // cadangan di sisi app lebih longgar dari timeout skrip
        assertTrue(shell.lastLimit >= 1000)
        assertEquals("/tmp/live", shell.lastLive)
        assertTrue(shell.lastCommand!!.contains("-s KILL 30 "))
        assertEquals("ok\n", res.output)
        assertEquals(1, shell.calls)
    }

    @Test
    fun timeout_exit124_dihentikanDanDilaporkan() {
        val (r, _) = runner("exit=124\nsebagian keluaran")
        val res = r.run(RunRequest("sleep 999", RunLimits(timeoutSec = 5)))
        assertTrue(res.timedOut)
        assertFalse(res.ok)
        assertEquals(124, res.exitCode)
        assertTrue(res.output.startsWith("Timeout: perintah melewati batas 5 detik"))
        assertTrue(res.output.contains("sebagian keluaran"))
        assertTrue(res.toLegacyString().startsWith("exit=124\n"))
    }

    @Test
    fun outputMelewatiBatas_dipotongDenganPenanda() {
        val big = "x".repeat(5000)
        val (r, _) = runner("exit=0\n$big")
        val res = r.run(RunRequest("yes", RunLimits(maxOutputBytes = 1000)))
        assertTrue(res.truncated)
        assertTrue(res.output.contains(OutputLimiter.MARK))
        assertTrue(res.output.length < 1000 + OutputLimiter.MARK.length + 5)
    }

    @Test
    fun outputDalamBatas_tidakDipotong() {
        val (r, _) = runner("exit=0\nhalo")
        val res = r.run(RunRequest("echo halo", RunLimits(maxOutputBytes = 1000)))
        assertFalse(res.truncated)
        assertEquals("halo", res.output)
    }

    @Test
    fun batasDihitungDalamByteUtf8() {
        val (out, cut) = OutputLimiter.limit("é".repeat(600), 1000)   // 1200 byte
        assertTrue(cut)
        assertTrue(out.contains(OutputLimiter.MARK))
        assertEquals("é".repeat(10), OutputLimiter.limit("é".repeat(10), 1000).first)
    }

    // ---------- petunjuk galat ----------

    @Test
    fun prootDistroBelumAda_memberiPetunjukPasang() {
        val (r, _) = runner("exit=127\nenv: '/data/data/com.termux/files/usr/bin/proot-distro': No such file or directory")
        val res = r.run(RunRequest("ls"))
        assertEquals(127, res.exitCode)
        assertTrue(res.output.contains("pkg install proot-distro"))
    }

    @Test
    fun distroBelumDipasang_memberiPetunjukPasang() {
        val (r, _) = runner("exit=1\nError: distribution 'ubuntu' is not installed.")
        assertTrue(r.run(RunRequest("ls")).output.contains("proot-distro install ubuntu"))
    }

    @Test
    fun gagalMasukWorkdir_exit97() {
        val (r, _) = runner("exit=97\n")
        assertTrue(r.run(RunRequest("ls")).output.contains("workdir"))
    }

    @Test
    fun hasilTanpaExit_dianggapGagal() {
        val (r, _) = runner("Gagal memanggil Termux: boom")
        val res = r.run(RunRequest("ls"))
        assertEquals(-1, res.exitCode)
        assertFalse(res.ok)
        assertTrue(res.output.contains("Gagal memanggil Termux"))
    }

    // ---------- parseTermuxResult ----------

    @Test
    fun parseTermuxResult_bentukUmum() {
        assertEquals(0 to "hi\n", parseTermuxResult("exit=0\nhi\n"))
        assertEquals(2 to "", parseTermuxResult("exit=2\n"))
        assertEquals(1 to "ERR: x\nboom", parseTermuxResult("ERR: x\nexit=1\nboom"))
        assertEquals(124, parseTermuxResult("exit=124 Timeout: lama\nhint").first)
        assertNull(parseTermuxResult("Tidak ada hasil.").first)
    }
}
