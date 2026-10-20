package com.kita.agent

import android.app.AlertDialog
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Integrasi Self-Healing Recovery ke siklus hidup Android (Application, MainActivity, AgentLoop,
 * dan Dialog Pemulihan dengan Persetujuan Pengguna).
 */
class KitaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SelfHealingRuntime.installGlobalExceptionHandler(this)
    }
}

internal object SelfHealingRuntime {
    @Volatile
    private var installed = false

    fun installGlobalExceptionHandler(appContext: android.content.Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            val ctx = appContext.applicationContext ?: appContext
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    val store = CrashReportStore(File(ctx.filesDir, "self_heal"))
                    val cpFile = File(ctx.filesDir, "agent_checkpoint.json")
                    val checkpoint = runCatching {
                        if (cpFile.isFile) checkpointFromJson(org.json.JSONObject(cpFile.readText())) else null
                    }.getOrNull()
                    val pkgInfo = runCatching {
                        ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                    }.getOrNull()
                    val verName = pkgInfo?.versionName ?: "2.6"
                    store.recordCrash(
                        throwable = throwable,
                        threadName = thread.name,
                        checkpoint = checkpoint,
                        appVersion = verName,
                        sdkInt = Build.VERSION.SDK_INT
                    )
                    // Jika aplikasi baru dipasang lewat Perbaiki Diri dan belum "boot ok", naikkan penghitung crash berturut-turut
                    runCatching {
                        val sp = ctx.getSharedPreferences("cfg", android.content.Context.MODE_PRIVATE)
                        val pendingBranch = sp.getString(PREF_SELF_PENDING_BRANCH, "") ?: ""
                        val bootOk = sp.getBoolean(PREF_SELF_BOOT_OK, false)
                        if (pendingBranch.isNotBlank() && !bootOk) {
                            val nextCount = sp.getInt(PREF_SELF_CRASH_COUNT, 0) + 1
                            sp.edit().putInt(PREF_SELF_CRASH_COUNT, nextCount).commit()
                        }
                    }
                } catch (_: Throwable) {
                    // Jangan pernah melempar exception baru di dalam UncaughtExceptionHandler
                } finally {
                    previous?.uncaughtException(thread, throwable)
                }
            }
            installed = true
        }
    }
}

internal fun MainActivity.selfHealDir(): File = File(filesDir, "self_heal").apply { mkdirs() }

internal fun MainActivity.crashReportStore(): CrashReportStore = CrashReportStore(selfHealDir())

internal fun MainActivity.selfHealingController(): SelfHealingController {
    val rootPath = wdTarget(termuxHome) ?: termuxHome
    return SelfHealingController(
        projectRoot = File(rootPath),
        stateDir = selfHealDir()
    )
}

/**
 * Periksa laporan crash sebelumnya saat MainActivity dibuka kembali dan tampilkan banner pemulihan
 * jika ada crash yang belum ditangani. Jika tidak ada laporan crash, tidak mengubah tampilan.
 */
