package com.kita.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pengujian Keamanan Mendalam CommandPolicy & Classify (BM-05 / Level 1).
 * Menguji variasi spasi, kapitalisasi, kutipan, ";", "&&", "||", "$()", backticks, Base64, dan path traversal.
 * Seluruh payload adalah string tiruan yang dievaluasi secara statis di JVM tanpa mengeksekusi proses shell.
 */
class CommandPolicySecurityTest {

    private val workdir = DEFAULT_SANDBOX_WORKDIR
    private val guestHome = DEFAULT_GUEST_HOME

    private fun assess(cmd: String): Assessment = assessCommand(cmd, workdir, guestHome)

    // =========================================================================
    // 1. Variasi Spasi, Tab, & Baris Baru (LULUS)
    // =========================================================================

    @Test
    fun sec01_variasiSpasiTabDanBarisBaru_diklasifikasikanDenganBenar() {
        val safeWithSpaces = listOf(
            "   ls    -la   ",
            "\tpwd\t",
            "cat   src/Main.kt   |   wc   -l"
        )
        for (cmd in safeWithSpaces) {
            assertEquals("Seharusnya SAFE: '$cmd'", Decision.SAFE, assess(cmd).decision)
        }

        val injectedViaNewlineOrTab = listOf(
            "ls -la\nrm -rf /",
            "pwd\t;\trm -rf ..",
            "cat notes.txt\n\necho pwned > /etc/hosts",
            "ls   &&   chmod   -R   777   ."
        )
        for (cmd in injectedViaNewlineOrTab) {
            assertEquals(
                "Injeksi baris baru/tab harus BLOCKED: '${cmd.replace("\n", "\\n")}'",
                Decision.BLOCKED,
                assess(cmd).decision
            )
        }
    }

    // =========================================================================
    // 2. Variasi Kapitalisasi (LULUS untuk pencegahan SAFE)
    // =========================================================================

    @Test
    fun sec02_variasiKapitalisasiPerintah_tidakPernahSalahMenjadiSAFE() {
        val capitalizedCmds = listOf(
            "LS -la",
            "Cat /etc/passwd",
            "RM -rf build",
            "SUDO ls",
            "BASH -c 'ls'",
            "PYTHON3 exploit.py",
            "CURL https://evil.example"
        )
        for (cmd in capitalizedCmds) {
            assertNotEquals(
                "Perintah dengan variasi kapitalisasi '$cmd' tidak boleh lolos sebagai SAFE",
                Decision.SAFE,
                assess(cmd).decision
            )
        }
    }

    // =========================================================================
    // 3. Variasi Kutipan (Quotes) & Escape Backslash pada Target Path (LULUS)
    // =========================================================================

    @Test
    fun sec03_variasiKutipanDanEscape_rmDanRedirectDiluarWorkdir_tetapDiblokir() {
        val quotedDestructive = listOf(
            "'rm' -rf /",
            "\"rm\" -rf /",
            "r\\m -rf /",
            "/bin/'rm' -rf /",
            "rm '-rf' '/'",
            "echo pwn > '/etc/passwd'",
            "echo pwn >> \"../outside.txt\"",
            "bash -c \"rm -rf /\"",
            "sh -c 'dd if=/dev/zero of=/dev/sda'"
        )
        for (cmd in quotedDestructive) {
            assertEquals(
                "Variasi kutipan/escape destruktif '$cmd' wajib BLOCKED",
                Decision.BLOCKED,
                assess(cmd).decision
            )
        }

        val quotedReadOutside = listOf(
            "cat '/etc/passwd'",
            "cat \"../../etc/hosts\"",
            "ls '../'"
        )
        for (cmd in quotedReadOutside) {
            assertNotEquals(
                "Baca file di luar workdir dengan kutipan '$cmd' tidak boleh SAFE",
                Decision.SAFE,
                assess(cmd).decision
            )
        }
    }

    // =========================================================================
    // 4. Operator Rantai (; , && , || , | , &) (LULUS)
    // =========================================================================

