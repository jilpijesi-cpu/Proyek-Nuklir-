package com.kita.agent

import java.io.File
import java.security.MessageDigest

/**
 * Pengendali perbaikan terkendali (Controlled Repair), Checkpoint & Rollback yang melindungi
 * perubahan lokal pengguna, Batas Percobaan (Retry Limit), Validasi Proyek, serta Artefak APK.
 * Murni Kotlin tanpa ketergantungan Android runtime agar dapat diuji langsung di JVM unit test.
 */

private val ALLOWED_REPAIR_EXT = setOf("kt", "java", "kts", "xml", "pro")
private val FORBIDDEN_FILE_NAMES = setOf(
    "keystore.properties",
    "local.properties",
    "debug.keystore",
    "debug.keystore.base64",
    ".env",
    ".env.local"
)

internal fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}

internal fun sha256File(file: File): String =
    if (file.isFile) sha256Hex(file.readBytes()) else ""

/**
 * Validasi apakah berkas target berada di dalam direktori proyek yang diizinkan dan bukan berkas rahasia/kunci.
 */
internal fun isAllowedRepairTarget(targetFile: File, projectRoot: File): Boolean {
    return try {
        val rootCanon = projectRoot.canonicalFile
        val targetCanon = targetFile.canonicalFile
        val rootPath = rootCanon.path
        val targetPath = targetCanon.path
        if (targetPath != rootPath && !targetPath.startsWith(rootPath + File.separator)) return false
        val rel = targetPath.removePrefix(rootPath).trimStart(File.separatorChar).replace('\\', '/')
        if (rel.isEmpty()) return false
        if (rel.startsWith(".git/") || rel == ".git" || rel.startsWith(".gradle/") || rel.startsWith("build/")) return false
        val name = targetCanon.name
        if (name in FORBIDDEN_FILE_NAMES || name.endsWith(".keystore") || name.endsWith(".jks")) return false
        val ext = name.substringAfterLast('.', "").lowercase()
        ext in ALLOWED_REPAIR_EXT
    } catch (_: Exception) {
        false
    }
}

internal data class FileSnapshot(
    val relativePath: String,
    val existedBefore: Boolean,
    val originalContent: String,
    val originalSha256: String
)

internal data class SelfHealingCheckpoint(
    val id: String,
    val createdAt: Long,
    val projectRootPath: String,
    val snapshots: Map<String, FileSnapshot>,
    val userModifiedFiles: Set<String>
)

internal data class RepairPatch(
    val relativePath: String,
    val newContent: String,
    val rationale: String
)

internal sealed interface PatchOutcome {
    data class Applied(
        val checkpoint: SelfHealingCheckpoint,
        val appliedFiles: List<String>,
        val diffSummary: String
    ) : PatchOutcome

    data class RejectedUserChanges(val conflictedFiles: List<String>) : PatchOutcome
    data class RejectedForbiddenTarget(val forbiddenFiles: List<String>) : PatchOutcome
    data class RejectedNoRootCause(val reason: String) : PatchOutcome
}

internal data class ValidationRunResult(
    val command: String,
    val exitCode: Int,
    val output: String,
    val apkFile: File? = null
) {
    val passed: Boolean
        get() = exitCode == 0 && !output.contains("FAILURE: Build failed", ignoreCase = true)
}

internal data class RepairAttemptRecord(
    val attemptNumber: Int,
    val timestamp: Long,
    val crashId: String,
    val rootCauseSummary: String,
    val patchedFiles: List<String>,
    val validationCommand: String,
    val validationPassed: Boolean,
    val validationSummary: String,
    val rolledBack: Boolean
)

internal data class SelfHealingArtifact(
    val id: String,
    val createdAt: Long,
    val crashId: String,
    val apkPath: String,
    val apkSizeBytes: Long,
    val apkSha256: String,
    val patchedFiles: List<String>,
    val diffSummary: String,
    val validationCommand: String,
    val validationSummary: String,
    val approvedByUser: Boolean = false
)

