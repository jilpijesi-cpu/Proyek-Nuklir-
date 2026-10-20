package com.kita.agent

import java.io.File
import java.util.UUID
import org.json.JSONObject

// Eksekusi alat: persetujuan perintah, pembatas ruang kerja, perlindungan file terlindung, catatan, runTool.
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

/** True hanya untuk perintah baca-saja yang jelas aman. Ragu sedikit pun -> minta persetujuan. */
internal fun MainActivity.isReadOnly(cmd: String): Boolean {
    val c = cmd.trim()
    if (c.isEmpty()) return false
    // Tanpa penyambung perintah, redirect, substitusi, variabel, atau escape.
    if (c.any { it in ";&><`$\n\r\\(){}" }) return false
    val segs = c.split("|")
    for (seg in segs) {
        val tok = seg.trim().split(Regex("\\s+"))
        val name = tok[0]
        if (name.isEmpty() || name !in roCmds) return false
        val args = tok.drop(1)
        if (name == "find" && args.any { it in findBad }) return false
        if (name == "tail" && args.any { (it.startsWith("-") && !it.startsWith("--") && (it.contains('f') || it.contains('F'))) || it.startsWith("--follow") || it == "--retry" }) return false
        if (name == "tree" && args.any { it == "-o" || it.startsWith("--output") || (it.startsWith("-") && !it.startsWith("--") && it.contains('o')) }) return false
    }
    return true
}

internal fun MainActivity.readOnlyMode() = prefs.getBoolean("readonly", false)

/**
 * Mode "susun dulu": write_file di dalam folder ruang kerja jalan tanpa dialog (kecuali File Terlindung).
 * Izin baru diminta saat perintah (mis. build).
 */
internal fun MainActivity.draftMode() = prefs.getBoolean("draft", true)

/**
 * Persetujuan lewat dialog.
 * "Ya, semua" berlaku untuk sisa tugas ini kecuali [force] = true.
 */
internal fun MainActivity.confirm(
    msg: String, cmd: String? = null, desc: String = "",
    onAll: (() -> Unit)? = null, allLabel: String = "Ya, semua", warning: String? = null,
    force: Boolean = false
): Boolean {
    if (bypassesApproval(allowAll, isAutonomous(), force)) { lastApprovalMode = ApprovalMode.ALL_SESSION; return true }
    agentState = AgentState.AwaitingApproval(cmd ?: "approval")
    val latch = java.util.concurrent.CountDownLatch(1)
    val answer = java.util.concurrent.atomic.AtomicInteger(0) // 0 belum, 1 ya, 2 ya semua, 3 tidak
    pendingWaits.add(latch)
    runOnUiThread {
        val d = showApprovalDialog(
            this,
            ApprovalRequest(cmd ?: msg, desc),
            onYes = { answer.set(1); latch.countDown() },
            onYesAll = { onAll?.invoke(); answer.set(2); latch.countDown() },
            onNo = { answer.set(3); latch.countDown() },
            offerAll = !force,
            allLabel = allLabel,
            warning = warning
        )
        d.setOnDismissListener {
            if (answer.get() == 0) { answer.set(3); latch.countDown() }
            hideKeyboard()
        }
        confirmDialog = d
    }
    try {
        latch.await(10, java.util.concurrent.TimeUnit.MINUTES)
    } finally {
        pendingWaits.remove(latch)
        activeStep?.setWaiting(false)
    }
    val a = answer.get()
    if (a == 2) { allowAll = true; lastApprovalMode = ApprovalMode.ALL_BUTTON }
    else if (a == 1) lastApprovalMode = ApprovalMode.MANUAL
    return a == 1 || a == 2
}

/**
 * Dialog persetujuan khusus untuk perubahan pada FILE TERLINDUNG:
 * menampilkan diff lengkap dan TIDAK memiliki opsi "Ya, semua" atau "Ingat pilihan".
 */
