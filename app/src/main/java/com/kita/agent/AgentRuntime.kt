package com.kita.agent

/** State tunggal agen. */
internal sealed interface AgentState {
    data object Idle : AgentState
    data object Planning : AgentState
    data class CallingModel(val attempt: Int) : AgentState
    data class AwaitingApproval(val tool: String) : AgentState
    data class RunningTool(val tool: String) : AgentState
    data object Verifying : AgentState
    data class Completed(val report: Report?) : AgentState
    data class Failed(val message: String) : AgentState
    data object Cancelled : AgentState
}

internal enum class ErrorKind {
    NONE, NETWORK_TRANSIENT, NETWORK_AUTH, MODEL_RATE_LIMIT, INVALID_TOOL_ARGUMENT,
    USER_DENIED, TERMUX_UNAVAILABLE, COMMAND_NOT_FOUND, COMMAND_TIMEOUT, COMMAND_FAILED,
    VERIFICATION_FAILED, CANCELLED, UNKNOWN
}

internal data class ToolResult(
    val exitCode: Int? = null,
    val stdout: String = "",
    val stderr: String = "",
    val timedOut: Boolean = false,
    val cancelled: Boolean = false
) {
    val ok: Boolean get() = !timedOut && !cancelled && exitCode == 0
    val errorKind: ErrorKind get() = when {
        cancelled -> ErrorKind.CANCELLED
        timedOut -> ErrorKind.COMMAND_TIMEOUT
        ok -> ErrorKind.NONE
        exitCode == 401 || exitCode == 403 -> ErrorKind.NETWORK_AUTH
        exitCode == 408 || exitCode == 429 || exitCode == 500 || exitCode == 502 || exitCode == 503 || exitCode == 504 || exitCode == 529 -> ErrorKind.NETWORK_TRANSIENT
        exitCode == 124 -> ErrorKind.COMMAND_TIMEOUT
        exitCode == 126 || exitCode == 127 -> ErrorKind.COMMAND_NOT_FOUND
        else -> ErrorKind.COMMAND_FAILED
    }

}

internal fun parseToolResult(raw: String): ToolResult {
    val first = raw.lineSequence().firstOrNull().orEmpty()
    val exit = Regex("^exit=(-?\\d+)").find(first)?.groupValues?.get(1)?.toIntOrNull()
    val body = raw.substringAfter('\n', "")
    val timedOut = exit == 124 || first.contains("Timeout", ignoreCase = true)
    return ToolResult(exitCode = exit, stdout = body, timedOut = timedOut)
}

/** Perintah dengan efek samping tidak diulang tanpa bukti aman/idempotent. */
internal fun retryPolicy(tool: String, command: String, result: ToolResult): Boolean = when {
    result.cancelled || result.errorKind == ErrorKind.USER_DENIED -> false
    tool == "write_file" -> false
    command.contains(Regex("\\b(rm|rmdir|unlink|shred|truncate|dd|mkfs)\\b")) -> false
    result.timedOut -> false
    result.errorKind == ErrorKind.NETWORK_TRANSIENT -> true
    else -> false
}

internal enum class VerificationKind { NONE, FILE_CONTENT, APK, TESTS, INSTALL, DOWNLOAD, PROCESS }

internal fun verificationKind(tool: String, command: String): VerificationKind = when {
    tool == "write_file" -> VerificationKind.FILE_CONTENT
    command.contains(Regex("\\b(apksigner|aapt2?|zipalign)\\b|\\bassemble\\w*\\b")) -> VerificationKind.APK
    command.contains(Regex("\\bgradlew?\\b.*\\b(test\\w*|check|lint\\w*|connected\\w*)\\b|\\b(pytest|ctest|ktlint|(npm|yarn|pnpm)( run)? test|cargo test|go test|make test)\\b")) -> VerificationKind.TESTS
    command.contains(Regex("\\b(install|pm install)\\b")) -> VerificationKind.INSTALL
    command.contains(Regex("\\b(curl|wget|aria2c|git clone)\\b")) -> VerificationKind.DOWNLOAD
    else -> VerificationKind.NONE
}

internal data class AgentCheckpoint(
    val taskId: String,
    val userMessage: String,
    val turn: Int,
    val lastTool: String,
    val phase: String,
    val lastError: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)