    @Test
    fun sec04_operatorRantaiTitikKomaAndOrPipe_mengambilKeputusanTerburuk() {
        val chainedApproval = listOf(
            "ls -la ; python3 script.py",
            "pwd && mkdir build_out",
            "cat a.txt || curl https://example.com",
            "echo hello | tee output.txt",
            "ls & touch background.txt"
        )
        for (cmd in chainedApproval) {
            val a = assess(cmd)
            assertEquals("Rantai perintah '$cmd' harus NEEDS_APPROVAL", Decision.NEEDS_APPROVAL, a.decision)
        }

        val chainedBlocked = listOf(
            "ls -la ; rm -rf /",
            "pwd && dd if=a of=b",
            "true || mkfs.ext4 /dev/block/sda",
            "cat a.txt | tee /etc/passwd",
            "echo ok & rm -rf ~"
        )
        for (cmd in chainedBlocked) {
            assertEquals("Rantai perintah destruktif '$cmd' harus BLOCKED", Decision.BLOCKED, assess(cmd).decision)
        }
    }

    // =========================================================================
    // 5. Substitusi Perintah $(), Backticks `...`, & Process Substitution (LULUS)
    // =========================================================================

    @Test
    fun sec05_substitusiPerintahDollarParenDanBacktick_tidakPernahSAFE() {
        val substitutions = listOf(
            "echo \$(id)",
            "echo `whoami`",
            "ls -la \$(pwd)",
            "cat `cat target.txt`",
            "grep foo <\$(cat list.txt)",
            "diff <(ls a) <(ls b)",
            "echo \${PATH}"
        )
        for (cmd in substitutions) {
            val a = assess(cmd)
            assertNotEquals(
                "Substitusi shell '$cmd' tidak boleh diklasifikasikan SAFE",
                Decision.SAFE,
                a.decision
            )
        }
    }

    // =========================================================================
    // 6. Base64 Decode & Eksekusi Terselubung (LULUS)
    // =========================================================================

    @Test
    fun sec06_payloadBase64DecodeKeShell_tidakPernahSAFE() {
        val base64Payloads = listOf(
            "echo cm0gLXJmIC8= | base64 -d | sh",
            "echo cm0gLXJmIC8= | base64 --decode | bash",
            "base64 -d payload.b64 > exploit.sh",
            "base64 /etc/passwd"
        )
        for (cmd in base64Payloads) {
            val a = assess(cmd)
            assertNotEquals(
                "Payload Base64 '$cmd' tidak boleh lolos sebagai SAFE",
                Decision.SAFE,
                a.decision
            )
        }
    }

    // =========================================================================
    // 7. Path Traversal Standar (LULUS)
    // =========================================================================

    @Test
    fun sec07_pathTraversalRelatifMutlakDanTilde_ditolakDariSAFE() {
        val traversals = listOf(
            "cat ../secret.txt",
            "cat a/b/../../../etc/passwd",
            "ls /root/sandbox/work/..",
            "ls /root/sandbox/work2/secret",
            "head -n 10 ~/../etc/shadow",
            "grep root --file=../../etc/passwd"
        )
        for (cmd in traversals) {
            assertNotEquals(
                "Path traversal '$cmd' tidak boleh diklasifikasikan SAFE",
                Decision.SAFE,
                assess(cmd).decision
            )
        }
    }

    // =========================================================================
    // 8. TEMUAN CELAH KEAMANAN (GAGAL): Perintah Berbahaya Salah Diklasifikasikan SAFE / READ
    // =========================================================================

    @Test
    fun sec08_regresiBypassSAFE_kutipanPadaFlagBerbahayaFindDanTree_seharusnyaBukanSAFE() {
        // CELAH KEAMANAN SEC-01 (Classify.kt:77 vs CommandPolicy.kt:168):
        // CommandPolicy.kt memanggil `unquote(it)` pada setiap token, tetapi Classify.kt TIDAK memanggil `unquote(it)`!
        // Akibatnya, saat flag berbahaya diberi tanda kutip atau backslash seperti:
        // - `find . "-delete"` atau `find . '-delete'` (menghapus semua file di workdir)
        // - `find . '-exec' sh -c id \;` (menjalankan perintah shell arbitrer)
        // - `tree '-o' overwritten.txt` (menimpa file)
        // `classify(cmd)` melihat token `"'-delete'"` != `"-delete"`, menganggapnya `Risk.READ`,
        // lalu `assessCommand(cmd)` melihat `"find"` / `"tree"` ada di `SAFE_ALLOWLIST` dan mengembalikan `Decision.SAFE`!
        val quotedFlagExploits = listOf(
            "find . \"-delete\"",
            "find . '-delete'",
            "find . '-exec' sh -c id \\;",
            "tree '-o' overwritten.txt"
        )
        for (cmd in quotedFlagExploits) {
            val a = assess(cmd)
            assertNotEquals(
                "SEC-01: Perintah berbahaya dengan flag berkutip '$cmd' SALAH diklasifikasikan sebagai SAFE (aktual=${a.decision}, risk=${a.risk})",
                Decision.SAFE,
                a.decision
            )
        }
    }

