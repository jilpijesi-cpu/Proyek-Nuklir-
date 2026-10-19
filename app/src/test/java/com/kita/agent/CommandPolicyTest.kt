package com.kita.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test kebijakan sandbox (assessCommand) dan pembatas workdir (resolvePath/isInside).
 * classify() sendiri diuji di ClassifyTest dan TIDAK diubah.
 * Murni JVM: ./gradlew testDebugUnitTest
 */
class CommandPolicyTest {
    private val wd = DEFAULT_SANDBOX_WORKDIR

    private fun decisionOf(cmd: String) = assessCommand(cmd).decision

    private fun assertAll(expected: Decision, cmds: List<String>) {
        for (c in cmds) assertEquals("assessCommand(${c.replace("\n", "\\n")})", expected, decisionOf(c))
    }

    // ---------- aman ----------

    @Test
    fun perintahBacaDiDalamWorkdir_SAFE() = assertAll(
        Decision.SAFE,
        listOf(
            "", "   ", "ls -la", "pwd", "echo hi", "cat src/a.kt", "wc -l notes.txt",
            "grep -r foo . | head -20", "find . -name '*.kt'", "ls > /dev/null", "ls 2>&1", "cat a.txt | wc -l"
        )
    )

    @Test
    fun readDiLuarWorkdir_butuhPersetujuan_bukanBlokir() = assertAll(
        Decision.NEEDS_APPROVAL,
        listOf("cat /etc/passwd", "ls ..", "ls ~", "cat ../a", "cat ../../etc/hosts", "ls /")
    )

    @Test
    fun variabelShellTidakPernahSAFE() = assertAll(
        Decision.NEEDS_APPROVAL,
        listOf("echo \$HOME", "ls \$PWD", "echo `ls`", "echo \$(ls)")
    )

    @Test
    fun tailFollowDanPerintahDiluarAllowlist_tidakSAFE() = assertAll(
        Decision.NEEDS_APPROVAL,
        listOf("tail -f log.txt", "tail -F log.txt", "unzip -l a.zip", "python3 --version")
    )

    // ---------- berisiko: wajib persetujuan ----------

    @Test
    fun perintahBerisiko_butuhPersetujuan() = assertAll(
        Decision.NEEDS_APPROVAL,
        listOf(
            "mkdir x", "touch f", "cp a b", "mv a b", "sed -i s/a/b/ f", "chmod +x run.sh",
            "python3 a.py", "git status", "echo hi > out.txt", "cat a >> b",
            "rm x", "rm -rf build", "rm -rf ./build/*", "rm -rf build/../out",
            "find . -name x -delete", "rm -f /root/sandbox/work/a.txt", "rmdir tmp"
        )
    )

    @Test
    fun tidakPernahLebihLonggarDariClassify() {
        val cmds = listOf(
            "ls", "mkdir x", "rm -rf build", "echo a > b", "python3 a.py", "sudo ls", "cat a | tee b",
            "bash -c 'ls'", "find . -exec cat {} \\;", "cat /etc/passwd", "curl http://x"
        )
        for (c in cmds) {
            val a = assessCommand(c)
            assertEquals(classify(c), a.risk)
            if (a.decision == Decision.SAFE) assertEquals("SAFE harus READ: $c", Risk.READ, classify(c))
        }
    }

    // ---------- destruktif: diblokir ----------

    @Test
    fun perintahDestruktif_diblokir() = assertAll(
        Decision.BLOCKED,
        listOf(
            "rm -rf /", "rm -rf /*", "rm -rf ~", "rm -rf ~/", "rm -rf ..", "rm -rf ../x",
            "rm -rf \$HOME/x", "rm -rf .", "rm -rf ./", "rm -rf /root/sandbox/work",
            "rm -rf /root/sandbox/work/..", "sudo rm -rf /", "ls; rm -rf /", "ls && rm -rf /", "'rm' -rf /",
            "bash -c 'rm -rf /'", "find / -delete", "unlink /etc/hosts", "truncate -s 0 /etc/hosts",
            "dd if=a of=b", "dd if=/dev/zero of=/dev/sda", "mkfs.ext4 x", "mkfs x", "shred f", "fdisk -l",
            "chmod -R 777 .", "chmod -R 777 /", "chown -R a .", "chgrp -R a .", "chmod --recursive 777 .",
            "bash -c 'dd if=a of=b'", "sh -c 'mkfs x'",
            "echo hi > /etc/x", "echo hi >> ../x", "cat a > ~/x", "touch /etc/x", "cp a /etc/b", "mv a ../b",
            "mkdir /x", "tee /etc/x", "sed -i s/a/b/ /etc/hosts",
            ":(){ :|:& };:", "termux-setup-storage", "termux-open x", "proot-distro login debian",
            "proot -r x", "su", "mount /dev/x /mnt", "reboot", "am start x", "pm list packages",
            "cd / && rm -rf *", "cd .. ; ls", "cd ~", "cd", "pushd /etc"
        )
    )

