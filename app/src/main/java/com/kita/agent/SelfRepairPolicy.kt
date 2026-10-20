package com.kita.agent

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fitur "Perbaiki Diri" (Self-Modification / Self-Repair dengan pengaman ketat).
 *
 * Berisi:
 * 1. Daftar eksplisit FILE TERLINDUNG di satu tempat (murni JVM, dapat diuji unit test).
 * 2. Validasi working tree bersih, pembuatan branch `self/<tanggal>-<ringkasan>`, larangan commit langsung ke `main`.
 * 3. Pengaman batas maksimal 5 percobaan (`MAX_SELF_REPAIR_ATTEMPTS = 5`) terintegrasi dengan `AgentGuard`.
 * 4. Penaikan `versionCode`, pencegahan perubahan `applicationId` atau konfigurasi signing/keystore.
 * 5. Cadangan APK versi berjalan (maksimal 3 versi terakhir) dan tombol "Kembali ke versi sebelumnya".
 * 6. Penanda "boot ok" setelah versi baru berjalan normal 1 menit + tombol "Gabungkan ke main",
 *    serta saran kembali ke versi sebelumnya bila aplikasi crash 2 kali berturut-turut sejak pemasangan.
 */

const val MAX_SELF_REPAIR_ATTEMPTS = 5
const val MAX_APK_BACKUPS = 3
const val BOOT_OK_DELAY_MS = 60_000L
const val SELF_REPAIR_CRASH_THRESHOLD = 2

const val PREF_SELF_REPAIR_MODE = "self_repair_mode"
const val PREF_SELF_PENDING_BRANCH = "self_pending_branch"
const val PREF_SELF_PENDING_COMMIT = "self_pending_commit"
const val PREF_SELF_BOOT_OK = "self_boot_ok"
const val PREF_SELF_CRASH_COUNT = "self_crash_count"

enum class ProtectedFileVerdict {
    /** File biasa di dalam ruang kerja. */
    UNPROTECTED,
    /** File terlindung: perubahan wajib melewati dialog persetujuan khusus dengan diff lengkap (tanpa "Ya, semua"). */
    NEEDS_SPECIAL_APPROVAL,
    /** File rahasia/kunci signing (.env, keystore*): akses atau modifikasi DILARANG TOTAL (BLOCKED). */
    BLOCKED
}

/**
 * Daftar eksplisit FILE TERLINDUNG di satu tempat sesuai spesifikasi keamanan AgentApp.
 */
object ProtectedFilesRegistry {
    /** Nama file sumber inti keamanan & fitur Perbaiki Diri yang wajib lewat persetujuan diff khusus. */
    val PROTECTED_SOURCE_FILES: Set<String> = setOf(
        "CommandPolicy.kt",
        "AgentGuard.kt",
        "ApprovalDialog.kt",
        "ToolExecution.kt",
        "TermuxFiles.kt",
        "SandboxRunner.kt",
        "SandboxIntegration.kt",
        "Secret.kt",
        "ApkInstall.kt",
        // Fitur Ruang Kerja & Perbaiki Diri itu sendiri:
        "WorkspacePolicy.kt",
        "SelfRepairPolicy.kt",
        "SelfHealingEngine.kt",
        "SelfHealingController.kt",
        "SelfHealingIntegration.kt"
    )

    /** File konfigurasi build, manifest, dan signing yang wajib terlindung. */
    val PROTECTED_BUILD_CONFIG_FILES: Set<String> = setOf(
        "AndroidManifest.xml",
        "build.gradle",
        "build.gradle.kts",
        "settings.gradle",
        "settings.gradle.kts",
        "gradle.properties",
        "libs.versions.toml",
        "proguard-rules.pro",
        "build-termux.sh",
        "local.properties"
    )

    /** Direktori workflow CI/CD yang wajib terlindung. */
    const val WORKFLOWS_PATH_FRAGMENT = ".github/workflows"

