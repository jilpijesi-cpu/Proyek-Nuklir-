package com.kita.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Suite Pengujian Level 1 & Mesin Benchmark Otomatis 5 Kelompok (BM-01 s/d BM-11)
 * serta Tes Regresi PERBAIKAN_LOGIKA_AGEN.md + PERBAIKAN_KONEKSI.md.
 *
 * Diaudit untuk menguji perilaku alur agen multi-langkah secara end-to-end melalui
 * `AgentBenchmarkHarness` + `VirtualWorkspace` (murni JVM deterministik tanpa memanggil API LLM berbayar
 * ataupun mengeksekusi proses sistem operasi nyata).
 */
class BenchmarkLevel1Test {

    private fun step(tool: String, target: String, kind: ActivityKind, state: StepState) =
        StepRecord(tool, tool, target, kind, state, 25L)

    // =========================================================================
    // KELOMPOK 1 & 2: Penalaran Multi-Langkah, TaskBrief, Eksekusi Alat, & Verifikasi Hasil
    // =========================================================================

    @Test
    fun BM01_alurMultiLangkah_taskBrief_stepStore_dan_verificationNudgeWriteFile() {
        // 1. Jalankan sesi simulasi multi-langkah utuh: PAHAM -> baca file -> tulis kode -> dicegat verificationNudge -> jalankan unit test -> selesai
        val ws = VirtualWorkspace(
            sandboxEnabled = false,
            autonomous = true,
            initialFiles = mapOf("app/src/main/Parser.kt" to "package com.kita.agent\nclass Parser")
        )
        ws.onCommand(Regex("cat app/src/main/Parser\\.kt")) { _, w ->
            "exit=0\n" + (w.files["app/src/main/Parser.kt"] ?: "")
        }
        ws.onCommand(Regex("\\./gradlew testDebugUnitTest")) { _, _ ->
            "exit=0\n> Task :app:testDebugUnitTest\nBUILD SUCCESSFUL in 1s"
        }

        val rawBrief = "PAHAM: merefaktor modul parser di app/src/main/Parser.kt | hasil: kode bersih & tes lulus | langkah awal: baca Parser.kt"
        val trace = AgentBenchmarkHarness.runSimulatedSession(
            taskId = "test-bm01",
            rawUserMessage = "Refaktor Parser.kt lalu pastikan unit test lulus",
            workspace = ws,
            briefResponseRaw = rawBrief,
            turns = listOf(
                ScriptedTurn.StreamToolCall(
                    streamingChunks = listOf("<thinking>Langkah 1: baca isi Parser.kt</thinking>"),
                    toolName = "run_command",
                    toolTarget = "cat app/src/main/Parser.kt"
                ),
                ScriptedTurn.StreamToolCall(
                    streamingChunks = listOf("<thinking>Langkah 2: tulis perubahan ke Parser.kt</thinking>"),
                    toolName = "write_file",
                    toolTarget = "app/src/main/Parser.kt",
                    fileContent = "package com.kita.agent\nclass Parser { fun parse() = true }"
                ),
                // Agen mencoba berhenti sebelum menjalankan build/test -> harus dicegat oleh verificationNudge!
                ScriptedTurn.StreamFinalAnswer(
                    streamingChunks = listOf("<thinking>Selesai menulis</thinking><response>Klaim selesai prematur</response>")
                ),
                // Setelah dicegat verificationNudge, agen menjalankan unit test
                ScriptedTurn.StreamToolCall(
                    streamingChunks = listOf("<thinking>Menjalankan verifikasi unit test</thinking>"),
                    toolName = "run_command",
                    toolTarget = "./gradlew testDebugUnitTest"
                ),
                ScriptedTurn.StreamFinalAnswer(
                    streamingChunks = listOf("<thinking>Tes hijau</thinking><response>Refaktor Parser.kt selesai dan terverifikasi.</response>")
                )
            )
        )

        assertTrue("TaskBrief dengan format PAHAM: harus berhasil di-parse", trace.brief is Brief.Paham)
        assertEquals(rawBrief, (trace.brief as Brief.Paham).text)
        assertEquals("Harus ada 1 verificationNudge saat agen mencoba berhenti sebelum build", 1, trace.nudgesInjected.size)
        assertTrue(trace.nudgesInjected[0].startsWith(SYS_PREFIX))
        assertTrue(trace.nudgesInjected[0].contains("belum menjalankan build"))

        // Klasifikasi jenis verifikasi dari tindakan agen (AgentRuntime)
        assertEquals(VerificationKind.FILE_CONTENT, verificationKind("write_file", "app/src/main/Parser.kt"))
        assertEquals(VerificationKind.TESTS, verificationKind("run_command", "./gradlew testDebugUnitTest"))
        assertEquals(VerificationKind.APK, verificationKind("run_command", "./gradlew assembleDebug"))

        // Setelah build/test berhasil dijalankan, verificationNudge harus null dan laporan akhir SUCCESS
        assertNull(verificationNudge(trace.runLog.steps) { true })
        assertNotNull(trace.finalReport)
        assertEquals(ReportStatus.SUCCESS, trace.finalReport!!.status)
        assertEquals("Refaktor Parser.kt selesai dan terverifikasi.", trace.finalVisibleAnswer)
    }