internal fun MainActivity.attachCrashRecoveryBannerIfPresent(parent: LinearLayout, insertIndex: Int = 1) {
    SelfHealingRuntime.installGlobalExceptionHandler(this)
    val pending = crashReportStore().loadPendingCrash() ?: return
    val analysis = analyzeRootCause(pending, recentSteps = runLog.steps)

    val banner = LinearLayout(this).apply {
        tag = "self_healing_recovery_banner"
        orientation = LinearLayout.VERTICAL
        background = rounded(
            if (pending.isCrashLoop) 0xFFFFEBEE.toInt() else 0xFFFFF8E1.toInt(),
            14,
            if (pending.isCrashLoop) Palette.RED else Palette.AMBER
        )
        setPadding(dp(14), dp(10), dp(14), dp(10))

        val titleText = if (pending.isCrashLoop) {
            "Peringatan Crash Berulang (${pending.crashCountInWindow}x)"
        } else {
            "Crash Terdeteksi dari Sesi Sebelumnya"
        }
        addView(TextView(context).apply {
            text = titleText
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(if (pending.isCrashLoop) Palette.RED else Palette.INK)
        })

        addView(TextView(context).apply {
            text = analysis.summary
            textSize = 12.5f
            setTextColor(Palette.INK)
            setPadding(0, dp(4), 0, dp(8))
        })

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END

            val dismissBtn = TextView(context).apply {
                text = "Abaikan"
                textSize = 13f
                setTextColor(Palette.INK_SOFT)
                background = pressable(rounded(Color.WHITE, 10, Palette.DIVIDER))
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener {
                    crashReportStore().markResolved(pending.id)
                    parent.removeView(this@apply.parent as View)
                }
            }

            val openBtn = TextView(context).apply {
                text = "Opsi Pemulihan"
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Color.WHITE)
                background = pressable(rounded(Palette.BRAND, 10))
                setPadding(dp(14), dp(6), dp(14), dp(6))
                setOnClickListener {
                    showSelfHealingDialog(pending) {
                        if (crashReportStore().loadPendingCrash() == null) {
                            parent.removeView(this@apply.parent as View)
                        }
                    }
                }
            }

            addView(dismissBtn, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(openBtn, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
        }
        addView(actions, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    val lp = LinearLayout.LayoutParams(MATCH, WRAP).apply {
        setMargins(dp(12), dp(6), dp(12), dp(2))
    }
    val safeIndex = insertIndex.coerceIn(0, parent.childCount)
    parent.addView(banner, safeIndex, lp)
}

/**
 * Dialog/Lembar Pemulihan Self-Healing lengkap:
 * - Menampilkan diagnostik crash (tanpa rahasia), Root Cause Analysis, dan riwayat percobaan.
 * - Menyediakan perbaikan terkendali lewat AgentLoop, rollback checkpoint, persetujuan pemasangan APK,
 *   serta panduan pemulihan eksternal Termux.
 */
internal fun MainActivity.showSelfHealingDialog(
    explicitCrash: CrashReport? = null,
    onStateChanged: (() -> Unit)? = null
) {
    val store = crashReportStore()
    val controller = selfHealingController()
    val crash = explicitCrash ?: store.loadPendingCrash()
    val analysis = analyzeRootCause(crash, recentSteps = runLog.steps)
    val attempts = if (crash != null) controller.attemptsForCrash(crash.id) else controller.getAttempts()
    val remainingAttempts = (MAX_REPAIR_ATTEMPTS - attempts.size).coerceAtLeast(0)
    val fmtTime = SimpleDateFormat("dd MMM yyyy HH:mm:ss", Locale.getDefault())

    var sheetDialog: android.app.Dialog? = null

    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }

    // 1. Kartu Status Crash & RCA
    val statusCard = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Palette.FIELD, 14)
        setPadding(dp(14), dp(12), dp(14), dp(12))

        if (crash == null) {
            addView(TextView(context).apply {
                text = "Status Sistem: Sehat ✓"
                textSize = 15f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(Palette.GREEN)
            })
            addView(TextView(context).apply {
                text = "Tidak ada laporan crash yang tertunda. Pemantau crash dan perlindungan checkpoint siap menjaga sesi kerja Anda."
                textSize = 13f
                setTextColor(Palette.INK_SOFT)
                setPadding(0, dp(4), 0, 0)
            })
        } else {
            addView(TextView(context).apply {
                text = if (crash.isCrashLoop) {
                    "Loop Crash Terdeteksi (${crash.crashCountInWindow}x berturut-turut)"
                } else {
                    "Laporan Crash Belum Ditangani"
                }
                textSize = 15f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(if (crash.isCrashLoop) Palette.RED else Palette.AMBER)
            })
            val detail = buildString {
                appendLine("Waktu: ${fmtTime.format(Date(crash.timestamp))}")
                appendLine("Exception: ${crash.exceptionType}")
                appendLine("Pesan: ${crash.message}")
                if (crash.suspectFile.isNotBlank()) {
                    appendLine("Lokasi Bukti: ${crash.suspectFile}:${crash.suspectLine} (${crash.suspectMethod})")
                }
                if (crash.lastPhase.isNotBlank() || crash.lastTool.isNotBlank()) {
                    appendLine("Fase Terakhir: ${crash.lastPhase.ifBlank { "-" }} • Alat: ${crash.lastTool.ifBlank { "-" }}")
                }
                append("Analisis Akar Masalah: ${analysis.summary}")
            }
            addView(TextView(context).apply {
                text = detail
                textSize = 12.5f
                typeface = Typeface.MONOSPACE
                setTextColor(Palette.INK)
                setPadding(0, dp(8), 0, 0)
                setTextIsSelectable(true)
            })
        }
    }
    box.addView(statusCard, LinearLayout.LayoutParams(MATCH, WRAP))

    // 2. Riwayat & Batas Percobaan Perbaikan
    val attemptsCard = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Palette.FIELD, 14)
        setPadding(dp(14), dp(10), dp(14), dp(10))

        addView(TextView(context).apply {
            text = "Batas Percobaan Perbaikan: ${attempts.size} / $MAX_REPAIR_ATTEMPTS digunakan"
            textSize = 13.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Palette.INK)
        })
        val attemptsDesc = if (attempts.isEmpty()) {
            "Belum ada percobaan perbaikan otomatis pada sesi ini."
        } else {
            attempts.joinToString("\n") { a ->
                val st = if (a.validationPassed) "LULUS" else if (a.rolledBack) "GAGAL (Rollback)" else "GAGAL"
                "#${a.attemptNumber} [$st] ${a.patchedFiles.joinToString(", ").ifEmpty { "-" }} — ${a.rootCauseSummary}"
            }
        }
        addView(TextView(context).apply {
            text = attemptsDesc
            textSize = 12f
            setTextColor(Palette.INK_SOFT)
            setPadding(0, dp(4), 0, 0)
        })
    }
    box.addView(attemptsCard, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })

    // 3. Tombol Aksi Pemulihan Terkendali
    if (crash != null) {
        val canAutoRepair = !crash.isCrashLoop && remainingAttempts > 0 && analysis.canAutoRepair
        val repairBtnLabel = when {
            crash.isCrashLoop -> "Perbaikan Otomatis Dihentikan (Crash Loop)"
            remainingAttempts <= 0 -> "Batas Percobaan Habis ($MAX_REPAIR_ATTEMPTS/$MAX_REPAIR_ATTEMPTS)"
            !analysis.canAutoRepair -> "Bukti Lokasi Belum Cukup untuk Auto-Repair"
            else -> "Analisis & Perbaiki Otomatis (Sisa ${remainingAttempts}x)"
        }
        val repairBtn = filledButton(repairBtnLabel) {
            if (!canAutoRepair) {
                toast("Gunakan Rollback Checkpoint atau Panduan Pemulihan Termux di bawah.")
                return@filledButton
            }
            val prompt = buildSelfHealingAgentPrompt(crash, analysis, remainingAttempts)
            sheetDialog?.dismiss()
            input.setText(prompt)
            input.setSelection(input.text.length)
            send()
        }.apply {
            alpha = if (canAutoRepair) 1f else 0.55f
        }
        box.addView(repairBtn, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
    }

    // Tombol Rollback ke Checkpoint Stabil
    val rollbackBtn = softButton("Pulihkan Checkpoint Stabil (Rollback)") {
        AlertDialog.Builder(this)
            .setTitle("Pulihkan Checkpoint Stabil?")
            .setMessage(
                "Tindakan ini mengembalikan file yang diubah oleh percobaan perbaikan ke kondisi stabil terakhir " +
                    "tanpa menghapus perubahan lokal pengguna yang tidak terkait."
            )
            .setNegativeButton("Batal", null)
            .setPositiveButton("Pulihkan") { _, _ ->
                val restoredLocal = controller.rollbackCheckpoint()
                Thread {
                    val wd = wdTarget(termuxHome) ?: termuxHome
                    val bakDir = "$termuxHome/.kita_bak"
                    val latestBak = try {
                        termux("ls -1t ${sh(bakDir)} 2>/dev/null | head -1", timeoutSec = 15)
                            .lines().drop(1).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                    } catch (_: Exception) {
                        ""
                    }
                    runOnUiThread {
                        val msg = when {
                            restoredLocal.isNotEmpty() ->
                                "Checkpoint dipulihkan (${restoredLocal.size} file: ${restoredLocal.joinToString(", ")})"
                            latestBak.isNotEmpty() ->
                                "Cadangan terakhir tersedia di ~/.kita_bak/$latestBak (folder kerja: $wd)"
                            else ->
                                "Tidak ada file perbaikan gagal yang perlu di-rollback."
                        }
                        toast(msg)
                    }
                }.start()
            }
            .show()
    }
    box.addView(rollbackBtn, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })

    // 4. Persetujuan Pengguna untuk Pemasangan APK Hasil Perbaikan
    val artifact = controller.getLatestArtifact()
    if (artifact != null && File(artifact.apkPath).isFile) {
        val approveInstallBtn = filledButton("Tinjau Perubahan & Pasang APK Terverifikasi") {
            showArtifactApprovalDialog(controller, artifact)
        }
        box.addView(approveInstallBtn, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
    }

    // 5. Panduan Pemulihan Eksternal (Termux / Manual)
    val guideText = buildExternalRecoveryGuide(workspaceDisplayPath())
    val guideBox = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Palette.FIELD, 14)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        addView(TextView(context).apply {
            text = guideText.trim()
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextColor(Palette.INK)
            setTextIsSelectable(true)
        })
        addView(softButton("Salin Perintah Pemulihan Termux") {
            val cm = getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("Pemulihan Termux", guideText))
            toast("Panduan pemulihan Termux disalin ke papan klip")
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
    }
    box.addView(guideBox, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })

    val footer = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(softButton("Tutup") { sheetDialog?.dismiss() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (crash != null) {
            addView(filledButton("Tandai Selesai") {
                store.markResolved(crash.id)
                onStateChanged?.invoke()
                sheetDialog?.dismiss()
                toast("Laporan crash ditandai selesai")
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(10) })
        }
    }

    sheetDialog = showSheet(
        "Self-Healing & Pemulihan",
        "Deteksi crash, analisis bukti, checkpoint, dan pemulihan aman",
        box,
        footer
    )
}

