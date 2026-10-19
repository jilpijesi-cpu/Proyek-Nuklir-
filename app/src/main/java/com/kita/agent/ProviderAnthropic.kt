package com.kita.agent

import java.util.TreeMap
import org.json.JSONArray
import org.json.JSONObject

// Penyedia Anthropic (Claude).
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

// ---- Anthropic (Claude)
internal fun MainActivity.callAnthropic(key: String, onText: (String) -> Unit, onFlush: () -> Unit): Hist? {
    val msgs = JSONArray()
    for (h in ctxHist()) {
        if (h.role == 0) {
            if (h.images.isEmpty()) {
                msgs.put(JSONObject().put("role", "user").put("content", h.text))
            } else {
                val content = JSONArray().put(JSONObject().put("type", "text").put("text", h.text))
                for (image in h.images) {
                    val payload = encodedImage(image)
                    content.put(JSONObject().put("type", "image").put(
                        "source", JSONObject().put("type", "base64")
                            .put("media_type", payload.mimeType)
                            .put("data", payload.base64)))
                }
                msgs.put(JSONObject().put("role", "user").put("content", content))
            }
        } else if (h.role == 1) {
            val blocks = JSONArray()
            if (h.text.isNotEmpty()) blocks.put(JSONObject().put("type", "text").put("text", h.text))
            for (c in h.calls) {
                blocks.put(JSONObject().put("type", "tool_use").put("id", c.id).put("name", c.name).put("input", c.args))
            }
            msgs.put(JSONObject().put("role", "assistant").put("content", blocks))
        } else {
            val blocks = JSONArray()
            for ((c, o) in h.results) {
                blocks.put(JSONObject().put("type", "tool_result").put("tool_use_id", c.id).put("content", o))
            }
            msgs.put(JSONObject().put("role", "user").put("content", blocks))
        }
    }
    val tl = JSONArray()
    for (d in declList()) {
        tl.put(
            JSONObject().put("name", d.getString("name")).put("description", d.getString("description"))
                .put("input_schema", d.getJSONObject("parameters"))
        )
    }
    val body = JSONObject().put("model", cfgModel()).put("max_tokens", 8192)
        .put("system", sysText()).put("messages", msgs).put("tools", tl).put("stream", true)
    val headers = HashMap<String, String>()
    headers["x-api-key"] = key
    headers["anthropic-version"] = "2023-06-01"
    val conn = openStream(cfgBase().trimEnd('/') + "/messages", headers, body)

    class Blk(val type: String, val id: String, val name: String, val json: StringBuilder = StringBuilder())
    val blocks = TreeMap<Int, Blk>()
    val buf = StringBuilder()
    var truncated = false
    var lastEmit = 0L

    conn.inputStream.bufferedReader().use { r ->
        while (true) {
            if (cancelled) throw Cancelled()
            val line = r.readLine() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty()) continue
            val chunk = try { JSONObject(data) } catch (e: Exception) { continue }
            when (chunk.optString("type")) {
                "content_block_start" -> {
                    val cb = chunk.optJSONObject("content_block") ?: continue
                    blocks[chunk.optInt("index")] = Blk(str(cb, "type"), str(cb, "id"), str(cb, "name"))
                }
                "content_block_delta" -> {
                    val d = chunk.optJSONObject("delta") ?: continue
                    when (d.optString("type")) {
                        "text_delta" -> {
                            buf.append(str(d, "text"))
                            val now = System.currentTimeMillis()
                            if (now - lastEmit > 80) {
                                onText(buf.toString())
                                lastEmit = now
                            }
                        }
                        "input_json_delta" -> blocks[chunk.optInt("index")]?.json?.append(str(d, "partial_json"))
                    }
                }
                "message_delta" -> {
                    if (chunk.optJSONObject("delta")?.optString("stop_reason") == "max_tokens") truncated = true
                }
                "error" -> {
                    val eo = chunk.optJSONObject("error")
                    val em = eo?.let { str(it, "message") } ?: data
                    throw apiStreamException(-1, eo?.let { str(it, "type") } ?: "", em)
                }
            }
        }
    }

    val text = buf.toString().let {
        if (truncated && it.isNotBlank()) it + "\n\n_(jawaban terpotong: batas token tercapai)_" else it
    }
    val calls = blocks.values.filter { it.type == "tool_use" }.mapIndexed { i, b ->
        Call(b.id.ifEmpty { "tu$i" }, b.name, argsOf(b.json.toString(), truncated))
    }
    if (text.isNotBlank()) { onText(text); onFlush() }
    if (text.isBlank() && calls.isEmpty()) return null
    return Hist(1, text, calls)
}