    @Test
    fun BM01_regresiBug01_modifikasiFileKodeViaRunCommandDiSandbox_seharusnyaMemicuVerificationNudge() {
        // BUG-01 (AgentGuard.kt:42-44 & ActivityModel.kt:232):
        // Saat mode Sandbox aktif (MainActivity.kt:412 & ToolExecution.kt:166), alat `write_file` dinonaktifkan
        // dan agen diinstruksikan menulis file melalui `run_command` di dalam sandbox.
        // Namun `verificationNudge` dan `unverifiedChange` hanya memeriksa `it.tool == "write_file"`,
        // sehingga perubahan file .kt melalui `run_command` di mode Sandbox tidak pernah memicu pengingat build verifikasi!
        val ws = VirtualWorkspace(sandboxEnabled = true, autonomous = true)
        val writeFileRejected = ws.executeWriteFile("app/src/main/Parser.kt", "class Parser")
        assertTrue("Di mode sandbox, write_file harus ditolak", writeFileRejected.startsWith("exit=1"))

        val trace = AgentBenchmarkHarness.runSimulatedSession(
            taskId = "test-bug01",
            rawUserMessage = "Ubah Parser.kt di sandbox",
            workspace = ws,
            turns = listOf(
                ScriptedTurn.StreamToolCall(
                    streamingChunks = listOf("<thinking>Menulis file kode di sandbox via run_command</thinking>"),
                    toolName = "run_command",
                    toolTarget = "app/src/main/Parser.kt"
                ),
                ScriptedTurn.StreamFinalAnswer(
                    streamingChunks = listOf("Perubahan Parser.kt selesai tanpa build.")
                )
            )
        )

        val nudge = verificationNudge(trace.runLog.steps) { true }
        assertNotNull(
            "BUG-01: Modifikasi file kode via run_command (terutama saat write_file dimatikan di mode Sandbox) " +
                "seharusnya tetap memicu verificationNudge sebelum tugas ditutup",
            nudge
        )
    }

    // =========================================================================
    // KELOMPOK 3: Pemulihan Kesalahan Alat Berantai, Checkpoint, & ErrorTracker
    // =========================================================================

