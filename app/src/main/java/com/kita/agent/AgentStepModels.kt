package com.kita.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Jenis tindakan yang aman ditampilkan kepada pengguna tanpa chain-of-thought. */
enum class AgentStepType { COMMAND, TOOL, READ_FILE, THINKING }

enum class AgentStepStatus { WAITING, RUNNING, SUCCESS, FAILED }

data class AgentStep(
    val id: String = UUID.randomUUID().toString(),
    val type: AgentStepType,
    val title: String,
    val fullCommand: String = "",
    val status: AgentStepStatus = AgentStepStatus.WAITING,
    val startedAt: Long? = null,
    val durationMs: Long = 0L,
    val output: String = ""
) {
    val isApprovalWaiting: Boolean
        get() = status == AgentStepStatus.WAITING && fullCommand.isNotBlank()
}

data class AgentStepGroup(
    val label: String,
    val summary: String,
    val steps: List<AgentStep> = emptyList(),
    val collapsed: Boolean = false
) {
}

/** Store kecil berbasis StateFlow; runner dapat mengirim event tanpa bergantung pada UI. */
class AgentStepStore {
    private val _groups = MutableStateFlow<List<AgentStepGroup>>(emptyList())
    val groups: StateFlow<List<AgentStepGroup>> = _groups.asStateFlow()

    fun clear() { _groups.value = emptyList() }

    fun add(step: AgentStep, groupLabel: String = defaultGroup(step.type)) {
        val current = _groups.value.toMutableList()
        val index = current.indexOfFirst { it.label == groupLabel }
        if (index < 0) current.add(AgentStepGroup(groupLabel, summaryFor(groupLabel, listOf(step)), listOf(step)))
        else {
            val group = current[index]
            val steps = group.steps + step
            current[index] = group.copy(steps = steps, summary = summaryFor(group.label, steps))
        }
        _groups.value = current
    }

    fun update(id: String, change: (AgentStep) -> AgentStep) {
        _groups.value = _groups.value.map { group ->
            val steps = group.steps.map { if (it.id == id) change(it) else it }
            group.copy(steps = steps, summary = summaryFor(group.label, steps))
        }
    }

    fun start(id: String, now: Long = System.currentTimeMillis()) = update(id) {
        it.copy(status = AgentStepStatus.RUNNING, startedAt = it.startedAt ?: now)
    }

    fun finish(id: String, success: Boolean, output: String? = null, now: Long = System.currentTimeMillis()) = update(id) {
        val start = it.startedAt ?: now
        it.copy(
            status = if (success) AgentStepStatus.SUCCESS else AgentStepStatus.FAILED,
            startedAt = start,
            durationMs = (now - start).coerceAtLeast(0L),
            output = output?.let { text -> truncateOutput(text) } ?: it.output
        )
    }

    fun toggle(label: String) {
        _groups.value = _groups.value.map { if (it.label == label) it.copy(collapsed = !it.collapsed) else it }
    }

    companion object {
        const val MAX_STEP_OUTPUT = 12_000

        fun defaultGroup(type: AgentStepType): String = when (type) {
            AgentStepType.COMMAND, AgentStepType.READ_FILE -> "Menjalankan perintah"
            AgentStepType.TOOL -> "Menggunakan alat"
            AgentStepType.THINKING -> "Menganalisis permintaan"
        }

        fun summaryFor(label: String, steps: List<AgentStep>): String = when {
            steps.isEmpty() -> label
            steps.size == 1 -> label
            else -> {
                val noun = when (label) {
                    "Menjalankan perintah" -> if (steps.all { it.type == AgentStepType.READ_FILE }) "Membaca ${steps.size} file" else "Menjalankan ${steps.size} perintah"
                    "Menggunakan alat" -> "Menggunakan ${steps.size} alat"
                    else -> "${steps.size} langkah"
                }
                "$label · $noun"
            }
        }

        fun truncateOutput(text: String, maxChars: Int = MAX_STEP_OUTPUT): String =
            if (text.length <= maxChars) text else text.take(maxChars) + "\n…(output dipotong)…"
    }
}
