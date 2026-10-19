package com.kita.agent

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Percobaan ulang saat membuka koneksi (jaringan seluler sering goyah) dan saat stream putus di tengah jalan. Dipersingkat saat peralihan AI. */
private const val OPEN_RETRIES_NORMAL = 5
private const val STREAM_RETRIES_NORMAL = 5
private fun MainActivity.openRetries() = if (fastFail) 1 else OPEN_RETRIES_NORMAL
private fun MainActivity.streamRetries() = if (fastFail) 1 else STREAM_RETRIES_NORMAL

// Pemanggilan model umum: pilih penyedia, ulangi saat gagal, stream, parsing argumen, CLI.
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

internal fun MainActivity.callModel(key: String, onText: (String) -> Unit, onFlush: () -> Unit): Hist? =
    when (preset(prov).fmt) {
        "openai" -> callOpenAI(key, onText, onFlush)
        "anthropic" -> callAnthropic(key, onText, onFlush)
        else -> callGemini(key, onText, onFlush)
    }

/**
 * Ulangi (maks 5 x) kalau koneksi putus/timeout di tengah stream. Teks parsial diganti oleh percobaan baru
 * karena kotak balasan digambar ulang dari awal. Tidak diulang kalau teks sudah di-flush (agar tidak dobel).
 */
internal fun MainActivity.callModelRetryOnce(key: String, onText: (String) -> Unit, onFlush: () -> Unit): Hist? {
    var attempt = 0
    while (true) {
        var flushed = false
        streamOpen = false
        try {
            return callModel(key, onText, { flushed = true; onFlush() })
        } catch (e: java.io.IOException) {
            if (cancelled) throw Cancelled()
            if (!streamOpen || flushed || attempt >= streamRetries()) {
                val part = lastPartial
                if (part.isNotBlank()) {
                    synchronized(hist) { hist.add(Hist(1, part)) }
                    lastPartial = ""
                }
                throw e
            }
            attempt++
            for (sec in minOf(2 shl (attempt - 1), 15) downTo 1) {
                setStatus(IconView.CLOCK, "Koneksi terputus, mengulang dalam $sec dtk ($attempt/${streamRetries()})")
                Thread.sleep(1000)
                if (cancelled) throw Cancelled()
            }
            lastPartial = ""
        }
    }
}

/** Jalankan [block] dengan penyedia [p] (kunci, URL, model milik p). Penyedia aktif dan override model dikembalikan setelahnya. */
internal fun <T> MainActivity.onProvider(p: String?, model: String?, block: () -> T): T {
    if (p == null || p == prov) return block()
    val savedProv = prov
    val savedModel = modelOverride
    prov = p
    modelOverride = model // override model milik penyedia utama tidak berlaku di penyedia lain
    try {
        return block()
    } finally {
        prov = savedProv
        modelOverride = savedModel
    }
}

/** Model pilihan pengguna untuk penyedia [p] (tanpa override peralihan). */
private fun MainActivity.savedModelOf(p: String): String = pvOf(p, "model").ifEmpty { preset(p).model }

/**
 * Daftar model satu penyedia untuk cadangan, atau null bila penyedia itu tidak siap (tanpa API key / URL / model).
 * Memakai hasil uji model bila ada; kalau belum pernah diuji, daftar diambil langsung dari penyedia. Hasil di-cache selama satu tugas.
 */
private fun MainActivity.poolFor(name: String): ProviderPool? {
    if (poolCache.containsKey(name)) return poolCache[name]
    val pool = try {
        onProvider(name, null) {
            val ready = preset(name).fmt != "cli" && cfgBase().isNotBlank() && (!needsKey() || cfgKey().isNotEmpty())
            if (!ready) return@onProvider null
            val saved = savedModelOf(name)
            val probed = loadProbed()
            val models = if (probed != null) probed.first + probed.second else
                try { fetchModels().filter { n -> skipWords.none { w -> n.contains(w, true) } }.take(40) } catch (e: Exception) { emptyList() }
            if (saved.isBlank() && models.isEmpty()) null else ProviderPool(name, saved, models)
        }
    } catch (e: Exception) {
        null
    }
    poolCache[name] = pool
    return pool
}

private const val MAX_ROUNDS = 3
private val ROUND_WAIT_SEC = intArrayOf(10, 25, 45)

private fun MainActivity.applyTarget(c: FoCandidate, mainProv: String) {
    if (c.provider == mainProv) {
        fallbackProv = null
        fallbackModel = null
        modelOverride = c.model.takeIf { it != savedModelOf(mainProv) }
    } else {
        fallbackProv = c.provider
        fallbackModel = c.model
        modelOverride = null
    }
}

