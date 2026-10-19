package com.kita.agent

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

// Riwayat percakapan: simpan/baca history.json di penyimpanan aplikasi.
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

internal fun MainActivity.histFile() = File(filesDir, "history.json")

internal fun MainActivity.callJson(c: Call) = JSONObject().put("id", c.id).put("name", c.name).put("args", c.args)

internal fun MainActivity.callFrom(o: JSONObject) =
    Call(o.optString("id"), o.optString("name"), o.optJSONObject("args") ?: JSONObject())

internal fun MainActivity.histToJson(h: Hist): JSONObject {
    val o = JSONObject().put("role", h.role).put("text", h.text)
    if (h.calls.isNotEmpty()) {
        val a = JSONArray()
        for (c in h.calls) a.put(callJson(c))
        o.put("calls", a)
    }
    if (h.results.isNotEmpty()) {
        val a = JSONArray()
        for ((c, r) in h.results) a.put(JSONObject().put("call", callJson(c)).put("out", r))
        o.put("results", a)
    }
    h.raw?.let { o.put("raw", it) }
    if (h.images.isNotEmpty()) {
        val images = JSONArray()
        h.images.forEach { images.put(it.path) }
        o.put("images", images)
    }
    return o
}

internal fun MainActivity.histFromJson(o: JSONObject): Hist {
    val calls = ArrayList<Call>()
    o.optJSONArray("calls")?.let { a ->
        for (i in 0 until a.length()) calls.add(callFrom(a.getJSONObject(i)))
    }
    val results = ArrayList<Pair<Call, String>>()
    o.optJSONArray("results")?.let { a ->
        for (i in 0 until a.length()) {
            val r = a.getJSONObject(i)
            results.add(Pair(callFrom(r.getJSONObject("call")), r.optString("out")))
        }
    }
    val images = ArrayList<HistImage>()
    o.optJSONArray("images")?.let { a ->
        for (i in 0 until a.length()) {
            val path = a.optString(i)
            if (path.isNotBlank()) images.add(HistImage(path))
        }
    }
    return Hist(o.optInt("role"), o.optString("text"), calls, results, o.optJSONObject("raw"), images)
}

@Synchronized
internal fun MainActivity.saveHist() {
    try {
        val arr = JSONArray()
        synchronized(hist) {
            val resIdx = hist.indices.filter { hist[it].role == 2 }
            val keepFrom = if (resIdx.size > 20) resIdx[resIdx.size - 20] else 0
            for ((i, h) in hist.withIndex()) {
                val hh = if (h.role == 2 && i < keepFrom) {
                    Hist(2, results = h.results.map { (c, r) -> Pair(c, if (r.length > 500) r.take(500) + "\n…(dipangkas)" else r) })
                } else h
                arr.put(histToJson(hh))
            }
        }
        val tmp = File(filesDir, "history.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(histFile())
    } catch (e: Exception) {
    }
}

/** Buang giliran model yang memanggil tool tapi belum sempat dapat hasil (aplikasi ditutup / dihentikan). */
internal fun MainActivity.trimDangling() {
    synchronized(hist) {
        val last = hist.lastOrNull() ?: return
        if (last.role == 1 && last.calls.isNotEmpty()) {
            hist.removeAt(hist.size - 1)
            if (last.text.isNotBlank()) hist.add(Hist(1, last.text))
        }
    }
}

internal fun MainActivity.restoreHistory() {
    try {
        val f = histFile()
        if (!f.exists()) return
        val arr = JSONArray(f.readText())
        for (i in 0 until arr.length()) {
            try { hist.add(histFromJson(arr.getJSONObject(i))) } catch (e: Exception) { android.util.Log.w("AgentApp", "entri riwayat rusak dilewati", e) }
        }
    } catch (e: Exception) {
        android.util.Log.w("AgentApp", "history.json tidak terbaca", e)
        hist.clear()
        try { histFile().renameTo(File(filesDir, "history.json.bad")) } catch (e2: Exception) { android.util.Log.w("AgentApp", "gagal memindahkan history.json rusak", e2) }
    }
    trimDangling()
    restoring = true
    try { restoreBubbles() } finally { restoring = false }
}

private fun MainActivity.restoreBubbles() {
    for ((i, h) in hist.withIndex()) {
        if (h.text.isNotBlank()) {
            if (h.role == 0 && h.text.startsWith(SYS_PREFIX)) { /* pesan sistem: tidak jadi gelembung */ }
            else if (h.role == 0) addMsg(USER, h.text) else if (h.role == 1) addMsg(AI, h.text)
        }
        // Langkah alat ikut dipulihkan sebagai kartu Aktivitas Agen (diringkas), bukan hilang saat aplikasi dibuka ulang.
        if (h.role == 1 && h.calls.isNotEmpty()) {
            val next = hist.getOrNull(i + 1)
            runOnUiThread { restoreFeed(h.calls, if (next?.role == 2) next.results else emptyList()) }
        }
    }
}
