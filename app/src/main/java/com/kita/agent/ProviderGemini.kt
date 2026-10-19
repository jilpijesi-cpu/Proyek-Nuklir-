package com.kita.agent

import org.json.JSONArray
import org.json.JSONObject

// Penyedia Gemini (streaming SSE).
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

// ---- Gemini (streaming SSE)
internal fun MainActivity.geminiContents(): JSONArray {
    val arr = JSONArray()
    for (h in ctxHist()) {
        if (h.role == 0) {
            val parts = JSONArray().put(JSONObject().put("text", h.text))
            for (image in h.images) {
                val payload = encodedImage(image)
                parts.put(
                    JSONObject().put("inlineData", JSONObject()
                        .put("mimeType", payload.mimeType)
                        .put("data", payload.base64))
                )
            }
            arr.put(JSONObject().put("role", "user").put("parts", parts))
        } else if (h.role == 1) {
            if (h.raw != null) {
                arr.put(h.raw)
            } else {
                val parts = JSONArray()
                if (h.text.isNotEmpty()) parts.put(JSONObject().put("text", h.text))
                for (c in h.calls) {
                    parts.put(JSONObject().put("functionCall", JSONObject().put("name", c.name).put("args", c.args)))
                }
                arr.put(JSONObject().put("role", "model").put("parts", parts))
            }
        } else {
            val parts = JSONArray()
            for ((c, o) in h.results) {
                parts.put(
                    JSONObject().put(
                        "functionResponse",
                        JSONObject().put("name", c.name).put("response", JSONObject().put("result", o))
                    )
                )
            }
            arr.put(JSONObject().put("role", "user").put("parts", parts))
        }
    }
    return arr
}

internal fun MainActivity.callGemini(key: String, onText: (String) -> Unit, onFlush: () -> Unit): Hist? {
    val body = JSONObject()
        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sysText()))))
        .put("contents", geminiContents())
        .put("tools", tools)
    val url = cfgBase().trimEnd('/') + "/models/" + cfgModel() + ":streamGenerateContent?alt=sse"
    val conn = openStream(url, mapOf("x-goog-api-key" to key), body)

    val parts = JSONArray()
    val buf = StringBuilder()
    var sig: String? = null
    var lastEmit = 0L
    var blockReason = ""
    var finishReason = ""

    fun flushText() {
        if (buf.isEmpty()) return
        onText(buf.toString())
        onFlush()
        val tp = JSONObject().put("text", buf.toString())
        sig?.let { tp.put("thoughtSignature", it) }
        parts.put(tp)
        buf.setLength(0)
        sig = null
    }

    conn.inputStream.bufferedReader().use { r ->
        while (true) {
            if (cancelled) throw Cancelled()
            val line = r.readLine() ?: break
            if (!line.startsWith("data:")) continue
            val json = line.removePrefix("data:").trim()
            if (json.isEmpty()) continue
            val chunk = try { JSONObject(json) } catch (e: Exception) { continue }
            chunk.optJSONObject("error")?.let { throw apiStreamException(it.optInt("code", -1), str(it, "status"), str(it, "message")) }
            chunk.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotBlank() }?.let { blockReason = it }
            chunk.optJSONArray("candidates")?.optJSONObject(0)?.optString("finishReason")?.takeIf { it.isNotBlank() }?.let { finishReason = it }
            val cps = chunk.optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts") ?: continue
            for (i in 0 until cps.length()) {
                val p = cps.getJSONObject(i)
                if (p.has("functionCall")) {
                    flushText()
                    parts.put(p)
                } else if (p.has("text") && !p.optBoolean("thought", false)) {
                    buf.append(p.getString("text"))
                    if (p.has("thoughtSignature")) sig = p.getString("thoughtSignature")
                    val now = System.currentTimeMillis()
                    if (now - lastEmit > 80) {
                        onText(buf.toString())
                        lastEmit = now
                    }
                }
            }
        }
    }
    flushText()
    if (parts.length() == 0) {
        if (blockReason.isNotEmpty()) throw RuntimeException("Permintaan diblokir oleh model ($blockReason). Ubah isi pesan lalu coba lagi.")
        if (finishReason.isNotEmpty() && finishReason != "STOP") throw RuntimeException("Model berhenti tanpa jawaban ($finishReason).")
        return null
    }
    val sb = StringBuilder()
    val calls = ArrayList<Call>()
    for (i in 0 until parts.length()) {
        val p = parts.getJSONObject(i)
        if (p.has("functionCall")) {
            val fc = p.getJSONObject("functionCall")
            calls.add(Call("g$i", fc.getString("name"), fc.optJSONObject("args") ?: JSONObject()))
        } else if (p.has("text")) {
            sb.append(p.getString("text"))
        }
    }
    return Hist(1, sb.toString(), calls, raw = JSONObject().put("role", "model").put("parts", parts))
}