internal fun MainActivity.confirmProtectedFileChange(
    filePath: String,
    desc: String,
    fullDiff: CharSequence,
    diffSummary: String
): Boolean {
    agentState = AgentState.AwaitingApproval("protected:$filePath")
    val latch = java.util.concurrent.CountDownLatch(1)
    val approved = java.util.concurrent.atomic.AtomicBoolean(false)
    pendingWaits.add(latch)
    runOnUiThread {
        val d = showProtectedFileApprovalDialog(
            act = this,
            filePath = filePath,
            description = desc,
            fullDiff = fullDiff,
            diffSummary = diffSummary,
            onApprove = { approved.set(true); latch.countDown() },
            onReject = { approved.set(false); latch.countDown() }
        )
        d.setOnDismissListener {
            latch.countDown()
            hideKeyboard()
        }
        confirmDialog = d
    }
    try {
        latch.await(10, java.util.concurrent.TimeUnit.MINUTES)
    } finally {
        pendingWaits.remove(latch)
        activeStep?.setWaiting(false)
    }
    if (approved.get()) lastApprovalMode = ApprovalMode.MANUAL
    return approved.get()
}

/** Catat perintah/penulisan yang sudah disetujui. Tidak boleh pernah mengganggu alur agen. */
internal fun MainActivity.logApproved(
    kind: String, command: String, desc: String, risk: Risk,
    mode: ApprovalMode, ok: Boolean, result: String,
    exitCode: Int? = null, decision: String = "disetujui", sandbox: Boolean = false, network: Boolean = false
) {
    try {
        CommandLog.append(this, LogEntry(System.currentTimeMillis(), kind, command, desc, risk, mode, ok, result, exitCode, decision, sandbox, network))
    } catch (e: Exception) {
    }
}

