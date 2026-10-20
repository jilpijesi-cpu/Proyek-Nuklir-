package com.kita.agent

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SelfHealingTest {

    private lateinit var tempDir: File
    private lateinit var projectRoot: File
    private lateinit var stateDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("kita_self_heal_test").toFile()
        projectRoot = File(tempDir, "project").apply { mkdirs() }
        stateDir = File(tempDir, "state").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun createRuntimeExceptionAtAppFrame(msg: String): RuntimeException {
        val ex = IllegalStateException(msg)
        ex.stackTrace = arrayOf(
            StackTraceElement("com.kita.agent.MainActivity", "onCreate", "MainActivity.kt", 358),
            StackTraceElement("android.app.Activity", "performCreate", "Activity.java", 8000)
        )
        return ex
    }

    @Test
    fun kondisiTanpaLaporanCrashMengembalikanNullDanTidakMemicuPemulihan() {
        val store = CrashReportStore(stateDir)
        assertNull(store.loadPendingCrash())

        val rca = analyzeRootCause(crash = null, validationOutput = "", recentSteps = emptyList())
        assertEquals(RootCauseCategory.INSUFFICIENT_EVIDENCE, rca.category)
        assertFalse(rca.canAutoRepair)
    }

    @Test
    fun pencatatanDanPemulihanLaporanCrashSertaSanitasiRahasia() {
        val store = CrashReportStore(stateDir)
        val cp = AgentCheckpoint(
            taskId = "task_99",
            userMessage = "Uji koneksi",
            turn = 2,
            lastTool = "run_command Bearer sk-1234567890abcdef12345678",
            phase = "running_tool"
        )
        val ex = createRuntimeExceptionAtAppFrame(
            "Gagal otorisasi dengan api_key=AIzaSyAbCdEfGhIjKlMnOpQrStUvWxYz123456 dan storePassword=rahasia_super"
        )

        val recorded = store.recordCrash(
            throwable = ex,
            threadName = "main",
            checkpoint = cp,
            appVersion = "2.6-test",
            sdkInt = 34,
            nowMs = 1_700_000_000_000L,
            extraSecrets = listOf("token_khusus_98765")
        )

        assertFalse(recorded.message.contains("AIzaSyAbCdEfGhIjKlMnOpQrStUvWxYz123456"))
        assertFalse(recorded.message.contains("rahasia_super"))
        assertFalse(recorded.lastTool.contains("sk-1234567890abcdef12345678"))
        assertTrue(recorded.message.contains("***REDACTED***"))
        assertEquals("MainActivity.kt", recorded.suspectFile)
        assertEquals(358, recorded.suspectLine)
        assertFalse(recorded.resolved)

        val loaded = store.loadPendingCrash()
        assertNotNull(loaded)
        assertEquals(recorded.id, loaded!!.id)
        assertEquals("MainActivity.kt", loaded.suspectFile)
        assertEquals(358, loaded.suspectLine)
        assertEquals("running_tool", loaded.lastPhase)

        store.markResolved(loaded.id)
        assertNull(store.loadPendingCrash())
    }

    @Test
    fun pencegahanLoopCrashBerulangMenghentikanPerbaikanOtomatis() {
        val store = CrashReportStore(stateDir)
        val baseTime = 1_700_000_100_000L
        store.recordCrash(createRuntimeExceptionAtAppFrame("Crash 1"), nowMs = baseTime)
        store.recordCrash(createRuntimeExceptionAtAppFrame("Crash 2"), nowMs = baseTime + 10_000L)
        val third = store.recordCrash(createRuntimeExceptionAtAppFrame("Crash 3"), nowMs = baseTime + 25_000L)

        assertTrue(third.isCrashLoop)
        assertEquals(3, third.crashCountInWindow)

        val rca = analyzeRootCause(third)
        assertEquals(RootCauseCategory.CRASH_LOOP, rca.category)
        assertFalse(rca.canAutoRepair)

        val controller = SelfHealingController(projectRoot, stateDir)
        assertFalse(controller.canAttemptRepair(third))

        val src = File(projectRoot, "app/src/main/java/com/kita/agent/MainActivity.kt").apply {
            parentFile.mkdirs()
            writeText("package com.kita.agent\nclass MainActivity\n")
        }
        val cycle = controller.executeRepairAttempt(
            crash = third,
            analysis = rca,
            patches = listOf(RepairPatch("app/src/main/java/com/kita/agent/MainActivity.kt", "// patched", "fix"))
        ) {
            ValidationRunResult(it, 0, "BUILD SUCCESSFUL")
        }
        assertTrue(cycle is SelfHealingCycleResult.BlockedCrashLoop)
        assertEquals("package com.kita.agent\nclass MainActivity\n", src.readText())
    }

    @Test
    fun batasPercobaanPerbaikanMencegahLoopTanpaAkhir() {
        val store = CrashReportStore(stateDir)
        val crash = store.recordCrash(createRuntimeExceptionAtAppFrame("NullPointer di baris 358"), nowMs = 1_700_000_500_000L)
        val rca = analyzeRootCause(crash)
        assertTrue(rca.canAutoRepair)

        val relPath = "app/src/main/java/com/kita/agent/MainActivity.kt"
        val targetFile = File(projectRoot, relPath).apply {
            parentFile.mkdirs()
            writeText("package com.kita.agent\n// versi stabil awal\n")
        }

        val controller = SelfHealingController(projectRoot, stateDir, maxAttempts = 3)

        for (i in 1..3) {
            val res = controller.executeRepairAttempt(
                crash = crash,
                analysis = rca,
                patches = listOf(RepairPatch(relPath, "package com.kita.agent\n// percobaan $i\n", "Perbaikan ke-$i")),
                nowMs = 1_700_000_500_000L + i * 1000L
            ) { cmd ->
                ValidationRunResult(cmd, exitCode = 1, output = "FAILURE: Build failed with an exception.")
            }
            assertTrue(res is SelfHealingCycleResult.ValidationFailedRolledBack)
            // Pastikan file kembali ke versi stabil setiap kali gagal
            assertEquals("package com.kita.agent\n// versi stabil awal\n", targetFile.readText())
        }

        assertEquals(3, controller.attemptsForCrash(crash.id).size)
        assertFalse(controller.canAttemptRepair(crash))

        // Percobaan ke-4 wajib ditolak oleh Retry Limit
        val fourth = controller.executeRepairAttempt(
            crash = crash,
            analysis = rca,
            patches = listOf(RepairPatch(relPath, "package com.kita.agent\n// percobaan 4\n", "Perbaikan ke-4"))
        ) { cmd ->
            ValidationRunResult(cmd, exitCode = 0, output = "BUILD SUCCESSFUL")
        }
        assertTrue(fourth is SelfHealingCycleResult.BlockedRetryLimit)
        assertEquals("package com.kita.agent\n// versi stabil awal\n", targetFile.readText())
    }

    @Test
    fun backupRollbackDanPenangananKegagalanBuildAtauTest() {
        val store = CrashReportStore(stateDir)
        val crash = store.recordCrash(createRuntimeExceptionAtAppFrame("Crash di MainActivity"), nowMs = 1_700_001_000_000L)
        val rca = analyzeRootCause(
            crash = crash,
            validationOutput = "e: file:///project/app/src/main/java/com/kita/agent/MainActivity.kt:358:12: Unresolved reference: rusak"
        )
        assertEquals(RootCauseCategory.COMPILE_ERROR, rca.category)
        assertTrue(rca.canAutoRepair)

        val relPath = "app/src/main/java/com/kita/agent/MainActivity.kt"
        val originalCode = "package com.kita.agent\nfun sehat() = true\n"
        val file = File(projectRoot, relPath).apply {
            parentFile.mkdirs()
            writeText(originalCode)
        }

        val controller = SelfHealingController(projectRoot, stateDir)
        val result = controller.executeRepairAttempt(
            crash = crash,
            analysis = rca,
            patches = listOf(RepairPatch(relPath, "package com.kita.agent\nfun sehat() = salah_ketik\n", "coba patch"))
        ) { cmd ->
            assertEquals(DEFAULT_VALIDATION_CMD, cmd)
            ValidationRunResult(cmd, exitCode = 1, output = "> Task :app:testDebugUnitTest FAILED\nFAILURE: Build failed")
        }

        assertTrue(result is SelfHealingCycleResult.ValidationFailedRolledBack)
        val rolledBack = result as SelfHealingCycleResult.ValidationFailedRolledBack
        assertTrue(rolledBack.attempt.rolledBack)
        assertFalse(rolledBack.attempt.validationPassed)
        assertTrue(rolledBack.rollbackRestoredFiles.contains(relPath))
        // Pastikan isi file dipulihkan persis ke originalCode
        assertEquals(originalCode, file.readText())
    }

    @Test
    fun perlindunganTerhadapPerubahanLokalPenggunaSaatPatchDanRollback() {
        val store = CrashReportStore(stateDir)
        val crash = store.recordCrash(createRuntimeExceptionAtAppFrame("Error"), nowMs = 1_700_002_000_000L)
        val rca = analyzeRootCause(crash)

        val targetRel = "app/src/main/java/com/kita/agent/MainActivity.kt"
        val userRel = "app/src/main/java/com/kita/agent/CatatanPengguna.kt"

        val targetFile = File(projectRoot, targetRel).apply {
            parentFile.mkdirs()
            writeText("package com.kita.agent\n// kode asli\n")
        }
        val userFile = File(projectRoot, userRel).apply {
            parentFile.mkdirs()
            writeText("package com.kita.agent\n// perubahan lokal milik pengguna yang belum dikomit\n")
        }

        val controller = SelfHealingController(projectRoot, stateDir)

        // 1. Jika patch mencoba menimpa file yang sedang dimodifikasi pengguna -> wajib ditolak!
        val blocked = controller.executeRepairAttempt(
            crash = crash,
            analysis = rca,
            patches = listOf(RepairPatch(userRel, "// ditimpa agen", "jangan timpa")),
            userModifiedPaths = setOf(userRel)
        ) { cmd ->
            ValidationRunResult(cmd, 0, "OK")
        }
        assertTrue(blocked is SelfHealingCycleResult.BlockedUserChanges)
        assertEquals("package com.kita.agent\n// perubahan lokal milik pengguna yang belum dikomit\n", userFile.readText())

        // 2. Jika patch mengubah targetFile lalu gagal validasi, rollback hanya memulihkan targetFile
        //    dan TIDAK PERNAH menyentuh atau menghapus userFile.
        val rolledBack = controller.executeRepairAttempt(
            crash = crash,
            analysis = rca,
            patches = listOf(RepairPatch(targetRel, "// patch gagal", "perbaiki bug")),
            userModifiedPaths = setOf(userRel)
        ) { cmd ->
            ValidationRunResult(cmd, 1, "FAILURE: Build failed")
        }
        assertTrue(rolledBack is SelfHealingCycleResult.ValidationFailedRolledBack)
        assertEquals("package com.kita.agent\n// kode asli\n", targetFile.readText())
        assertEquals("package com.kita.agent\n// perubahan lokal milik pengguna yang belum dikomit\n", userFile.readText())
    }

    @Test
    fun perbaikanBerhasilMenyimpanArtefakApkDanMewajibkanPersetujuanPengguna() {
        val store = CrashReportStore(stateDir)
        val crash = store.recordCrash(createRuntimeExceptionAtAppFrame("Bug kecil"), nowMs = 1_700_003_000_000L)
        val rca = analyzeRootCause(crash)

        val targetRel = "app/src/main/java/com/kita/agent/MainActivity.kt"
        val targetFile = File(projectRoot, targetRel).apply {
            parentFile.mkdirs()
            writeText("package com.kita.agent\nval x = 1\n")
        }
        val builtApk = File(projectRoot, "app/build/outputs/apk/debug/app-debug.apk").apply {
            parentFile.mkdirs()
            writeBytes(ByteArray(2048) { 0x50 })
        }

        val controller = SelfHealingController(projectRoot, stateDir)
        val cycle = controller.executeRepairAttempt(
            crash = crash,
            analysis = rca,
            patches = listOf(RepairPatch(targetRel, "package com.kita.agent\nval x = 2\n", "Perbaiki nilai x"))
        ) { cmd ->
            ValidationRunResult(cmd, exitCode = 0, output = "BUILD SUCCESSFUL in 4s", apkFile = builtApk)
        }

        assertTrue(cycle is SelfHealingCycleResult.RepairedAndVerified)
        val ok = cycle as SelfHealingCycleResult.RepairedAndVerified
        assertEquals("package com.kita.agent\nval x = 2\n", targetFile.readText())
        assertNotNull(ok.artifact)
        val artifact = ok.artifact!!
        assertFalse("Artefak tidak boleh disetujui otomatis tanpa konfirmasi pengguna", artifact.approvedByUser)
        assertTrue(File(artifact.apkPath).isFile)
        assertEquals(2048L, artifact.apkSizeBytes)
        assertTrue(artifact.diffSummary.contains("MainActivity.kt"))

        val approved = controller.approveArtifactForInstall(artifact.id)
        assertNotNull(approved)
        assertTrue(approved!!.approvedByUser)
    }

    @Test
    fun pembatasanFileTerlarangDiLuarProyekAtauKeystoreDitolak() {
        assertFalse(isAllowedRepairTarget(File(projectRoot, "keystore.properties"), projectRoot))
        assertFalse(isAllowedRepairTarget(File(projectRoot, "debug.keystore"), projectRoot))
        assertFalse(isAllowedRepairTarget(File(projectRoot, ".git/config"), projectRoot))
        assertFalse(isAllowedRepairTarget(File(tempDir, "Outside.kt"), projectRoot))
        assertTrue(isAllowedRepairTarget(File(projectRoot, "app/src/main/java/com/kita/agent/MainActivity.kt"), projectRoot))
    }
}