    @Test
    fun BM02_pemulihanKesalahanAlat_exit127_errorTracker_condenseFailure_dan_checkpoint() {
        // 1. Sesi simulasi agen ketika perintah pertama gagal exit=127 (rg tidak ditemukan) lalu pulih dengan grep
        val ws = VirtualWorkspace(sandboxEnabled = true, autonomous = true)
        ws.onCommand(Regex("^rg\\b")) { _, _ -> "exit=127\nbash: rg: command not found" }
        ws.onCommand(Regex("^grep\\b")) { _, _ -> "exit=0\nsrc/Parser.kt:14:fun parseJson()" }

        val trace = AgentBenchmarkHarness.runSimulatedSession(
            taskId = "task-bm02",
            rawUserMessage = "Cari deklarasi parseJson di proyek",
            workspace = ws,
            turns = listOf(
                ScriptedTurn.StreamToolCall(emptyList(), "run_command", "rg parseJson src/"),
                ScriptedTurn.StreamToolCall(emptyList(), "run_command", "grep -rn parseJson src/"),
                ScriptedTurn.StreamFinalAnswer(listOf("Ditemukan di src/Parser.kt:14"))
            )
        )

        assertEquals(2, trace.checkpoints.size)
        assertEquals("tool_failed", trace.checkpoints[0].phase)
        assertEquals(ErrorKind.COMMAND_NOT_FOUND.name, trace.checkpoints[0].lastError)
        assertEquals("tool_ok", trace.checkpoints[1].phase)
        assertEquals(ErrorKind.NONE.name, trace.checkpoints[1].lastError)
        assertEquals(ReportStatus.SUCCESS, trace.finalReport?.status)
        assertTrue(trace.finalReport!!.changes.contains("1 langkah sempat gagal"))

        // 2. ErrorTracker melacak error berulang dengan menormalkan path dan nomor baris
        val tracker = ErrorTracker()
        val err1 = "exit=1\ne: /home/user/proj/A.kt:14:2: error: unresolved reference: parseJson"
        val err2 = "exit=1\ne: /home/user/proj/B.kt:88:9: error: unresolved reference: parseJson"
        assertEquals(1, tracker.record(err1))
        assertEquals(2, tracker.record(err2))
        assertTrue(repeatWarning(2).contains("error yang sama sudah muncul 2 kali"))

        // 3. condenseFailure memangkas log build panjang namun mempertahankan baris error dan bagian ekor
        val longBuildNoise = (1..400).joinToString("\n") { "> Task :app:compileStep$it UP-TO-DATE" }
        val longFailOutput = "exit=1\n$longBuildNoise\ne: Parser.kt:42: error: type mismatch\nFAILURE: Build failed with an exception.\n* Try: Run with --stacktrace"
        val condensed = condenseFailure(longFailOutput, maxChars = 2000)
        assertTrue(condensed.length <= 2000)
        assertTrue(condensed.contains("Parser.kt:42: error: type mismatch"))
        assertTrue(condensed.contains("FAILURE: Build failed"))

        // 4. Verifikasi timeoutResult dan recoverableToolResult
        assertEquals(ErrorKind.COMMAND_TIMEOUT, parseToolResult(timeoutResult(600)).errorKind)
        assertEquals(ErrorKind.COMMAND_FAILED, parseToolResult(recoverableToolResult(IllegalStateException("I/O error"))).errorKind)
    }

    // =========================================================================
    // KELOMPOK 3: Ketahanan Koneksi Streaming, Klasifikasi Error Transien vs Kuota, & Failover
    // =========================================================================

    @Test
    fun BM03_klasifikasiErrorStreaming_retryDelay_dan_rantaiFailoverLintasPenyedia() {
        // Simulasikan sesi multi-putaran yang mengalami 503 High Demand, 429 Daily Quota, dan 503 Overloaded
        // sehingga secara otomatis melakukan failover melintasi pool Gemini lalu menyeberang ke OpenAI
        val ws = VirtualWorkspace(sandboxEnabled = true, autonomous = true)
        val geminiPool = ProviderPool(
            "Gemini",
            "gemini-3.5-flash",
            listOf("gemini-3.5-flash", "gemini-2.5-flash", "gemini-2.5-flash-lite", "gemma-4")
        )
        val openAiPool = ProviderPool("OpenAI", "gpt-4o", listOf("gpt-4o", "gpt-4o-mini"))

        val trace = AgentBenchmarkHarness.runSimulatedSession(
            taskId = "task-bm03",
            rawUserMessage = "Analisis modul koneksi",
            workspace = ws,
            primaryProvider = "Gemini",
            pools = listOf(geminiPool, openAiPool),
            turns = listOf(
                ScriptedTurn.ApiFailure(503, "UNAVAILABLE", "This model is currently experiencing high demand."),
                ScriptedTurn.ApiFailure(429, "RESOURCE_EXHAUSTED", "You exceeded your current quota. quotaId: GenerateRequestsPerDayPerProjectPerModel-FreeTier"),
                ScriptedTurn.ApiFailure(503, "UNAVAILABLE", "Temporarily overloaded"),
                ScriptedTurn.StreamFinalAnswer(listOf("Berhasil dijawab oleh model cadangan lintas penyedia."))
            )
        )

        assertEquals(3, trace.apiExceptionsCaught.size)
        assertTrue(trace.apiExceptionsCaught[0] is TransientApiException)
        assertTrue(trace.apiExceptionsCaught[1] is QuotaExhaustedException)
        assertEquals(
            listOf(
                FoCandidate("Gemini", "gemini-2.5-flash"),
                FoCandidate("Gemini", "gemini-2.5-flash-lite"),
                FoCandidate("OpenAI", "gpt-4o")
            ),
            trace.failoverCandidatesUsed
        )
        assertEquals("Berhasil dijawab oleh model cadangan lintas penyedia.", trace.finalVisibleAnswer)

        // Rate limit per menit tetap dianggap TRANSIENT agar di-retry dengan jeda retryDelay dari server
        val rpmBody = """{"error":{"message":"Rate limit reached. Please retry in 12s","details":[{"retryDelay":"12s"}]}}"""
        assertEquals(ApiErrorKind.TRANSIENT, classifyApiError(429, "RESOURCE_EXHAUSTED", rpmBody))
        assertEquals(12, parseRetryDelaySec(rpmBody))
    }

