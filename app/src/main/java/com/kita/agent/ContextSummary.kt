package com.kita.agent

// Konteks model: prompt sistem, pemangkasan, dan ringkasan riwayat panjang.
// Fungsi-fungsi di sini adalah ekstensi MainActivity (dipecah dari MainActivity.kt).

internal fun MainActivity.sysText(): String {
    sysOverride?.let { return it }
    val extra = prefs.getString("extra", "") ?: ""
    var t = if (extra.isBlank()) baseSystem() else "${baseSystem()}\nInstruksi tambahan dari pengguna: $extra"
    if (readOnlyMode()) {
        t += "\nMODE HANYA BACA aktif: jangan menulis, mengubah, atau menghapus apa pun. " +
            "Hanya baca dan analisis; kalau perlu perubahan, jelaskan saja usulannya."
    }
    if (draftMode() && !readOnlyMode() && !sandboxEnabled()) {
        t += "\nMODE SUSUN DULU: baca lampiran dan file proyek langsung (tanpa izin). Pahami permintaan, lalu susun dan tulis SEMUA perubahan kode dengan write_file " +
            "(diizinkan otomatis, ada cadangan). Jangan menjalankan build atau perintah yang mengubah sistem sebelum semua kode selesai. " +
            "Setelah selesai, jalankan build satu kali (pengguna akan diminta izin); bila gagal, baca errornya, perbaiki, lalu build lagi."
    }
    if (isAutonomous()) t += "\nMODE OTONOM: pengguna tidak perlu tahu detail teknis. Kerjakan sampai tujuan benar-benar tercapai: pilih sendiri alat dan caranya, jangan meminta izin, dan jangan menunggu jawaban. Coba pendekatan lain bila gagal. Akhiri hanya setelah tujuan terbukti tercapai, atau bila sudah mencoba beberapa pendekatan dan tetap mentok; lalu laporkan singkat dan jujur apa yang sudah dan belum tercapai."
    if (sandboxEnabled()) t += "\nMODE SANDBOX: run_command berjalan di dalam proot-distro (${sandboxConfig.distro}), bukan di Termux utama. Folder kerja: ${sandboxConfig.workdir}; tetap di dalamnya. Tidak ada akses /sdcard atau home Termux. Lampiran pengguna sudah disalin otomatis ke folder kerja dengan nama file yang sama. Jangan berasumsi alat build (JDK, Android SDK) sudah ada: cek dulu dengan perintah seperti java -version sebelum build. Perintah yang menyentuh jaringan selalu butuh persetujuan pengguna. Beberapa perintah (dd, mkfs, chmod -R, rm di luar workdir, dll.) diblokir. write_file tidak tersedia; tulis file lewat run_command."
    if (apkReMode()) t += "\n$APK_RE_SYSTEM_PROMPT"
    if (taskBrief.isNotBlank()) t += "\nPemahaman tugas saat ini: $taskBrief. Patuhi ini; jika ternyata keliru, koreksi dulu sebelum bertindak."
    return t
}

internal fun MainActivity.histSize(h: Hist): Int =
    (if (h.raw != null) h.raw.toString().length
    else h.text.length + h.calls.sumOf { it.args.toString().length + 40 } + h.results.sumOf { it.second.length + 40 }) +
        h.images.size * 5000

internal fun MainActivity.shrinkRes(h: Hist, n: Int): Hist =
    if (h.role != 2) h
    else Hist(2, results = h.results.map { (c, o) -> Pair(c, if (o.length > n) o.take(n) + "\n…(dipangkas)" else o) })

/** Awal bagian riwayat yang masih dikirim utuh; sebelumnya sudah diwakili ringkasan. 0 = tanpa ringkasan. */
internal fun MainActivity.activeStart(snap: List<Hist>): Int =
    if (summaryText.isNotBlank() && sumUpto in 1 until snap.size && snap[sumUpto].role == 0) sumUpto else 0