    /**
     * Periksa apakah path menunjuk ke file `.env` atau `keystore*` / kunci signing yang wajib BLOCKED.
     */
    fun isBlockedSecretOrKeystore(rawPath: String): Boolean {
        val norm = rawPath.trim().replace('\\', '/').trimEnd('/')
        if (norm.isEmpty()) return false
        val fileName = norm.substringAfterLast('/').lowercase()
        if (fileName == ".env" || fileName.startsWith(".env.")) return true
        if (fileName.startsWith("keystore") || fileName.contains("keystore")) return true
        if (fileName.endsWith(".keystore") || fileName.endsWith(".jks") || fileName.endsWith(".p12") || fileName.endsWith(".pfx")) return true
        if (fileName == "signing.properties" || fileName == "signing.gradle" || fileName == "signing.gradle.kts") return true
        return false
    }

    /**
     * Klasifikasikan sebuah path terhadap daftar eksplisit File Terlindung.
     */
    fun classifyPath(rawPath: String): ProtectedFileVerdict {
        val norm = rawPath.trim().replace('\\', '/').trimEnd('/')
        if (norm.isEmpty()) return ProtectedFileVerdict.UNPROTECTED
        if (isBlockedSecretOrKeystore(norm)) return ProtectedFileVerdict.BLOCKED

        val fileName = norm.substringAfterLast('/')
        if ( PROTECTED_SOURCE_FILES.any { it.equals(fileName, ignoreCase = true) }) {
            return ProtectedFileVerdict.NEEDS_SPECIAL_APPROVAL
        }
        if (PROTECTED_BUILD_CONFIG_FILES.any { it.equals(fileName, ignoreCase = true) }) {
            return ProtectedFileVerdict.NEEDS_SPECIAL_APPROVAL
        }
        val cleanRel = norm.removePrefix("./").trimStart('/')
        if (cleanRel == WORKFLOWS_PATH_FRAGMENT ||
            cleanRel.startsWith("$WORKFLOWS_PATH_FRAGMENT/") ||
            norm.contains("/$WORKFLOWS_PATH_FRAGMENT/") ||
            norm.endsWith("/$WORKFLOWS_PATH_FRAGMENT")
        ) {
            return ProtectedFileVerdict.NEEDS_SPECIAL_APPROVAL
        }
        return ProtectedFileVerdict.UNPROTECTED
    }
}

fun classifyProtectedPath(rawPath: String): ProtectedFileVerdict =
    ProtectedFilesRegistry.classifyPath(rawPath)

fun isProtectedFile(rawPath: String): Boolean =
    ProtectedFilesRegistry.classifyPath(rawPath) != ProtectedFileVerdict.UNPROTECTED

fun isBlockedSecretOrKeystoreFile(rawPath: String): Boolean =
    ProtectedFilesRegistry.isBlockedSecretOrKeystore(rawPath)

private val APP_ID_LINE_RE = Regex("""^\s*applicationId\s*[= ]\s*["']([^"']+)["']""", RegexOption.MULTILINE)
private val SIGNING_KEYWORDS_RE = Regex(
    """\b(signingConfigs|storeFile|storePassword|keyAlias|keyPassword|signingConfig)\b"""
)

/**
 * Agen dilarang mengubah `applicationId` atau konfigurasi kunci signing.
 * Mengembalikan alasan penolakan berbahasa Indonesia bila terdeteksi perubahan terlarang, atau null bila aman.
 */
fun detectForbiddenGradleTampering(oldContent: String, newContent: String): String? {
    if (oldContent.isEmpty()) return null
    val oldAppId = APP_ID_LINE_RE.find(oldContent)?.groupValues?.get(1)
    val newAppId = APP_ID_LINE_RE.find(newContent)?.groupValues?.get(1)
    if (oldAppId != null && newAppId != oldAppId) {
        return "DILARANG: Agen tidak boleh mengubah applicationId ('$oldAppId' -> '${newAppId ?: "dihapus"}')."
    }
    val oldSigningLines = oldContent.lines().map { it.trim() }.filter { SIGNING_KEYWORDS_RE.containsMatchIn(it) }
    val newSigningLines = newContent.lines().map { it.trim() }.filter { SIGNING_KEYWORDS_RE.containsMatchIn(it) }
    if (oldSigningLines != newSigningLines) {
        return "DILARANG: Agen tidak boleh mengubah konfigurasi signing atau keystore di file build."
    }
    return null
}

/**
 * Periksa apakah working tree git bersih berdasarkan keluaran `git status --porcelain`.
 */