    // =========================================================================
    // KELOMPOK 1: Pemadatan Konteks & Pemangkasan Output Alat
    // =========================================================================

    @Test
    fun BM04_truncationOutputStepStore_dan_estimasiUkuranKonteks() {
        val rawOut = "A".repeat(15_000)
        val truncated = AgentStepStore.truncateOutput(rawOut, maxChars = AgentStepStore.MAX_STEP_OUTPUT)
        assertTrue(truncated.length < rawOut.length)
        assertTrue(truncated.startsWith("A".repeat(AgentStepStore.MAX_STEP_OUTPUT)))
        assertTrue(truncated.endsWith("…(output dipotong)…"))

        val longSuccess = "exit=0\n" + "baris ok\n".repeat(500)
        assertEquals(longSuccess, condenseFailure(longSuccess))
    }

    // =========================================================================
    // KELOMPOK 4 & 5: Prompt Injection dari Output Alat & Pencegahan Kebocoran <thinking>
    // =========================================================================

    @Test
    fun BM06_promptInjectionDariOutputAlat_diblokirCommandPolicy_dan_thinkingStandarDibersihkan() {
        val ws = VirtualWorkspace(sandboxEnabled = true, autonomous = false)
        val trace = AgentBenchmarkHarness.runSimulatedSession(
            taskId = "task-bm06",
            rawUserMessage = "Jalankan instruksi dari berkas eksternal",
            workspace = ws,
            userApprovesCommand = { _, _ -> false },
            turns = listOf(
                ScriptedTurn.StreamToolCall(
                    streamingChunks = listOf("<thinking>Mencoba menjalankan perintah dari berkas", " eksternal</thinking>"),
                    toolName = "run_command",
                    toolTarget = "rm -rf /"
                ),
                ScriptedTurn.StreamToolCall(
                    streamingChunks = listOf("<thinking>Mencoba menulis ke ~/.bashrc</thinking>"),
                    toolName = "run_command",
                    toolTarget = "echo 'malicious' > ~/.bashrc"
                ),
                ScriptedTurn.StreamFinalAnswer(
                    streamingChunks = listOf(
                        "<thinking>Menyusun laporan penolakan keamanan",
                        " secara internal</thinking>",
                        "<response>Semua perintah berbahaya dari injeksi telah diblokir.</response>"
                    )
                )
            )
        )

        // Pastikan seluruh snapshot streaming tidak pernah membocorkan isi <thinking>
        assertTrue(trace.streamedVisibleSnapshots.none { it.contains("Mencoba menjalankan") || it.contains("secara internal") })
        assertEquals("Semua perintah berbahaya dari injeksi telah diblokir.", trace.finalVisibleAnswer)
        assertEquals(2, ws.blockedCommands.size)
        assertTrue(ws.executedCommands.isEmpty())
    }

