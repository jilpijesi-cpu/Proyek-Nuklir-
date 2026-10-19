package com.kita.agent

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.content.res.Configuration
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.util.concurrent.CountDownLatch
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {

    internal var tts: TextToSpeech? = null

    internal val presets = listOf(
        Preset("Gemini", "gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-3.8-flash"),
        Preset("OpenAI", "openai", "https://api.openai.com/v1", ""),
        Preset("Anthropic", "anthropic", "https://api.anthropic.com/v1", ""),
        Preset("OpenRouter", "openai", "https://openrouter.ai/api/v1", ""),
        Preset("Groq", "openai", "https://api.groq.com/openai/v1", ""),
        Preset("DeepSeek", "openai", "https://api.deepseek.com", ""),
        Preset("Ollama lokal", "openai", "http://127.0.0.1:11434/v1", ""),
        Preset("Kustom (OpenAI)", "openai", "", "")
    ) + CLI_LOGINS.map { Preset(it.name, "cli", it.run, "") } + Preset("CLI Termux", "cli", "gemini -p {prompt}", "")

    internal var prov = "Gemini"

    @Volatile internal var agentState: AgentState = AgentState.Idle
    @Volatile internal var agentTaskId = ""

    internal fun preset(n: String) = presets.firstOrNull { it.name == n } ?: presets[0]

    @Volatile internal var keyWarning: String? = null

    private fun readStoredKey(storageName: String): String {
        val raw = prefs.getString(storageName, "") ?: ""
        return when (val result = Secret.decrypt(raw)) {
            is SecretDecryptResult.Success -> result.value
            SecretDecryptResult.Missing -> {
                prefs.edit().remove(storageName).apply()
                keyWarning = "API key lama tidak dapat dibuka karena kunci Keystore hilang. Masukkan ulang API key."
                ""
            }
            SecretDecryptResult.Corrupt -> {
                prefs.edit().remove(storageName).apply()
                keyWarning = "API key tersimpan rusak dan sudah dihapus. Masukkan ulang API key."
                ""
            }
            SecretDecryptResult.KeystoreUnavailable -> {
                keyWarning = "Android Keystore tidak tersedia. API key tidak dibaca; coba lagi atau masukkan ulang nanti."
                ""
            }
        }
    }

    internal fun pvOf(n: String, k: String): String {
        val storageName = "p_${n}_$k"
        return if (k == "key") readStoredKey(storageName) else prefs.getString(storageName, "") ?: ""
    }

    /** Enkripsi kunci API lama yang masih tersimpan sebagai teks biasa. */
    internal fun migrateKeys() {
        val ed = prefs.edit()
        var changed = false
        for (pr in presets) {
            val k = "p_${pr.name}_key"
            val raw = prefs.getString(k, "") ?: ""
            if (raw.isNotEmpty() && !Secret.isEncrypted(raw)) {
                val enc = Secret.encrypt(raw).getOrNull()
                if (enc != null && Secret.isEncrypted(enc)) { ed.putString(k, enc); changed = true }
                else { ed.remove(k); changed = true; keyWarning = "API key lama tidak dapat diamankan dan sudah dihapus. Masukkan ulang API key." }
            }
        }
        val old = prefs.getString("key", "") ?: ""
        if (old.isNotEmpty() && !Secret.isEncrypted(old)) {
            val enc = Secret.encrypt(old).getOrNull()
            if (enc != null && Secret.isEncrypted(enc)) { ed.putString("key", enc); changed = true }
            else { ed.remove("key"); changed = true; keyWarning = "API key lama tidak dapat diamankan dan sudah dihapus. Masukkan ulang API key." }
        }
        if (changed) ed.apply()
    }

    internal fun cfgKey(): String {
        val k = pvOf(prov, "key")
        return if (k.isEmpty() && prov == "Gemini") readStoredKey("key") else k
    }

    /** Model cadangan sementara selama satu tugas (diisi callModelRetry saat model utama sibuk/kuota habis). */
    @Volatile internal var modelOverride: String? = null

    /** Penyedia cadangan sementara selama satu tugas (memakai API key lain yang sudah terdaftar). Null = pakai penyedia utama. */
    @Volatile internal var fallbackProv: String? = null

    /** Model yang dipakai pada [fallbackProv] (model penyedia lain, bukan pilihan di Pengaturan). */
    @Volatile internal var fallbackModel: String? = null

    /** True selama peralihan otomatis: percobaan ulang dipersingkat agar pindah ke AI lain lebih cepat. */
    @Volatile internal var fastFail = false

    /** Cache daftar model per penyedia, dan model yang gagal selama tugas ini (kuota habis / ditolak). Dikosongkan tiap tugas baru. */
    internal val poolCache = HashMap<String, ProviderPool?>()
    internal val hardFailed = HashSet<String>()

    internal fun resetFailover() {
        modelOverride = null
        fallbackProv = null
        fallbackModel = null
        fastFail = false
        poolCache.clear()
        hardFailed.clear()
    }

    internal fun cfgModel(): String = modelOverride ?: pvOf(prov, "model").ifEmpty { preset(prov).model }

    internal fun cfgBase(): String = pvOf(prov, "base").ifEmpty { preset(prov).base }

    internal fun needsKey(): Boolean = preset(prov).fmt != "cli" && !cfgBase().startsWith("http://")

    internal fun workdir(): String = (prefs.getString("workdir", "kita") ?: "kita").trim().trimEnd('/')

    internal fun baseSystem(): String {
        val wd = workdir()
        val where = when {
            wd.isEmpty() || wd == "~" -> "home Termux"
            wd.startsWith("/") || wd.startsWith("~/") -> "folder kerja $wd"
            else -> "folder kerja ~/$wd"
        }
        return "Kamu asisten pengembang yang cerdas dan mandiri, bekerja di dalam aplikasi AgentApp. " +
            "Lingkunganmu: perintah berjalan di Termux (Android) dengan direktori kerja = $where; path relatif terhadap folder itu. " +
            "Alatmu: run_command, write_file, dan ask_user (bertanya ke pengguna lewat dialog). Isi 'deskripsi' tiap tool dengan kalimat singkat tentang langkahmu. " +
            (if (sandboxEnabled()) "File lampiran pengguna otomatis disalin ke ${sandboxConfig.workdir}/ di dalam sandbox dengan nama file yang sama " +
                "(contoh: /sdcard/Download/ClaudeMythos/a.zip menjadi ${sandboxConfig.workdir}/a.zip). Selalu baca salinan itu, jangan coba /sdcard. "
            else "File lampiran pengguna ada di /sdcard/Download/ClaudeMythos/ dan bisa dibaca dari Termux. ") +
            "Kamu yang menentukan sendiri: perlu bertindak atau cukup menjawab, alat apa yang dipakai, seberapa dalam menyelidiki, dan kapan perlu bertanya. " +
            "Sapaan atau obrolan biasa cukup dijawab langsung. " +
            "Untuk bekerja lebih baik: pahami tujuan sebenarnya di balik permintaan; baca sebelum mengubah; " +
            "kalau gagal, pelajari penyebabnya lalu ubah pendekatan; periksa hasilmu sendiri sebelum menyatakan selesai; " +
            "laporkan apa adanya, termasuk yang belum terverifikasi atau tidak bisa kamu lakukan. " +
            "Jawab dalam bahasa Indonesia yang ringkas dan jelas untuk layar HP, tanpa emoji.\n\n" +
            buildPersistenceDirective()
    }

    internal val desk =
        "Kalimat sangat singkat (maks 5 kata) berbahasa Indonesia, diawali kata kerja, tentang apa yang sedang kamu lakukan, " +
        "contoh: 'Membaca MainActivity.kt' atau 'Memperbaiki error build'"

    internal lateinit var prefs: SharedPreferences

    internal val hist = ArrayList<Hist>()

    internal lateinit var chatBox: LinearLayout

    internal lateinit var scroll: ScrollView
    internal lateinit var scrollDownBtn: TextView
    @Volatile internal var userScrolledUp = false

    internal lateinit var emptyState: View

    internal lateinit var modelLabel: TextView

    /** Judul bilah atas ("Menunggu perintah Anda..." / "Sedang bekerja..."). */
    internal lateinit var headerTitle: TextView

    internal fun refreshHeader() {
        if (::headerTitle.isInitialized) headerTitle.text = if (busy) "Sedang bekerja..." else "Menunggu perintah Anda..."
    }

    internal lateinit var input: EditText

    internal lateinit var actionIcon: IconView

    internal lateinit var actionBg: GradientDrawable

    // Baris status tunggal (ikon + teks berkilau + tanda >)
    internal lateinit var statusRoot: LinearLayout

    internal lateinit var statusLead: FrameLayout

    internal lateinit var statusTitle: ShimmerText
    // Satu baris status dinamis: hanya merotasi penjelasan yang sesuai fase aktif.
    internal val statusTickerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    internal var statusTickerTask: Runnable? = null
    internal var statusBaseTitle = ""

    internal lateinit var statusChevron: IconView

    internal lateinit var statusDetail: TextView

    internal var leadKind = -2

    internal var detailOpen = false

    @Volatile internal var curKind = KIND_DOTS

    @Volatile internal var curTitle = ""

    @Volatile internal var curDetail = ""

    @Volatile internal var curQuiet = false

    /** Panel aktivitas AI tetap di bawah header: wadah kartu (maks 2) + area teks tengah / laporan akhir. */
    internal lateinit var zone: ActivityZone

    /** Fakta nyata putaran agen yang sedang/baru berjalan; sumber laporan akhir. */
    internal val runLog = RunLog()

    /** Langkah alat yang sedang berjalan; dipakai untuk menandai "menunggu izin". */
    @Volatile internal var activeStep: StepCard? = null

    /** Sumber StateFlow untuk daftar langkah live yang dapat dibuka dari baris aktivitas compact. */
    internal val agentSteps = AgentStepStore()

    @Volatile internal var busy = false

    /** True selama riwayat lama dipulihkan: gelembung tanpa jam (waktu aslinya tidak tersimpan). */
    internal var restoring = false

    internal var reqCode = 0

    internal lateinit var pillView: View

    /** Lebar kolom isi maksimum (dp): di tablet / lanskap isi chat tetap nyaman dibaca, di tengah layar. */
    private val maxColumnDp = 760

    internal fun applyResponsive() {
        if (!::chatBox.isInitialized) return
        val wDp = resources.configuration.screenWidthDp
        val side = maxOf(16, (wDp - maxColumnDp) / 2)
        chatBox.setPadding(dp(side), dp(8), dp(side), dp(16))
        (pillView.layoutParams as? LinearLayout.LayoutParams)?.let {
            val m = dp(maxOf(10, side - 4))
            it.setMargins(m, dp(4), m, dp(10))
            pillView.layoutParams = it
        }
        attachScroll.setPadding(dp(side), dp(4), dp(side), dp(4))
        if (::zone.isInitialized) zone.setPadding(dp(side), dp(6), dp(side), 0)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyResponsive()
    }

    @Volatile internal var cancelled = false

    @Volatile internal var pendingInterrupt: String? = null
    @Volatile internal var voiceTurnPending = false

    @Volatile internal var allowAll = false
    @Volatile internal var lastApprovalMode = ApprovalMode.MANUAL // diisi confirm(), dibaca runTool() untuk catatan

    @Volatile internal var activeConn: HttpURLConnection? = null

    @Volatile internal var lastPartial = ""

    internal var worker: Thread? = null

    internal var confirmDialog: AlertDialog? = null

    internal var streamText = ""
    internal val typewriterHandler = android.os.Handler(android.os.Looper.getMainLooper())
    internal var typewriterTarget = ""
    internal var typewriterShown = ""
    internal var typewriterTick: Runnable? = null
    @Volatile internal var askSkipped = false
    internal var streamEnded = false
    internal var streamCopyAdded = false

    internal fun col(s: String) = Color.parseColor(s)

    internal fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    internal fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    internal fun decl(name: String, desc: String, vararg params: Pair<String, String>): JSONObject {
        val props = JSONObject()
        val req = JSONArray()
        for ((n, d) in params) {
            props.put(n, JSONObject().put("type", "string").put("description", d))
            req.put(n)
        }
        return JSONObject().put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", req))
    }

    internal val tools: JSONArray by lazy {
        JSONArray().put(
            JSONObject().put(
                "functionDeclarations",
                JSONArray()
                    .put(
                        decl(
                            "run_command",
                            "Jalankan perintah bash di Termux (ls, cat, grep, ./gradlew, dll).",
                            "deskripsi" to desk, "command" to "Perintah bash"
                        )
                    )
                    .put(
                        decl(
                            "write_file",
                            "Tulis (timpa) file dengan isi lengkap. Path relatif terhadap folder kerja (sama seperti run_command), atau path absolut. Awalan ~/ berarti home Termux.",
                            "deskripsi" to desk, "path" to "Path file", "content" to "Isi lengkap file"
                        )
                    )
                    .put(
                        decl(
                            "ask_user",
                            "Tanyakan sesuatu ke pengguna lewat dialog saat kamu butuh keputusan, izin, atau informasi yang tidak bisa kamu tentukan sendiri. Isi pilihan jika jawabannya pendek.",
                            "pertanyaan" to "Pertanyaan singkat", "pilihan" to "Pilihan jawaban dipisah dengan | (opsional, kosongkan untuk jawaban bebas)"
                        )
                    )
            )
        )
    }

    // ---------------------------------------------------------------- UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale("id", "ID")
        }
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        // Versi lama pernah mengaktifkan mode otonom secara default; minta opt-in eksplisit setelah upgrade.
        if (prefs.getBoolean("autonomous", false) && !prefs.getBoolean("autonomous_ack", false)) {
            prefs.edit().putBoolean("autonomous", false).apply()
        }
        prov = prefs.getString("prov", "Gemini") ?: "Gemini"
        migrateKeys()
        summaryText = prefs.getString("sum_text", "") ?: ""
        sumUpto = prefs.getInt("sum_upto", 0)

        window.statusBarColor = Palette.BAR
        window.navigationBarColor = Palette.NAV
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
        }

        // Bilah atas pucat: panah kembali | tiga bola + status | telepon (suara) | caret (model) | menu
        modelLabel = TextView(this) // teks model dipakai pemilih model; tidak ditampilkan di bilah
        fun barBtn(type: Int, onClick: (View) -> Unit): View = FrameLayout(this).apply {
            background = pressable(null)
            addView(IconView(context, type, Palette.INK), FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            setOnClickListener { onClick(this) }
        }
        headerTitle = TextView(this).apply {
            text = "Menunggu perintah Anda..."
            textSize = 14f
            setTextColor(Palette.INK)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setOnClickListener { pickModel() }
        }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Palette.BAR)
            elevation = 0f
            setPadding(dp(8), dp(6), dp(4), dp(6))
            addView(barBtn(IconView.BACK) { onBackPressed() }, LinearLayout.LayoutParams(dp(40), dp(44)))
            addView(DotsView(context), LinearLayout.LayoutParams(dp(36), dp(24)).apply { marginStart = dp(2) })
            addView(headerTitle, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(4) })
            addView(barBtn(IconView.PHONE) { voice() }, LinearLayout.LayoutParams(dp(38), dp(44)))
            addView(barBtn(IconView.CHEVRON_DOWN) { pickModel() }, LinearLayout.LayoutParams(dp(28), dp(44)))
            addView(barBtn(IconView.DOTS_V) { anchor ->
                val menu = android.widget.PopupMenu(this@MainActivity, anchor)
                menu.menu.add(0, 1, 0, "Chat baru")
                menu.menu.add(0, 2, 1, "Model AI")
                menu.menu.add(0, 3, 2, "Pengaturan")
                menu.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> confirmDelete()
                        2 -> pickModel()
                        3 -> showSettings()
                    }
                    true
                }
                menu.show()
            }, LinearLayout.LayoutParams(dp(34), dp(44)))
        }
        updateModelLabel()

        // Area tengah: daftar chat + sapaan kosong
        chatBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        scroll = ScrollView(this).apply { addView(chatBox) }
        val emptyContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(LogoView(context).apply { setColorFilter(Color.WHITE) }, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(TextView(context).apply {
                text = "Halo, mau mengerjakan apa?"
                textSize = 24f
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(16) })
            addView(TextView(context).apply {
                text = "Tulis perintah, atau pilih salah satu."
                textSize = 14.5f
                gravity = Gravity.CENTER
                setTextColor(Palette.ON_BLUE_SOFT)
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6); bottomMargin = dp(20) })
            for ((label, prompt) in listOf(
                "Lihat isi folder" to "Lihat isi folder kerja lalu jelaskan singkat.",
                "Cari dan perbaiki error" to "Cari error di kode lalu perbaiki.",
                "Build aplikasi" to "Build aplikasi dengan ./gradlew assembleDebug."
            )) {
                addView(TextView(context).apply {
                    text = label
                    textSize = 15f
                    gravity = Gravity.CENTER
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                    setTextColor(Color.WHITE)
                    background = pressable(rounded(0x33FFFFFF, 22, 0x66FFFFFF))
                    setPadding(dp(20), dp(11), dp(20), dp(11))
                    setOnClickListener {
                        input.setText(prompt)
                        input.setSelection(input.text.length)
                        input.requestFocus()
                    }
                }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(8) })
            }
        }
        emptyState = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(emptyContent, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
        }
        scrollDownBtn = TextView(this).apply {
            text = "↓"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Palette.TEXT_MAIN)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(1), Palette.DIVIDER)
            }
            elevation = dp(3).toFloat()
            visibility = View.GONE
            setOnClickListener { forceScrollDown() }
        }
        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            val content = scroll.getChildAt(0)
            val atBottom = content == null || content.height - (y + scroll.height) <= dp(36)
            userScrolledUp = !atBottom
            scrollDownBtn.visibility = if (atBottom) View.GONE else View.VISIBLE
        }
        val center = FrameLayout(this).apply {
            addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(emptyState, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(scrollDownBtn, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, dp(18), dp(18))
            })
        }

        buildStatusRow()

        // Kolom input putih bulat: stiker | teks | lampiran | garis | suara / kirim / berhenti
        val stickerBtn = FrameLayout(this).apply {
            addView(IconView(context, IconView.SMILEY, 0xFF4A5560.toInt()), FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
            setOnClickListener { showQuickPrompts(this) }
        }
        input = EditText(this).apply {
            hint = "Pesan..."
            setHintTextColor(Palette.INK_SOFT)
            setTextColor(Palette.INK)
            textSize = 16f
            background = null
            maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setPadding(0, dp(10), 0, dp(10))
        }
        val clipBtn = FrameLayout(this).apply {
            addView(IconView(context, IconView.CLIP, 0xFF4A5560.toInt()), FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
            setOnClickListener { showTemplates(this) }
        }
        actionIcon = IconView(this, IconView.MIC, Palette.INK)
        actionBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
        }
        val actionBtn = FrameLayout(this).apply {
            background = actionBg
            addView(actionIcon, FrameLayout.LayoutParams(MATCH, MATCH))
            setOnClickListener { onAction() }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateAction()
            }
        })
        val pillBg = rounded(Color.WHITE, 28)
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillBg
            elevation = dp(2).toFloat()
            minimumHeight = dp(52)
            setPadding(dp(12), dp(4), dp(8), dp(4))
            addView(stickerBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
            addView(input, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(6) })
            addView(clipBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
            addView(View(context).apply { setBackgroundColor(Palette.DIVIDER) }, LinearLayout.LayoutParams(dp(1), dp(24)).apply {
                marginStart = dp(4); marginEnd = dp(4)
            })
            addView(actionBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        }

        zone = ActivityZone(this)
        root.addView(topBar, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(center, LinearLayout.LayoutParams(MATCH, 0, 1f))
        attachRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        attachScroll = HorizontalScrollView(this).apply {
            visibility = View.GONE
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(16), dp(4), dp(16), dp(4))
            addView(attachRow)
        }
        root.addView(attachScroll, LinearLayout.LayoutParams(MATCH, WRAP))
        pillView = pill
        root.addView(pill, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(10), dp(4), dp(10), dp(10)) })

        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Palette.SKY_TOP)
            addView(ChatBackground(context), FrameLayout.LayoutParams(MATCH, MATCH))
            addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
        })
        applyResponsive()
        restoreHistory()

        val perms = ArrayList<String>()
        perms.add("com.termux.permission.RUN_COMMAND")
        if (Build.VERSION.SDK_INT >= 33) perms.add("android.permission.POST_NOTIFICATIONS")
        requestPermissions(perms.toTypedArray(), 1)
    }

    internal val skipWords = listOf(
        "image", "tts", "embed", "computer-use", "deep-research", "imagen", "veo", "live", "audio",
        "robotics", "aqa", "banana", "lyria", "whisper", "dall-e", "moderation", "davinci", "babbage", "transcribe", "realtime"
    )

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if ((requestCode == 78 || requestCode == 79) && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) importFile(uri)
        }
        if (requestCode == 77 && resultCode == RESULT_OK) {
            val r = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!r.isNullOrEmpty()) {
                input.setText(r[0])
                input.setSelection(input.text.length)
                if (voiceTurnPending) {
                    voiceTurnPending = false
                    send()
                }
            }
        } else if (requestCode == 77) {
            voiceTurnPending = false
        }
    }

    @Volatile internal var pendingImport: Uri? = null
    internal val attachments = ArrayList<Attachment>()
    internal lateinit var attachScroll: HorizontalScrollView
    internal lateinit var attachRow: LinearLayout

    // ------------------------------------------------ Balasan AI: streaming + markdown

    internal var streamBox: LinearLayout? = null

    internal val fenceRe = Regex("```([^\\n`]*)\\n(.*?)(```|\\z)", RegexOption.DOT_MATCHES_ALL)

    internal val headRe = Regex("^(#{1,6})\\s+(.*)$")

    internal val bulletRe = Regex("^(\\s*)[-*+]\\s+(.*)$")

    internal val inlineRe = Regex("`([^`]+)`|\\*\\*(.+?)\\*\\*|(?<![\\w*])\\*(?!\\s)([^*\\n]+?)(?<!\\s)\\*(?![\\w*])")

    internal val kwSet = setOf(
        "fun", "val", "var", "class", "object", "interface", "override", "private", "public", "protected", "internal",
        "if", "else", "when", "for", "while", "do", "return", "break", "continue", "import", "package", "null",
        "true", "false", "this", "super", "is", "in", "as", "try", "catch", "finally", "throw", "new", "static",
        "final", "void", "int", "long", "double", "float", "boolean", "char", "string", "let", "const", "function",
        "def", "lambda", "from", "with", "pass", "None", "True", "False", "elif", "except", "raise", "yield",
        "async", "await", "fi", "then", "done", "esac", "case", "echo", "cd", "export", "set", "local", "sudo",
        "enum", "data", "sealed", "abstract", "open", "lateinit", "by", "init", "constructor", "extends",
        "implements", "switch", "default", "struct", "impl", "pub", "use", "mod", "mut", "fn", "match", "self"
    )

    internal val hashLangs = setOf("bash", "sh", "shell", "zsh", "python", "py", "yaml", "yml", "toml", "ruby", "rb", "properties", "dockerfile")

    // ------------------------------------------------ Siapkan Termux

    internal var setupRecheck: (() -> Unit)? = null

    internal val allowCmd =
        "mkdir -p ~/.termux && (grep -q '^allow-external-apps=true' ~/.termux/termux.properties 2>/dev/null || " +
        "echo 'allow-external-apps=true' >> ~/.termux/termux.properties) && termux-reload-settings"

    internal val toolCheckCmd =
        "for t in java git; do command -v \$t >/dev/null 2>&1 && echo \$t=ok || echo \$t=no; done; " +
        "(ls /sdcard/Download >/dev/null 2>&1 && echo storage=ok) || echo storage=no"

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2) {
            val u = pendingImport
            pendingImport = null
            if (u != null) {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) importFile(u)
                else toast("Izin penyimpanan ditolak, file tidak dilampirkan")
            }
            return
        }
        setupRecheck?.invoke()
    }

    // ------------------------------------------------ Kartu langkah (baca / tulis / build)

    internal fun <T> onUi(f: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return f()
        val box = arrayOfNulls<Any>(1)
        val err = arrayOfNulls<Throwable>(1)
        val l = CountDownLatch(1)
        runOnUiThread {
            try { box[0] = f() } catch (t: Throwable) { err[0] = t } finally { l.countDown() }
        }
        l.await()
        err[0]?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return box[0] as T
    }

    /** Buat satu baris langkah di kartu Aktivitas Agen. Panggil di thread UI. */
    internal fun newStepCard(
        title: String, body: String, icon: Int = IconView.BLOB_TERM, tool: String = "", target: String = ""
    ): StepCard {
        val row = feedForNewStep().addStep(title, body, icon)
        val type = when (tool) {
            "run_command" -> if (body.startsWith("$ ")) AgentStepType.COMMAND else AgentStepType.READ_FILE
            "write_file" -> AgentStepType.TOOL
            else -> AgentStepType.THINKING
        }
        val step = AgentStep(
            type = type,
            title = title,
            fullCommand = body,
            status = AgentStepStatus.RUNNING,
            startedAt = System.currentTimeMillis()
        )
        agentSteps.add(step)
        zone.renderSteps(agentSteps.groups.value)
        return StepCard(row, tool, title, target, step.id)
    }

    /** Pegangan satu langkah di feed: hasil tool memperbarui barisnya (status, waktu, detail). */
    internal inner class StepCard(
        private val row: FeedRow?,
        private val tool: String = "",
        private val title: String = "",
        private val target: String = "",
        private val stepId: String = ""
    ) {
        private var recorded = false

        fun finish(ok: Boolean, note: String = "", body: CharSequence? = null, state: StepState? = null) {
            val st = state ?: if (ok) StepState.OK else StepState.FAIL
            // Catat fakta langkah untuk laporan akhir (sekali saja per langkah; RunLog aman lintas-thread).
            if (!recorded && row != null) {
                recorded = true
                runLog.record(StepRecord(tool, title, target, row.kind, st, System.currentTimeMillis() - row.startedAt))
            }
            if (stepId.isNotBlank()) agentSteps.finish(stepId, st == StepState.OK, body?.toString())
            runOnUiThread {
                row?.finish(st, note, body)
                zone.renderSteps(agentSteps.groups.value)
            }
        }

        fun setWaiting(w: Boolean) {
            if (stepId.isNotBlank()) agentSteps.update(stepId) { it.copy(status = if (w) AgentStepStatus.WAITING else AgentStepStatus.RUNNING) }
            runOnUiThread {
                row?.setWaiting(w)
                zone.renderSteps(agentSteps.groups.value)
            }
        }

        /** Keluaran sebagian dari perintah yang sedang berjalan (tampil real-time di kartu). */
        fun liveOutput(text: String) {
            if (stepId.isNotBlank()) agentSteps.update(stepId) { it.copy(output = AgentStepStore.truncateOutput(text)) }
            runOnUiThread {
                row?.setLive(text)
                zone.renderSteps(agentSteps.groups.value)
            }
        }
    }

    // ------------------------------------------------ Chip saran setelah balasan

    internal var chipsRow: View? = null

    @Volatile internal var sawBuild = false

    @Volatile internal var sawFail = false
    @Volatile internal var taskBrief = ""

    override fun onResume() {
        super.onResume()
        onLoginRefresh?.invoke()
    }

    override fun onDestroy() {
        typewriterHandler.removeCallbacksAndMessages(null)
        tts?.stop()
        tts?.shutdown()
        tts = null
        releaseWaits()
        if (isFinishing) {
            cancelled = true
            try { activeConn?.disconnect() } catch (e: Exception) { /* cleanup best-effort: kegagalan tidak berdampak */ }
            worker?.interrupt()
            confirmDialog?.dismiss()
            try { stopService(Intent(this, AgentService::class.java)) } catch (e: Exception) { /* cleanup best-effort: kegagalan tidak berdampak */ }
        }
        super.onDestroy()
    }

    // ------------------------------------------------ Persetujuan otomatis untuk perintah baca saja

    internal val roCmds = setOf(
        "ls", "cat", "grep", "egrep", "fgrep", "head", "tail", "wc", "pwd", "tree",
        "find", "du", "df", "stat", "diff", "cut", "basename", "dirname", "which"
    )

    internal val findBad = setOf(
        "-exec", "-execdir", "-ok", "-okdir", "-delete", "-fprint", "-fprint0", "-fprintf", "-fls"
    )

    @Volatile internal var ctxOverride: List<Hist>? = null

    @Volatile internal var sysOverride: String? = null

    @Volatile internal var summaryText = ""

    @Volatile internal var sumUpto = 0

    internal var compactSkipUntil = 0

    internal val ctxBudget = 100_000

    internal val retryable = setOf(408, 429, 500, 502, 503, 504, 529)

    /** True setelah koneksi streaming berhasil dibuka; dipakai membedakan putus di tengah stream dari gagal konek. */
    @Volatile internal var streamOpen = false

    internal val truncMsg = "Argumen tool tidak valid atau terpotong (kemungkinan batas token tercapai). " +
        "Pecah pekerjaan menjadi bagian lebih kecil, misalnya beberapa file yang lebih pendek, lalu coba lagi."

    // ---- CLI di Termux (gemini, claude, codex, dll). Tanpa memori percakapan antar pesan.
    /** Template CLI yang sudah disetujui "YA, SELALU"; berlaku sampai percakapan dihapus atau template diubah. */
    @Volatile internal var cliAllowedTpl: String? = null

    /** Diisi dialog Pengaturan selagi terbuka: dipanggil saat pengguna kembali ke aplikasi (mis. selesai login di browser). */
    @Volatile internal var onLoginRefresh: (() -> Unit)? = null

    internal val buildRe = Regex("""gradle\S*\s+.*assemble""")

    internal val termuxHome = "/data/data/com.termux/files/home"

    internal val termuxTmp = "/data/data/com.termux/files/usr/tmp"
}