fun isWorkingTreeClean(termuxGitStatusOutput: String): Boolean {
    val (exitCode, body) = parseTermuxResult(termuxGitStatusOutput)
    if (exitCode != 0) return false
    return body.lines().all { it.isBlank() }
}

/**
 * Buat nama branch Perbaiki Diri dengan format `self/<tanggal>-<ringkasan>`.
 * Contoh: `self/20261020-perbaiki-header`
 */
fun buildSelfRepairBranchName(dateYmd: String, rawSummary: String): String {
    val cleanDate = dateYmd.filter { it.isDigit() }.take(8).ifEmpty {
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    }
    val slug = rawSummary.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(28)
        .trim('-')
        .ifEmpty { "perbaikan" }
    return "self/$cleanDate-$slug"
}

private val SELF_BRANCH_RE = Regex("^self/\\d{8}-[a-z0-9][a-z0-9-]{0,40}$")

fun isValidSelfRepairBranch(branch: String): Boolean {
    val b = branch.trim()
    if (b == "main" || b == "master") return false
    return SELF_BRANCH_RE.matches(b)
}

/**
 * Deteksi apakah perintah shell mencoba melakukan commit langsung ke branch `main`/`master`
 * atau menggabungkan (merge) ke `main` tanpa tombol persetujuan pengguna.
 */
fun isDirectMainCommitOrPush(cmd: String, activeBranch: String = ""): Boolean {
    val lower = cmd.lowercase(Locale.US)
    if (Regex("""\bgit\s+push\s+\S+\s+(HEAD:)?(main|master)\b""").containsMatchIn(lower)) return true
    if (Regex("""\bgit\s+checkout\s+(main|master)\b.*\bgit\s+(merge|commit)\b""").containsMatchIn(lower)) return true
    if ((activeBranch == "main" || activeBranch == "master") &&
        Regex("""\bgit\s+commit\b""").containsMatchIn(lower) &&
        !Regex("""\bgit\s+checkout\s+-b\s+self/""").containsMatchIn(lower) &&
        !Regex("""\bgit\s+switch\s+-c\s+self/""").containsMatchIn(lower)
    ) {
        return true
    }
    return false
}

/**
 * Naikkan `versionCode` sebesar +1 pada isi `app/build.gradle.kts` tanpa mengubah bagian lain.
 */
fun bumpVersionCodeInGradle(gradleContent: String): Pair<String, Int?> {
    val eqRegex = Regex("""(\bversionCode\s*=\s*)(\d+)""")
    val matchEq = eqRegex.find(gradleContent)
    if (matchEq != null) {
        val current = matchEq.groupValues[2].toIntOrNull() ?: return Pair(gradleContent, null)
        val next = current + 1
        val updated = gradleContent.replaceRange(matchEq.range, "${matchEq.groupValues[1]}$next")
        return Pair(updated, next)
    }
    val spaceRegex = Regex("""(\bversionCode\s+)(\d+)""")
    val matchSpace = spaceRegex.find(gradleContent)
    if (matchSpace != null) {
        val current = matchSpace.groupValues[2].toIntOrNull() ?: return Pair(gradleContent, null)
        val next = current + 1
        val updated = gradleContent.replaceRange(matchSpace.range, "${matchSpace.groupValues[1]}$next")
        return Pair(updated, next)
    }
    return Pair(gradleContent, null)
}

sealed interface SelfRepairAttemptDecision {
    data class Passed(val attemptNumber: Int) : SelfRepairAttemptDecision
    data class RetryAllowed(
        val attemptNumber: Int,
        val remainingAttempts: Int,
        val obstacleGuidance: String
    ) : SelfRepairAttemptDecision
    data class Exhausted(
        val attemptsUsed: Int,
        val failureReport: String
    ) : SelfRepairAttemptDecision
}

/**
 * Pengaman batas percobaan Perbaiki Diri (maksimal 5 percobaan) yang memakai `AgentGuard`
 * (`ErrorTracker` & `analyzeObstacle`) untuk menganalisis kegagalan `./gradlew testDebugUnitTest`
 * dan `./gradlew assembleDebug`.
 */
