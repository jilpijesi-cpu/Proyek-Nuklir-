package com.kita.agent

/** Hasil pembukaan key tersimpan tanpa membocorkan isi key ke log atau error UI. */
internal sealed interface SecretDecryptResult {
    data class Success(val value: String) : SecretDecryptResult
    data object Missing : SecretDecryptResult
    data object Corrupt : SecretDecryptResult
    data object KeystoreUnavailable : SecretDecryptResult
}

/** Provider kripto dapat diganti fake pada unit test JVM. */
internal interface SecretCryptoProvider {
    fun encrypt(plain: String): Result<String>
    fun decrypt(stored: String): SecretDecryptResult
}

/**
 * Simpan hanya hasil enkripsi yang sukses. Fungsi ini sengaja murni agar test dapat memastikan
 * plaintext tidak pernah masuk ke penyimpanan ketika provider gagal.
 */
internal fun putEncryptedValue(
    store: MutableMap<String, String>,
    name: String,
    plain: String,
    provider: SecretCryptoProvider
): Result<Unit> = provider.encrypt(plain).map { encrypted ->
    store[name] = encrypted
}
