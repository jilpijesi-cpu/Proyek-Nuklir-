package com.kita.agent

import java.io.File
import java.util.UUID
import org.json.JSONObject

// Eksekusi alat: persetujuan perintah, catatan, mode baca-saja, runTool.
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
 * Mode "susun dulu": write_file di dalam folder kerja jalan tanpa dialog (tetap ada cadangan, tombol urungkan, dan catatan).
 * Izin baru diminta saat perintah (mis. build). Tulis ke area sensitif atau di luar folder kerja tetap minta persetujuan.
 */
internal fun MainActivity.draftMode() = prefs.getBoolean("draft", true)

/**
 * Persetujuan lewat dialog. Tidak ada tindakan yang ditolak otomatis: area sensitif hanya diberi peringatan merah.
 * "Ya, semua" berlaku untuk sisa tugas ini. Tanpa jawaban dalam 10 menit dianggap tidak.
 */
internal fun MainActivity.confirm(
    msg: String, cmd: String? = null, desc: String = "",
    onAll: (() -> Unit)? = null, allLabel: String = "Ya, semua", warning: String? = null,
    force: Boolean = false   // true: tidak bisa dilewati oleh mode otonom / "Ya, semua" (mis. perintah berjaringan di sandbox)
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
        val sb = sandboxVerdict(cmd) // null bila mode sandbox mati
        val warn = sensitiveReason(cmd) ?: if (TERMUX_SYSTEM.containsMatchIn(cmd) && classify(cmd) != Risk.READ) "folder sistem Termux (perubahan)" else null
        val auto = prefs.getBoolean("autoread", true) && isReadOnly(cmd) && warn == null &&
            (sb == null || sb.decision == Decision.SAFE)
        if (buildRe.containsMatchIn(cmd)) sawBuild = true
        if (sb != null && sb.decision == Decision.BLOCKED) {
            logSandboxOutcome(cmd, args.optString("deskripsi"), sb, "diblokir")
            card.finish(false, "diblokir sandbox", state = StepState.DENIED)
            "Diblokir oleh sandbox: ${sb.reasons.joinToString("; ")}. Perintah TIDAK dijalankan. Gunakan pendekatan lain di dalam workdir sandbox."
        } else if (readOnlyMode() && classify(cmd) != Risk.READ) {
            card.finish(false, "mode hanya baca", state = StepState.DENIED)
            "Ditolak: mode hanya baca aktif. Hanya perintah yang membaca yang diizinkan."
        } else if (!auto && !confirm(
                "Jalankan di Termux:\n\n$cmd", cmd, args.optString("deskripsi"),
                warning = listOfNotNull(warn, sb?.warning()).joinToString("\n").ifBlank { null },
                force = sb?.network == true
            )
        ) {
            if (sb != null) logSandboxOutcome(cmd, args.optString("deskripsi"), sb, "ditolak")
            card.finish(false, "ditolak", state = StepState.DENIED)
            "Ditolak pengguna. Tanyakan alasannya lewat ask_user atau pakai pendekatan lain."
        } else {
            val desc = args.optString("deskripsi")
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
                // perintah sudah disetujui dan mungkin sudah mulai jalan: tetap dicatat
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
                sandbox = sb != null, network = sb?.network == true
            )
            if (!ok) sawFail = true
            card.finish(ok, if (ok) "" else res.lineSequence().first().take(24), "$ $cmd\n\n" + res.substringAfter("\n", ""))
            if (ok && buildRe.containsMatchIn(cmd)) {
                setStatus(IconView.CHECK, "Memvalidasi hasil", quiet = true)
                findApk()?.let { runLog.apkPath = it; addInstallCard(it) }
            }
            res
        }
    }
    "write_file" -> {
        val path = normPath(args.optString("path"))
        val content = args.optString("content")
        val old = if (readOnlyMode() || sandboxEnabled()) null else readOld(path)
        val diff = if (old != null) buildDiff(old, content) else null
        val info = diff?.second ?: "${content.length} karakter"
        val abs = absPath(path)
        val wdBase = (wdTarget(termuxHome) ?: termuxHome).trimEnd('/')
        val warnW = sensitiveReason(abs) ?: if (TERMUX_SYSTEM.containsMatchIn(abs)) "folder sistem Termux" else null
            ?: if (abs == wdBase || abs.startsWith("$wdBase/")) null else "File ini di luar folder kerja"
        val draft = draftMode() && warnW == null
        if (sandboxEnabled()) {
            card.finish(false, "mode sandbox", state = StepState.DENIED)
            "Ditolak: mode sandbox aktif, write_file menulis ke Termux utama (di luar sandbox). Tulis file lewat run_command di dalam workdir sandbox (mis. printf atau tee)."
        } else if (readOnlyMode()) {
            card.finish(false, "mode hanya baca", state = StepState.DENIED)
            "Ditolak: mode hanya baca aktif. Jangan menulis atau mengubah file."
        } else if (!draft && !confirm("Tulis file: $path ($info)", warning = warnW)) {
            card.finish(false, "ditolak", diff?.first, StepState.DENIED)
            "Ditolak pengguna. Tanyakan alasannya lewat ask_user atau pakai pendekatan lain."
        } else {
            val mode = if (draft) ApprovalMode.AUTO_DRAFT else lastApprovalMode
            val wDesc = args.optString("deskripsi")
            val wCmd = "Tulis file: $path ($info)"
            // readOld() mengembalikan "" untuk file yang belum ada (file baru = WRITE) dan null bila file ada tapi
            // terlalu besar untuk dibaca penuh (tetap dianggap menimpa data).
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
    "ask_user" -> {
        val q = args.optString("pertanyaan").ifBlank { "Butuh keputusanmu" }
        val opts = args.optString("pilihan").split("|").map { it.trim() }.filter { it.isNotEmpty() }
        val ans = askUser(q, opts)
        card.finish(true, "", "Pertanyaan: $q\n\nJawaban: $ans")
        "Jawaban pengguna: $ans"
    }
    else -> "Tool tidak dikenal: $name"
}