class SelfRepairRetryGuard(
    private val maxAttempts: Int = MAX_SELF_REPAIR_ATTEMPTS
) {
    private val errorTracker = ErrorTracker()
    var attemptsUsed: Int = 0
        private set
    val failureLogs = ArrayList<String>()

    fun canRetry(): Boolean = attemptsUsed < maxAttempts

    fun recordBuildTestAttempt(
        unitTestPassed: Boolean,
        assemblePassed: Boolean,
        rawOutput: String
    ): SelfRepairAttemptDecision {
        if (unitTestPassed && assemblePassed) {
            return SelfRepairAttemptDecision.Passed(attemptsUsed + 1)
        }
        attemptsUsed++
        val condensed = condenseFailure(rawOutput)
        failureLogs.add("Percobaan #$attemptsUsed: ${condensed.lineSequence().firstOrNull().orEmpty()}")
        val seenCount = errorTracker.record(rawOutput).coerceAtLeast(1)
        val failedStage = if (!unitTestPassed) "./gradlew testDebugUnitTest" else "./gradlew assembleDebug"
        val plan = analyzeObstacle("run_command", failedStage, rawOutput, seenCount)

        if (attemptsUsed >= maxAttempts) {
            val report = buildString {
                appendLine("Berhenti setelah $attemptsUsed/$maxAttempts percobaan Perbaiki Diri gagal.")
                appendLine("Tahap gagal terakhir: $failedStage")
                appendLine("Penyebab utama (${plan.errorKind}): ${plan.rootCause}")
                append("Riwayat: ${failureLogs.joinToString(" | ")}")
            }
            return SelfRepairAttemptDecision.Exhausted(attemptsUsed, report)
        }

        val remaining = maxAttempts - attemptsUsed
        val guidance = "Percobaan Perbaiki Diri #$attemptsUsed/$maxAttempts gagal pada `$failedStage` " +
            "(sisa $remaining percobaan)." + formatObstacleGuidance(plan, seenCount)
        return SelfRepairAttemptDecision.RetryAllowed(attemptsUsed, remaining, guidance)
    }
}

/**
 * Simpan APK versi yang sedang berjalan ke folder cadangan dan pangkas agar maksimal 3 versi terakhir.
 */
fun pruneApkBackups(backupDir: File, maxKeep: Int = MAX_APK_BACKUPS): List<File> {
    if (!backupDir.isDirectory) return emptyList()
    val apks = backupDir.listFiles { f -> f.isFile && f.name.endsWith(".apk") }
        ?.sortedByDescending { it.lastModified() }
        ?: return emptyList()
    if (apks.size > maxKeep) {
        for (old in apks.drop(maxKeep)) {
            old.delete()
        }
    }
    return backupDir.listFiles { f -> f.isFile && f.name.endsWith(".apk") }
        ?.sortedByDescending { it.lastModified() }
        ?: emptyList()
}

fun backupInstalledApk(
    runningSourceApk: File,
    backupDir: File,
    versionCode: Int,
    nowMs: Long = System.currentTimeMillis(),
    maxKeep: Int = MAX_APK_BACKUPS
): File? {
    if (!runningSourceApk.isFile || runningSourceApk.length() <= 0L) return null
    backupDir.mkdirs()
    val dest = File(backupDir, "agentapp_backup_v${versionCode}_$nowMs.apk")
    runningSourceApk.copyTo(dest, overwrite = true)
    dest.setLastModified(nowMs)
    pruneApkBackups(backupDir, maxKeep)
    return dest
}

data class SelfRepairBootState(
    val pendingBranch: String = "",
    val pendingCommit: String = "",
    val crashCountSinceInstall: Int = 0,
    val bootOk: Boolean = false
) {
    val shouldSuggestRollback: Boolean
        get() = pendingBranch.isNotBlank() && !bootOk && crashCountSinceInstall >= SELF_REPAIR_CRASH_THRESHOLD

    val canMergeToMain: Boolean
        get() = pendingBranch.isNotBlank() && bootOk
}

fun onSelfRepairApkInstallTriggered(branch: String, commitHash: String): SelfRepairBootState =
    SelfRepairBootState(
        pendingBranch = branch.trim(),
        pendingCommit = commitHash.trim(),
        crashCountSinceInstall = 0,
        bootOk = false
    )