    @Test
    fun sec09_regresiBypassSAFE_flagPendekMelekatPathTraversalDanTreeOutput_seharusnyaBukanSAFE() {
        // CELAH KEAMANAN SEC-02 (CommandPolicy.kt:95-97 & Classify.kt:98):
        // 1. `candidatePaths` di CommandPolicy.kt mengabaikan semua argumen yang diawali `-` jika tidak mengandung `=`.
        //    Akibatnya flag pendek POSIX yang langsung diikuti path di luar workdir seperti:
        //    `grep -f/etc/passwd root .` atau `grep -f../../etc/shadow root .` tidak terdeteksi keluar workdir dan menjadi `Decision.SAFE`!
        // 2. `Classify.kt:98` hanya memeriksa `it == "-o" || it.startsWith("--output")` pada `tree`, tidak memeriksa `-o<path>` melekat
        //    (berbeda dari ToolExecution.kt:24). Akibatnya `tree -o/etc/pwned .` maupun `tree -ohacked.txt .` diklasifikasikan `Decision.SAFE`!
        val attachedFlagExploits = listOf(
            "grep -f/etc/passwd root .",
            "grep -f../../etc/shadow root .",
            "tree -o/etc/pwned .",
            "tree -ohacked.txt ."
        )
        for (cmd in attachedFlagExploits) {
            val a = assess(cmd)
            assertNotEquals(
                "SEC-02: Perintah dengan flag pendek melekat '$cmd' SALAH diklasifikasikan sebagai SAFE (aktual=${a.decision}, risk=${a.risk})",
                Decision.SAFE,
                a.decision
            )
        }
    }

    @Test
    fun sec10_regresiBypassClassifyREAD_flagVersiPalsuPadaInterpreterDanGit_seharusnyaBukanREAD() {
        // CELAH KEAMANAN SEC-03 (Classify.kt:101):
        // `VERSION_CMDS.contains(name) && t.any { it in VERSION_FLAGS } -> {}`
        // memeriksa apakah ADA argumen `-v`, `-V`, `--version`, atau `version` di posisi mana pun pada perintah
        // `python3`, `node`, `git`, `gradle`, dll.
        // Akibatnya:
        // - `python3 -v exploit.py` (`-v` pada Python adalah verbose import, tetap mengeksekusi `exploit.py`!)
        // - `python3 exploit.py --version` (menjalankan skrip `exploit.py` dengan argumen `--version`!)
        // - `git branch -D main -v` (menghapus branch `main` dengan output verbose!)
        // - `git clean -fd -v` (menghapus file untracked dengan output verbose!)
        // semuanya SALAH diklasifikasikan sebagai `Risk.READ`!
        val fakeVersionCmds = listOf(
            "python3 -v exploit.py",
            "python3 exploit.py --version",
            "git branch -D main -v",
            "git clean -fd -v"
        )
        for (cmd in fakeVersionCmds) {
            val risk = classify(cmd)
            assertTrue(
                "SEC-03: Perintah eksekusi/destruktif '$cmd' SALAH diklasifikasikan sebagai Risk.READ oleh classify()",
                risk.ordinal >= Risk.WRITE.ordinal
            )
        }
    }

    @Test
    fun sec11_regresiBypassBLOCKED_perintahDestruktifDalamBacktickAtauKapital_seharusnyaBLOCKED() {
        // CELAH KEAMANAN SEC-04 (CommandPolicy.kt:71 & 171):
        // 1. `SEGMENT_SPLIT` tidak memecah backtick `` `...` `` sehingga `echo `rm -rf /`` hanya dilihat sebagai perintah `echo`
        //    dan menghasilkan `Decision.NEEDS_APPROVAL` alih-alih `Decision.BLOCKED`.
        // 2. Nama perintah diperiksa secara case-sensitive (`RM -rf /`, `DD if=/dev/zero of=/dev/sda`) sehingga tidak masuk `BLOCK_CMDS` / `RM_LIKE`.
        val destructiveBypassBlocked = listOf(
            "echo `rm -rf /`",
            "RM -rf /",
            "DD if=/dev/zero of=/dev/sda"
        )
        for (cmd in destructiveBypassBlocked) {
            val a = assess(cmd)
            assertEquals(
                "SEC-04: Perintah destruktif '$cmd' seharusnya BLOCKED, bukan ${a.decision}",
                Decision.BLOCKED,
                a.decision
            )
        }
    }
}