internal fun MainActivity.withSummary(list: List<Hist>): List<Hist> {
    if (summaryText.isBlank() || list.isEmpty() || list[0].role != 0) return list
    val f = list[0]
    return listOf(Hist(0, "[Ringkasan percakapan sebelumnya]\n$summaryText\n\n[Pesan pengguna]\n${f.text}", images = f.images)) + list.drop(1)
}

internal fun MainActivity.clearSummary() {
    summaryText = ""
    sumUpto = 0
    prefs.edit().remove("sum_text").remove("sum_upto").apply()
}

/**
 * Salinan riwayat yang dikirim ke model: bagian lama diwakili ringkasan, hasil tool lama dipersingkat,
 * dan giliran terlama dibuang bila masih terlalu besar. Riwayat asli (tampilan, simpanan) utuh.
 */
internal fun MainActivity.ctxHist(): List<Hist> {
    ctxOverride?.let { return it }
    val snap = synchronized(hist) { ArrayList(hist) }
    val start = activeStart(snap)
    val resIdx = (start until snap.size).filter { snap[it].role == 2 }
    val keepFrom = if (resIdx.size > 4) resIdx[resIdx.size - 4] else start
    var list: List<Hist> = (start until snap.size).map { i ->
        val h = snap[i]
        if (i < keepFrom) shrinkRes(h, 300) else h
    }
    var total = list.sumOf { histSize(it) }
    while (total > ctxBudget) {
        val next = (1 until list.size).firstOrNull { list[it].role == 0 } ?: break
        total -= list.subList(0, next).sumOf { histSize(it) }
        list = list.subList(next, list.size)
    }
    return if (start > 0) withSummary(list) else list
}

/**
 * Kalau riwayat aktif sudah besar (>60% batas), minta model meringkas giliran lama, lalu simpan ringkasannya.
 * Dua pesan pengguna terakhir tetap dikirim utuh. Kalau gagal, cukup dilewati (ctxHist tetap memangkas).
 */
internal fun MainActivity.compactIfNeeded(key: String) {
    if (ctxOverride != null) return
    val snap = synchronized(hist) { ArrayList(hist) }
    if (snap.size < compactSkipUntil) return
    val start = activeStart(snap)
    val active = snap.subList(start, snap.size)
    if (active.sumOf { histSize(it) } < ctxBudget * 6 / 10) return
    val users = active.indices.filter { active[it].role == 0 }
    if (users.size < 3) return
    val cut = start + users[users.size - 2]
    val old = snap.subList(start, cut).map { shrinkRes(it, 300) }
    val input = (if (start > 0) withSummary(old) else old) + Hist(
        0,
        "Ringkas percakapan di atas dalam bahasa Indonesia, maksimal 600 kata. Sertakan: tujuan pengguna, " +
            "file/path penting dan perubahan yang sudah dibuat, keputusan, serta hal yang belum selesai. " +
            "Jangan memanggil tool. Balas hanya dengan ringkasannya."
    )
    val statusSnap = saveStatus()
    setStatus(IconView.ARCHIVE, "Meringkas percakapan lama")
    var ok = false
    try {
        ctxOverride = input
        sysOverride = "Kamu penulis ringkasan percakapan teknis. Jangan memakai tool."
        val turn = callModel(key, { }, { })
        val txt = turn?.text?.trim().orEmpty()
        if (txt.isNotBlank()) {
            summaryText = txt.take(6000)
            sumUpto = cut
            prefs.edit().putString("sum_text", summaryText).putInt("sum_upto", cut).apply()
            ok = true
        }
    } catch (e: Cancelled) {
        throw e
    } catch (e: Exception) {
    } finally {
        ctxOverride = null
        sysOverride = null
        restoreStatus(statusSnap)
    }
    if (cancelled) throw Cancelled()
    if (!ok) compactSkipUntil = snap.size + 6
}