/**
 * Dialog persetujuan pengguna wajib sebelum memasang APK hasil perbaikan:
 * menampilkan daftar file yang diubah, ringkasan diff, dan hasil pengujian.
 */
internal fun MainActivity.showArtifactApprovalDialog(
    controller: SelfHealingController,
    artifact: SelfHealingArtifact
) {
    val msg = buildString {
        appendLine("Tinjau ringkasan perubahan dan hasil pengujian sebelum memasang pembaruan APK:")
        appendLine()
        appendLine("• Berkas diubah: ${artifact.patchedFiles.joinToString(", ").ifEmpty { "-" }}")
        appendLine("• Ukuran APK: ${artifact.apkSizeBytes / 1024} KB (SHA-256: ${artifact.apkSha256.take(16)}…)")
        appendLine("• Perintah Validasi: ${artifact.validationCommand}")
        appendLine()
        appendLine("Ringkasan Perubahan:")
        appendLine(artifact.diffSummary.ifBlank { "(Tidak ada ringkasan diff)" })
        appendLine()
        appendLine("Hasil Pengujian & Build:")
        appendLine(artifact.validationSummary.take(600))
    }

    AlertDialog.Builder(this)
        .setTitle("Setujui Pemasangan APK?")
        .setMessage(msg)
        .setNegativeButton("Batal", null)
        .setPositiveButton("Setujui & Buka Installer") { _, _ ->
            val approved = controller.approveArtifactForInstall(artifact.id)
            if (approved != null && approved.approvedByUser) {
                launchInstaller(File(approved.apkPath))
            }
        }
        .show()
}
