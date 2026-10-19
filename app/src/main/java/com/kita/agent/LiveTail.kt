package com.kita.agent

/**
 * Membaca keluaran perintah yang sedang berjalan (ditulis lewat tee ke file sementara di Termux)
 * dan mengirimkannya ke kartu langkah secara berkala. Murni tampilan: kegagalan membaca diabaikan,
 * sehingga tidak pernah mengganggu perintah atau hasil akhirnya.
 */
internal class LiveTail(
    private val act: MainActivity,
    private val path: String,
    private val pollMs: Long = POLL_MS,
    private val onText: (String) -> Unit
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        running = true
        val t = Thread {
            while (running) {
                try {
                    val r = act.termux(
                        "tail -c 8000 ${act.sh(path)} 2>/dev/null",
                        limit = 8000, timeoutSec = 10, useWorkdir = false, killable = false, silentRetry = true
                    )
                    if (running && r.startsWith("exit=0")) {
                        val text = r.substringAfter("\n").lines().takeLast(60).joinToString("\n")
                        act.runOnUiThread { if (running) onText(text) }
                    }
                } catch (e: Exception) {
                    // abaikan: tampilan hanya pelengkap
                }
                try {
                    Thread.sleep(pollMs)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        t.isDaemon = true
        thread = t
        t.start()
    }

    /** Hentikan pembacaan dan hapus file sementara. Aman dipanggil berkali-kali. */
    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        Thread {
            try {
                act.termux("rm -f ${act.sh(path)}", limit = 200, timeoutSec = 10, useWorkdir = false, killable = false, silentRetry = true)
            } catch (e: Exception) {
            }
        }.start()
    }

    companion object {
        const val POLL_MS = 1500L
    }
}
