package com.kita.agent

import android.app.AlertDialog
import android.widget.EditText
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// Tool ask_user: agen bertanya langsung ke pengguna lewat dialog, lalu lanjut setelah dijawab atau dilewati.

/** Thread agen yang sedang menunggu jawaban dialog. Dilepas saat Activity ditutup atau diputar agar tidak macet. */
internal val pendingWaits = CopyOnWriteArrayList<CountDownLatch>()

internal fun releaseWaits() {
    pendingWaits.forEach { it.countDown() }
}

/** Tampilkan pertanyaan. Jika [options] kosong, pengguna mengetik jawaban bebas. Thread agen menunggu jawaban. */
internal fun MainActivity.askUser(question: String, options: List<String>): String {
    if (isAutonomous()) return "(mode otonom aktif: putuskan sendiri pendekatan terbaik yang paling mungkin mencapai tujuan)"
    if (askSkipped) return "(pengguna sudah melewati pertanyaan sebelumnya; jangan bertanya lagi, putuskan sendiri dengan asumsi wajar dan kerjakan)"
    val latch = CountDownLatch(1)
    val answer = AtomicReference<String?>(null)
    pendingWaits.add(latch)
    runOnUiThread {
        val b = AlertDialog.Builder(this)
            .setTitle("AI bertanya")
            .setMessage(question)
            .setOnCancelListener { latch.countDown() }
            .setNegativeButton("Lewati") { _, _ -> latch.countDown() }
        if (options.isNotEmpty()) {
            b.setItems(options.toTypedArray()) { _, i -> answer.set(options[i]); latch.countDown() }
        } else {
            val input = EditText(this)
            b.setView(input)
            b.setPositiveButton("Kirim") { _, _ -> answer.set(input.text.toString().trim()); latch.countDown() }
        }
        confirmDialog = b.show().also { d -> d.setOnDismissListener { latch.countDown(); hideKeyboard() } }
    }
    try {
        latch.await(10, TimeUnit.MINUTES)
    } finally {
        pendingWaits.remove(latch)
    }
    return answer.get()?.takeIf { it.isNotEmpty() }
        ?: run {
            askSkipped = true
            "(pengguna tidak menjawab; lanjutkan dengan pendekatan terbaik yang aman)"
        }
}