/**
 * Panggil model dengan retry. Kalau tetap gagal karena server sibuk / kuota habis / tidak terhubung, sistem otomatis
 * mencari AI yang bisa dipakai: model lain di penyedia yang sama, lalu penyedia lain yang API key-nya terdaftar (semua model
 * yang diketahui, dari yang terbaik). Bila semua sibuk, menunggu lalu mengulang sampai [MAX_ROUNDS] putaran. Begitu ada yang
 * berhasil, giliran dilanjutkan dan AI itu dipakai sampai tugas selesai. Berjalan diam-diam di latar belakang, tanpa kartu atau teks di chat.
 */
internal fun MainActivity.callModelRetry(key: String, onText: (String) -> Unit, onFlush: () -> Unit): Hist? {
    val histSize = synchronized(hist) { hist.size }
    val mainProv = prov
    fun runTarget(): Hist? {
        val fp = fallbackProv
        return if (fp == null) callModelRetryOnce(key, onText, onFlush)
        else onProvider(fp, fallbackModel) { callModelRetryOnce(cfgKey(), onText, onFlush) }
    }

    val first = try {
        return runTarget()
    } catch (e: Exception) {
        if (cancelled || e is Cancelled || preset(prov).fmt == "cli" || !isFallbackWorthy(e)) throw e
        e
    }

    // Teks parsial dari percobaan pertama (sudah masuk riwayat) disimpan; dikembalikan bila semua cadangan gagal.
    val kept = synchronized(hist) { hist.subList(histSize, hist.size).toList() }
    val soft = HashSet<String>() // gagal sementara (sibuk): dicoba lagi di putaran berikutnya
    fun stateOf(e: Throwable): FoState = when {
        e is QuotaExhaustedException -> FoState.QUOTA
        e is TransientApiException -> FoState.BUSY
        e is java.io.IOException -> FoState.OFFLINE
        else -> FoState.REJECTED
    }
    fun mark(p: String, m: String, e: Throwable) {
        val st = stateOf(e)
        val id = "$p/$m"
        if (st == FoState.BUSY || st == FoState.OFFLINE) soft += id else hardFailed += id
    }
    fun trimHist() {
        synchronized(hist) { while (hist.size > histSize) hist.removeAt(hist.size - 1) }
        lastPartial = ""
    }

    val failingProv = fallbackProv ?: mainProv
    val failingModel = if (fallbackProv != null) fallbackModel.orEmpty() else cfgModel()
    mark(failingProv, failingModel, first)
    fastFail = true
    try {
        for (round in 1..MAX_ROUNDS) {
            val pools = presets.map { it.name }.filter { it == mainProv || preset(it).fmt != "cli" }.mapNotNull { poolFor(it) }
            val chain = buildChain(mainProv, pools, hardFailed + soft)
            for (c in chain) {
                if (cancelled) throw Cancelled()
                trimHist()
                applyTarget(c, mainProv)
                try {
                    val r = runTarget()
                    return r
                } catch (e: Exception) {
                    if (e is Cancelled) throw e
                    if (cancelled) throw Cancelled()
                    mark(c.provider, c.model, e)
                }
            }
            if (round == MAX_ROUNDS) break
            if (chain.isEmpty() && soft.isEmpty()) break // semua sudah ditolak / kuota habis; menunggu tidak akan membantu
            for (sec in ROUND_WAIT_SEC[round - 1] downTo 1) {
                Thread.sleep(1000)
                if (cancelled) throw Cancelled()
            }
            soft.clear()
        }
        resetFailover()
        synchronized(hist) { while (hist.size > histSize) hist.removeAt(hist.size - 1); hist.addAll(kept) }
        throw first
    } finally {
        fastFail = false
    }
}

/**
 * Buka koneksi POST streaming. Untuk error sementara (429, 502, 503, 504, 529, atau jaringan putus)
 * otomatis dicoba lagi sampai 5 kali dengan jeda 2, 4, 8, 16 detik (atau sesuai Retry-After / retryDelay dari server, maks 45 detik).
 * Kuota harian habis tidak diulang (QuotaExhaustedException).
 */
