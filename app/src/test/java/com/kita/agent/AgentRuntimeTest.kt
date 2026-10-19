package com.kita.agent

import org.junit.Assert.*
import org.junit.Test

class AgentRuntimeTest {
    @Test fun timeoutTidakBolehDiulangOtomatis() {
        val r = ToolResult(exitCode = 124, timedOut = true)
        assertEquals(ErrorKind.COMMAND_TIMEOUT, r.errorKind)
        assertFalse(retryPolicy("run_command", "./gradlew build", r))
    }

    @Test fun networkTransientBolehDiulang() {
        val r = ToolResult(exitCode = 503)
        assertEquals(ErrorKind.NETWORK_TRANSIENT, r.errorKind)
        assertTrue(retryPolicy("model", "POST", r))
    }

    @Test fun writeDanDeleteTidakBolehDiulang() {
        assertFalse(retryPolicy("write_file", "", ToolResult(exitCode = 1)))
        assertFalse(retryPolicy("run_command", "rm -rf build", ToolResult(exitCode = 1)))
    }

    @Test fun hasilToolDiparseTerstruktur() {
        val r = parseToolResult("exit=1\nerror: file tidak ditemukan")
        assertEquals(1, r.exitCode)
        assertEquals("error: file tidak ditemukan", r.stdout)
        assertEquals(ErrorKind.COMMAND_FAILED, parseToolResult("exit=1\nerror").errorKind)
    }

    @Test fun jenisVerifikasiMengikutiJenisTindakan() {
        assertEquals(VerificationKind.FILE_CONTENT, verificationKind("write_file", ""))
        assertEquals(VerificationKind.APK, verificationKind("run_command", "./gradlew assembleDebug"))
        assertEquals(VerificationKind.TESTS, verificationKind("run_command", "./gradlew testDebugUnitTest"))
        assertEquals(VerificationKind.DOWNLOAD, verificationKind("run_command", "curl -O https://example.com/a"))
        assertEquals(VerificationKind.NONE, verificationKind("run_command", "git checkout main"))
        assertEquals(VerificationKind.TESTS, verificationKind("run_command", "pytest -q"))
    }

    @Test fun exit127AdalahPerintahTidakDitemukanBukanTermuxHilang() {
        assertEquals(ErrorKind.COMMAND_NOT_FOUND, parseToolResult("exit=127\nbash: foo: command not found").errorKind)
        assertEquals(ErrorKind.COMMAND_NOT_FOUND, parseToolResult("exit=126\nPermission denied").errorKind)
    }

    @Test fun stateMencakupSiklusUtamaAgen() {
        val states: List<AgentState> = listOf(
            AgentState.Idle, AgentState.Planning, AgentState.CallingModel(1),
            AgentState.AwaitingApproval("run_command"), AgentState.RunningTool("run_command"),
            AgentState.Verifying, AgentState.Completed(null), AgentState.Failed("x"), AgentState.Cancelled
        )
        assertEquals(9, states.size)
    }
}