internal sealed interface SelfHealingCycleResult {
    data class RepairedAndVerified(
        val attempt: RepairAttemptRecord,
        val artifact: SelfHealingArtifact?,
        val checkpoint: SelfHealingCheckpoint
    ) : SelfHealingCycleResult

    data class ValidationFailedRolledBack(
        val attempt: RepairAttemptRecord,
        val rollbackRestoredFiles: List<String>
    ) : SelfHealingCycleResult

    data class BlockedCrashLoop(val crashCount: Int) : SelfHealingCycleResult
    data class BlockedRetryLimit(val attemptsUsed: Int, val maxAttempts: Int = MAX_REPAIR_ATTEMPTS) : SelfHealingCycleResult
    data class BlockedUnprovenRootCause(val analysis: RootCauseAnalysis) : SelfHealingCycleResult
    data class BlockedUserChanges(val conflictedFiles: List<String>) : SelfHealingCycleResult
    data class BlockedForbiddenTarget(val forbiddenFiles: List<String>) : SelfHealingCycleResult
}

/**
 * Pengelola sesi Self-Healing: menyimpan riwayat percobaan perbaikan, checkpoint file stabil,
 * perlindungan perubahan lokal pengguna, rollback terisolasi, serta penyimpanan artefak APK.
 */
internal class SelfHealingController(
    private val projectRoot: File,
    private val stateDir: File,
    private val maxAttempts: Int = MAX_REPAIR_ATTEMPTS
) {
    private val attempts = ArrayList<RepairAttemptRecord>()
    private var latestCheckpoint: SelfHealingCheckpoint? = null
    private var latestArtifact: SelfHealingArtifact? = null

    init {
        stateDir.mkdirs()
        loadAttemptsFromDisk()
    }

    @Synchronized
    fun getAttempts(): List<RepairAttemptRecord> = attempts.toList()

    @Synchronized
    fun attemptsForCrash(crashId: String): List<RepairAttemptRecord> =
        attempts.filter { it.crashId == crashId }

    @Synchronized
    fun canAttemptRepair(crash: CrashReport): Boolean {
        if (crash.isCrashLoop) return false
        return attemptsForCrash(crash.id).size < maxAttempts
    }

    @Synchronized
    fun getLatestCheckpoint(): SelfHealingCheckpoint? = latestCheckpoint

    @Synchronized
    fun getLatestArtifact(): SelfHealingArtifact? = latestArtifact

    /**
     * Buat checkpoint sebelum menerapkan patch pada daftar path relatif target.
     * [userModifiedPaths] berisi daftar path relatif yang sudah dimodifikasi lokal oleh pengguna
     * sebelum sesi self-healing dimulai (mis. dari git status atau deteksi perubahan pengguna).
     */
    @Synchronized
    fun createCheckpoint(
        targetRelativePaths: Collection<String>,
        userModifiedPaths: Set<String> = emptySet(),
        nowMs: Long = System.currentTimeMillis()
    ): SelfHealingCheckpoint {
        val normUserMod = userModifiedPaths.map { normalizeRelPath(it) }.toSet()
        val snapshots = LinkedHashMap<String, FileSnapshot>()
        for (rawRel in targetRelativePaths) {
            val rel = normalizeRelPath(rawRel)
            val f = File(projectRoot, rel)
            val existed = f.isFile
            val content = if (existed) f.readText() else ""
            val sha = if (existed) sha256Hex(content.toByteArray()) else ""
            snapshots[rel] = FileSnapshot(
                relativePath = rel,
                existedBefore = existed,
                originalContent = content,
                originalSha256 = sha
            )
        }
        val cp = SelfHealingCheckpoint(
            id = "cp_$nowMs",
            createdAt = nowMs,
            projectRootPath = projectRoot.canonicalPath,
            snapshots = snapshots,
            userModifiedFiles = normUserMod
        )
        persistCheckpointToDisk(cp)
        latestCheckpoint = cp
        return cp
    }

    /**
     * Terapkan patch terbatas hanya bila:
     * 1. Root cause terbukti (canAutoRepair == true).
     * 2. Seluruh file target berada di dalam direktori proyek yang diizinkan dan bukan file rahasia.
     * 3. Tidak ada file target yang sedang memiliki perubahan lokal pengguna yang belum disimpan/dikomit.
     */
    @Synchronized
    fun applyControlledPatches(
        analysis: RootCauseAnalysis,
        patches: List<RepairPatch>,
        userModifiedPaths: Set<String> = emptySet(),
        nowMs: Long = System.currentTimeMillis()
    ): PatchOutcome {
        if (!analysis.canAutoRepair || analysis.targetFile.isBlank()) {
            return PatchOutcome.RejectedNoRootCause(analysis.summary)
        }
        if (patches.isEmpty()) {
            return PatchOutcome.RejectedNoRootCause("Daftar patch kosong")
        }

        val normUserMod = userModifiedPaths.map { normalizeRelPath(it) }.toSet()
        val forbidden = ArrayList<String>()
        val conflicted = ArrayList<String>()

        for (p in patches) {
            val rel = normalizeRelPath(p.relativePath)
            val target = File(projectRoot, rel)
            if (!isAllowedRepairTarget(target, projectRoot)) {
                forbidden.add(rel)
            } else if (rel in normUserMod) {
                conflicted.add(rel)
            }
        }
        if (forbidden.isNotEmpty()) return PatchOutcome.RejectedForbiddenTarget(forbidden)
        if (conflicted.isNotEmpty()) return PatchOutcome.RejectedUserChanges(conflicted)

        val cp = createCheckpoint(
            targetRelativePaths = patches.map { it.relativePath },
            userModifiedPaths = normUserMod,
            nowMs = nowMs
        )

        val applied = ArrayList<String>()
        val diffLines = ArrayList<String>()
        for (p in patches) {
            val rel = normalizeRelPath(p.relativePath)
            val target = File(projectRoot, rel)
            val before = cp.snapshots[rel]?.originalContent.orEmpty()
            target.parentFile?.mkdirs()
            target.writeText(p.newContent)
            applied.add(rel)
            val oldLines = if (before.isEmpty()) 0 else before.lines().size
            val newLines = if (p.newContent.isEmpty()) 0 else p.newContent.lines().size
            diffLines.add("$rel ($oldLines -> $newLines baris): ${p.rationale}")
        }

        return PatchOutcome.Applied(
            checkpoint = cp,
            appliedFiles = applied,
            diffSummary = diffLines.joinToString("\n")
        )
    }

    /**
     * Pulihkan hanya berkas yang tercatat pada [checkpoint] tanpa menyentuh berkas perubahan pengguna
     * lainnya di dalam proyek.
     */
    @Synchronized
    fun rollbackCheckpoint(checkpoint: SelfHealingCheckpoint? = latestCheckpoint): List<String> {
        val cp = checkpoint ?: return emptyList()
        val restored = ArrayList<String>()
        for ((rel, snap) in cp.snapshots) {
            if (rel in cp.userModifiedFiles) continue
            val target = File(projectRoot, rel)
            if (!isAllowedRepairTarget(target, projectRoot)) continue
            if (snap.existedBefore) {
                target.parentFile?.mkdirs()
                target.writeText(snap.originalContent)
                restored.add(rel)
            } else if (target.exists()) {
                target.delete()
                restored.add(rel)
            }
        }
        return restored
    }

    /**
     * Jalankan satu siklus Self-Healing lengkap:
     * 1. Cek crash-loop dan batas percobaan (Retry Limit).
     * 2. Verifikasi bahwa Root Cause Analysis berbasis bukti.
     * 3. Buat checkpoint & terapkan patch terkendali tanpa menimpa perubahan pengguna.
     * 4. Jalankan validasi (lint + testDebugUnitTest + assembleDebug).
     * 5. Jika validasi gagal -> otomatis rollback ke checkpoint stabil.
     * 6. Jika validasi berhasil -> simpan artefak APK beserta ringkasan diff & hasil tes untuk persetujuan pengguna.
     */
    @Synchronized
    fun executeRepairAttempt(
        crash: CrashReport,
        analysis: RootCauseAnalysis,
        patches: List<RepairPatch>,
        userModifiedPaths: Set<String> = emptySet(),
        validationCommand: String = DEFAULT_VALIDATION_CMD,
        nowMs: Long = System.currentTimeMillis(),
        validator: (String) -> ValidationRunResult
    ): SelfHealingCycleResult {
        if (crash.isCrashLoop) {
            return SelfHealingCycleResult.BlockedCrashLoop(crash.crashCountInWindow)
        }
        val priorAttempts = attemptsForCrash(crash.id).size
        if (priorAttempts >= maxAttempts) {
            return SelfHealingCycleResult.BlockedRetryLimit(priorAttempts, maxAttempts)
        }
        if (!analysis.canAutoRepair || analysis.targetFile.isBlank()) {
            return SelfHealingCycleResult.BlockedUnprovenRootCause(analysis)
        }

        val patchOutcome = applyControlledPatches(analysis, patches, userModifiedPaths, nowMs)
        val applied = when (patchOutcome) {
            is PatchOutcome.Applied -> patchOutcome
            is PatchOutcome.RejectedUserChanges ->
                return SelfHealingCycleResult.BlockedUserChanges(patchOutcome.conflictedFiles)
            is PatchOutcome.RejectedForbiddenTarget ->
                return SelfHealingCycleResult.BlockedForbiddenTarget(patchOutcome.forbiddenFiles)
            is PatchOutcome.RejectedNoRootCause ->
                return SelfHealingCycleResult.BlockedUnprovenRootCause(analysis)
        }

        val attemptNo = priorAttempts + 1
        val valResult = runCatching {
            validator(validationCommand)
        }.getOrElse { e ->
            ValidationRunResult(
                command = validationCommand,
                exitCode = 1,
                output = "Eksekusi validasi gagal: ${redactSecrets(e.message ?: e.javaClass.simpleName)}"
            )
        }

        val safeOutput = redactSecrets(valResult.output).take(2_000)
        if (!valResult.passed) {
            val restored = rollbackCheckpoint(applied.checkpoint)
            val record = RepairAttemptRecord(
                attemptNumber = attemptNo,
                timestamp = nowMs,
                crashId = crash.id,
                rootCauseSummary = analysis.summary,
                patchedFiles = applied.appliedFiles,
                validationCommand = valResult.command,
                validationPassed = false,
                validationSummary = "Validasi gagal (exit=${valResult.exitCode}). Checkpoint dipulihkan (${restored.size} file).\n$safeOutput",
                rolledBack = true
            )
            recordAttempt(record)
            return SelfHealingCycleResult.ValidationFailedRolledBack(record, restored)
        }

        val record = RepairAttemptRecord(
            attemptNumber = attemptNo,
            timestamp = nowMs,
            crashId = crash.id,
            rootCauseSummary = analysis.summary,
            patchedFiles = applied.appliedFiles,
            validationCommand = valResult.command,
            validationPassed = true,
            validationSummary = "Validasi lulus (exit=0).\n$safeOutput",
            rolledBack = false
        )
        recordAttempt(record)

        val artifact = storeVerifiedArtifact(
            crashId = crash.id,
            sourceApk = valResult.apkFile ?: File(projectRoot, "app/build/outputs/apk/debug/app-debug.apk"),
            patchedFiles = applied.appliedFiles,
            diffSummary = applied.diffSummary,
            validationCommand = valResult.command,
            validationSummary = record.validationSummary,
            nowMs = nowMs
        )
        return SelfHealingCycleResult.RepairedAndVerified(record, artifact, applied.checkpoint)
    }

    /**
     * Simpan APK hasil build terverifikasi beserta ringkasan perubahan & log pengujian.
     * approvedByUser selalu false sampai pengguna menyetujui pemasangan di UI.
     */
    @Synchronized
    fun storeVerifiedArtifact(
        crashId: String,
        sourceApk: File,
        patchedFiles: List<String>,
        diffSummary: String,
        validationCommand: String,
        validationSummary: String,
        nowMs: Long = System.currentTimeMillis()
    ): SelfHealingArtifact? {
        if (!sourceApk.isFile || sourceApk.length() <= 0L) return null
        val artifactsDir = File(stateDir, "artifacts").apply { mkdirs() }
        val destApk = File(artifactsDir, "app-debug-verified-$nowMs.apk")
        sourceApk.copyTo(destApk, overwrite = true)
        val sha = sha256File(destApk)
        val metaFile = File(artifactsDir, "app-debug-verified-$nowMs.meta.txt")
        val artifact = SelfHealingArtifact(
            id = "artifact_$nowMs",
            createdAt = nowMs,
            crashId = crashId,
            apkPath = destApk.absolutePath,
            apkSizeBytes = destApk.length(),
            apkSha256 = sha,
            patchedFiles = patchedFiles,
            diffSummary = redactSecrets(diffSummary),
            validationCommand = validationCommand,
            validationSummary = redactSecrets(validationSummary),
            approvedByUser = false
        )
        metaFile.writeText(
            buildString {
                appendLine("id=${artifact.id}")
                appendLine("crashId=${artifact.crashId}")
                appendLine("apkPath=${artifact.apkPath}")
                appendLine("apkSizeBytes=${artifact.apkSizeBytes}")
                appendLine("apkSha256=${artifact.apkSha256}")
                appendLine("patchedFiles=${artifact.patchedFiles.joinToString(",")}")
                appendLine("validationCommand=${artifact.validationCommand}")
                appendLine("approvedByUser=${artifact.approvedByUser}")
                appendLine("---DIFF---")
                appendLine(artifact.diffSummary)
                appendLine("---VALIDATION---")
                appendLine(artifact.validationSummary)
            }
        )
        latestArtifact = artifact
        return artifact
    }

    /**
     * Catat persetujuan eksplisit pengguna sebelum mengizinkan peluncuran installer APK.
     */
    @Synchronized
    fun approveArtifactForInstall(artifactId: String): SelfHealingArtifact? {
        val cur = latestArtifact ?: return null
        if (cur.id != artifactId) return null
        val approved = cur.copy(approvedByUser = true)
        latestArtifact = approved
        return approved
    }

    private fun recordAttempt(record: RepairAttemptRecord) {
        attempts.add(record)
        saveAttemptsToDisk()
    }

    private fun attemptsFile() = File(stateDir, "repair_attempts.log")

    private fun saveAttemptsToDisk() {
        runCatching {
            val lines = attempts.map { a ->
                listOf(
                    a.attemptNumber.toString(),
                    a.timestamp.toString(),
                    a.crashId.replace("|", "_"),
                    a.validationPassed.toString(),
                    a.rolledBack.toString(),
                    a.patchedFiles.joinToString(","),
                    a.rootCauseSummary.replace("\n", " ").replace("|", "/")
                ).joinToString("|")
            }
            attemptsFile().writeText(lines.joinToString("\n"))
        }
    }

    private fun loadAttemptsFromDisk() {
        val f = attemptsFile()
        if (!f.isFile) return
        runCatching {
            for (line in f.readLines()) {
                val p = line.split("|")
                if (p.size >= 7) {
                    attempts.add(
                        RepairAttemptRecord(
                            attemptNumber = p[0].toIntOrNull() ?: 1,
                            timestamp = p[1].toLongOrNull() ?: 0L,
                            crashId = p[2],
                            validationPassed = p[3].toBooleanStrictOrNull() ?: false,
                            rolledBack = p[4].toBooleanStrictOrNull() ?: false,
                            patchedFiles = p[5].split(",").filter { it.isNotBlank() },
                            rootCauseSummary = p[6],
                            validationCommand = DEFAULT_VALIDATION_CMD,
                            validationSummary = ""
                        )
                    )
                }
            }
        }
    }

    private fun persistCheckpointToDisk(cp: SelfHealingCheckpoint) {
        runCatching {
            val dir = File(stateDir, "checkpoints/${cp.id}").apply { mkdirs() }
            for ((rel, snap) in cp.snapshots) {
                val safeName = rel.replace("/", "__")
                if (snap.existedBefore) {
                    File(dir, "$safeName.bak").writeText(snap.originalContent)
                }
            }
        }
    }
}