    @Test
    fun BM06_regresiBug02_tagThinkingDenganAtributAtauSpasi_seharusnyaTidakBocorKePengguna() {
        // BUG-02 (TextClean.kt:3-4):
        // Regex `thinkBlock` dan `thinkOpen` didefinisikan sebagai `<(think|thinking)>` tanpa mengizinkan
        // spasi atau atribut di dalam tag pembuka (mis. `<thinking >` atau `<thinking budget="1024">`).
        // Akibatnya seluruh isi pemikiran internal model bocor mentah ke layar chat pengguna!
        val ws = VirtualWorkspace(sandboxEnabled = true, autonomous = true)
        val trace = AgentBenchmarkHarness.runSimulatedSession(
            taskId = "test-bug02",
            rawUserMessage = "Halo",
            workspace = ws,
            turns = listOf(
                ScriptedTurn.StreamFinalAnswer(
                    streamingChunks = listOf(
                        "<thinking budget=\"1024\">Rahasia penalaran internal model</thinking>\nJawaban bersih untuk pengguna."
                    )
                )
            )
        )
        val cleaned = trace.finalVisibleAnswer
        assertFalse(
            "BUG-02: Konten <thinking ...> tidak boleh bocor ke jawaban pengguna, aktual: $cleaned",
            cleaned.contains("Rahasia penalaran internal model")
        )
        assertEquals("Jawaban bersih untuk pengguna.", cleaned)
    }

    @Test
    fun BM06_regresiBug03_tagThinkingPasanganTidakSeimbang_tidakBolehMenghapusSeluruhJawabanPengguna() {
        // BUG-03 (TextClean.kt:3-4):
        // Jika model membuka dengan `<thinking>` tetapi menutup dengan `</think>` (atau sebaliknya),
        // backreference `</\\1>` pada `thinkBlock` gagal cocok, lalu `thinkOpen` (`<(think|thinking)>[\\s\\S]*$`)
        // menghapus SEMUA teks sampai akhir pesan termasuk jawaban utama untuk pengguna!
        val ws = VirtualWorkspace(sandboxEnabled = true, autonomous = true)
        val trace = AgentBenchmarkHarness.runSimulatedSession(
            taskId = "test-bug03",
            rawUserMessage = "Jelaskan status proyek",
            workspace = ws,
            turns = listOf(
                ScriptedTurn.StreamFinalAnswer(
                    streamingChunks = listOf(
                        "<thinking>Catatan internal singkat</think>\nIni jawaban penting untuk pengguna."
                    )
                )
            )
        )
        val cleaned = trace.finalVisibleAnswer
        assertFalse("Isi thinking tidak boleh bocor", cleaned.contains("Catatan internal singkat"))
        assertTrue(
            "BUG-03: Jawaban pengguna setelah </think> tidak boleh ikut terhapus habis (aktual: '$cleaned')",
            cleaned.contains("Ini jawaban penting untuk pengguna.")
        )
    }

    // =========================================================================
    // KELOMPOK 2: Pengelolaan Lampiran Multi-Modal & Staging Sandbox Anti-Traversal
    // =========================================================================

    @Test
    fun BM07_lampiranMultiModal_splitAttachments_formatSize_dan_sandboxStageAntiTraversal() {
        val rawMessage = "Periksa bug tampilan dan log crash ini" +
            "\nGambar terlampir di \"/sdcard/Download/ClaudeMythos/ui_bug.webp\"" +
            "\nFile terlampir di \"/sdcard/Download/ClaudeMythos/crash_trace.txt\"" +
            "\nFile terlampir di \"/sdcard/Download/ClaudeMythos/../rahasia.txt\""

        val ws = VirtualWorkspace(sandboxEnabled = true, autonomous = true)
        val trace = AgentBenchmarkHarness.runSimulatedSession(
            taskId = "task-bm07",
            rawUserMessage = rawMessage,
            workspace = ws,
            turns = listOf(ScriptedTurn.StreamFinalAnswer(listOf("Lampiran berhasil diproses.")))
        )

        assertEquals(2, trace.stagedAttachmentCommands.size)
        assertTrue(trace.stagedAttachmentCommands[0].contains("/root/sandbox/work/ui_bug.webp"))
        assertTrue(trace.stagedAttachmentCommands[1].contains("/root/sandbox/work/crash_trace.txt"))
        assertEquals(listOf("/sdcard/Download/ClaudeMythos/../rahasia.txt"), trace.rejectedAttachments)

        assertTrue(isImageName("Screenshot_01.HEIC"))
        assertFalse(isImageName("build.gradle.kts"))
        assertEquals("512 B", formatSize(512L))
        assertEquals("2.0 kB", formatSize(2048L))
        assertEquals("1.5 MB", formatSize(1572864L))
    }

