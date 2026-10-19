package com.kita.agent

// Logika murni (tanpa android.*) untuk peralihan AI otomatis: susunan cadangan dan status yang ditampilkan di kartu.
// Dipisah dari UI supaya bisa diuji sebagai unit test JVM.

internal data class FoCandidate(val provider: String, val model: String) {
    val id: String get() = "$provider/$model"
}

/** Daftar model satu penyedia: [saved] = model pilihan pengguna di Pengaturan, [models] = model lain yang diketahui. */
internal class ProviderPool(val name: String, val saved: String, val models: List<String>)

/** Urutkan model dari yang paling disukai (aturan [bestModel]); duplikat dibuang. */
internal fun rankModels(models: List<String>): List<String> {
    val rest = models.distinct().toMutableList()
    val out = ArrayList<String>()
    while (rest.isNotEmpty()) {
        val m = bestModel(rest) ?: break
        out += m
        rest.remove(m)
    }
    return out
}

/**
 * Susun urutan percobaan lintas penyedia dan model. Penyedia utama didahulukan, tapi tiap "tingkat" hanya
 * mengambil [tierSize] model per penyedia sebelum pindah ke penyedia berikutnya. Jadi kalau satu penyedia sedang
 * bermasalah total, penyedia lain dicoba lebih cepat. Model di [blocked] (id "penyedia/model") dilewati.
 */
internal fun buildChain(
    main: String,
    pools: List<ProviderPool>,
    blocked: Set<String>,
    tierSize: Int = 2,
    tiers: Int = 3,
    total: Int = 18
): List<FoCandidate> {
    val ordered = pools.sortedBy { if (it.name == main) 0 else 1 }
    val lists = ordered.map { p ->
        val all = (listOf(p.saved).filter { it.isNotBlank() } + rankModels(p.models)).distinct()
        p.name to all.map { FoCandidate(p.name, it) }.filter { it.id !in blocked }
    }
    val out = ArrayList<FoCandidate>()
    for (t in 0 until tiers) {
        for ((_, l) in lists) out += l.drop(t * tierSize).take(tierSize)
    }
    return out.take(total)
}

internal enum class FoState { WAIT, TRYING, BUSY, QUOTA, REJECTED, OFFLINE, OK }