internal fun normalizeRelPath(path: String): String =
    path.trim().replace('\\', '/').trimStart('/')

/**
 * Panduan pemulihan eksternal yang jujur dan realistis ketika aplikasi mengalami crash berulang
 * atau gagal diluncurkan pada Android.
 */
internal fun buildExternalRecoveryGuide(projectDirHint: String = "~/kita"): String = buildString {
    appendLine("JALUR PEMULIHAN EKSTERNAL (TERMUX / MANUAL):")
    appendLine("Catatan Batasan Android: Ketika proses aplikasi mati total atau gagal diluncurkan saat startup, Android tidak mengizinkan proses yang sudah mati memperbaiki dirinya sendiri di latar belakang.")
    appendLine()
    appendLine("Opsi 1 — Pulihkan Kode & Build Ulang via Termux:")
    appendLine("  cd $projectDirHint")
    appendLine("  git status -s")
    appendLine("  # Kembalikan file yang rusak tanpa menghapus file baru pengguna:")
    appendLine("  git checkout -- app/src/main/java/com/kita/agent/")
    appendLine("  WITH_TESTS=1 bash build-termux.sh")
    appendLine()
    appendLine("Opsi 2 — Pasang Ulang APK Stabil Terakhir:")
    appendLine("  Buka aplikasi File Manager / Unduhan Android lalu pasang ulang:")
    appendLine("  • ~/storage/downloads/agentapp-1.1.apk, atau")
    appendLine("  • APK terverifikasi di folder .kita_self_heal/artifacts/")
}