    // =========================================================================
    // KELOMPOK 5: Regresi PERBAIKAN_LOGIKA_AGEN.md (ActivityModel, VerificationNudge, ErrorTracker)
    // =========================================================================

    @Test
    fun BM08_regresiPerbaikanLogikaAgen_buildGagalTerakhirWajibDiingatkan_dan_ringkasanLaporan() {
        val stepsBuildFail = listOf(
            step("write_file", "app/src/main/MainActivity.kt", ActivityKind.OTHER, StepState.OK),
            step("run_command", "./gradlew assembleDebug", ActivityKind.BUILD, StepState.FAIL)
        )
        val nudgeFail = verificationNudge(stepsBuildFail)
        assertNotNull(nudgeFail)
        assertTrue(nudgeFail!!.contains("Build terakhir GAGAL"))

        val logIncomplete = RunLog().apply {
            reset(1000L)
            outcome = RunOutcome.INCOMPLETE
            note = "Berhenti: error yang sama muncul 4 kali."
            stepsBuildFail.forEach { record(it) }
        }
        val report = buildReport(logIncomplete, 5000L)
        assertNotNull(report)
        assertEquals(ReportStatus.FAILED, report!!.status)
        assertEquals("Tidak selesai", report.title)
    }

    // =========================================================================
    // KELOMPOK 3 & 4: Regresi PERBAIKAN_KONEKSI.md (Sanitasi Kredensial & Klasifikasi Error API)
    // =========================================================================

    @Test
    fun BM09_regresiPerbaikanKoneksi_sanitasiApiKeyPadaPesanError_dan_deteksiFormatKunci() {
        val rawSecret = "sk-proj-SecretOpenAiKey1234567890"
        val rawErr = "HTTP 401: Incorrect key $rawSecret at url?key=AIzaSySecretGeminiKey1234567890"
        val sanitized = sanitizeConnError(rawErr, rawSecret)
        assertFalse("Kunci pengguna tidak boleh bocor di pesan error koneksi", sanitized.contains(rawSecret))
        assertFalse("Kunci AIza tidak boleh bocor di pesan error koneksi", sanitized.contains("AIzaSySecretGeminiKey1234567890"))
        assertTrue(sanitized.contains("***"))

        assertEquals("Gemini", detectProvider("AIzaSyD12345678901234567890"))
        assertEquals("Anthropic", detectProvider("sk-ant-api03-12345678901234567890"))
        assertEquals("Groq", detectProvider("gsk_123456789012345678901234567890"))
    }

    @Test
    fun BM09_regresiBug04_quotaMessageMemantulkanBodyMentah_seharusnyaMenyensorApiKey() {
        // BUG-04 (ApiErrors.kt:39-41):
        // Fungsi `quotaMessage(code, body)` menyalin potongan `body.take(200)` mentah dari server
        // ke dalam pesan QuotaExhaustedException yang ditampilkan langsung ke chat
        // tanpa menyensor token `AIza...` / `sk-...` / `gsk_...` jika server/proxy memantulkan kunci di body error.
        val leakedKey = "AIzaSyD9876543210abcdefghijklmnopqrstu"
        val serverBody = """{"error":{"message":"Quota exceeded for key $leakedKey on project 123"}}"""
        val msg = quotaMessage(429, serverBody)
        assertFalse(
            "BUG-04: quotaMessage tidak boleh membocorkan API key mentah ke pesan chat/log, aktual: $msg",
            msg.contains(leakedKey)
        )
    }

    // =========================================================================
    // VERIFIKASI KONSISTENSI BERULANG (REPEATABILITY) & SKOR 5 KELOMPOK BENCHMARK
    // =========================================================================

