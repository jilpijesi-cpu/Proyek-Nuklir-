package com.kita.agent

import android.speech.tts.TextToSpeech

private fun voicePlainText(text: String): String = text
    .replace(Regex("```[\\s\\S]*?```"), " blok kode ")
    .replace(Regex("[*_`#>]"), "")
    .replace(Regex("\\s+"), " ")
    .trim()
    .take(4000)

internal fun MainActivity.speakReply(text: String) {
    val spoken = voicePlainText(text)
    if (spoken.isBlank()) return
    tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "agent-reply-${System.currentTimeMillis()}")
}
