package com.kita.agent

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

// Dialog Pengaturan minimalis dalam 4 bagian:
// 1. AI & Model (penyedia, API key dengan tampil/sembunyi, model & uji koneksi)
// 2. Eksekusi (terminal Termux, sandbox, izin perintah baca)
// 3. Aplikasi (perilaku kerja asisten & antarmuka)
// 4. Lanjutan (konfigurasi teknis yang jarang digunakan, tertutup secara default)

internal data class RiskConfirmSpec(
    val key: String,
    val title: String,
    val message: String,
    val confirmLabel: String = "Saya paham, aktifkan"
)

internal fun riskConfirmSpecFor(prefKey: String): RiskConfirmSpec? = when (prefKey) {
    "autonomous" -> RiskConfirmSpec(
        key = "autonomous",
        title = "Aktifkan mode otonom?",
        message = "Dalam mode otonom, AI dapat menjalankan perintah shell, menulis file, " +
            "dan memulai pemasangan APK tanpa dialog persetujuan. Aktifkan hanya " +
            "jika Anda memahami dan menerima risikonya."
    )
    "sandbox" -> RiskConfirmSpec(
        key = "sandbox",
        title = "Aktifkan mode sandbox?",
        message = "Semua perintah terminal akan dijalankan di dalam proot-distro (Ubuntu) yang " +
            "terisolasi dari penyimpanan utama dan home Termux. Pastikan Ubuntu sudah " +
            "terpasang di Termux sebelum menjalankan tugas."
    )
    "autoread" -> RiskConfirmSpec(
        key = "autoread",
        title = "Setujui otomatis perintah baca?",
        message = "AI akan diizinkan menjalankan perintah baca (seperti ls, cat, grep, dan find) " +
            "tanpa menampilkan dialog konfirmasi setiap kali. Pastikan folder kerja tidak " +
            "berisi berkas rahasia."
    )
    "apk_re" -> RiskConfirmSpec(
        key = "apk_re",
        title = "Aktifkan Mode Analisis APK?",
        message = "Mode ini mengizinkan dekompilasi dan analisis statis APK (seperti jadx atau apktool). " +
            "Perintah yang menulis file tetap memerlukan izin, dan pembobolan lisensi/DRM tetap dilarang."
    )
    else -> null
}

/** Pastikan nilai API key tidak pernah bocor ke pesan status atau error yang ditampilkan. */
internal fun sanitizeConnError(raw: String, apiKey: String): String {
    var cleaned = raw.trim()
    val norm = normalizeKey(apiKey)
    if (norm.length >= 4) {
        cleaned = cleaned.replace(norm, "***")
    }
    if (apiKey.trim().length >= 4) {
        cleaned = cleaned.replace(apiKey.trim(), "***")
    }
    cleaned = cleaned.replace(Regex("(sk-[A-Za-z0-9_-]{8,}|AIza[A-Za-z0-9_-]{8,}|gsk_[A-Za-z0-9_-]{8,})"), "***")
    return cleaned.lineSequence().firstOrNull()?.take(110)?.trim().orEmpty().ifEmpty { "Koneksi gagal" }
}

/** Ringkasan status awal koneksi berdasarkan konfigurasi penyedia, key, dan model. */
internal fun initialConnectionStatus(
    isCli: Boolean,
    needsKey: Boolean,
    hasKey: Boolean,
    modelName: String,
    probedCount: Int
): String {
    if (isCli) return "Mode CLI Termux aktif"
    if (needsKey && !hasKey) return "API key belum diisi"
    val mdl = modelName.removePrefix("models/").trim()
    return when {
        probedCount > 0 && mdl.isNotEmpty() -> "Siap • Model: $mdl ($probedCount lolos uji)"
        mdl.isNotEmpty() -> "Model aktif: $mdl • Ketuk Uji untuk verifikasi"
        else -> "API key terisi • Ketuk Uji untuk memuat model"
    }
}