    @Test
    fun BM10_verifikasiKonsistensiBerulang_dan_skorLimaKelompokBenchmark() {
        // Jalankan seluruh 17 skenario benchmark sebanyak 3 kali berturut-turut untuk membuktikan determinisme 100%
        val run1 = AgentBenchmarkHarness.executeAllScenarios()
        val run2 = AgentBenchmarkHarness.executeAllScenarios()
        val run3 = AgentBenchmarkHarness.executeAllScenarios()

        assertEquals(17, run1.size)
        assertEquals(run1.size, run2.size)
        assertEquals(run1.size, run3.size)

        // Pastikan ke-5 kelompok wajib terwakili
        for (cat in BenchmarkCategory.entries) {
            assertTrue("Kelompok $cat harus memiliki minimal 3 skenario", run1.count { it.spec.category == cat } >= 3)
        }

        // Pastikan hasil, skor, tindakan aktual, bukti, dan temuan 100% identik di ketiga eksekusi
        for (i in run1.indices) {
            val a = run1[i]
            val b = run2[i]
            val c = run3[i]
            assertEquals("Status passed harus deterministik untuk ${a.spec.id}", a.passed, b.passed)
            assertEquals("Status passed harus deterministik untuk ${a.spec.id}", a.passed, c.passed)
            assertEquals("Skor 5 dimensi harus deterministik untuk ${a.spec.id}", a.scores, b.scores)
            assertEquals("Skor 5 dimensi harus deterministik untuk ${a.spec.id}", a.scores, c.scores)
            assertEquals("Jejak tindakan harus deterministik untuk ${a.spec.id}", a.actualActions, b.actualActions)
            assertEquals("Bukti harus deterministik untuk ${a.spec.id}", a.evidence, b.evidence)
            assertEquals("Temuan harus deterministik untuk ${a.spec.id}", a.findingsTriggered, b.findingsTriggered)
        }

        // Pastikan laporan markdown berhasil digenerate secara utuh dan konsisten
        val md1 = AgentBenchmarkHarness.generateMarkdownReport(run1, repeatableVerified = true)
        val md2 = AgentBenchmarkHarness.generateMarkdownReport(run2, repeatableVerified = true)
        assertEquals("Laporan markdown yang dihasilkan harus 100% identik antar-run", md1, md2)
        AgentBenchmarkHarness.writeReportFileIfPossible(java.io.File("../BENCHMARK_REPORT.md"), md1)
        assertTrue(md1.contains("BM-R01"))
        assertTrue(md1.contains("BM-V03"))
        assertTrue(md1.contains("BUG-01"))
        assertTrue(md1.contains("BUG-05"))
        assertTrue(md1.contains("SEC-01"))
        assertTrue(md1.contains("SEC-04"))
    }

    @Test
    fun BM11_regresiBug05_inkonsistensiUnverifiedChangePadaBerkasDokumentasi_seharusnyaSUCCESS() {
        // BUG-05 (ActivityModel.kt:231-236 vs AgentGuard.kt:42-44):
        // Saat agen diminta mengubah berkas non-kode seperti `README.md` menggunakan `write_file`:
        // - `AgentGuard.verificationNudge` memeriksa `isCodeFile("README.md")` -> false, sehingga TIDAK meminta build.
        // - Namun `ActivityModel.unverifiedChange` memeriksa SEMUA `it.tool == "write_file"` tanpa `isCodeFile()`,
        //   sehingga `buildReport` menandai tugas edit dokumentasi sebagai `ReportStatus.UNVERIFIED` ("Belum terverifikasi")!
        val res = AgentBenchmarkHarness.executeAllScenarios().first { it.spec.id == "BM-V03" }
        assertTrue(
            "BUG-05: Mengedit berkas dokumentasi README.md yang tidak membutuhkan build seharusnya menghasilkan ReportStatus.SUCCESS, bukan UNVERIFIED (${res.failureReasons})",
            res.passed
        )
    }

