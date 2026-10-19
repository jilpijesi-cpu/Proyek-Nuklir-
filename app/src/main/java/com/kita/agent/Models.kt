package com.kita.agent

import android.view.ViewGroup
import org.json.JSONObject

internal const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
internal const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
internal const val USER = 0
internal const val AI = 1
internal const val KIND_HIDE = -3 // penanda: sembunyikan baris status
internal const val KIND_DOTS = -1 // penanda: tampilkan titik animasi sebagai ikon status

/** Penanda pada tampilan pesan: siapa pengirimnya dan teks mentahnya (untuk menu tekan-lama). */
internal class MsgMeta(val role: Int, var text: String, var time: String = "")

/** Satu baris pemeriksaan di layar "Siapkan Termux". */
internal class SetupStep(
    val label: String,
    val ok: Boolean,
    val help: String = "",
    val acts: List<Pair<String, () -> Unit>> = emptyList()
)

/** Dilempar saat pengguna menekan Stop. */
internal class Cancelled : RuntimeException("Dihentikan")

class Call(val id: String, val name: String, val args: JSONObject)

/** Riwayat netral: role 0 = pengguna, 1 = model, 2 = hasil tool. Dikonversi ke format tiap penyedia. */
class HistImage(val path: String) {
    @Volatile internal var cachedPayload: EncodedImage? = null
}

class Hist(
    val role: Int,
    val text: String = "",
    val calls: List<Call> = emptyList(),
    val results: List<Pair<Call, String>> = emptyList(),
    val raw: JSONObject? = null,
    val images: List<HistImage> = emptyList()
)

internal class Preset(val name: String, val fmt: String, val base: String, val model: String)