fun onCrashSinceSelfRepairInstall(state: SelfRepairBootState): SelfRepairBootState =
    if (state.pendingBranch.isBlank() || state.bootOk) state
    else state.copy(crashCountSinceInstall = state.crashCountSinceInstall + 1)

fun onSelfRepairBootOkReached(state: SelfRepairBootState): SelfRepairBootState =
    if (state.pendingBranch.isBlank()) state
    else state.copy(bootOk = true, crashCountSinceInstall = 0)

data class SelfRepairCycleSummary(
    val userRequest: String,
    val branchName: String,
    val commitHash: String,
    val versionCode: Int?,
    val changedFiles: List<String>,
    val diffSummary: String,
    val testAndBuildSummary: String,
    val apkPathInTermux: String
)

// =========================================================================================
// Integrasi Mode "Perbaiki Diri" dengan MainActivity, CommandLog, ApkInstall, & Pengaturan
// =========================================================================================

internal fun MainActivity.selfRepairMode(): Boolean =
    prefs.getBoolean(PREF_SELF_REPAIR_MODE, false)

internal fun MainActivity.apkBackupDir(): File =
    File(filesDir, "apk_backups").apply { mkdirs() }

internal fun MainActivity.loadSelfRepairBootState(): SelfRepairBootState =
    SelfRepairBootState(
        pendingBranch = prefs.getString(PREF_SELF_PENDING_BRANCH, "") ?: "",
        pendingCommit = prefs.getString(PREF_SELF_PENDING_COMMIT, "") ?: "",
        crashCountSinceInstall = prefs.getInt(PREF_SELF_CRASH_COUNT, 0),
        bootOk = prefs.getBoolean(PREF_SELF_BOOT_OK, false)
    )

internal fun MainActivity.saveSelfRepairBootState(state: SelfRepairBootState) {
    prefs.edit()
        .putString(PREF_SELF_PENDING_BRANCH, state.pendingBranch)
        .putString(PREF_SELF_PENDING_COMMIT, state.pendingCommit)
        .putInt(PREF_SELF_CRASH_COUNT, state.crashCountSinceInstall)
        .putBoolean(PREF_SELF_BOOT_OK, state.bootOk)
        .apply()
}

/**
 * Catat satu siklus Perbaiki Diri ke CommandLog:
 * memuat permintaan, branch, hasil build dan tes, serta hash commit.
 */
internal fun MainActivity.logSelfRepairCycle(
    userRequest: String,
    branch: String,
    buildAndTestResult: String,
    commitHash: String,
    ok: Boolean
) {
    try {
        val desc = "Perbaiki Diri [branch=$branch, commit=${commitHash.ifBlank { "-" }}]: ${userRequest.take(120)}"
        CommandLog.append(
            this,
            LogEntry(
                time = System.currentTimeMillis(),
                kind = "perbaiki-diri",
                command = "branch=$branch commit=${commitHash.ifBlank { "none" }}",
                description = desc,
                risk = Risk.WRITE,
                mode = ApprovalMode.MANUAL,
                ok = ok,
                result = buildAndTestResult.take(280),
                exitCode = if (ok) 0 else 1,
                decision = if (ok) "lolos-uji-dan-build" else "gagal",
                sandbox = sandboxEnabled(),
                network = false
            )
        )
    } catch (_: Exception) {
    }
}

/**
 * Simpan APK aplikasi yang sedang berjalan saat ini ke folder cadangan (maksimal 3 versi terakhir)
 * sebelum memasang APK hasil Perbaiki Diri.
 */
internal fun MainActivity.backupCurrentlyRunningApk(): File? {
    return try {
        val src = File(applicationInfo.sourceDir)
        val pkg = packageManager.getPackageInfo(packageName, 0)
        val vCode = if (android.os.Build.VERSION.SDK_INT >= 28) pkg.longVersionCode.toInt() else @Suppress("DEPRECATION") pkg.versionCode
        backupInstalledApk(src, apkBackupDir(), vCode)
    } catch (_: Exception) {
        null
    }
}

/**
 * Kembali ke versi APK sebelumnya dari folder cadangan (maksimal 3 versi terakhir).
 */