    @Test
    fun perintahDiblokir_punyaAlasan() {
        for (c in listOf("rm -rf /", "dd if=a of=b", "chmod -R 777 .", "echo x > /etc/y")) {
            val a = assessCommand(c)
            assertEquals(Decision.BLOCKED, a.decision)
            assertTrue("alasan kosong untuk $c", a.reasons.any { it.startsWith("DIBLOKIR") })
            assertTrue(a.warning()!!.contains("DIBLOKIR"))
        }
    }

    // ---------- jaringan ----------

    @Test
    fun perintahJaringan_ditandaiDanButuhPersetujuan() {
        val cmds = listOf(
            "curl http://example.com", "wget -O a.html https://example.com", "git clone https://github.com/x/y",
            "git push", "git pull", "pip3 install requests", "npm install", "npm i", "ssh host", "scp a host:b",
            "ping 1.1.1.1", "./gradlew build", "bash -c 'curl x'", "apt-get update", "echo hi; curl x",
            "nc 1.2.3.4 80", "git fetch origin", "git@github.com:x/y.git"
        )
        for (c in cmds) {
            val a = assessCommand(c)
            assertTrue("network harus true: $c", a.network)
            assertNotEquals("jaringan tidak boleh SAFE: $c", Decision.SAFE, a.decision)
            assertNotNull2(a.warning(), c)
        }
    }

    @Test
    fun jaringanDiLuarWorkdir_tetapDiblokirBukanSekadarDitandai() {
        val a = assessCommand("curl -o /etc/x http://example.com")
        assertTrue(a.network)
        assertEquals(Decision.BLOCKED, a.decision)
    }

    @Test
    fun bukanJaringan_tidakDitandai() {
        for (c in listOf("ls", "git status", "git log", "git diff", "cat a.txt", "mkdir x", "python3 a.py")) {
            assertFalse("network seharusnya false: $c", assessCommand(c).network)
        }
    }

    // ---------- pembatas workdir ----------

    @Test
    fun resolvePath_relatifMutlakDanTilde() {
        assertEquals("$wd/a/b", resolvePath("a/b", wd, "/root"))
        assertEquals("$wd/a", resolvePath("./a", wd, "/root"))
        assertEquals("/root/sandbox/x", resolvePath("../x", wd, "/root"))
        assertEquals("/root", resolvePath("a/../../..", wd, "/root"))
        assertEquals("/etc", resolvePath("/etc", wd, "/root"))
        assertEquals("/", resolvePath("/..", wd, "/root"))
        assertEquals("/root", resolvePath("~", wd, "/root"))
        assertEquals("$wd/x", resolvePath("~/sandbox/work/x", wd, "/root"))
    }

    @Test
    fun resolvePath_tidakBisaDipastikan_null() {
        assertNull(resolvePath("\$HOME/x", wd, "/root"))
        assertNull(resolvePath("`pwd`/x", wd, "/root"))
        assertNull(resolvePath("~other/x", wd, "/root"))
    }

    @Test
    fun isInside_tepatDiWorkdirSaja() {
        assertTrue(isInside(wd, wd))
        assertTrue(isInside("$wd/x/y", wd))
        assertTrue(isInside("$wd/x", "$wd/"))
        assertFalse(isInside("/root/sandbox/work2", wd))   // awalan sama, folder lain
        assertFalse(isInside("/root/sandbox", wd))
        assertFalse(isInside("/", wd))
    }

    @Test
    fun workdirKustom_dipakai() {
        val a = assessCommand("rm -rf /srv/w/build", workdir = "/srv/w", guestHome = "/home/u")
        assertEquals(Decision.NEEDS_APPROVAL, a.decision)
        assertEquals(Decision.BLOCKED, assessCommand("rm -rf /srv/w2/build", workdir = "/srv/w", guestHome = "/home/u").decision)
    }

    // ---------- persetujuan tidak bisa dilewati bila force ----------

    @Test
    fun force_tidakBisaDilewatiOtonomAtauYaSemua() {
        assertFalse(bypassesApproval(allowAll = false, autonomous = false, force = false))
        assertTrue(bypassesApproval(allowAll = true, autonomous = false, force = false))
        assertTrue(bypassesApproval(allowAll = false, autonomous = true, force = false))
        assertFalse(bypassesApproval(allowAll = true, autonomous = true, force = true))
        assertFalse(bypassesApproval(allowAll = false, autonomous = true, force = true))
    }

    private fun assertNotNull2(v: Any?, msg: String) = assertTrue("null: $msg", v != null)
}
