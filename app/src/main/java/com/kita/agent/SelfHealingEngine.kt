package com.kita.agent

import java.io.File
import java.security.MessageDigest

/**
 * Mesin inti Self-Healing Recovery (murni Kotlin tanpa dependensi Android runtime sehingga
 * dapat diuji penuh secara deterministik di JVM unit test).
 *
 * Mencakup:
 * 1. Sanitasi rahasia/kredensial (API key, token, password) dari laporan crash & log.
 * 2. Pencatatan & pemulihan laporan crash (CrashReport & CrashReportStore) + deteksi crash loop.
 * 3. Root Cause Analysis (RCA) berbasis bukti dari stacktrace, keluaran kompiler/lint/test, dan log langkah.
 * 4. Checkpoint sumber daya, perlindungan perubahan lokal pengguna, batas percobaan (Retry Limit),
 *    validasi terintegrasi (./gradlew lint testDebugUnitTest assembleDebug --no-daemon), serta Rollback aman.
 * 5. Penyimpanan artefak APK terverifikasi beserta ringkasan diff & hasil pengujian untuk persetujuan pengguna.
 */

internal const val MAX_REPAIR_ATTEMPTS = 3
internal const val CRASH_LOOP_WINDOW_MS = 120_000L
internal const val CRASH_LOOP_THRESHOLD = 3
internal const val DEFAULT_VALIDATION_CMD = "./gradlew lint testDebugUnitTest assembleDebug --no-daemon"

private val SECRET_PATTERNS = listOf(
    Regex("""(?i)\b(Bearer\s+)[A-Za-z0-9._\-+/=]{8,}"""),
    Regex("""\b(sk-ant-[A-Za-z0-9_\-]{6,}|sk-or-[A-Za-z0-9_\-]{6,}|sk-[A-Za-z0-9_\-]{12,}|gsk_[A-Za-z0-9_\-]{8,}|AIza[A-Za-z0-9_\-]{8,}|nvapi-[A-Za-z0-9_\-]{8,}|enc1:[A-Za-z0-9+/=_.\-]{8,})"""),
    Regex("""(?i)\b(password|passwd|pwd|storePassword|keyPassword|api[_-]?key|secret|token|authorization|x-api-key|x-goog-api-key)\s*([=:]\s*)([^\s,;'"}\]\n]+)""")
)

/**
 * Hapus kata sandi, token, API key, dan rahasia lainnya dari teks diagnostik/log.
 */
internal fun redactSecrets(raw: String, extraSecrets: Collection<String> = emptyList()): String {
    if (raw.isEmpty()) return ""
    var cleaned = raw
    for (secret in extraSecrets) {
        val trimmed = secret.trim()
        if (trimmed.length >= 4) {
            cleaned = cleaned.replace(trimmed, "***REDACTED***")
        }
        val norm = normalizeKey(trimmed)
        if (norm.length >= 4) {
            cleaned = cleaned.replace(norm, "***REDACTED***")
        }
    }
    cleaned = SECRET_PATTERNS[0].replace(cleaned) { "${it.groupValues[1]}***REDACTED***" }
    cleaned = SECRET_PATTERNS[1].replace(cleaned, "***REDACTED***")
    cleaned = SECRET_PATTERNS[2].replace(cleaned) { "${it.groupValues[1]}${it.groupValues[2]}***REDACTED***" }
    return cleaned
}

internal data class CrashReport(
    val id: String,
    val timestamp: Long,
    val threadName: String,
    val exceptionType: String,
    val message: String,
    val stackTrace: String,
    val suspectFile: String = "",
    val suspectLine: Int = 0,
    val suspectMethod: String = "",
    val lastTaskId: String = "",
    val lastPhase: String = "",
    val lastTool: String = "",
    val appVersion: String = "",
    val sdkInt: Int = 0,
    val crashCountInWindow: Int = 1,
    val resolved: Boolean = false
) {
    val isCrashLoop: Boolean get() = crashCountInWindow >= CRASH_LOOP_THRESHOLD
}

private val APP_FRAME_RE = Regex("""at\s+(com\.kita\.agent\.[A-Za-z0-9_$.]+)\(([A-Za-z0-9_]+\.(?:kt|java)):(\d+)\)""")
private val KOTLIN_ERR_RE = Regex("""e:\s*(?:file://)?([^\s:]+\.(?:kt|java|kts)):(\d+)(?::\d+)?:\s*(.+)""")
private val JAVAC_ERR_RE = Regex("""([^\s:]+\.(?:kt|java|kts)):(\d+):\s*error:\s*(.+)""")
private val TEST_FAIL_RE = Regex("""at\s+com\.kita\.agent\.([A-Za-z0-9_$]+)\.([A-Za-z0-9_$]+)\(([A-Za-z0-9_]+\.(?:kt|java)):(\d+)\)""")