internal fun MainActivity.restorePreviousApkVersion() {
    val backups = pruneApkBackups(apkBackupDir(), MAX_APK_BACKUPS)
    if (backups.isEmpty()) {
        toast("Belum ada APK versi sebelumnya di folder cadangan.")
        return
    }
    val fmt = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
    val labels = backups.map { f ->
        val sizeKb = f.length() / 1024
        "${f.name} ($sizeKb KB • ${fmt.format(Date(f.lastModified()))})"
    }.toTypedArray()

    AlertDialog.Builder(this)
        .setTitle("Kembali ke versi sebelumnya")
        .setItems(labels) { _, which ->
            val chosen = backups[which]
            try {
                val apkCacheDir = File(cacheDir, "apk").apply { mkdirs() }
                val staged = File(apkCacheDir, "rollback_${chosen.name}")
                chosen.copyTo(staged, overwrite = true)
                launchInstaller(staged)
            } catch (e: Exception) {
                toast("Gagal menyiapkan APK cadangan: ${(e.message ?: "").take(80)}")
            }
        }
        .setNegativeButton("Batal", null)
        .show()
}

/**
 * Gabungkan branch `self/<tanggal>-<ringkasan>` ke `main` HANYA setelah penanda "boot ok" tercapai
 * dan pengguna menekan tombol "Gabungkan ke main".
 */
internal fun MainActivity.mergeSelfBranchToMain(branch: String, onMerged: (() -> Unit)? = null) {
    if (!isValidSelfRepairBranch(branch)) {
        toast("Nama branch Perbaiki Diri tidak valid: $branch")
        return
    }
    AlertDialog.Builder(this)
        .setTitle("Gabungkan ke main?")
        .setMessage(
            "Versi dari branch '$branch' telah berjalan stabil (boot ok).\n\n" +
                "Gabungkan perubahan branch '$branch' ke branch 'main' di ruang kerja?"
        )
        .setNegativeButton("Batal", null)
        .setPositiveButton("Gabungkan ke main") { _, _ ->
            Thread {
                val cmd = "git checkout main && git merge --no-ff ${sh(branch)} -m ${sh("Merge $branch setelah verifikasi boot ok")}"
                val res = try {
                    termux(cmd, timeoutSec = 60)
                } catch (e: Exception) {
                    "gagal: ${e.message}"
                }
                val ok = res.startsWith("exit=0")
                logSelfRepairCycle(
                    userRequest = "Gabungkan $branch ke main",
                    branch = branch,
                    buildAndTestResult = if (ok) "Berhasil digabungkan ke main" else res.take(200),
                    commitHash = "merged",
                    ok = ok
                )
                runOnUiThread {
                    if (ok) {
                        saveSelfRepairBootState(SelfRepairBootState())
                        toast("Branch '$branch' berhasil digabungkan ke main")
                        onMerged?.invoke()
                    } else {
                        toast("Gagal menggabungkan ke main: " + parseTermuxResult(res).second.take(120))
                    }
                }
            }.start()
        }
        .show()
}

/**
 * Tampilkan kartu hasil "Perbaiki Diri" di ruang chat setelah `./gradlew testDebugUnitTest`
 * dan `./gradlew assembleDebug` lolos:
 * memuat branch, hash commit, daftar file yang berubah, ringkasan diff, serta tombol Pasang dan Tolak.
 */
