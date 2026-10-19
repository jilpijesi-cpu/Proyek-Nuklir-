package com.kita.agent

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import java.net.URL

// Dialog Pengaturan (penyedia, kunci API, model, folder kerja, mode baca-saja, mode otonom).
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

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
    val key = EditText(this).apply {
        hint = "Tempel API key (kosong untuk server lokal)"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    val mdl = EditText(this).apply {
        hint = "Nama model"
        inputType = InputType.TYPE_CLASS_TEXT
    }
    val wdir = EditText(this).apply {
        hint = "Folder kerja di Termux, contoh: kita"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        setText(prefs.getString("workdir", "kita"))
    }
    val extra = EditText(this).apply {
        hint = "Instruksi tambahan untuk AI (opsional)"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        minLines = 3
        gravity = Gravity.TOP
        setText(prefs.getString("extra", ""))
    }
    for (f in listOf(base, key, mdl, wdir, extra)) styleField(f)

    /** Satu baris pengaturan: judul + keterangan singkat + saklar. */
    fun toggleRow(title: String, hint: String, on: Boolean): Pair<View, Switch> {
        val sw = Switch(this).apply { isChecked = on }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = title; textSize = 15f; setTextColor(Palette.INK) })
            addView(TextView(context).apply { text = hint; textSize = 12.5f; setTextColor(Palette.INK_SOFT) })
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), 0, dp(8))
            addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(sw, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(12) })
            setOnClickListener { sw.toggle() }
        }
        return row to sw
    }
    val (autoRow, auto) = toggleRow("Setujui otomatis perintah baca", "ls, cat, grep jalan tanpa bertanya", prefs.getBoolean("autoread", true))
    val (draftRow, draftSw) = toggleRow("Susun kode dulu, izin saat build", "AI menulis file di folder kerja tanpa bertanya; build tetap minta izin", prefs.getBoolean("draft", true))
    val (roRow, ro) = toggleRow("Mode hanya baca", "AI tidak boleh menulis atau menghapus", prefs.getBoolean("readonly", false))
    val (autonomousRow, autonomous) = toggleRow("Mode otonom", "AI bertindak tanpa persetujuan (berisiko)", prefs.getBoolean("autonomous", false))
    val (sandboxRow, sandbox) = toggleRow("Mode sandbox", "Perintah jalan di proot-distro, bukan Termux utama", prefs.getBoolean("sandbox", false))
    val (apkReRow, apkRe) = toggleRow("Mode Analisis APK", "Dekompilasi & analisis statis saja; tidak membobol lisensi/DRM atau membajak", prefs.getBoolean("apk_re", false))

    /** Baris tautan: teks + panah, seluruh baris bisa diketuk. */
    fun linkRow(label: String, onClick: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = pressable(null)
        setPadding(dp(4), dp(14), dp(4), dp(14))
        addView(TextView(context).apply { text = label; textSize = 15f; setTextColor(Palette.INK) }, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(TextView(context).apply { text = "›"; textSize = 20f; setTextColor(Palette.INK_SOFT) })
        setOnClickListener { onClick() }
    }
    fun divider(): View = View(this).apply { setBackgroundColor(Palette.DIVIDER) }
    val keyLabel = fieldLabel("API key")
    var cur = prov
    var needBase = false
    var isCliCur = false
    var loading = false // true saat kolom diisi dari penyimpanan (bukan ketikan pengguna)
    var pendingKey: String? = null // key hasil tempel yang harus tetap tampil setelah ganti penyedia
    val detectNote = TextView(this).apply {
        textSize = 12.5f
        setTextColor(Palette.INK_SOFT)
        setPadding(dp(4), dp(6), 0, 0)
        visibility = View.GONE
    }
    var advOpen = false
    val reportBtn = linkRow("Salin laporan masalah") { copyReport() }
    val setupBtn = linkRow("Siapkan Termux") { showSetup() }
    val logBtn = linkRow("Catatan perintah") { startActivity(Intent(this, CommandLogActivity::class.java)) }
    var curLogin: CliLogin? = null
    val loginStatus = TextView(this).apply {
        textSize = 13.5f
        setTextColor(Palette.INK_SOFT)
        setPadding(dp(4), dp(10), 0, 0)
        visibility = View.GONE
    }
    val loginBtn = TextView(this).apply {
        textSize = 15f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = pressable(rounded(Palette.BRAND, 24))
        setPadding(dp(20), dp(12), dp(20), dp(12))
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
                loginStatus.setTextColor(Palette.INK_SOFT)
            }
        }.start()
    }
    loginStatus.setOnClickListener { checkLogin() }
    val adv = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        addView(fieldLabel("Folder kerja"))
        addView(wdir)
        addView(autoRow)
        addView(draftRow)
        addView(roRow)
        addView(autonomousRow)
        addView(sandboxRow)
        addView(apkReRow)
        addView(fieldLabel("Instruksi tambahan"))
        addView(extra)
    }
    val advBtn = TextView(this).apply {
        text = "Lanjutan ▾"
        textSize = 14f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setTextColor(Palette.BRAND)
        setPadding(dp(4), dp(14), 0, dp(8))
    }
    fun refresh() {
        adv.visibility = if (advOpen) View.VISIBLE else View.GONE
        base.visibility = if (needBase || advOpen) View.VISIBLE else View.GONE
        // Cukup isi API key: nama model diisi otomatis setelah Simpan, kolomnya hanya tampil di "Lanjutan".
        key.visibility = if (isCliCur) View.GONE else View.VISIBLE
        keyLabel.visibility = key.visibility
        mdl.visibility = if (isCliCur || !(advOpen || needBase)) View.GONE else View.VISIBLE
        advBtn.text = if (advOpen) "Lanjutan ▴" else "Lanjutan ▾"
    }
    advBtn.setOnClickListener { advOpen = !advOpen; refresh() }
    fun load(n: String) {
        val p = preset(n)
        val isCli = p.fmt == "cli"
        base.hint = if (isCli) "Perintah CLI, pakai {prompt}" else "Alamat API (base URL)"
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
    var dialog: Dialog? = null
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
            .putString("workdir", wdir.text.toString().trim())
            .putBoolean("autoread", auto.isChecked)
            .putBoolean("draft", draftSw.isChecked)
            .putBoolean("readonly", ro.isChecked)
            .putBoolean("sandbox", sandbox.isChecked)
            .putBoolean("apk_re", apkRe.isChecked)
        if (encryptedKey.isSuccess) {
            ed.putString("p_${cur}_key", encryptedKey.getOrThrow())
        } else {
            toast("API key tidak dapat diamankan oleh Android Keystore. Key tidak disimpan; masukkan ulang nanti.")
        }
        ed.apply()
        if (!wantsAutonomous || prefs.getBoolean("autonomous_ack", false)) {
            prefs.edit().putBoolean("autonomous", wantsAutonomous).apply()
        } else {
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
        addView(fieldLabel("Penyedia AI"))
        addView(spinHolder, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(keyLabel)
        addView(key, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(detectNote)
        addView(base, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        addView(mdl, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        addView(loginStatus)
        addView(loginBtn, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        addView(divider(), LinearLayout.LayoutParams(MATCH, dp(1)).apply { topMargin = dp(18) })
        addView(logBtn)
        addView(reportBtn)
        addView(setupBtn)
        addView(divider(), LinearLayout.LayoutParams(MATCH, dp(1)))
        addView(advBtn)
        addView(adv)
    }
    val footer = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(softButton("Batal") { dialog?.dismiss() }, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(filledButton("Simpan") { save() }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(10) })
    }
    onLoginRefresh = { checkLogin() }
    dialog = showSheet("Pengaturan", null, box, footer).also {
        it.setOnDismissListener { onLoginRefresh = null }
    }
}