/**
 * Susun instruksi tugas terstruktur untuk AgentLoop saat pengguna memilih "Perbaiki Otomatis (Self-Heal)".
 */
internal fun buildSelfHealingAgentPrompt(
    crash: CrashReport,
    analysis: RootCauseAnalysis,
    remainingAttempts: Int
): String = buildString {
    appendLine("[SELF-HEALING RECOVERY MODE]")
    appendLine("Laporan crash terdeteksi saat aplikasi dibuka kembali:")
    appendLine("- ID Crash: ${crash.id}")
    appendLine("- Exception: ${crash.exceptionType}: ${crash.message}")
    if (crash.suspectFile.isNotBlank()) {
        appendLine("- Lokasi Terbukti: ${crash.suspectFile}:${crash.suspectLine} (${crash.suspectMethod})")
    }
    appendLine("- Analisis Akar Masalah (RCA): ${analysis.summary}")
    appendLine("- Sisa Batas Percobaan Perbaikan: $remainingAttempts dari $MAX_REPAIR_ATTEMPTS")
    appendLine()
    appendLine("ATURAN WAJIB SELF-HEALING:")
    appendLine("1. Periksa git status -s dan baca file penyebab (${crash.suspectFile.ifBlank { "terkait" }}) sebelum mengubah kode. Jangan menimpa perubahan lokal pengguna yang tidak terkait.")
    appendLine("2. Buat cadangan/checkpoint sebelum menerapkan patch terbatas hanya pada akar masalah yang terbukti.")
    appendLine("3. Jalankan validasi wajib: $DEFAULT_VALIDATION_CMD")
    appendLine("4. Jika validasi gagal setelah maksimal $remainingAttempts percobaan, pulihkan checkpoint stabil (rollback) dan laporkan hasilnya secara jujur.")
    appendLine("5. Jika validasi berhasil, tampilkan ringkasan diff dan hasil pengujian untuk persetujuan pengguna sebelum memasang APK.")
}
