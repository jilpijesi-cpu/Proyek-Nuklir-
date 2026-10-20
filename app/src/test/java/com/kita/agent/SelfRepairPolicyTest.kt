package com.kita.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SelfRepairPolicyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // =========================================================================
    // 1. Daftar File Terlindung (Protected Files Registry)
    // =========================================================================

    @Test
    fun classifyProtectedPath_blocksKeystoreAndEnvFilesUnconditionally() {
        for (blockedPath in listOf(
            ".env",
            ".env.production",
            "app/.env",
            "debug.keystore",
            "debug.keystore.base64",
            "keystore.properties",
            "app/release.jks",
            "signing/my-key.keystore"
        )) {
            val verdict = classifyProtectedPath(blockedPath)
            assertEquals("Path $blockedPath harus BLOCKED", ProtectedFileVerdict.BLOCKED, verdict)
            assertTrue("Path $blockedPath harus terdeteksi isBlockedSecretOrKeystoreFile", isBlockedSecretOrKeystoreFile(blockedPath))
        }
    }

    @Test
    fun classifyProtectedPath_requiresSpecialApprovalForCoreProtectedFiles() {
        val protectedPaths = listOf(
            "AndroidManifest.xml",
            "app/src/main/AndroidManifest.xml",
            "build.gradle",
            "build.gradle.kts",
            "app/build.gradle.kts",
            "settings.gradle",
            "settings.gradle.kts",
            "gradle.properties",
            "CommandPolicy.kt",
            "app/src/main/java/com/kita/agent/CommandPolicy.kt",
            "AgentGuard.kt",
            "app/src/main/java/com/kita/agent/AgentGuard.kt",
            "ApprovalDialog.kt",
            "app/src/main/java/com/kita/agent/ApprovalDialog.kt",
            "ToolExecution.kt",
            "TermuxFiles.kt",
            "SandboxRunner.kt",
            "SandboxIntegration.kt",
            "Secret.kt",
            "ApkInstall.kt",
            "WorkspacePolicy.kt",
            "SelfRepairPolicy.kt",
            ".github/workflows/android.yml",
            ".github/workflows/release.yaml"
        )
        for (path in protectedPaths) {
            val verdict = classifyProtectedPath(path)
            assertTrue("Path $path harus terdaftar sebagai File Terlindung", isProtectedFile(path))
            assertEquals("Path $path harus NEEDS_SPECIAL_APPROVAL", ProtectedFileVerdict.NEEDS_SPECIAL_APPROVAL, verdict)
        }
    }

    @Test
    fun classifyProtectedPath_allowsNormalSourceFilesWithoutProtectedRestriction() {
        val normalPath = "app/src/main/java/com/kita/agent/NewFeature.kt"
        assertFalse(isProtectedFile(normalPath))
        assertEquals(ProtectedFileVerdict.UNPROTECTED, classifyProtectedPath(normalPath))
    }

    @Test
    fun assessWorkspaceCommand_requiresApprovalWhenShellMutatesProtectedFiles() {
        val home = tmp.newFolder("home")
        val ws = File(home, "workspaces/default").apply { mkdirs() }

        val sedRes = assessWorkspaceCommand(
            "sed -i 's/foo/bar/g' app/src/main/java/com/kita/agent/CommandPolicy.kt",
            ws.canonicalPath,
            home.canonicalPath
        )
        assertEquals(Decision.NEEDS_APPROVAL, sedRes.decision)

        val redirectRes = assessWorkspaceCommand(
            "echo 'x=1' >> gradle.properties",
            ws.canonicalPath,
            home.canonicalPath
        )
        assertEquals(Decision.NEEDS_APPROVAL, redirectRes.decision)
    }

    @Test
    fun detectForbiddenGradleTampering_blocksApplicationIdAndSigningConfigChanges() {
        val oldGradle = """
            android {
                namespace = "com.kita.agent"
                defaultConfig {
                    applicationId = "com.kita.agent"
                    versionCode = 14
                }
                signingConfigs {
                    create("release") {
                        storeFile = file("debug.keystore")
                    }
                }
            }
        """.trimIndent()

        // Mengubah versionCode diperbolehkan
        val (bumpedGradle, newVer) = bumpVersionCodeInGradle(oldGradle)
        assertEquals(15, newVer)
        assertNull(detectForbiddenGradleTampering(oldGradle, bumpedGradle))

        // Mengubah applicationId dilarang keras
        val changedAppId = oldGradle.replace("applicationId = \"com.kita.agent\"", "applicationId = \"com.evil.agent\"")
        assertNotNull(detectForbiddenGradleTampering(oldGradle, changedAppId))

        // Mengubah signingConfigs dilarang keras
        val changedSigning = oldGradle.replace("storeFile = file(\"debug.keystore\")", "storeFile = file(\"evil.keystore\")")
        assertNotNull(detectForbiddenGradleTampering(oldGradle, changedSigning))
    }

    // =========================================================================
    // 2. Alur Batas Percobaan (Max 5 Attempts dengan AgentGuard)
    // =========================================================================

    @Test
    fun selfRepairRetryGuard_stopsExactlyAfter5FailedBuildOrTestAttempts() {
        val guard = SelfRepairRetryGuard(maxAttempts = MAX_SELF_REPAIR_ATTEMPTS)
        assertEquals(5, MAX_SELF_REPAIR_ATTEMPTS)

        for (i in 1..4) {
            val decision = guard.recordBuildTestAttempt(
                unitTestPassed = false,
                assemblePassed = false,
                rawOutput = "e: file:///App.kt:$i:10 Unresolved reference: foo"
            )
            assertTrue("Percobaan ke-$i harus mengembalikan RetryAllowed", decision is SelfRepairAttemptDecision.RetryAllowed)
            val retry = decision as SelfRepairAttemptDecision.RetryAllowed
            assertEquals(i, retry.attemptNumber)
            assertEquals(5 - i, retry.remainingAttempts)
            assertTrue(guard.canRetry())
        }

        // Kegagalan ke-5 wajib menghentikan agen dan melaporkan penyebabnya
        val fifth = guard.recordBuildTestAttempt(
            unitTestPassed = true,
            assemblePassed = false,
            rawOutput = "Execution failed for task ':app:compileDebugKotlin'"
        )
        assertTrue("Percobaan ke-5 wajib mengembalikan Exhausted", fifth is SelfRepairAttemptDecision.Exhausted)
        val exhausted = fifth as SelfRepairAttemptDecision.Exhausted
        assertEquals(5, exhausted.attemptsUsed)
        assertFalse(guard.canRetry())
        assertTrue(exhausted.failureReport.contains("5/5"))
        assertTrue(exhausted.failureReport.contains("assembleDebug"))
    }

    @Test
    fun selfRepairRetryGuard_returnsPassedWhenUnitTestAndAssembleSucceed() {
        val guard = SelfRepairRetryGuard(maxAttempts = 5)
        val firstFail = guard.recordBuildTestAttempt(
            unitTestPassed = false,
            assemblePassed = false,
            rawOutput = "Test failed"
        )
        assertTrue(firstFail is SelfRepairAttemptDecision.RetryAllowed)
        assertEquals(1, guard.attemptsUsed)

        val secondPass = guard.recordBuildTestAttempt(
            unitTestPassed = true,
            assemblePassed = true,
            rawOutput = "BUILD SUCCESSFUL"
        )
        assertTrue(secondPass is SelfRepairAttemptDecision.Passed)
    }

    // =========================================================================
    // 3. Validasi Branch, Kenaikan versionCode, Backup APK, & Boot-OK / Rollback
    // =========================================================================

    @Test
    fun buildSelfRepairBranchName_createsValidSelfBranchAndBlocksMainCommits() {
        val branch = buildSelfRepairBranchName("20261020", "Perbaiki tombol pengaturan!")
        assertEquals("self/20261020-perbaiki-tombol-pengaturan", branch)
        assertTrue(isValidSelfRepairBranch(branch))
        assertFalse(isValidSelfRepairBranch("main"))
        assertFalse(isValidSelfRepairBranch("master"))

        assertTrue(isDirectMainCommitOrPush("git commit -m 'fix'", "main"))
        assertFalse(isDirectMainCommitOrPush("git commit -m 'fix'", branch))
        assertTrue(isDirectMainCommitOrPush("git push origin main", branch))
        assertTrue(isDirectMainCommitOrPush("git checkout main && git merge $branch", branch))
    }

    @Test
    fun bumpVersionCodeInGradle_incrementsVersionCodeBy1() {
        val content = """
            defaultConfig {
                applicationId = "com.kita.agent"
                versionCode = 26
                versionName = "2.6"
            }
        """.trimIndent()
        val (updated, nextVer) = bumpVersionCodeInGradle(content)
        assertEquals(27, nextVer)
        assertTrue(updated.contains("versionCode = 27"))
    }

    @Test
    fun pruneApkBackups_keepsAtMost3NewestApkFiles() {
        val dir = tmp.newFolder("apk_backups")
        for (i in 1..5) {
            val f = File(dir, "backup_$i.apk")
            f.writeText("apk-$i")
            f.setLastModified(1000L * i)
        }
        val kept = pruneApkBackups(dir, maxKeep = 3)
        assertEquals(3, kept.size)
        assertEquals(listOf("backup_5.apk", "backup_4.apk", "backup_3.apk"), kept.map { it.name })
        assertFalse(File(dir, "backup_1.apk").exists())
        assertFalse(File(dir, "backup_2.apk").exists())
    }

    @Test
    fun selfRepairBootState_enablesMergeAfterBootOkAndSuggestsRollbackAfter2Crashes() {
        val initial = onSelfRepairApkInstallTriggered("self/20261020-fix-ui", "abc1234")
        assertFalse(initial.canMergeToMain)
        assertFalse(initial.shouldSuggestRollback)

        // Crash 1x -> belum menyarankan rollback
        val crash1 = onCrashSinceSelfRepairInstall(initial)
        assertEquals(1, crash1.crashCountSinceInstall)
        assertFalse(crash1.shouldSuggestRollback)

        // Crash 2x berturut-turut -> sarankan kembali ke versi sebelumnya
        val crash2 = onCrashSinceSelfRepairInstall(crash1)
        assertEquals(2, crash2.crashCountSinceInstall)
        assertTrue(crash2.shouldSuggestRollback)
        assertFalse(crash2.canMergeToMain)

        // Jika berjalan normal selama 1 menit -> boot ok tercapai dan tombol Gabungkan ke main aktif
        val bootOkState = onSelfRepairBootOkReached(initial)
        assertTrue(bootOkState.bootOk)
        assertTrue(bootOkState.canMergeToMain)
    }
}