internal fun MainActivity.showSettings() {
    val names = presets.map { it.name }
    val spin = Spinner(this)
    spin.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, names) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            (super.getView(position, convertView, parent) as TextView).apply {
                textSize = 15f
                setTextColor(Palette.INK)
                setPadding(0, 0, 0, 0)
            }
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            (super.getDropDownView(position, convertView, parent) as TextView).apply {
                textSize = 15f
                setTextColor(Palette.INK)
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
    }
    spin.background = null
    spin.setPopupBackgroundDrawable(rounded(Color.WHITE, 14, Palette.DIVIDER))
    val spinHolder = FrameLayout(this).apply {
        background = rounded(Palette.FIELD, 14)
        setPadding(dp(14), 0, dp(12), 0)
        addView(spin, FrameLayout.LayoutParams(MATCH, dp(48)))
        addView(
            IconView(context, IconView.CHEVRON_DOWN, Palette.INK_SOFT),
            FrameLayout.LayoutParams(dp(18), dp(18), Gravity.END or Gravity.CENTER_VERTICAL)
        )
    }

    val base = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    }
    var keyVisible = false
    var loading = false // true saat kolom diisi dari penyimpanan atau ubah visibilitas
    val key = EditText(this).apply {
        hint = "Tempel API key (kosong untuk server lokal)"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        background = null
        setPadding(dp(14), dp(12), dp(8), dp(12))
        textSize = 15f
        setTextColor(Palette.INK)
        setHintTextColor(Palette.INK_SOFT)
        setSingleLine(true)
    }
    val keyToggleBtn = TextView(this).apply {
        text = "Tampil"
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Palette.BRAND)
        gravity = Gravity.CENTER
        background = pressable(rounded(Color.WHITE, 10, Palette.DIVIDER))
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setOnClickListener {
            keyVisible = !keyVisible
            val sel = key.selectionStart.coerceAtLeast(0)
            loading = true
            key.inputType = InputType.TYPE_CLASS_TEXT or
                if (keyVisible) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                else InputType.TYPE_TEXT_VARIATION_PASSWORD
            key.setSelection(sel.coerceAtMost(key.text?.length ?: 0))
            loading = false
            text = if (keyVisible) "Sembunyi" else "Tampil"
        }
    }
    val keyHolder = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(Palette.FIELD, 14)
        setPadding(0, dp(2), dp(6), dp(2))
        addView(key, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(keyToggleBtn, LinearLayout.LayoutParams(WRAP, WRAP))
    }

    val mdl = EditText(this).apply {
        hint = "Nama model (contoh: gemini-2.5-flash)"
        inputType = InputType.TYPE_CLASS_TEXT
    }
    val workspaceStatusText = TextView(this).apply {
        text = "Ruang kerja aktif: ${activeWorkspaceName()} (${workspaceDisplayPath()})"
        textSize = 13.5f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Palette.INK)
    }
    val switchWorkspaceBtn = TextView(this).apply {
        text = "Ganti ruang kerja"
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Palette.BRAND)
        gravity = Gravity.CENTER
        background = pressable(rounded(Color.WHITE, 12, Palette.DIVIDER))
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setOnClickListener {
            confirmSwitchWorkspaceDialog {
                workspaceStatusText.text = "Ruang kerja aktif: ${activeWorkspaceName()} (${workspaceDisplayPath()})"
            }
        }
    }
    val workspaceCard = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Palette.FIELD, 14)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        addView(workspaceStatusText, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(TextView(context).apply {
            text = "Agen hanya diizinkan membaca dan menulis di dalam folder ruang kerja ini."
            textSize = 12f
            setTextColor(Palette.INK_SOFT)
            setPadding(0, dp(2), 0, dp(8))
        })
        addView(switchWorkspaceBtn, LinearLayout.LayoutParams(WRAP, WRAP))
    }
    val extra = EditText(this).apply {
        hint = "Instruksi tambahan untuk AI (opsional)"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        minLines = 3
        gravity = Gravity.TOP
        setText(prefs.getString("extra", ""))
    }
    for (f in listOf(base, mdl, extra)) styleField(f)

    /** Header bagian (AI & Model, Eksekusi, Aplikasi, Lanjutan). */
    fun sectionHeader(title: String, subtitle: String? = null): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(14), dp(4), dp(4))
        addView(TextView(context).apply {
            text = title
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Palette.BRAND_DEEP)
        })
        if (!subtitle.isNullOrBlank()) {
            addView(TextView(context).apply {
                text = subtitle
                textSize = 12f
                setTextColor(Palette.INK_SOFT)
                setPadding(0, dp(1), 0, 0)
            })
        }
    }

    var autonomousConfirmed = prefs.getBoolean("autonomous_ack", false)

    /** Satu baris pengaturan: judul + keterangan singkat + sakelar, dengan konfirmasi opsional untuk opsi berisiko. */
    fun toggleRow(title: String, hint: String, on: Boolean, riskKey: String? = null): Pair<View, Switch> {
        var suppressRiskDialog = false
        val sw = Switch(this).apply { isChecked = on }
        val spec = riskKey?.let { riskConfirmSpecFor(it) }
        if (spec != null) {
            sw.setOnCheckedChangeListener { buttonView, isChecked ->
                if (suppressRiskDialog || !isChecked) return@setOnCheckedChangeListener
                AlertDialog.Builder(this)
                    .setTitle(spec.title)
                    .setMessage(spec.message)
                    .setNegativeButton("Batal") { _, _ ->
                        suppressRiskDialog = true
                        buttonView.isChecked = false
                        suppressRiskDialog = false
                    }
                    .setOnCancelListener {
                        suppressRiskDialog = true
                        buttonView.isChecked = false
                        suppressRiskDialog = false
                    }
                    .setPositiveButton(spec.confirmLabel) { _, _ ->
                        if (spec.key == "autonomous") autonomousConfirmed = true
                    }
                    .show()
            }
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = title; textSize = 15f; setTextColor(Palette.INK) })
            addView(TextView(context).apply { text = hint; textSize = 12.5f; setTextColor(Palette.INK_SOFT) })
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pressable(null)
            setPadding(dp(4), dp(9), dp(4), dp(9))
            addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(sw, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(12) })
            setOnClickListener { sw.toggle() }
        }
        return row to sw
    }

    // Bagian 2: Eksekusi
    val (roRow, ro) = toggleRow(
        "Mode hanya baca",
        "AI tidak boleh menulis, mengubah, atau menghapus file",
        prefs.getBoolean("readonly", false)
    )
    val (autoRow, auto) = toggleRow(
        "Setujui otomatis perintah baca",
        "Perintah baca (ls, cat, grep) jalan tanpa dialog konfirmasi",
        prefs.getBoolean("autoread", true),
        riskKey = "autoread"
    )
    val (sandboxRow, sandbox) = toggleRow(
        "Mode sandbox",
        "Isolasi perintah di dalam proot-distro Ubuntu",
        prefs.getBoolean("sandbox", false),
        riskKey = "sandbox"
    )

    // Bagian 3: Aplikasi
    val (draftRow, draftSw) = toggleRow(
        "Susun kode dulu, izin saat build",
        "AI menulis file di folder kerja dulu; build tetap minta izin",
        prefs.getBoolean("draft", true)
    )
    val (understandRow, understandSw) = toggleRow(
        "Pahami tugas sebelum bertindak",
        "Rumuskan tujuan dan langkah awal sebelum menjalankan alat",
        prefs.getBoolean("understand", false)
    )

    // Bagian 4: Lanjutan (opsi berisiko tinggi & teknis)
    val (selfRepairRow, selfRepairSw) = toggleRow(
        "Mode Perbaiki Diri",
        "Izinkan agen mengubah kode aplikasi di branch self/<tanggal>-<ringkasan> dengan pengaman ketat",
        prefs.getBoolean(PREF_SELF_REPAIR_MODE, false)
    )
    val (autonomousRow, autonomous) = toggleRow(
        "Mode otonom",
        "Jalankan perintah & pasang APK tanpa bertanya (berisiko)",
        prefs.getBoolean("autonomous", false),
        riskKey = "autonomous"
    )
    val (apkReRow, apkRe) = toggleRow(
        "Mode Analisis APK",
        "Khusus dekompilasi & analisis statis; tidak membobol DRM/lisensi",
        prefs.getBoolean("apk_re", false),
        riskKey = "apk_re"
    )

    /** Baris tautan: teks + deskripsi opsional + panah, seluruh baris bisa diketuk. */
    fun linkRow(label: String, subtitle: String? = null, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = pressable(null)
        setPadding(dp(4), dp(11), dp(4), dp(11))
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = label; textSize = 15f; setTextColor(Palette.INK) })
            if (!subtitle.isNullOrBlank()) {
                addView(TextView(context).apply { text = subtitle; textSize = 12.5f; setTextColor(Palette.INK_SOFT) })
            }
        }
        addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(TextView(context).apply { text = "›"; textSize = 20f; setTextColor(Palette.INK_SOFT) })
        setOnClickListener { onClick() }
    }

    fun divider(): View = View(this).apply { setBackgroundColor(Palette.DIVIDER) }

    val keyLabel = fieldLabel("API key")
    val baseLabel = fieldLabel("Alamat API / Perintah CLI")
    val mdlLabel = fieldLabel("Nama model manual")
    var cur = prov
    var needBase = false
    var isCliCur = false
    var pendingKey: String? = null
    val detectNote = TextView(this).apply {
        textSize = 12.5f
        setTextColor(Palette.INK_SOFT)
        setPadding(dp(4), dp(6), 0, 0)
        visibility = View.GONE
    }

    // Baris status koneksi AI ringkas + tombol Uji koneksi & Pilih model
    val connStatusText = TextView(this).apply {
        textSize = 13f
        setTextColor(Palette.INK_SOFT)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    var testingConn = false
    val testConnBtn = TextView(this).apply {
        text = "Uji koneksi"
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Palette.BRAND)
        gravity = Gravity.CENTER
        background = pressable(rounded(Color.WHITE, 12, Palette.DIVIDER))
        setPadding(dp(12), dp(8), dp(12), dp(8))
    }
    val pickModelBtn = TextView(this).apply {
        text = "Pilih model"
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Palette.INK)
        gravity = Gravity.CENTER
        background = pressable(rounded(Color.WHITE, 12, Palette.DIVIDER))
        setPadding(dp(12), dp(8), dp(12), dp(8))
    }
    val connActionsRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, 0)
        addView(testConnBtn, LinearLayout.LayoutParams(WRAP, WRAP))
        addView(pickModelBtn, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
    }
    val connCard = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Palette.FIELD, 14)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        addView(connStatusText, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(connActionsRow, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    var curLogin: CliLogin? = null
    val loginStatus = TextView(this).apply {
        textSize = 13.5f
        setTextColor(Palette.INK_SOFT)
        setPadding(dp(4), dp(8), 0, 0)
        visibility = View.GONE
    }
    val loginBtn = TextView(this).apply {
        textSize = 14.5f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = pressable(rounded(Palette.BRAND, 20))
        setPadding(dp(18), dp(10), dp(18), dp(10))
        visibility = View.GONE
        setOnClickListener {
            curLogin?.let { c ->
                cliSetup(c) { msg ->
                    runOnUiThread {
                        if (curLogin === c) {
                            loginStatus.text = msg
                            loginStatus.setTextColor(Palette.INK_SOFT)
                        }
                    }
                }
            }
        }
    }

    fun checkLogin() {
        val c = curLogin ?: return
        loginStatus.text = "Memeriksa login…"
        loginStatus.setTextColor(Palette.INK_SOFT)
        Thread {
            val ok = cliLoggedIn(c)
            runOnUiThread {
                if (curLogin !== c) return@runOnUiThread
                loginStatus.text = if (ok) "✓ Sudah masuk dengan ${c.account}" else "Belum masuk. Ketuk tombol di bawah untuk memulai."
                loginStatus.setTextColor(if (ok) Palette.GREEN else Palette.INK_SOFT)
                connStatusText.text = if (ok) "Terhubung ✓ (${c.account})" else "CLI belum login (${c.account})"
                connStatusText.setTextColor(if (ok) Palette.GREEN else Palette.AMBER)
            }
        }.start()
    }
    loginStatus.setOnClickListener { checkLogin() }

    fun updateConnSummary() {
        val p = preset(cur)
        val isCli = p.fmt == "cli"
        val curBase = base.text.toString().trim().ifEmpty { p.base }
        val reqKey = !isCli && !curBase.startsWith("http://", ignoreCase = true)
        val hasKey = normalizeKey(key.text.toString()).isNotEmpty()
        val curMdl = mdl.text.toString().trim().ifEmpty { p.model }
        val probedCount = if (cur == prov) (loadProbed()?.first?.size ?: 0) else 0
        connStatusText.text = initialConnectionStatus(isCli, reqKey, hasKey, curMdl, probedCount)
        connStatusText.setTextColor(
            when {
                reqKey && !hasKey -> Palette.AMBER
                probedCount > 0 -> Palette.GREEN
                else -> Palette.INK_SOFT
            }
        )
        pickModelBtn.visibility = if (isCli) View.GONE else View.VISIBLE
    }

    // Uji koneksi langsung menggunakan nilai yang sedang diisi di dialog
    testConnBtn.setOnClickListener {
        if (testingConn) return@setOnClickListener
        val targetProv = cur
        val p = preset(targetProv)
        if (p.fmt == "cli") {
            if (curLogin != null) {
                checkLogin()
            } else {
                testingConn = true
                testConnBtn.text = "Menguji…"
                connStatusText.text = "Memeriksa kesiapan Termux…"
                connStatusText.setTextColor(Palette.INK_SOFT)
                Thread {
                    val out = try {
                        termux("echo ok", limit = 100, timeoutSec = 10, useWorkdir = false, killable = false)
                    } catch (e: Exception) {
                        ""
                    }
                    val ok = out.startsWith("exit=0")
                    runOnUiThread {
                        testingConn = false
                        testConnBtn.text = "Uji koneksi"
                        if (cur == targetProv) {
                            connStatusText.text = if (ok) "Terhubung ✓ Termux siap menjalankan perintah CLI"
                            else "Gagal menghubungi Termux. Buka 'Siapkan Termux' di bagian Eksekusi."
                            connStatusText.setTextColor(if (ok) Palette.GREEN else Palette.RED)
                        }
                    }
                }.start()
            }
            return@setOnClickListener
        }
        val testKey = normalizeKey(key.text.toString())
        val testBase = base.text.toString().trim().ifEmpty { p.base }.trimEnd('/')
        val reqKey = !testBase.startsWith("http://", ignoreCase = true)
        if (reqKey && testKey.isEmpty()) {
            connStatusText.text = "Masukkan API key terlebih dahulu sebelum menguji koneksi."
            connStatusText.setTextColor(Palette.AMBER)
            key.requestFocus()
            return@setOnClickListener
        }
        testingConn = true
        testConnBtn.text = "Menguji…"
        connStatusText.text = "Menguji koneksi ke $targetProv…"
        connStatusText.setTextColor(Palette.INK_SOFT)
        Thread {
            val outcome = testApiConnection(p.fmt, testBase, testKey)
            runOnUiThread {
                testingConn = false
                testConnBtn.text = "Uji koneksi"
                if (cur != targetProv) return@runOnUiThread
                connStatusText.text = outcome.message
                connStatusText.setTextColor(outcome.color)
                if (outcome.ok && mdl.text.toString().isBlank() && outcome.suggestedModel.isNotBlank()) {
                    mdl.setText(outcome.suggestedModel)
                }
            }
        }.start()
    }

    var dialog: Dialog? = null

    val setupBtn = linkRow("Siapkan Termux", "Periksa izin RUN_COMMAND dan paket dasar") { showSetup() }
    val logBtn = linkRow("Catatan perintah", "Riwayat eksekusi perintah terminal") {
        startActivity(Intent(this, CommandLogActivity::class.java))
    }
    val rollbackApkBtn = linkRow("Kembali ke versi sebelumnya", "Pasang ulang APK cadangan (maksimal 3 versi terakhir)") {
        dialog?.dismiss()
        restorePreviousApkVersion()
    }
    val bootState = loadSelfRepairBootState()
    val mergeMainBtn = if (bootState.canMergeToMain) {
        linkRow("Gabungkan ke main (${bootState.pendingBranch})", "Versi baru sudah berjalan stabil (boot ok)") {
            dialog?.dismiss()
            mergeSelfBranchToMain(bootState.pendingBranch)
        }
    } else null
    val selfHealBtn = linkRow("Self-Healing & Pemulihan", "Laporan crash, checkpoint stabil, dan panduan pemulihan") {
        dialog?.dismiss()
        showSelfHealingDialog()
    }
    val reportBtn = linkRow("Salin laporan masalah", "Salin info diagnostik tanpa kredensial") { copyReport() }

    // Tombol Pilih model menyimpan sementara provider & key lalu membuka pemilih model
    pickModelBtn.setOnClickListener {
        val normKey = normalizeKey(key.text.toString())
        if (needsKeyFor(cur, base.text.toString()) && normKey.isEmpty()) {
            connStatusText.text = "Isi API key terlebih dahulu untuk memilih model."
            connStatusText.setTextColor(Palette.AMBER)
            key.requestFocus()
            return@setOnClickListener
        }
        prov = cur
        val ed = prefs.edit()
            .putString("prov", cur)
            .putString("p_${cur}_base", base.text.toString().trim())
            .putString("p_${cur}_model", mdl.text.toString().trim())
        val enc = Secret.encrypt(normKey)
        if (enc.isSuccess) ed.putString("p_${cur}_key", enc.getOrThrow())
        ed.apply()
        dialog?.dismiss()
        pickModel()
    }

    var advOpen = false
    val adv = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        addView(mdlLabel)
        addView(mdl, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(baseLabel)
        addView(base, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(selfRepairRow)
        addView(autonomousRow)
        addView(apkReRow)
        addView(fieldLabel("Instruksi tambahan"))
        addView(extra, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(divider(), LinearLayout.LayoutParams(MATCH, dp(1)).apply { topMargin = dp(10); bottomMargin = dp(4) })
        mergeMainBtn?.let { addView(it) }
        addView(rollbackApkBtn)
        addView(selfHealBtn)
        addView(logBtn)
        addView(reportBtn)
    }

    // Wadah khusus untuk Base URL bila penyedia membutuhkan input langsung (Kustom / Ollama / CLI)
    val primaryBaseBox = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
    }

    val advHeaderTitle = TextView(this).apply {
        text = "Lanjutan"
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Palette.BRAND)
    }
    val advHeaderHint = TextView(this).apply {
        text = "Model manual, URL API, folder kerja, mode otonom & log"
        textSize = 12f
        setTextColor(Palette.INK_SOFT)
    }
    val advArrow = TextView(this).apply {
        text = "▾"
        textSize = 16f
        setTextColor(Palette.BRAND)
    }
    val advBtn = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = pressable(rounded(Palette.FIELD, 14))
        setPadding(dp(14), dp(10), dp(14), dp(10))
        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(advHeaderTitle)
            addView(advHeaderHint)
        }
        addView(textCol, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(advArrow, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
    }

    fun moveBaseField(toPrimary: Boolean) {
        (baseLabel.parent as? ViewGroup)?.removeView(baseLabel)
        (base.parent as? ViewGroup)?.removeView(base)
        if (toPrimary) {
            primaryBaseBox.addView(baseLabel)
            primaryBaseBox.addView(base, LinearLayout.LayoutParams(MATCH, WRAP))
            primaryBaseBox.visibility = View.VISIBLE
        } else {
            primaryBaseBox.visibility = View.GONE
            adv.addView(baseLabel, 2)
            adv.addView(base, 3, LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }

    fun refresh() {
        adv.visibility = if (advOpen) View.VISIBLE else View.GONE
        advArrow.text = if (advOpen) "▴" else "▾"
        keyHolder.visibility = if (isCliCur) View.GONE else View.VISIBLE
        keyLabel.visibility = keyHolder.visibility
        mdlLabel.visibility = if (isCliCur) View.GONE else View.VISIBLE
        mdl.visibility = if (isCliCur) View.GONE else View.VISIBLE
        moveBaseField(toPrimary = needBase && !advOpen)
        updateConnSummary()
    }
    advBtn.setOnClickListener { advOpen = !advOpen; refresh() }

    fun load(n: String) {
        val p = preset(n)
        val isCli = p.fmt == "cli"
        baseLabel.text = if (isCli) "Perintah CLI (pakai {prompt})" else "Alamat API (base URL)"
        base.hint = if (isCli) "Contoh: gemini -p {prompt}" else "Alamat API (base URL)"
        base.setText(pvOf(n, "base").ifEmpty { p.base })
        val oldKey = if (n == "Gemini") {
            when (val result = Secret.decrypt(prefs.getString("key", "") ?: "")) {
                is SecretDecryptResult.Success -> result.value
                SecretDecryptResult.Missing -> {
                    prefs.edit().remove("key").apply()
                    keyWarning = "API key lama tidak dapat dibuka karena kunci Keystore hilang. Masukkan ulang API key."
                    ""
                }
                SecretDecryptResult.Corrupt -> {
                    prefs.edit().remove("key").apply()
                    keyWarning = "API key tersimpan rusak dan sudah dihapus. Masukkan ulang API key."
                    ""
                }
                SecretDecryptResult.KeystoreUnavailable -> {
                    keyWarning = "Android Keystore tidak tersedia. API key tidak dibaca; masukkan ulang nanti."
                    ""
                }
            }
        } else ""
        val typed = pendingKey
        pendingKey = null
        loading = true
        key.setText(typed ?: pvOf(n, "key").ifEmpty { oldKey })
        loading = false
        if (typed == null) {
            val warning = keyWarning
            if (warning != null) {
                detectNote.text = warning
                detectNote.visibility = View.VISIBLE
            } else {
                detectNote.visibility = View.GONE
            }
        }
        mdl.setText(pvOf(n, "model").ifEmpty { p.model })
        isCliCur = isCli
        needBase = isCli || n.startsWith("Kustom") || n.startsWith("Ollama")
        refresh()
        curLogin = cliLoginOf(n)
        val hasLogin = curLogin != null
        loginStatus.visibility = if (hasLogin) View.VISIBLE else View.GONE
        loginBtn.visibility = if (hasLogin) View.VISIBLE else View.GONE
        curLogin?.let { loginBtn.text = "Masuk dengan ${it.account}" }
        checkLogin()
    }

    spin.setSelection(names.indexOf(prov).coerceAtLeast(0))
    spin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
            cur = names[pos]
            load(cur)
        }
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    // Deteksi otomatis: tempel key, penyedia ikut berganti sesuai bentuk key (lihat KeyDetect.kt).
    key.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (loading) return
            updateConnSummary()
            val typed = s?.toString().orEmpty()
            val hit = detectProvider(typed)
            if (hit == null || hit !in names) { detectNote.visibility = View.GONE; return }
            val custom = cur.startsWith("Kustom")
            detectNote.text = if (custom) "Terdeteksi: $hit (tidak diganti karena memakai Kustom)" else "Terdeteksi: $hit"
            detectNote.visibility = View.VISIBLE
            if (hit != cur && !custom) {
                pendingKey = typed
                spin.setSelection(names.indexOf(hit))
            }
        }
    })

    fun save() {
        dialog?.dismiss()
        prov = cur
        val wantsAutonomous = autonomous.isChecked
        val normalizedKey = normalizeKey(key.text.toString())
        val encryptedKey = Secret.encrypt(normalizedKey)
        val ed = prefs.edit()
            .putString("prov", cur)
            .putString("p_${cur}_base", base.text.toString().trim())
            .putString("p_${cur}_model", mdl.text.toString().trim())
            .putString("extra", extra.text.toString().trim())
            .putString("workdir", workspaceRelativePath(activeWorkspaceName()))
            .putBoolean("autoread", auto.isChecked)
            .putBoolean("draft", draftSw.isChecked)
            .putBoolean("understand", understandSw.isChecked)
            .putBoolean("readonly", ro.isChecked)
            .putBoolean("sandbox", sandbox.isChecked)
            .putBoolean("apk_re", apkRe.isChecked)
            .putBoolean(PREF_SELF_REPAIR_MODE, selfRepairSw.isChecked)
        if (encryptedKey.isSuccess) {
            ed.putString("p_${cur}_key", encryptedKey.getOrThrow())
        } else {
            toast("API key tidak dapat diamankan oleh Android Keystore. Key tidak disimpan; masukkan ulang nanti.")
        }
        if (!wantsAutonomous || autonomousConfirmed || prefs.getBoolean("autonomous_ack", false)) {
            ed.putBoolean("autonomous", wantsAutonomous)
            if (wantsAutonomous) ed.putBoolean("autonomous_ack", true)
            ed.apply()
        } else {
            ed.apply()
            AlertDialog.Builder(this)
                .setTitle("Aktifkan mode otonom?")
                .setMessage(
                    "Dalam mode otonom, AI dapat menjalankan perintah shell, menulis file, " +
                        "dan memulai pemasangan APK tanpa dialog persetujuan. Aktifkan hanya " +
                        "jika Anda memahami dan menerima risikonya."
                )
                .setNegativeButton("Batal") { _, _ ->
                    prefs.edit().putBoolean("autonomous", false).apply()
                }
                .setPositiveButton("Saya paham, aktifkan") { _, _ ->
                    prefs.edit()
                        .putBoolean("autonomous", true)
                        .putBoolean("autonomous_ack", true)
                        .apply()
                }
                .show()
        }
        updateModelLabel()
        autoSetupModels()
    }

    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL

        // 1. BAGIAN AI & MODEL
        addView(sectionHeader("1. AI & Model", "Penyedia AI, kunci akses, dan kesiapan model"))
        addView(fieldLabel("Penyedia AI"))
        addView(spinHolder, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(keyLabel)
        addView(keyHolder, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(detectNote)
        addView(primaryBaseBox, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(connCard, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        addView(loginStatus)
        addView(loginBtn, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })

        // 2. BAGIAN EKSEKUSI & RUANG KERJA
        addView(divider(), LinearLayout.LayoutParams(MATCH, dp(1)).apply { topMargin = dp(16) })
        addView(sectionHeader("2. Ruang Kerja & Eksekusi", "Folder ruang kerja terisolasi, Termux, dan izin perintah"))
        addView(workspaceCard, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4); bottomMargin = dp(6) })
        addView(roRow)
        addView(autoRow)
        addView(sandboxRow)
        addView(setupBtn)

        // 3. BAGIAN APLIKASI
        addView(divider(), LinearLayout.LayoutParams(MATCH, dp(1)).apply { topMargin = dp(12) })
        addView(sectionHeader("3. Aplikasi", "Perilaku asisten dan alur kerja antarmuka"))
        addView(draftRow)
        addView(understandRow)

        // 4. BAGIAN LANJUTAN
        addView(divider(), LinearLayout.LayoutParams(MATCH, dp(1)).apply { topMargin = dp(12); bottomMargin = dp(12) })
        addView(advBtn, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(adv, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    val footer = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(softButton("Batal") { dialog?.dismiss() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(filledButton("Simpan") { save() }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(10) })
    }
    onLoginRefresh = { checkLogin() }
    dialog = showSheet("Pengaturan", "Konfigurasi ringkas AgentApp", box, footer).also {
        it.setOnDismissListener { onLoginRefresh = null }
    }
}

private fun MainActivity.needsKeyFor(providerName: String, rawBase: String): Boolean {
    val p = preset(providerName)
    val b = rawBase.trim().ifEmpty { p.base }
    return p.fmt != "cli" && !b.startsWith("http://", ignoreCase = true)
}

internal data class ConnTestOutcome(
    val ok: Boolean,
    val message: String,
    val color: Int,
    val suggestedModel: String = ""
)

/** Uji koneksi ke endpoint penyedia AI tanpa membocorkan API key di pesan status maupun log. */
internal fun MainActivity.testApiConnection(fmt: String, baseUrl: String, apiKey: String): ConnTestOutcome {
    return try {
        checkUrl(baseUrl)
        val headers = HashMap<String, String>()
        val url: String
        when (fmt) {
            "gemini" -> {
                url = "$baseUrl/models?pageSize=100"
                headers["x-goog-api-key"] = apiKey
            }
            "anthropic" -> {
                url = "$baseUrl/models?limit=100"
                headers["x-api-key"] = apiKey
                headers["anthropic-version"] = "2023-06-01"
            }
            else -> {
                url = "$baseUrl/models"
                if (apiKey.isNotEmpty()) headers["Authorization"] = "Bearer $apiKey"
            }
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        val code = conn.responseCode
        if (code in 200..299) {
            val txt = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(txt)
            val models = ArrayList<String>()
            if (fmt == "gemini") {
                val arr = json.optJSONArray("models") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    models.add(m.optString("name").removePrefix("models/"))
                }
            } else {
                val arr = json.optJSONArray("data") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    models.add(arr.getJSONObject(i).optString("id"))
                }
            }
            val valid = models.filter { it.isNotBlank() && skipWords.none { w -> it.contains(w, true) } }
            val best = bestModel(valid) ?: valid.firstOrNull().orEmpty()
            val label = if (valid.isNotEmpty()) "Terhubung ✓ (${valid.size} model siap)" else "Terhubung ✓"
            ConnTestOutcome(true, label, Palette.GREEN, best)
        } else if (code == 401 || code == 403) {
            ConnTestOutcome(false, "Gagal: API key ditolak atau tidak valid (HTTP $code)", Palette.RED)
        } else if (code == 429) {
            ConnTestOutcome(true, "Terhubung, tetapi kuota/batas laju penuh (HTTP 429)", Palette.AMBER)
        } else {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            ConnTestOutcome(false, "Gagal (HTTP $code): ${sanitizeConnError(err, apiKey)}", Palette.RED)
        }
    } catch (e: Exception) {
        ConnTestOutcome(false, "Gagal: ${sanitizeConnError(e.message ?: "Tidak dapat terhubung", apiKey)}", Palette.RED)
    }
}
