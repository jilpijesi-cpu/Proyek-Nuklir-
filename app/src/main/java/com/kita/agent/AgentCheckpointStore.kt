package com.kita.agent

import java.io.File
import org.json.JSONObject

internal fun MainActivity.checkpointFile() = File(filesDir, "agent_checkpoint.json")

internal fun AgentCheckpoint.toJson() = JSONObject()
    .put("taskId", taskId)
    .put("userMessage", userMessage)
    .put("turn", turn)
    .put("lastTool", lastTool)
    .put("phase", phase)
    .put("lastError", lastError)
    .put("updatedAt", updatedAt)

internal fun checkpointFromJson(o: JSONObject) = AgentCheckpoint(
    taskId = o.optString("taskId"),
    userMessage = o.optString("userMessage"),
    turn = o.optInt("turn"),
    lastTool = o.optString("lastTool"),
    phase = o.optString("phase"),
    lastError = o.optString("lastError"),
    updatedAt = o.optLong("updatedAt")
)

internal fun MainActivity.saveAgentCheckpoint(checkpoint: AgentCheckpoint) {
    try {
        val tmp = File(filesDir, "agent_checkpoint.json.tmp")
        tmp.writeText(checkpoint.toJson().toString())
        if (!tmp.renameTo(checkpointFile())) {
            checkpointFile().writeText(checkpoint.toJson().toString())
            tmp.delete()
        }
    } catch (_: Exception) {
    }
}

internal fun MainActivity.loadAgentCheckpoint(): AgentCheckpoint? = try {
    val f = checkpointFile()
    if (!f.isFile) null else checkpointFromJson(JSONObject(f.readText())).takeIf { it.userMessage.isNotBlank() }
} catch (_: Exception) {
    null
}

internal fun MainActivity.clearAgentCheckpoint() {
    try { checkpointFile().delete() } catch (_: Exception) { /* cleanup best-effort: kegagalan tidak berdampak */ }
}