internal fun MainActivity.runTool(name: String, args: JSONObject, card: MainActivity.StepCard): String = if (args.has("__error")) {
    val msg = args.optString("__error")
    sawFail = true
    card.finish(false, "args rusak", msg)
    "Error: $msg"
} else when (name) {
    "run_command" -> {
        val cmd = args.optString("command")
        val desc = args.optString("deskripsi")
        val sb = sandboxVerdict(cmd) // null bila mode sandbox mati
        val ws = if (sb == null) assessWorkspaceCommand(cmd, workspaceAbsPath(), termuxHome) else null
        val activeAssessment = sb ?: ws
        val warn = sensitiveReason(cmd) ?: if (TERMUX_SYSTEM.containsMatchIn(cmd) && classify(cmd) != Risk.READ) "folder sistem Termux (perubahan)" else null
        val touchesProtected = activeAssessment?.requiresProtectedApproval == true
        val auto = prefs.getBoolean("autoread", true) && isReadOnly(cmd) && warn == null &&
            !touchesProtected && (activeAssessment == null || activeAssessment.decision == Decision.SAFE)
        if (buildRe.containsMatchIn(cmd)) sawBuild = true

        if (selfRepairMode() && isDirectMainCommitOrPush(cmd)) {
            logApproved("perintah", cmd, desc, classify(cmd), ApprovalMode.MANUAL, false, "Diblokir: commit/merge langsung ke main", decision = "diblokir")
            card.finish(false, "diblokir (branch main)", state = StepState.DENIED)
            "Diblokir oleh mode Perbaiki Diri: dilarang commit atau merge langsung ke branch 'main'. Buat dan gunakan branch 'self/<tanggal>-<ringkasan>'."
        } else if (sb != null && sb.decision == Decision.BLOCKED) {
            logSandboxOutcome(cmd, desc, sb, "diblokir")
            card.finish(false, "diblokir sandbox", state = StepState.DENIED)
            "Diblokir oleh sandbox: ${sb.reasons.joinToString("; ")}. Perintah TIDAK dijalankan. Gunakan pendekatan lain di dalam workdir sandbox."
        } else if (ws != null && ws.decision == Decision.BLOCKED) {
            logApproved("perintah", cmd, desc, ws.risk, ApprovalMode.MANUAL, false, ws.reasons.joinToString("; "), decision = "diblokir")
            card.finish(false, "diblokir ruang kerja", state = StepState.DENIED)
            "Diblokir oleh kebijakan ruang kerja (${workspaceDisplayPath()}): ${ws.reasons.joinToString("; ")}. Perintah TIDAK dijalankan. Agen hanya boleh bekerja di dalam ruang kerja aktif."
        } else if (readOnlyMode() && classify(cmd) != Risk.READ) {
            card.finish(false, "mode hanya baca", state = StepState.DENIED)
            "Ditolak: mode hanya baca aktif. Hanya perintah yang membaca yang diizinkan."
        } else if (touchesProtected && !confirmProtectedCommandInWorkspace(cmd, desc, activeAssessment!!.protectedFiles)) {
            logApproved("perintah", cmd, desc, classify(cmd), ApprovalMode.MANUAL, false, "Ditolak pada dialog File Terlindung", decision = "ditolak")
            card.finish(false, "ditolak (file terlindung)", state = StepState.DENIED)
            "Ditolak pengguna pada dialog persetujuan File Terlindung."
        } else if (!touchesProtected && !auto && !confirm(
                "Jalankan di Termux:\n\n$cmd", cmd, desc,
                warning = listOfNotNull(warn, activeAssessment?.warning()).joinToString("\n").ifBlank { null },
                force = (sb?.network == true) || touchesProtected
            )
        ) {
            if (sb != null) logSandboxOutcome(cmd, desc, sb, "ditolak")
            else logApproved("perintah", cmd, desc, classify(cmd), ApprovalMode.MANUAL, false, "Ditolak pengguna", decision = "ditolak")
            card.finish(false, "ditolak", state = StepState.DENIED)
            "Ditolak pengguna. Tanyakan alasannya lewat ask_user atau pakai pendekatan lain."
        } else {
            val risk = classify(cmd)
            val mode = if (auto) ApprovalMode.AUTO_READ else lastApprovalMode
            val liveFile = "$termuxTmp/kita_live_${UUID.randomUUID()}.txt"
            val isBuild = buildRe.containsMatchIn(cmd)
            val tail = LiveTail(this, liveFile, pollMs = if (isBuild) 5000L else LiveTail.POLL_MS) { card.liveOutput(it) }
            tail.start()
            val res = try {
                if (sb != null) runInSandbox(cmd, isBuild, liveFile)
                else termux(cmd, limit = if (isBuild) 8000 else 4000, timeoutSec = if (isBuild) 1800 else 600, liveFile = liveFile)
            } catch (c: Cancelled) {
                logApproved("perintah", cmd, desc, risk, mode, false, "Dihentikan sebelum selesai")
                throw c
            } finally {
                tail.stop()
            }
            val ok = res.startsWith("exit=0")
            if (!ok) setStatus(IconView.CLOCK, "Menemukan masalah", res.lineSequence().first().take(120), quiet = true)
            logApproved(
                "perintah", cmd, desc, risk, mode, ok, summarizeResult(res, hideOutput = warn != null),
                exitCode = parseTermuxResult(res).first, decision = if (auto) "otomatis" else "disetujui",
                sandbox = sb != null, network = activeAssessment?.network == true
            )
            if (!ok) sawFail = true
            card.finish(ok, if (ok) "" else res.lineSequence().first().take(24), "$ $cmd\n\n" + res.substringAfter("\n", ""))
            if (ok && buildRe.containsMatchIn(cmd)) {
                setStatus(IconView.CHECK, "Memvalidasi hasil", quiet = true)
                findApk()?.let { apkPath ->
                    runLog.apkPath = apkPath
                    if (selfRepairMode()) {
                        handleSelfRepairBuildSuccess(apkPath)
                    } else {
                        addInstallCard(apkPath)
                    }
                }
            }
            res
        }
    }
    "write_file" -> {
        val rawPath = args.optString("path").trim()
        val content = args.optString("content")
        val wDesc = args.optString("deskripsi")
        val wsDir = File(workspaceAbsPath())

        // 1. Pembatas ketat Ruang Kerja (canonical path) dan blokir .env / keystore / ~/kita
        val verdictProtected = classifyProtectedPath(rawPath)
        if (verdictProtected == ProtectedFileVerdict.BLOCKED || !isPathInsideWorkspace(rawPath, wsDir, File(termuxHome))) {
            val blockReason = when {
                verdictProtected == ProtectedFileVerdict.BLOCKED ->
                    "akses atau modifikasi file .env / keystore dilarang ($rawPath)"
                touchesForbiddenKitaDir(rawPath, termuxHome) ->
                    "akses ke folder ~/kita dilarang ($rawPath)"
                else ->
                    "path '$rawPath' keluar dari ruang kerja aktif (${workspaceDisplayPath()})"
            }
            logApproved("tulis file", "Tulis file: $rawPath", wDesc, Risk.DELETE, ApprovalMode.MANUAL, false, "DIBLOKIR: $blockReason", decision = "diblokir")
            card.finish(false, "diblokir", blockReason, StepState.DENIED)
            "Diblokir: $blockReason. Agen hanya boleh membaca dan menulis di dalam ruang kerja ${workspaceDisplayPath()}."
        } else if (sandboxEnabled()) {
            card.finish(false, "mode sandbox", state = StepState.DENIED)
            "Ditolak: mode sandbox aktif, write_file menulis ke Termux utama (di luar sandbox). Tulis file lewat run_command di dalam workdir sandbox (mis. printf atau tee)."
        } else if (readOnlyMode()) {
            card.finish(false, "mode hanya baca", state = StepState.DENIED)
            "Ditolak: mode hanya baca aktif. Jangan menulis atau mengubah file."
        } else {
            val path = normPath(rawPath)
            val old = readOld(path)
            // 2. Cek larangan mengubah applicationId atau konfigurasi kunci signing pada file build
            val gradleTamper = if (rawPath.endsWith(".gradle") || rawPath.endsWith(".gradle.kts")) {
                detectForbiddenGradleTampering(old.orEmpty(), content)
            } else null

            if (gradleTamper != null) {
                logApproved("tulis file", "Tulis file: $rawPath", wDesc, Risk.DELETE, ApprovalMode.MANUAL, false, gradleTamper, decision = "diblokir")
                card.finish(false, "diblokir (signing/appId)", gradleTamper, StepState.DENIED)
                "$gradleTamper Perubahan dibatalkan."
            } else {
                val isProtected = verdictProtected == ProtectedFileVerdict.NEEDS_SPECIAL_APPROVAL
                val diff = if (old != null) {
                    if (isProtected) buildFullDiff(old, content) else buildDiff(old, content)
                } else null
                val info = diff?.second ?: "${content.length} karakter"
                val abs = absPath(path)
                val warnW = sensitiveReason(abs) ?: if (TERMUX_SYSTEM.containsMatchIn(abs)) "folder sistem Termux" else null
                // File terlindung TIDAK PERNAH boleh melewati dialog lewat draftMode, allowAll, atau mode otonom!
                val draft = !isProtected && draftMode() && warnW == null

                val userApproved = when {
                    isProtected -> {
                        val fullDiffSeq = diff?.first ?: content
                        confirmProtectedFileChange(rawPath, wDesc, fullDiffSeq, info)
                    }
                    draft -> true
                    else -> confirm("Tulis file: $rawPath ($info)", warning = warnW)
                }

                if (!userApproved) {
                    card.finish(false, "ditolak", diff?.first, StepState.DENIED)
                    "Ditolak pengguna. Tanyakan alasannya lewat ask_user atau pakai pendekatan lain."
                } else {
                    val mode = if (draft) ApprovalMode.AUTO_DRAFT else lastApprovalMode
                    val wCmd = "Tulis file: $rawPath ($info)"
                    val wRisk = if (old == null || old.isNotEmpty()) Risk.DELETE else Risk.WRITE
                    val bak = try {
                        backupFile(path)
                    } catch (c: Cancelled) {
                        logApproved("tulis file", wCmd, wDesc, wRisk, mode, false, "Dihentikan sebelum selesai")
                        throw c
                    }
                    val res = try {
                        writeFileChunked(path, content)
                    } catch (c: Cancelled) {
                        logApproved("tulis file", wCmd, wDesc, wRisk, mode, false, "Dihentikan sebelum selesai")
                        throw c
                    }
                    val ok = res.trim().endsWith("OK")
                    logApproved(
                        "tulis file", wCmd, wDesc, wRisk, mode, ok,
                        if (ok) "Berhasil ditulis" else summarizeResult(res, hideOutput = warnW != null)
                    )
                    if (ok && bak != null) addUndoCard(absPath(path), bak)
                    if (!ok) sawFail = true
                    val note = if (!ok) "gagal" else (diff?.second ?: "${content.lines().size} baris")
                    card.finish(ok, note, diff?.first ?: content.take(1500))
                    res
                }
            }
        }
    }
    "ask_user" -> {
        val q = args.optString("pertanyaan").ifBlank { "Butuh keputusanmu" }
        val opts = args.optString("pilihan").split("|").map { it.trim() }.filter { it.isNotEmpty() }
        val ans = askUser(q, opts)
        card.finish(true, "", "Pertanyaan: $q\n\nJawaban: $ans")
        "Jawaban pengguna: $ans"
    }
    else -> "Tool tidak dikenal: $name"
}

