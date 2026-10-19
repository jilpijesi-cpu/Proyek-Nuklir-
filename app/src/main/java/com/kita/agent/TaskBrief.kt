package com.kita.agent

// Tahap pemahaman: sebelum agen bertindak pada pesan pengguna yang baru, model merumuskan tujuan dan
// langkah awal (PAHAM:), atau bertanya bila ada informasi penting yang kurang (TANYA:).
// Rumusan PAHAM dipakai sebagai arahan di prompt sistem untuk tugas itu. Tidak ada pemanggilan tool.

internal sealed class Brief {
    class Paham(val text: String) : Brief()
    object Kosong : Brief()
}

/** Membaca baris pertama yang tidak kosong. Format lain dianggap kosong agar tugas tetap berjalan. */
internal fun parseBrief(raw: String): Brief {
    val s = raw.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    return when {
        s.startsWith("TANYA:", ignoreCase = true) -> {
            // Tidak menahan tugas: hal yang belum jelas dicatat sebagai asumsi; agen boleh bertanya lewat ask_user.
            val q = s.substring(6).trim()
            if (q.isEmpty()) Brief.Kosong else Brief.Paham("Hal yang belum jelas: ${q.take(300)}")
        }
        s.startsWith("PAHAM:", ignoreCase = true) -> Brief.Paham(s.take(600))
        else -> Brief.Kosong
    }
}

private const val UNDERSTAND_SYS = "Kamu membaca permintaan pengguna sebelum tindakan dimulai. " +
    "Jangan menjalankan tindakan. Balas dengan satu baris dalam bentuk: " +
    "PAHAM: tujuan ... | hasil: bentuk hasil yang diharapkan | langkah awal: ... " +
    "Jika ada yang kurang jelas, tulis sebagai asumsi di PAHAM. " +
    "Jika permintaan merupakan lanjutan dari riwayat, sebutkan itu di PAHAM. Gunakan bahasa Indonesia."

/** Minta model merumuskan pemahaman untuk [userMsg]. Dipanggil sebelum loop tool dimulai. */
internal fun MainActivity.understandTask(key: String, userMsg: String): Brief {
    val snap = synchronized(hist) { ArrayList(hist) }
    // Pesan pengguna terbaru sudah ada di riwayat; konteks diambil dari sebelumnya.
    val prior = snap.dropLast(1).takeLast(4)
    val digest = prior.joinToString("\n") { h ->
        when (h.role) {
            0 -> "Pengguna: ${h.text.take(300)}"
            1 -> "AI: ${h.text.take(300)}"
            else -> "Hasil alat: (ringkas)"
        }
    }
    val prompt = (if (digest.isBlank()) "" else "Riwayat singkat:\n$digest\n\n") + "Permintaan terbaru:\n$userMsg"
    setStatus(KIND_DOTS, "Memahami permintaan")
    ctxOverride = listOf(Hist(0, prompt))
    sysOverride = UNDERSTAND_SYS
    val out = try {
        callModel(key, { }, { })?.text?.trim().orEmpty()
    } finally {
        ctxOverride = null
        sysOverride = null
    }
    return parseBrief(out)
}
