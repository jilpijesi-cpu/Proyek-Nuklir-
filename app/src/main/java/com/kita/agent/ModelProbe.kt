package com.kita.agent

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

// Daftar model dan uji (probe) model: cek URL, ambil daftar, simpan hasil.
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

internal fun MainActivity.loadProbed(): Pair<List<String>, List<String>>? {
    val raw = pvOf(prov, "probe")
    if (raw.isEmpty()) return null
    return try {
        val o = JSONObject(raw)
        if (o.optInt("h") != cfgKey().hashCode()) return null
        val g = o.optJSONArray("g") ?: JSONArray()
        val b = o.optJSONArray("b") ?: JSONArray()
        val gl = (0 until g.length()).map { g.getString(it) }
        val bl = (0 until b.length()).map { b.getString(it) }
        if (gl.isEmpty() && bl.isEmpty()) null else Pair(gl, bl)
    } catch (e: Exception) {
        null
    }
}

internal fun MainActivity.saveProbed(good: List<String>, busy: List<String>) {
    val o = JSONObject().put("h", cfgKey().hashCode()).put("g", JSONArray(good)).put("b", JSONArray(busy))
    prefs.edit().putString("p_${prov}_probe", o.toString()).apply()
}

/** Cleartext (http://) hanya boleh ke localhost/127.0.0.1, supaya kunci API tidak terkirim tanpa enkripsi. */
internal fun MainActivity.checkUrl(url: String) {
    if (!url.startsWith("http://", ignoreCase = true)) return
    val host = try { URL(url).host.lowercase().trim('[', ']') } catch (e: Exception) { "" }
    val local = host == "localhost" || host == "127.0.0.1"
    if (!local) {
        throw RuntimeException("Basis URL http:// ke host publik diblokir agar kunci API tidak terkirim tanpa enkripsi. Pakai https://.")
    }
}

internal fun MainActivity.postStatus(url: String, headers: Map<String, String>, body: JSONObject): Int {
    checkUrl(url)
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.doOutput = true
    conn.connectTimeout = 20000
    conn.readTimeout = 60000
    conn.setRequestProperty("Content-Type", "application/json")
    for ((k, v) in headers) conn.setRequestProperty(k, v)
    conn.outputStream.use { it.write(body.toString().toByteArray()) }
    val code = conn.responseCode
    try {
        val st = if (code >= 400) conn.errorStream else conn.inputStream
        st?.use { it.readBytes() }
    } catch (e: Exception) {
    }
    return code
}

/** Uji satu model dengan permintaan kecil + definisi tool. Mengembalikan kode HTTP (-1 jika gagal). */
internal fun MainActivity.probe(m: String): Int {
    return try {
        val key = cfgKey()
        val base = cfgBase().trimEnd('/')
        val fmt = preset(prov).fmt
        val headers = HashMap<String, String>()
        val url: String
        val body: JSONObject
        val userMsg = JSONObject().put("role", "user").put("content", "hi")
        if (fmt == "gemini") {
            url = "$base/models/$m:generateContent"
            val part = JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", "hi")))
            body = JSONObject().put("contents", JSONArray().put(part)).put("tools", tools)
            headers["x-goog-api-key"] = key
        } else if (fmt == "anthropic") {
            url = "$base/messages"
            val tl = JSONArray()
            for (d in declList()) {
                tl.put(
                    JSONObject().put("name", d.getString("name")).put("description", d.getString("description"))
                        .put("input_schema", d.getJSONObject("parameters"))
                )
            }
            body = JSONObject().put("model", m).put("max_tokens", 16)
                .put("messages", JSONArray().put(userMsg)).put("tools", tl)
            headers["x-api-key"] = key
            headers["anthropic-version"] = "2023-06-01"
        } else {
            url = "$base/chat/completions"
            val tl = JSONArray()
            for (d in declList()) tl.put(JSONObject().put("type", "function").put("function", d))
            body = JSONObject().put("model", m).put("messages", JSONArray().put(userMsg)).put("tools", tl)
            if (key.isNotEmpty()) headers["Authorization"] = "Bearer $key"
        }
        postStatus(url, headers, body)
    } catch (e: Exception) {
        -1
    }
}

/** Uji banyak model sekaligus (5 paralel). Mengembalikan (bisa dipakai, sedang sibuk). Jalankan di thread latar. */
internal fun MainActivity.probeBlocking(cand: List<String>): Pair<List<String>, List<String>> {
    val pool = Executors.newFixedThreadPool(5)
    val res = try {
        pool.invokeAll(cand.map { m -> Callable { Pair(m, probe(m)) } }).map { it.get() }
    } finally {
        pool.shutdown()
    }
    val good = res.filter { it.second in 200..299 }.map { it.first }
    val busy = res.filter { it.second == 429 || it.second in 500..599 }.map { it.first }
    return Pair(good, busy)
}

internal fun MainActivity.getJson(url: String, headers: Map<String, String>): JSONObject {
    checkUrl(url)
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.requestMethod = "GET"
    conn.connectTimeout = 20000
    conn.readTimeout = 30000
    for ((k, v) in headers) conn.setRequestProperty(k, v)
    val code = conn.responseCode
    if (code >= 400) {
        val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
        throw RuntimeException("API $code: ${err.take(200)}")
    }
    val txt = conn.inputStream.bufferedReader().use { it.readText() }
    return JSONObject(txt)
}

/** Ambil daftar model langsung dari penyedia yang aktif. */
internal fun MainActivity.fetchModels(): List<String> {
    val key = cfgKey()
    val base = cfgBase().trimEnd('/')
    val out = ArrayList<String>()
    val fmt = preset(prov).fmt
    if (fmt == "gemini") {
        val r = getJson("$base/models?pageSize=1000", mapOf("x-goog-api-key" to key))
        val arr = r.optJSONArray("models") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val methods = m.optJSONArray("supportedGenerationMethods")
            var ok = methods == null
            if (methods != null) {
                for (j in 0 until methods.length()) {
                    if (methods.getString(j) == "generateContent") ok = true
                }
            }
            if (ok) out.add(m.optString("name").removePrefix("models/"))
        }
    } else {
        val h = HashMap<String, String>()
        val url: String
        if (fmt == "anthropic") {
            h["x-api-key"] = key
            h["anthropic-version"] = "2023-06-01"
            url = "$base/models?limit=1000"
        } else {
            if (key.isNotEmpty()) h["Authorization"] = "Bearer $key"
            url = "$base/models"
        }
        val r = getJson(url, h)
        val arr = r.optJSONArray("data") ?: JSONArray()
        for (i in 0 until arr.length()) out.add(arr.getJSONObject(i).optString("id"))
    }
    return out.filter { it.isNotBlank() }.sorted()
}