internal fun extractAppStackFrame(stackTrace: String): Triple<String, String, Int>? {
    val m = APP_FRAME_RE.find(stackTrace) ?: return null
    val method = m.groupValues[1]
    val file = m.groupValues[2]
    val line = m.groupValues[3].toIntOrNull() ?: 0
    return Triple(method, file, line)
}

internal fun buildCrashReport(
    throwable: Throwable,
    threadName: String = Thread.currentThread().name,
    checkpoint: AgentCheckpoint? = null,
    appVersion: String = "2.6",
    sdkInt: Int = 34,
    nowMs: Long = System.currentTimeMillis(),
    crashCountInWindow: Int = 1,
    extraSecrets: Collection<String> = emptyList()
): CrashReport {
    val rawStack = throwable.stackTraceToString().take(8_000)
    val safeStack = redactSecrets(rawStack, extraSecrets)
    val safeMsg = redactSecrets(throwable.message ?: throwable.javaClass.simpleName, extraSecrets).take(500)
    val frame = extractAppStackFrame(safeStack)
    return CrashReport(
        id = "crash_${nowMs}_${throwable.javaClass.simpleName}",
        timestamp = nowMs,
        threadName = threadName.take(80),
        exceptionType = throwable.javaClass.name,
        message = safeMsg,
        stackTrace = safeStack,
        suspectFile = frame?.second.orEmpty(),
        suspectLine = frame?.third ?: 0,
        suspectMethod = frame?.first.orEmpty(),
        lastTaskId = checkpoint?.taskId.orEmpty(),
        lastPhase = checkpoint?.phase.orEmpty(),
        lastTool = redactSecrets(checkpoint?.lastTool.orEmpty(), extraSecrets).take(120),
        appVersion = appVersion,
        sdkInt = sdkInt,
        crashCountInWindow = crashCountInWindow,
        resolved = false
    )
}

internal fun isCrashLoop(timestamps: List<Long>, nowMs: Long, windowMs: Long = CRASH_LOOP_WINDOW_MS): Boolean {
    val recent = timestamps.count { nowMs - it in 0..windowMs }
    return recent >= CRASH_LOOP_THRESHOLD
}

/**
 * Penyimpanan laporan crash di direktori internal aplikasi (tanpa ketergantungan org.json Android).
 */
internal class CrashReportStore(private val baseDir: File) {
    private val reportFile get() = File(baseDir, "self_heal_crash.kv")
    private val historyFile get() = File(baseDir, "self_heal_crash_timestamps.txt")

    @Synchronized
    fun recordCrash(
        throwable: Throwable,
        threadName: String = Thread.currentThread().name,
        checkpoint: AgentCheckpoint? = null,
        appVersion: String = "2.6",
        sdkInt: Int = 34,
        nowMs: Long = System.currentTimeMillis(),
        extraSecrets: Collection<String> = emptyList()
    ): CrashReport {
        baseDir.mkdirs()
        val prevTimestamps = loadRecentTimestamps(nowMs)
        val updatedTimestamps = (prevTimestamps + nowMs).takeLast(20)
        historyFile.writeText(updatedTimestamps.joinToString("\n"))
        val inWindow = updatedTimestamps.count { nowMs - it in 0..CRASH_LOOP_WINDOW_MS }
        val report = buildCrashReport(
            throwable = throwable,
            threadName = threadName,
            checkpoint = checkpoint,
            appVersion = appVersion,
            sdkInt = sdkInt,
            nowMs = nowMs,
            crashCountInWindow = inWindow.coerceAtLeast(1),
            extraSecrets = extraSecrets
        )
        saveReport(report)
        return report
    }

    @Synchronized
    fun saveReport(report: CrashReport) {
        baseDir.mkdirs()
        val tmp = File(baseDir, "self_heal_crash.kv.tmp")
        tmp.writeText(serializeCrashReport(report))
        if (!tmp.renameTo(reportFile)) {
            reportFile.writeText(tmp.readText())
            tmp.delete()
        }
    }

    @Synchronized
    fun loadPendingCrash(): CrashReport? {
        if (!reportFile.isFile) return null
        return runCatching {
            deserializeCrashReport(reportFile.readText())
        }.getOrNull()?.takeIf { !it.resolved && it.exceptionType.isNotBlank() }
    }

    @Synchronized
    fun markResolved(crashId: String? = null) {
        val current = loadPendingCrash() ?: return
        if (crashId != null && current.id != crashId) return
        saveReport(current.copy(resolved = true))
    }

    @Synchronized
    fun clearAll() {
        runCatching { reportFile.delete() }
        runCatching { historyFile.delete() }
    }