internal fun MainActivity.addSelfRepairResultCard(summary: SelfRepairCycleSummary) = runOnUiThread {
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Palette.SURFACE, 16, Palette.DIVIDER)
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    card.addView(TextView(this).apply {
        text = "Perbaiki Diri Siap Dipasang"
        textSize = 15.5f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(Palette.INK)
    })

    val metaText = buildString {
        appendLine("Branch: ${summary.branchName}")
        appendLine("Commit: ${summary.commitHash.ifBlank { "-" }}${summary.versionCode?.let { " • versionCode: $it" } ?: ""}")
        appendLine("File berubah (${summary.changedFiles.size}): ${summary.changedFiles.joinToString(", ").ifEmpty { "-" }}")
    }.trim()

    card.addView(TextView(this).apply {
        text = metaText
        textSize = 12.5f
        typeface = Typeface.MONOSPACE
        setTextColor(Palette.INK_SOFT)
        setPadding(0, dp(6), 0, dp(6))
    })

    if (summary.diffSummary.isNotBlank()) {
        card.addView(TextView(this).apply {
            text = summary.diffSummary.take(1200)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Palette.INK)
            background = rounded(Palette.FIELD, 10)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setTextIsSelectable(true)
        })
    }

    val actions = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        setPadding(0, dp(12), 0, 0)
    }

    val rejectBtn = TextView(this).apply {
        text = "Tolak"
        textSize = 14f
        setTextColor(Palette.RED)
        background = pressable(rounded(Color.WHITE, 12, Palette.DIVIDER))
        setPadding(dp(18), dp(9), dp(18), dp(9))
    }

    val installBtn = TextView(this).apply {
        text = "Pasang"
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(Color.WHITE)
        background = pressable(rounded(Palette.BRAND, 12))
        setPadding(dp(20), dp(9), dp(20), dp(9))
    }

    rejectBtn.setOnClickListener {
        installBtn.isEnabled = false
        rejectBtn.isEnabled = false
        rejectBtn.text = "Ditolak"
        logSelfRepairCycle(
            userRequest = summary.userRequest,
            branch = summary.branchName,
            buildAndTestResult = "Ditolak pengguna sebelum pemasangan",
            commitHash = summary.commitHash,
            ok = false
        )
        toast("Pembaruan Perbaiki Diri ditolak. Branch ${summary.branchName} tidak dipasang.")
    }

    installBtn.setOnClickListener {
        // 1. Cadangkan APK versi yang sedang berjalan (maks 3 versi terakhir)
        backupCurrentlyRunningApk()
        // 2. Catat status boot pending agar setelah 1 menit berjalan normal ditulis "boot ok"
        //    dan jika crash 2x berturut-turut disarankan kembali ke versi sebelumnya.
        saveSelfRepairBootState(onSelfRepairApkInstallTriggered(summary.branchName, summary.commitHash))
        // 3. Jalankan pemasangan lewat ApkInstall (hanya setelah pengguna mengetuk Pasang)
        installApk(summary.apkPathInTermux, installBtn)
    }

    actions.addView(rejectBtn, LinearLayout.LayoutParams(WRAP, WRAP))
    actions.addView(installBtn, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(10) })
    card.addView(actions, LinearLayout.LayoutParams(MATCH, WRAP))

    insertBeforeStatus(card, LinearLayout.LayoutParams(MATCH, WRAP).apply {
        topMargin = dp(12)
        marginStart = dp(10)
        marginEnd = dp(10)
    })
    scrollDown()
}

/**
 * Dipanggil saat MainActivity dibuka:
 * - Jika aplikasi crash 2 kali berturut-turut sejak pemasangan Perbaiki Diri, tampilkan saran
 *   kembali ke versi sebelumnya.
 * - Jika ada pemasangan Perbaiki Diri yang sedang diuji, jadwalkan timer 1 menit; setelah berjalan
 *   normal 1 menit, tulis penanda "boot ok" dan tampilkan tombol "Gabungkan ke main".
 */