internal fun MainActivity.openStream(url: String, headers: Map<String, String>, body: JSONObject): HttpURLConnection {
    checkUrl(url)
    val payload = body.toString().toByteArray()
    var attempt = 0
    while (true) {
        if (cancelled) throw Cancelled()
        var code = -1
        var err = ""
        var retryAfter: Int? = null
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            activeConn = c
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 30000
            c.readTimeout = 180000 // model berpikir/konteks besar bisa lama sebelum token pertama
            c.useCaches = false
            // Jangan pakai ulang koneksi lama: setelah tool berjalan lama (mis. build) koneksi idle sering sudah
            // dimatikan server/NAT dan menghasilkan "unexpected end of stream".
            c.setRequestProperty("Connection", "close")
            c.setRequestProperty("Content-Type", "application/json")
            for ((k, v) in headers) c.setRequestProperty(k, v)
            c.outputStream.use { it.write(payload) }
            code = c.responseCode
            if (code < 400) { streamOpen = true; return c }
            err = c.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            retryAfter = c.getHeaderField("Retry-After")?.trim()?.toIntOrNull()
            c.disconnect()
        } catch (e: java.io.IOException) {
            c.disconnect()
            if (cancelled) throw Cancelled()
            if (attempt >= openRetries()) {
                throw java.io.IOException(
                    "Tidak bisa terhubung ke server AI setelah ${openRetries()} percobaan (${e.message ?: e.javaClass.simpleName}). " +
                        "Periksa sinyal/VPN, lalu kirim \"Lanjutkan\".", e
                )
            }
            err = e.message ?: "koneksi gagal"
            code = -1
        }
        // Kuota harian/kredit habis: mengulang hanya membuang waktu dan kuota. Langsung beri tahu (atau beralih model).
        if (code in retryable && classifyApiError(code, "", err) == ApiErrorKind.QUOTA) {
            throw QuotaExhaustedException(quotaMessage(code, err))
        }
        if (code != -1 && code !in retryable) throw RuntimeException("API $code: ${err.take(300)}")
        if (attempt >= openRetries()) throw TransientApiException("API $code: ${err.take(300)}")
        attempt++
        val wait = minOf(retryAfter ?: parseRetryDelaySec(err) ?: (2 shl (attempt - 1)), 45)
        val snap = saveStatus()
        val why = if (code > 0) "Server sibuk ($code)" else "Koneksi terputus"
        for (sec in wait downTo 1) {
            setStatus(IconView.CLOCK, "$why · ulang $sec dtk ($attempt/${openRetries()})")
            Thread.sleep(1000)
            if (cancelled) throw Cancelled()
        }
        restoreStatus(snap)
    }
}

internal fun MainActivity.str(o: JSONObject, k: String): String = if (o.isNull(k)) "" else o.optString(k, "")

internal fun MainActivity.declList(): List<JSONObject> {
    val decls = tools.getJSONObject(0).getJSONArray("functionDeclarations")
    return (0 until decls.length()).map { decls.getJSONObject(it) }
}

internal fun MainActivity.parseArgs(s: String): JSONObject =
    try { JSONObject(if (s.isBlank()) "{}" else s) } catch (e: Exception) { JSONObject().put("__error", truncMsg) }

/** Argumen kosong pada respons yang terpotong dianggap rusak, bukan "tanpa argumen". */
internal fun MainActivity.argsOf(s: String, truncated: Boolean): JSONObject =
    if (truncated && s.isBlank()) JSONObject().put("__error", truncMsg) else parseArgs(s)

internal fun MainActivity.cliChat(userMsg: String) {
    val lg = cliLoginOf(prov)
    if (lg != null && !cliLoggedIn(lg)) {
        runOnUiThread { showSettings() }
        addMsg(AI, "Belum masuk dengan ${lg.account}. Ketuk \"Masuk dengan ${lg.account}\" di Pengaturan, lalu kirim pesanmu lagi.")
        return
    }
    var t = cfgBase().ifBlank { "gemini -p {prompt} 2>/dev/null" }
    if (!t.contains("{prompt}")) t += " {prompt}"
    val cmd = t.replace("{prompt}", sh(userMsg))
    synchronized(hist) { hist.add(Hist(0, userMsg)) }
    setStatus(IconView.TERMINAL, "Menjalankan CLI di Termux", "$ " + cmd.take(300))
    val approved = cliAllowedTpl == t || confirm(
        "Jalankan di Termux:\n\n$cmd", cmd, "Mengirim pesan Anda ke CLI di Termux",
        onAll = { cliAllowedTpl = t }, allLabel = "Ya, selalu"
    )
    if (!approved) {
        if (cancelled) throw Cancelled()
        val msg = "Ditolak pengguna. CLI tidak dijalankan."
        addMsg(AI, msg)
        synchronized(hist) { hist.add(Hist(1, msg)) }
        saveHist()
        return
    }
    // stdin ditutup: CLI yang membaca stdin (mis. codex) akan menunggu tanpa henti di Termux.
    val out = termux("$cmd < /dev/null", timeoutSec = 300)
    val lines = out.lines()
    val ei = lines.indexOfFirst { it.startsWith("exit=") }
    val body = (if (ei >= 0) lines.take(ei) + lines.drop(ei + 1) else lines).joinToString("\n").trim()
    val shown = when {
        body.isNotEmpty() -> body
        cliLoginOf(prov) != null -> "CLI tidak menjawab. Mungkin belum masuk: buka Pengaturan lalu ketuk \"Masuk dengan ${cliLoginOf(prov)?.account}\"."
        else -> "(CLI tidak mengeluarkan teks)"
    }
    addMsg(AI, shown)
    showChips(shown)
    synchronized(hist) { hist.add(Hist(1, shown)) }
    saveHist()
}