    @Synchronized
    fun loadRecentTimestamps(nowMs: Long = System.currentTimeMillis()): List<Long> {
        if (!historyFile.isFile) return emptyList()
        return runCatching {
            historyFile.readLines()
                .mapNotNull { it.trim().toLongOrNull() }
                .filter { nowMs - it <= CRASH_LOOP_WINDOW_MS * 5 }
        }.getOrDefault(emptyList())
    }
}

private fun escapeKv(v: String): String =
    v.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")

private fun unescapeKv(v: String): String {
    val sb = StringBuilder(v.length)
    var i = 0
    while (i < v.length) {
        val c = v[i]
        if (c == '\\' && i + 1 < v.length) {
            when (val n = v[i + 1]) {
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                '\\' -> sb.append('\\')
                else -> sb.append(n)
            }
            i += 2
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}

internal fun serializeCrashReport(r: CrashReport): String = buildString {
    appendLine("id=${escapeKv(r.id)}")
    appendLine("timestamp=${r.timestamp}")
    appendLine("threadName=${escapeKv(r.threadName)}")
    appendLine("exceptionType=${escapeKv(r.exceptionType)}")
    appendLine("message=${escapeKv(r.message)}")
    appendLine("stackTrace=${escapeKv(r.stackTrace)}")
    appendLine("suspectFile=${escapeKv(r.suspectFile)}")
    appendLine("suspectLine=${r.suspectLine}")
    appendLine("suspectMethod=${escapeKv(r.suspectMethod)}")
    appendLine("lastTaskId=${escapeKv(r.lastTaskId)}")
    appendLine("lastPhase=${escapeKv(r.lastPhase)}")
    appendLine("lastTool=${escapeKv(r.lastTool)}")
    appendLine("appVersion=${escapeKv(r.appVersion)}")
    appendLine("sdkInt=${r.sdkInt}")
    appendLine("crashCountInWindow=${r.crashCountInWindow}")
    appendLine("resolved=${r.resolved}")
}

internal fun deserializeCrashReport(text: String): CrashReport {
    val map = HashMap<String, String>()
    for (line in text.lineSequence()) {
        val idx = line.indexOf('=')
        if (idx > 0) {
            map[line.substring(0, idx).trim()] = unescapeKv(line.substring(idx + 1))
        }
    }
    return CrashReport(
        id = map["id"].orEmpty(),
        timestamp = map["timestamp"]?.toLongOrNull() ?: 0L,
        threadName = map["threadName"].orEmpty(),
        exceptionType = map["exceptionType"].orEmpty(),
        message = map["message"].orEmpty(),
        stackTrace = map["stackTrace"].orEmpty(),
        suspectFile = map["suspectFile"].orEmpty(),
        suspectLine = map["suspectLine"]?.toIntOrNull() ?: 0,
        suspectMethod = map["suspectMethod"].orEmpty(),
        lastTaskId = map["lastTaskId"].orEmpty(),
        lastPhase = map["lastPhase"].orEmpty(),
        lastTool = map["lastTool"].orEmpty(),
        appVersion = map["appVersion"].orEmpty(),
        sdkInt = map["sdkInt"]?.toIntOrNull() ?: 0,
        crashCountInWindow = map["crashCountInWindow"]?.toIntOrNull() ?: 1,
        resolved = map["resolved"]?.toBooleanStrictOrNull() ?: false
    )
}

internal enum class RootCauseCategory {
    UNCAUGHT_EXCEPTION,
    COMPILE_ERROR,
    TEST_FAILURE,
    LINT_FAILURE,
    CRASH_LOOP,
    INSUFFICIENT_EVIDENCE
}

internal data class RootCauseAnalysis(
    val category: RootCauseCategory,
    val targetFile: String,
    val targetLine: Int,
    val summary: String,
    val evidence: List<String>,
    val canAutoRepair: Boolean
)

/**
 * Analisis akar masalah berbasis bukti nyata (stacktrace, log kompilasi/pengujian, atau langkah gagal).
 * Tidak pernah mengizinkan perbaikan acak bila lokasi file atau penyebab tidak dapat dibuktikan.
 */
internal fun analyzeRootCause(
    crash: CrashReport? = null,
    validationOutput: String = "",
    recentSteps: List<StepRecord> = emptyList()
): RootCauseAnalysis {
    val evidence = ArrayList<String>()
    val cleanValidation = redactSecrets(validationOutput)

    if (crash != null && crash.isCrashLoop) {
        evidence.add("Terdeteksi ${crash.crashCountInWindow} crash beruntun dalam jendela waktu singkat (${crash.exceptionType}).")
        if (crash.suspectFile.isNotBlank()) {
            evidence.add("Lokasi crash berulang: ${crash.suspectFile}:${crash.suspectLine} (${crash.suspectMethod})")
        }
        return RootCauseAnalysis(
            category = RootCauseCategory.CRASH_LOOP,
            targetFile = crash.suspectFile,
            targetLine = crash.suspectLine,
            summary = "Loop crash berulang terdeteksi (${crash.crashCountInWindow}x): ${crash.exceptionType}. Perbaikan otomatis dihentikan untuk mencegah kerusakan berulang.",
            evidence = evidence,
            canAutoRepair = false
        )
    }

    KOTLIN_ERR_RE.find(cleanValidation)?.let { m ->
        val file = m.groupValues[1]
        val line = m.groupValues[2].toIntOrNull() ?: 0
        val msg = m.groupValues[3].trim()
        evidence.add("Kompiler melaporkan error di $file:$line -> $msg")
        return RootCauseAnalysis(
            category = RootCauseCategory.COMPILE_ERROR,
            targetFile = file,
            targetLine = line,
            summary = "Error kompilasi pada ${file.substringAfterLast('/')}:$line — $msg",
            evidence = evidence,
            canAutoRepair = file.isNotBlank() && line > 0
        )
    }

    JAVAC_ERR_RE.find(cleanValidation)?.let { m ->
        val file = m.groupValues[1]
        val line = m.groupValues[2].toIntOrNull() ?: 0
        val msg = m.groupValues[3].trim()
        evidence.add("Javac/Gradle melaporkan error di $file:$line -> $msg")
        return RootCauseAnalysis(
            category = RootCauseCategory.COMPILE_ERROR,
            targetFile = file,
            targetLine = line,
            summary = "Error kompilasi pada ${file.substringAfterLast('/')}:$line — $msg",
            evidence = evidence,
            canAutoRepair = file.isNotBlank() && line > 0
        )
    }

    if (cleanValidation.contains("FAILED", ignoreCase = true) || cleanValidation.contains("AssertionError")) {
        val tm = TEST_FAIL_RE.find(cleanValidation)
        if (tm != null) {
            val cls = tm.groupValues[1]
            val method = tm.groupValues[2]
            val file = tm.groupValues[3]
            val line = tm.groupValues[4].toIntOrNull() ?: 0
            evidence.add("Unit test gagal pada $cls.$method ($file:$line)")
            return RootCauseAnalysis(
                category = RootCauseCategory.TEST_FAILURE,
                targetFile = file,
                targetLine = line,
                summary = "Kegagalan pengujian pada $cls.$method ($file:$line)",
                evidence = evidence,
                canAutoRepair = file.isNotBlank() && line > 0
            )
        }
    }

    if (cleanValidation.contains("Lint found errors", ignoreCase = true)) {
        evidence.add("Pemeriksaan Android Lint menemukan error pada proyek.")
        return RootCauseAnalysis(
            category = RootCauseCategory.LINT_FAILURE,
            targetFile = crash?.suspectFile.orEmpty(),
            targetLine = crash?.suspectLine ?: 0,
            summary = "Pemeriksaan Android Lint gagal pada proyek",
            evidence = evidence,
            canAutoRepair = !crash?.suspectFile.isNullOrBlank()
        )
    }

    if (crash != null && crash.suspectFile.isNotBlank() && crash.suspectLine > 0) {
        evidence.add("Exception: ${crash.exceptionType}: ${crash.message}")
        evidence.add("Frame aplikasi: ${crash.suspectMethod} (${crash.suspectFile}:${crash.suspectLine})")
        if (crash.lastTool.isNotBlank()) {
            evidence.add("Alat terakhir saat kejadian: ${crash.lastTool} (fase: ${crash.lastPhase})")
        }
        return RootCauseAnalysis(
            category = RootCauseCategory.UNCAUGHT_EXCEPTION,
            targetFile = crash.suspectFile,
            targetLine = crash.suspectLine,
            summary = "${crash.exceptionType.substringAfterLast('.')} di ${crash.suspectFile}:${crash.suspectLine} — ${crash.message}",
            evidence = evidence,
            canAutoRepair = true
        )
    }

    val failedBuildStep = recentSteps.lastOrNull { it.state == StepState.FAIL }
    if (failedBuildStep != null) {
        evidence.add("Langkah gagal terakhir: ${failedBuildStep.tool} (${failedBuildStep.target})")
    }
    if (crash != null) {
        evidence.add("Exception tanpa lokasi baris kode aplikasi: ${crash.exceptionType}: ${crash.message}")
    }

    return RootCauseAnalysis(
        category = RootCauseCategory.INSUFFICIENT_EVIDENCE,
        targetFile = "",
        targetLine = 0,
        summary = "Bukti lokasi kode sumber belum cukup untuk perbaikan otomatis; diperlukan pemeriksaan manual.",
        evidence = evidence,
        canAutoRepair = false
    )
}