internal fun MainActivity.attachSelfRepairBootMonitor(parent: LinearLayout, insertIndex: Int = 1) {
    val state = loadSelfRepairBootState()
    if (state.pendingBranch.isBlank()) return

    if (state.shouldSuggestRollback) {
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xFFFFEBEE.toInt(), 14, Palette.RED)
            setPadding(dp(14), dp(10), dp(14), dp(10))

            addView(TextView(context).apply {
                text = "Saran Pemulihan: Aplikasi Crash ${state.crashCountSinceInstall}x Berturut-turut"
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(Palette.RED)
            })
            addView(TextView(context).apply {
                text = "Sejak memasang pembaruan dari branch '${state.pendingBranch}', aplikasi mengalami crash berulang. " +
                    "Disarankan segera kembali ke versi APK stabil sebelumnya."
                textSize = 12.5f
                setTextColor(Palette.INK)
                setPadding(0, dp(4), 0, dp(8))
            })
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                val dismissBtn = TextView(context).apply {
                    text = "Tutup"
                    textSize = 13f
                    setTextColor(Palette.INK_SOFT)
                    background = pressable(rounded(Color.WHITE, 10, Palette.DIVIDER))
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    setOnClickListener {
                        saveSelfRepairBootState(SelfRepairBootState())
                        parent.removeView(this@apply.parent as View)
                    }
                }
                val restoreBtn = TextView(context).apply {
                    text = "Kembali ke versi sebelumnya"
                    textSize = 13f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    background = pressable(rounded(Palette.RED, 10))
                    setPadding(dp(14), dp(6), dp(14), dp(6))
                    setOnClickListener { restorePreviousApkVersion() }
                }
                addView(dismissBtn, LinearLayout.LayoutParams(WRAP, WRAP))
                addView(restoreBtn, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
            }
            addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        val lp = LinearLayout.LayoutParams(MATCH, WRAP).apply {
            setMargins(dp(12), dp(6), dp(12), dp(2))
        }
        parent.addView(banner, insertIndex.coerceIn(0, parent.childCount), lp)
        return
    }

    fun showMergeBanner(branch: String) {
        if (isFinishing) return
        val existing = parent.findViewWithTag<View>("self_repair_boot_ok_banner")
        if (existing != null) return
        val banner = LinearLayout(this).apply {
            tag = "self_repair_boot_ok_banner"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(0xFFE8F5E9.toInt(), 14, Palette.GREEN)
            setPadding(dp(14), dp(10), dp(14), dp(10))

            addView(TextView(context).apply {
                text = "Versi baru stabil (boot ok): $branch"
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Palette.INK)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))

            val mergeBtn = TextView(context).apply {
                text = "Gabungkan ke main"
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = pressable(rounded(Palette.GREEN, 10))
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener {
                    mergeSelfBranchToMain(branch) {
                        parent.removeView(this@apply)
                    }
                }
            }
            addView(mergeBtn, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
        }
        val lp = LinearLayout.LayoutParams(MATCH, WRAP).apply {
            setMargins(dp(12), dp(6), dp(12), dp(2))
        }
        parent.addView(banner, insertIndex.coerceIn(0, parent.childCount), lp)
    }

    if (state.bootOk) {
        showMergeBanner(state.pendingBranch)
    } else {
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing) {
                val cur = loadSelfRepairBootState()
                if (cur.pendingBranch.isNotBlank() && !cur.bootOk) {
                    val updated = onSelfRepairBootOkReached(cur)
                    saveSelfRepairBootState(updated)
                    runCatching {
                        val markerFile = File(filesDir, "self_heal/boot_ok").apply { parentFile?.mkdirs() }
                        markerFile.writeText("boot ok\nbranch=${updated.pendingBranch}\ncommit=${updated.pendingCommit}\ntime=${System.currentTimeMillis()}\n")
                    }
                    showMergeBanner(updated.pendingBranch)
                }
            }
        }, BOOT_OK_DELAY_MS)
    }
}

/**
 * Instruksi sistem tambahan ketika Mode Perbaiki Diri diaktifkan pengguna di Pengaturan.
 */
internal fun buildSelfRepairSystemPrompt(workspacePath: String): String =
    "\nMODE PERBAIKI DIRI AKTIF:\n" +
        "Pengguna mengizinkanmu mengubah kode sumber aplikasi AgentApp yang berada di ruang kerja ($workspacePath) dengan aturan ketat:\n" +
        "1. Sebelum mengubah file apa pun, jalankan `git status --porcelain` untuk memastikan working tree bersih, lalu buat branch baru bernama `self/<YYYYMMDD>-<ringkasan-singkat>` (contoh: `git checkout -b self/20261020-perbaiki-fitur`).\n" +
        "2. Ubah kode HANYA di branch `self/...` tersebut. DILARANG commit atau merge langsung ke branch `main`.\n" +
        "3. DILARANG mengubah `applicationId`, konfigurasi signing, atau mengakses `keystore*` / `.env`.\n" +
        "4. Setelah mengubah kode, WAJIB jalankan `./gradlew testDebugUnitTest` lalu `./gradlew assembleDebug` (maksimal $MAX_SELF_REPAIR_ATTEMPTS percobaan perbaikan bila gagal).\n" +
        "5. Jika kedua pemeriksaan lolos, naikkan `versionCode` di `app/build.gradle.kts`, lakukan `git commit` di branch `self/...`, dan laporkan ringkasan diff serta daftar file yang berubah agar pengguna dapat meninjau kartu Pasang / Tolak."
