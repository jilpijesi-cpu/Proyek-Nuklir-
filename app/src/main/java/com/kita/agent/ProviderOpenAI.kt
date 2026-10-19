package com.kita.agent

import java.util.TreeMap
import org.json.JSONArray
import org.json.JSONObject

// Penyedia OpenAI-compatible (OpenAI, OpenRouter, Groq, DeepSeek, Ollama, dll).
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

// ---- OpenAI-compatible (OpenAI, OpenRouter, Groq, DeepSeek, Ollama, LM Studio, dll)
internal fun MainActivity.callOpenAI(key: String, onText: (String) -> Unit, onFlush: () -> Unit): Hist? {
    val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sysText()))
    for (h in ctxHist()) {
        if (h.role == 0) {
            if (h.images.isEmpty()) {
                msgs.put(JSONObject().put("role", "user").put("content", h.text))
            } else {
                val content = JSONArray().put(JSONObject().put("type", "text").put("text", h.text))
                for (image in h.images) {
                    val payload = encodedImage(image)
                    content.put(JSONObject().put("type", "image_url").put(
                        "image_url", JSONObject().put("url", "data:${payload.mimeType};base64,${payload.base64}")))
                }
                msgs.put(JSONObject().put("role", "user").put("content", content))
            }
        } else if (h.role == 1) {
            val m = JSONObject().put("role", "assistant")
            m.put("content", if (h.text.isEmpty()) JSONObject.NULL else h.text)
            if (h.calls.isNotEmpty()) {
                val tc = JSONArray()
                for (c in h.calls) {
                    tc.put(
                        JSONObject().put("id", c.id).put("type", "function").put(
                            "function",
                            JSONObject().put("name", c.name).put("arguments", c.args.toString())
                        )
                    )
                }
                m.put("tool_calls", tc)
            }
            msgs.put(m)
        } else {
            for ((c, o) in h.results) {
                msgs.put(JSONObject().put("role", "tool").put("tool_call_id", c.id).put("content", o))
            }
        }
    }
    val tl = JSONArray()
    for (d in declList()) tl.put(JSONObject().put("type", "function").put("function", d))
    val body = JSONObject().put("model", cfgModel()).put("messages", msgs).put("tools", tl).put("stream", true)
    val headers = HashMap<String, String>()
    if (key.isNotEmpty()) headers["Authorization"] = "Bearer $key"
    val conn = openStream(cfgBase().trimEnd('/') + "/chat/completions", headers, body)

    class Acc(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())
    val accs = TreeMap<Int, Acc>()
    val buf = StringBuilder()
    val rawNon = StringBuilder()
    var sawData = false
    var truncated = false
    var finished = false // [DONE] atau finish_reason: tanda stream selesai dengan benar
    var lastEmit = 0L

    conn.inputStream.bufferedReader().use { r ->
        while (true) {
            if (cancelled) throw Cancelled()
            val line = r.readLine() ?: break
            if (!line.startsWith("data:")) {
                if (!sawData) rawNon.append(line).append('\n')
                continue
            }
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty()) continue
            if (data == "[DONE]") { finished = true; break }
            sawData = true
            val chunk = try { JSONObject(data) } catch (e: Exception) { continue }
            chunk.optJSONObject("error")?.let { throw apiStreamException(it.optInt("code", -1), str(it, "type") + " " + str(it, "code"), str(it, "message")) }
            val ch0 = chunk.optJSONArray("choices")?.optJSONObject(0)
            if (ch0?.optString("finish_reason") == "length") truncated = true
            if (ch0?.optString("finish_reason")?.isNotEmpty() == true) finished = true
            val delta = ch0?.optJSONObject("delta") ?: continue
            val t = str(delta, "content")
            if (t.isNotEmpty()) {
                buf.append(t)
                val now = System.currentTimeMillis()
                if (now - lastEmit > 80) {
                    onText(buf.toString())
                    lastEmit = now
                }
            }
            val tcs = delta.optJSONArray("tool_calls")
            if (tcs != null) {
                for (i in 0 until tcs.length()) {
                    val tc = tcs.getJSONObject(i)
                    val a = accs.getOrPut(tc.optInt("index", i)) { Acc() }
                    val id = str(tc, "id")
                    if (id.isNotEmpty()) a.id = id
                    val f = tc.optJSONObject("function")
                    if (f != null) {
                        val n = str(f, "name")
                        if (n.isNotEmpty()) a.name = n
                        a.args.append(str(f, "arguments"))
                    }
                }
            }
        }
    }

    // Server yang mengabaikan stream dan membalas JSON biasa.
    if (!sawData && rawNon.isNotBlank()) {
        try {
            val res = JSONObject(rawNon.toString())
            res.optJSONObject("error")?.let { throw apiStreamException(it.optInt("code", -1), str(it, "type") + " " + str(it, "code"), str(it, "message")) }
            val m = res.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            if (m != null) {
                buf.append(str(m, "content"))
                val tcs = m.optJSONArray("tool_calls")
                if (tcs != null) {
                    for (i in 0 until tcs.length()) {
                        val tc = tcs.getJSONObject(i)
                        val f = tc.optJSONObject("function") ?: continue
                        accs[i] = Acc(str(tc, "id"), str(f, "name"), StringBuilder(str(f, "arguments")))
                    }
                }
            }
        } catch (e: RuntimeException) {
            throw e
        } catch (e: java.io.IOException) {
            // Koneksi putus di tengah stream: jangan dianggap jawaban lengkap, biarkan callModelRetry mengulang.
            throw e
        } catch (e: Exception) {
        }
    }

    // Stream berakhir tanpa penanda selesai: koneksi kemungkinan putus. Lempar agar callModelRetry mengulang.
    if (sawData && !finished) throw java.io.IOException("Koneksi AI terputus sebelum jawaban selesai")
    val text = buf.toString().let {
        if (truncated && it.isNotBlank()) it + "\n\n_(jawaban terpotong: batas token tercapai)_" else it
    }
    val calls = accs.values.mapIndexed { i, a ->
        Call(a.id.ifEmpty { "call$i" }, a.name, argsOf(a.args.toString(), truncated))
    }.filter { it.name.isNotEmpty() }
    if (text.isNotBlank()) { onText(text); onFlush() }
    if (text.isBlank() && calls.isEmpty()) return null
    return Hist(1, text, calls)
}