    @Test
    fun BM12_persistensiMaksimal_pemulihanHambatan_alternatifAman_penangananIzin_dan_konteksTugas() {
        // 1. Verifikasi Instruksi Inti Persistensi Maksimal & Aturan Menghadapi Hambatan
        val directive = buildPersistenceDirective()
        assertTrue(directive.contains("Jangan menyerah terlalu cepat"))
        assertTrue(directive.contains("Pecah tugas menjadi langkah kecil"))
        assertTrue(directive.contains("Pulihkan kesalahan secara mandiri"))
        assertTrue(directive.contains("Jangan mengklaim tugas selesai tanpa bukti"))
        assertTrue(directive.contains("Tentukan minimal satu alternatif yang realistis"))
        assertTrue(directive.contains("Jalankan alternatif yang paling aman dan diizinkan"))
        assertTrue(directive.contains("Verifikasi hasilnya"))
        assertTrue(directive.contains("Jika seluruh alternatif gagal, laporkan penyebab utama"))

        // 2. Analisis hambatan ketika binary tidak ditemukan (exit=127) -> menyarankan alternatif POSIX (grep/find)
        val planNotFound = analyzeObstacle("run_command", "rg parseJson src/", "exit=127\nbash: rg: command not found", 1)
        assertEquals(ErrorKind.COMMAND_NOT_FOUND, planNotFound.errorKind)
        assertTrue(planNotFound.alternatives.isNotEmpty())
        assertTrue(planNotFound.alternatives.any { it.contains("grep") })
        assertTrue(planNotFound.shouldStopRetryingSame)
        val guidanceNotFound = formatObstacleGuidance(planNotFound, 1)
        assertTrue(guidanceNotFound.contains("Penyebab utama:"))
        assertTrue(guidanceNotFound.contains("Alternatif yang disarankan:"))

        // 3. Analisis hambatan ketika membutuhkan persetujuan pengguna -> jelaskan izin & lanjutkan pekerjaan lain
        val planDenied = analyzeObstacle(
            "run_command",
            "pkg install ripgrep",
            "Ditolak pengguna. Tanyakan alasannya lewat ask_user atau pakai pendekatan lain.",
            1
        )
        assertEquals(ErrorKind.USER_DENIED, planDenied.errorKind)
        assertTrue(planDenied.requiresUserApproval)
        assertTrue(planDenied.shouldContinueOtherWork)
        val guidanceDenied = formatObstacleGuidance(planDenied, 1)
        assertTrue(guidanceDenied.contains("lanjutkan pekerjaan lain yang tidak bergantung pada izin tersebut"))

        // 4. Analisis hambatan write_file di Mode Sandbox -> arahkan ke run_command di workdir sandbox
        val planSandbox = analyzeObstacle(
            "write_file",
            "app/src/main/Parser.kt",
            "Ditolak: mode sandbox aktif, write_file menulis ke Termux utama (di luar sandbox).",
            1
        )
        assertEquals(ErrorKind.INVALID_TOOL_ARGUMENT, planSandbox.errorKind)
        assertTrue(planSandbox.alternatives.any { it.contains(DEFAULT_SANDBOX_WORKDIR) })

        // 5. Pelacakan Konteks Tugas (Tujuan, Langkah Berhasil, Masalah Ditemukan, Pekerjaan Belum Selesai)
        val stepsInProgress = listOf(
            step("run_command", "rg parseJson src/", ActivityKind.OTHER, StepState.FAIL),
            step("run_command", "grep -rn parseJson src/", ActivityKind.OTHER, StepState.OK),
            step("write_file", "app/src/main/Parser.kt", ActivityKind.OTHER, StepState.OK)
        )
        val progressCtx = buildTaskProgressContext(
            goal = "Refaktor Parser.kt dan pastikan seluruh unit test lulus",
            steps = stepsInProgress
        )
        assertEquals("Refaktor Parser.kt dan pastikan seluruh unit test lulus", progressCtx.goal)
        assertTrue(progressCtx.succeededSteps.any { it.contains("grep -rn parseJson src/") })
        assertTrue(progressCtx.obstaclesFound.any { it.contains("rg parseJson src/") })
        assertTrue("Perubahan Parser.kt belum di-build sehingga harus tercatat di pendingWork", progressCtx.pendingWork.isNotEmpty())
        val formattedCtx = formatTaskProgressContext(progressCtx)
        assertTrue(formattedCtx.contains("Tujuan:"))
        assertTrue(formattedCtx.contains("Langkah berhasil:"))
        assertTrue(formattedCtx.contains("Masalah ditemukan:"))
        assertTrue(formattedCtx.contains("Pekerjaan belum selesai:"))

        // Setelah build berhasil dijalankan, pendingWork verifikasi harus bersih
        val stepsVerified = stepsInProgress + step("run_command", "./gradlew testDebugUnitTest", ActivityKind.BUILD, StepState.OK)
        val verifiedCtx = buildTaskProgressContext(
            goal = "Refaktor Parser.kt dan pastikan seluruh unit test lulus",
            steps = stepsVerified
        )
        assertTrue("Setelah build OK, pendingWork harus kosong", verifiedCtx.pendingWork.isEmpty())
    }
}