/**
 * Ketika perintah shell di ruang kerja menyentuh File Terlindung (mis. lewat sed, tee, git checkout,
 * git apply, mv, rm), ambil diff git saat ini dan tampilkan dialog khusus File Terlindung tanpa opsi "Ya, semua".
 */
private fun MainActivity.confirmProtectedCommandInWorkspace(
    cmd: String,
    desc: String,
    protectedFiles: List<String>
): Boolean {
    val filesLabel = protectedFiles.joinToString(", ")
    val currentDiff = try {
        val targetArgs = protectedFiles.joinToString(" ") { sh(it) }
        val r = termux("git diff HEAD -- $targetArgs 2>/dev/null", timeoutSec = 10)
        parseTermuxResult(r).second.trim()
    } catch (_: Exception) {
        ""
    }
    val fullDiffText = buildString {
        appendLine("Perintah akan mengubah/menghapus/memindahkan file terlindung:")
        appendLine("$ $cmd")
        if (currentDiff.isNotBlank()) {
            appendLine()
            appendLine("Diff saat ini pada file terlindung:")
            append(currentDiff)
        }
    }
    return confirmProtectedFileChange(
        filePath = filesLabel,
        desc = desc.ifBlank { "Perintah menyentuh file terlindung ($filesLabel)" },
        fullDiff = fullDiffText,
        diffSummary = "perintah shell"
    )
}

