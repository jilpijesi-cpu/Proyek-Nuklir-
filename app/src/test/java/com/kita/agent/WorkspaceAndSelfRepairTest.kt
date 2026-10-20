package com.kita.agent

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit test murni JVM untuk fitur "Ruang Kerja" dan "Perbaiki Diri" (beserta daftar File Terlindung
 * dan batas percobaan).
 */
class WorkspaceAndSelfRepairTest {

    private lateinit var tempRoot: File
    private lateinit var fakeHome: File
    private lateinit var workspaceDir: File

    @Before
    fun setUp() {
        tempRoot = Files.createTempDirectory("kita_ws_self_test").toFile()
        fakeHome = File(tempRoot, "home").apply { mkdirs() }
        workspaceDir = File(fakeHome, "workspaces/default").apply { mkdirs() }
        File(fakeHome, "kita").mkdirs()
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    // =========================================================================
    // 1. Validasi Nama Ruang Kerja & Inisialisasi Termux
    // =========================================================================

    @Test
    fun validasiNamaRuangKerja_menerimaNamaValidDanMenolakSpasiTitikGarisMiring() {
        val validNames = listOf("default", "proyek_1", "agen-kita", "A", "a".repeat(32), "Ruang_Kerja-99")
        for (n in validNames) {
            val v = validateWorkspaceName(n)
            assertTrue("Seharusnya valid: '$n'", v.valid)
            assertNull(v.error)
            assertTrue(isValidWorkspaceName(n))
        }

        val invalidNames = listOf(
            "",
            "   ",
            "ruang kerja",
            "nama.titik",
            ".hidden",
            "..",
            "a..b",
            "sub/folder",
            "sub\\folder",
            "~/default",
            "a".repeat(33),
            "nama@ spesial!"
        )
        for (n in invalidNames) {
            val v = validateWorkspaceName(n)
            assertFalse("Seharusnya ditolak: '$n'", v.valid)
            assertNotNull("Pesan error wajib ada untuk '$n'", v.error)
            assertFalse(isValidWorkspaceName(n))
        }
    }

    @Test
    fun perintahPembuatanRuangKerja_membuatFolderWorkspacesDanGitInitSertaCekExitCode() {
        val cmd = buildCreateWorkspaceCommand("default", "/data/data/com.termux/files/home")
        assertTrue(cmd.contains("/data/data/com.termux/files/home/workspaces/default"))
        assertTrue(cmd.contains("mkdir -p"))
        assertTrue(cmd.contains("git init"))

        assertTrue(didWorkspaceCommandSucceed("exit=0\nInitialized empty Git repository"))
        assertFalse(didWorkspaceCommandSucceed("exit=1\nmkdir: Permission denied"))
        assertFalse(didWorkspaceCommandSucceed("Gagal memanggil Termux"))
    }

    // =========================================================================
    // 2. Pembatas Canonical Path Ruang Kerja & CommandPolicy
    // =========================================================================

    @Test
    fun canonicalPathRuangKerja_menolakPathKeluarSymlinkKitaEnvDanKeystore() {
        val insideFile = File(workspaceDir, "src/Main.kt").apply {
            parentFile.mkdirs()
            writeText("class Main")
        }
        assertTrue(isPathInsideWorkspace("src/Main.kt", workspaceDir, fakeHome))
        assertTrue(isPathInsideWorkspace("./src/../src/Main.kt", workspaceDir, fakeHome))
        assertTrue(isPathInsideWorkspace(insideFile.absolutePath, workspaceDir, fakeHome))

        // Path keluar ruang kerja: absolut di luar, "../", ~, $HOME, ~/kita
        assertFalse(isPathInsideWorkspace("../other", workspaceDir, fakeHome))
        assertFalse(isPathInsideWorkspace("../../kita/Main.kt", workspaceDir, fakeHome))
        assertFalse(isPathInsideWorkspace("/etc/passwd", workspaceDir, fakeHome))
        assertFalse(isPathInsideWorkspace("~", workspaceDir, fakeHome))
        assertFalse(isPathInsideWorkspace("~/workspaces/default", workspaceDir, fakeHome))
        assertFalse(isPathInsideWorkspace("\$HOME/workspaces/default", workspaceDir, fakeHome))
        assertFalse(isPathInsideWorkspace("~/kita", workspaceDir, fakeHome))
        assertFalse(isPathInsideWorkspace("${fakeHome.absolutePath}/kita/file.txt", workspaceDir, fakeHome))

        // File .env dan keystore di dalam ruang kerja sekalipun tetap BLOCKED
        assertFalse(isPathInsideWorkspace(".env", workspaceDir, fakeHome))
        assertFalse(isPathInsideWorkspace(".env.local", workspaceDir, fakeHome))
        assertFalse(isPathInsideWorkspace("keystore.properties", workspaceDir, fakeHome))
        assertFalse(isPathInsideWorkspace("app/debug.keystore", workspaceDir, fakeHome))
        assertFalse(isPathInsideWorkspace("release.jks", workspaceDir, fakeHome))

        // Symlink di dalam ruang kerja yang mengarah ke luar ruang kerja wajib ditolak oleh canonical path
        val outsideSecret = File(tempRoot, "outside_secret.txt").apply { writeText("rahasia") }
        val symlinkInWorkspace = File(workspaceDir, "link_keluar.txt")
        Files.createSymbolicLink(symlinkInWorkspace.toPath(), outsideSecret.toPath())

        assertFalse(
            "Symlink di dalam ruang kerja yang mengarah ke luar wajib ditolak via canonical path",
            isPathInsideWorkspace("link_keluar.txt", workspaceDir, fakeHome)
        )
    }

    @Test
    fun assessWorkspaceCommand_memblokirPathKeluarSymlinkCdKeluarKitaEnvDanKeystore() {
        val wsPath = workspaceDir.canonicalPath
        val homePath = fakeHome.canonicalPath

        // Buat symlink yang mengarah ke luar ruang kerja
        val outsideDir = File(tempRoot, "outside_dir").apply { mkdirs() }
        val symlinkPath = File(workspaceDir, "escaped_link")
        Files.createSymbolicLink(symlinkPath.toPath(), outsideDir.toPath())

        val blockedCmds = listOf(
            "cat /etc/passwd",
            "ls ..",
            "cat ../rahasia.txt",
            "cat escaped_link/file.txt",
            "cd ..",
            "cd /sdcard",
            "cd ~",
            "cd",
            "ls ~",
            "echo \$HOME",
            "cat ~/kita/MainActivity.kt",
            "ls ${homePath}/kita",
            "cat .env",
            "cat .env.production",
            "echo SECRET=1 > .env",
            "cat keystore.properties",
            "cp debug.keystore /tmp/x",
            "rm release.jks"
        )
        for (cmd in blockedCmds) {
            val a = assessWorkspaceCommand(cmd, wsPath, homePath)
            assertEquals("Perintah '$cmd' wajib BLOCKED di ruang kerja", Decision.BLOCKED, a.decision)
        }

        // Perintah baca di dalam ruang kerja tetap SAFE
        val safeCmds = listOf(
            "ls -la",
            "pwd",
            "cat src/Main.kt",
            "grep -r foo src"
        )
        for (cmd in safeCmds) {
            val a = assessWorkspaceCommand(cmd, wsPath, homePath)
            assertEquals("Perintah '$cmd' di dalam ruang kerja seharusnya SAFE", Decision.SAFE, a.decision)
        }
    }

    // =========================================================================
    // 3. Daftar Eksplisit File Terlindung & Penilaian di CommandPolicy
    // =========================================================================

    @Test
    fun daftarFileTerlindung_lengkapDanTepatDiSatuTempat() {
        val mustNeedSpecialApproval = listOf(
            "CommandPolicy.kt",
            "app/src/main/java/com/kita/agent/CommandPolicy.kt",
            "AgentGuard.kt",
            "ApprovalDialog.kt",
            "ToolExecution.kt",
            "SandboxRunner.kt",
            "SandboxIntegration.kt",
            "Secret.kt",
            "ApkInstall.kt",
            "WorkspacePolicy.kt",
            "SelfRepairPolicy.kt",
            "SelfHealingEngine.kt",
            "SelfHealingController.kt",
            "SelfHealingIntegration.kt",
            "build.gradle.kts",
            "app/build.gradle.kts",
            "settings.gradle.kts",
            "gradle.properties",
            "build-termux.sh",
            ".github/workflows/android.yml",
            ".github/workflows/release.yaml"
        )
        for (path in mustNeedSpecialApproval) {
            assertEquals(
                "File '$path' wajib NEEDS_SPECIAL_APPROVAL",
                ProtectedFileVerdict.NEEDS_SPECIAL_APPROVAL,
                classifyProtectedPath(path)
            )
            assertTrue(isProtectedFile(path))
        }

        val mustBeBlocked = listOf(
            ".env",
            ".env.local",
            "app/.env",
            "keystore.properties",
            "debug.keystore",
            "debug.keystore.base64",
            "app/release.keystore",
            "upload-key.jks",
            "signing.properties"
        )
        for (path in mustBeBlocked) {
            assertEquals(
                "File '$path' wajib BLOCKED",
                ProtectedFileVerdict.BLOCKED,
                classifyProtectedPath(path)
            )
            assertTrue(isBlockedSecretOrKeystoreFile(path))
        }

        // File biasa tidak termasuk file terlindung
        assertEquals(ProtectedFileVerdict.UNPROTECTED, classifyProtectedPath("app/src/main/java/com/kita/agent/Palette.kt"))
        assertEquals(ProtectedFileVerdict.UNPROTECTED, classifyProtectedPath("README.md"))
    }

    @Test
    fun commandPolicy_menilaiModifikasiFileTerlindungLewatSedTeeGitCheckoutApplyMvRm() {
        val wsPath = workspaceDir.canonicalPath
        val homePath = fakeHome.canonicalPath

        val mutatingProtectedCmds = listOf(
            "sed -i 's/a/b/' app/src/main/java/com/kita/agent/CommandPolicy.kt",
            "tee app/src/main/java/com/kita/agent/Secret.kt",
            "git checkout -- app/src/main/java/com/kita/agent/ToolExecution.kt",
            "git apply patch.diff app/src/main/java/com/kita/agent/AgentGuard.kt",
            "mv Temp.kt app/src/main/java/com/kita/agent/ApprovalDialog.kt",
            "rm app/src/main/java/com/kita/agent/SandboxRunner.kt",
            "echo '// ubah' >> app/build.gradle.kts",
            "cp x.yml .github/workflows/ci.yml",
            "rm app/src/main/java/com/kita/agent/SelfRepairPolicy.kt"
        )
        for (cmd in mutatingProtectedCmds) {
            val a = assessWorkspaceCommand(cmd, wsPath, homePath)
            assertEquals(
                "Perintah '$cmd' yang memodifikasi file terlindung harus NEEDS_APPROVAL",
                Decision.NEEDS_APPROVAL,
                a.decision
            )
            assertTrue(
                "Perintah '$cmd' wajib menandai requiresProtectedApproval = true",
                a.requiresProtectedApproval
            )
            assertTrue(a.protectedFiles.isNotEmpty())
        }

        // Keystore & .env serta modifikasi applicationId / signingConfigs wajib BLOCKED
        val blockedProtectedCmds = listOf(
            "sed -i 's/a/b/' .env",
            "rm debug.keystore",
            "mv a.jks release.keystore",
            "tee keystore.properties",
            "sed -i 's/com.kita.agent/com.evil.app/' app/build.gradle.kts && echo applicationId",
            "keytool -genkey -v -keystore my.keystore",
            "apksigner sign --ks debug.keystore app.apk"
        )
        for (cmd in blockedProtectedCmds) {
            val a = assessWorkspaceCommand(cmd, wsPath, homePath)
            assertEquals(
                "Perintah '$cmd' pada keystore/.env/signing wajib BLOCKED",
                Decision.BLOCKED,
                a.decision
            )
        }
    }

    @Test
    fun laranganMengubahApplicationIdDanKonfigurasiSigningDiGradle() {
        val originalGradle = """
            android {
                namespace = "com.kita.agent"
                defaultConfig {
                    applicationId = "com.kita.agent"
                    versionCode = 11
                }
                signingConfigs {
                    create("release") {
                        storeFile = file("release.keystore")
                    }
                }
            }
        """.trimIndent()

        // Mengubah versionCode diperbolehkan
        val (bumpedGradle, newVer) = bumpVersionCodeInGradle(originalGradle)
        assertEquals(12, newVer)
        assertNull(detectForbiddenGradleTampering(originalGradle, bumpedGradle))

        // Mengubah applicationId dilarang
        val changedAppId = originalGradle.replace("applicationId = \"com.kita.agent\"", "applicationId = \"com.lain.app\"")
        val appIdErr = detectForbiddenGradleTampering(originalGradle, changedAppId)
        assertNotNull(appIdErr)
        assertTrue(appIdErr!!.contains("applicationId"))

        // Mengubah signingConfigs / storeFile dilarang
        val changedSigning = originalGradle.replace("release.keystore", "hacker.keystore")
        val signErr = detectForbiddenGradleTampering(originalGradle, changedSigning)
        assertNotNull(signErr)
        assertTrue(signErr!!.contains("signing"))
    }

    // =========================================================================
    // 4. Alur Perbaiki Diri: Branch, Batas 5 Percobaan (AgentGuard), Backup 3 APK, Boot OK & Crash 2x
    // =========================================================================

    @Test
    fun alurBranchPerbaikiDiri_workingTreeBersihDanLaranganCommitKeMain() {
        assertTrue(isWorkingTreeClean("exit=0\n"))
        assertTrue(isWorkingTreeClean("exit=0\n   \n"))
        assertFalse(isWorkingTreeClean("exit=0\n M app/src/main/java/com/kita/agent/MainActivity.kt\n"))
        assertFalse(isWorkingTreeClean("exit=128\nfatal: not a git repository"))

        val branch = buildSelfRepairBranchName("2026-10-20", "Perbaiki Warna Header!")
        assertEquals("self/20261020-perbaiki-warna-header", branch)
        assertTrue(isValidSelfRepairBranch(branch))
        assertFalse(isValidSelfRepairBranch("main"))
        assertFalse(isValidSelfRepairBranch("master"))
        assertFalse(isValidSelfRepairBranch("feature/lain"))

        assertTrue(isDirectMainCommitOrPush("git commit -m 'fix'", activeBranch = "main"))
        assertTrue(isDirectMainCommitOrPush("git push origin main"))
        assertTrue(isDirectMainCommitOrPush("git checkout main && git merge self/20261020-fix"))
        assertFalse(isDirectMainCommitOrPush("git commit -m 'fix'", activeBranch = "self/20261020-fix"))
    }

    @Test
    fun batasPercobaanPerbaikiDiri_maksimal5PercobaanLaluBerhentiDanMelaporkanPenyebab() {
        val guard = SelfRepairRetryGuard(maxAttempts = MAX_SELF_REPAIR_ATTEMPTS)
        assertEquals(5, MAX_SELF_REPAIR_ATTEMPTS)

        // Percobaan 1..4 gagal -> masih diizinkan mengulang (RetryAllowed) dengan panduan AgentGuard
        for (i in 1..4) {
            assertTrue(guard.canRetry())
            val decision = guard.recordBuildTestAttempt(
                unitTestPassed = false,
                assemblePassed = false,
                rawOutput = "exit=1\n> Task :app:testDebugUnitTest FAILED\nMainActivity.kt:42: Unresolved reference: salah_$i"
            )
            assertTrue("Percobaan ke-$i harus RetryAllowed", decision is SelfRepairAttemptDecision.RetryAllowed)
            val retry = decision as SelfRepairAttemptDecision.RetryAllowed
            assertEquals(i, retry.attemptNumber)
            assertEquals(5 - i, retry.remainingAttempts)
        }

        // Percobaan ke-5 gagal -> wajib berhenti (Exhausted) dan melaporkan penyebabnya
        assertTrue(guard.canRetry())
        val fifth = guard.recordBuildTestAttempt(
            unitTestPassed = true,
            assemblePassed = false,
            rawOutput = "exit=1\n> Task :app:assembleDebug FAILED\nFAILURE: Build failed with an exception."
        )
        assertTrue("Percobaan ke-5 yang gagal wajib Exhausted", fifth is SelfRepairAttemptDecision.Exhausted)
        assertFalse("Setelah 5 percobaan gagal, canRetry() harus false", guard.canRetry())
        val exhausted = fifth as SelfRepairAttemptDecision.Exhausted
        assertEquals(5, exhausted.attemptsUsed)
        assertTrue(exhausted.failureReport.contains("5/5"))
        assertTrue(exhausted.failureReport.contains("./gradlew assembleDebug"))
    }

    @Test
    fun cadanganApk_menyimpanMaksimal3VersiTerakhir() {
        val backupDir = File(tempRoot, "apk_backups")
        val dummyRunningApk = File(tempRoot, "base.apk").apply {
            writeBytes(ByteArray(4096) { 0x1A })
        }

        for (v in 1..5) {
            val saved = backupInstalledApk(
                runningSourceApk = dummyRunningApk,
                backupDir = backupDir,
                versionCode = v,
                nowMs = 1_700_000_000_000L + v * 10_000L,
                maxKeep = 3
            )
            assertNotNull(saved)
        }

        val kept = pruneApkBackups(backupDir, maxKeep = 3)
        assertEquals("Maksimal 3 versi APK terakhir yang boleh disimpan", 3, kept.size)
        assertTrue(kept[0].name.contains("_v5_"))
        assertTrue(kept[1].name.contains("_v4_"))
        assertTrue(kept[2].name.contains("_v3_"))
    }

    @Test
    fun bootOkSatuMenitDanDeteksiCrash2KaliBerturutTurut() {
        var state = onSelfRepairApkInstallTriggered("self/20261020-fitur", "a1b2c3d")
        assertFalse(state.bootOk)
        assertFalse(state.canMergeToMain)
        assertFalse(state.shouldSuggestRollback)

        // Crash ke-1 sejak pemasangan: belum mencapai ambang 2x
        state = onCrashSinceSelfRepairInstall(state)
        assertEquals(1, state.crashCountSinceInstall)
        assertFalse(state.shouldSuggestRollback)

        // Crash ke-2 berturut-turut sejak pemasangan: wajib sarankan kembali ke versi sebelumnya!
        state = onCrashSinceSelfRepairInstall(state)
        assertEquals(2, state.crashCountSinceInstall)
        assertTrue(state.shouldSuggestRollback)
        assertFalse(state.canMergeToMain)

        // Jika versi baru berjalan normal 1 menit -> "boot ok" aktif dan tombol "Gabungkan ke main" tersedia
        val healthyInstall = onSelfRepairApkInstallTriggered("self/20261020-stabil", "f00ba77")
        val afterOneMin = onSelfRepairBootOkReached(healthyInstall)
        assertTrue(afterOneMin.bootOk)
        assertTrue(afterOneMin.canMergeToMain)
        assertFalse(afterOneMin.shouldSuggestRollback)
    }
}