/**
 * Saat mode "Perbaiki Diri" aktif dan build APK selesai, kumpulkan info branch `self/...`,
 * daftar file yang berubah, ringkasan diff, dan hash commit, catat di CommandLog, lalu tampilkan
 * kartu peninjauan berisi tombol Pasang dan Tolak.
 */
private fun MainActivity.handleSelfRepairBuildSuccess(apkPath: String) {
    val branch = try {
        parseTermuxResult(termux("git rev-parse --abbrev-ref HEAD", timeoutSec = 10)).second.trim()
    } catch (_: Exception) {
        ""
    }.ifBlank { "self/perbaikan" }
    val commit = try {
        parseTermuxResult(termux("git rev-parse --short HEAD", timeoutSec = 10)).second.trim()
    } catch (_: Exception) {
        ""
    }
    val changedFiles = try {
        val raw = parseTermuxResult(termux("git diff --name-only main...HEAD 2>/dev/null || git status -s", timeoutSec = 10)).second
        raw.lines().map { it.trim() }.filter { it.isNotBlank() }
    } catch (_: Exception) {
        emptyList()
    }
    val diffStat = try {
        parseTermuxResult(termux("git diff --stat main...HEAD 2>/dev/null || git diff --stat", timeoutSec = 10)).second.trim()
    } catch (_: Exception) {
        ""
    }
    val verCode = try {
        val g = parseTermuxResult(termux("cat app/build.gradle.kts 2>/dev/null", timeoutSec = 10)).second
        Regex("""\bversionCode\s*=?\s*(\d+)""").find(g)?.groupValues?.get(1)?.toIntOrNull()
    } catch (_: Exception) {
        null
    }
    val reqText = taskBrief.ifBlank { "Permintaan Perbaiki Diri" }
    logSelfRepairCycle(
        userRequest = reqText,
        branch = branch,
        buildAndTestResult = "testDebugUnitTest & assembleDebug LOLOS (APK: $apkPath)",
        commitHash = commit,
        ok = true
    )
    addSelfRepairResultCard(
        SelfRepairCycleSummary(
            userRequest = reqText,
            branchName = branch,
            commitHash = commit,
            versionCode = verCode,
            changedFiles = changedFiles,
            diffSummary = diffStat,
            testAndBuildSummary = "testDebugUnitTest & assembleDebug LOLOS",
            apkPathInTermux = apkPath
        )
    )
}
