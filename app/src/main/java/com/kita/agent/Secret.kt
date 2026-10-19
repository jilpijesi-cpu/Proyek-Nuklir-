package com.kita.agent

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Enkripsi kunci API dengan Android Keystore (AES-256-GCM). Kunci tidak pernah ditulis plaintext. */
object Secret {
    private const val ALIAS = "kita_api_key"
    private const val PREFIX = "enc1:"
    private const val IV_SIZE = 12

    private class KeystoreUnavailable(cause: Throwable) : Exception(cause)

    private val androidProvider = object : SecretCryptoProvider {
        private fun keyForEncrypt(): SecretKey {
            try {
                val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
                val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                kg.init(
                    KeyGenParameterSpec.Builder(
                        ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                return kg.generateKey()
            } catch (e: Exception) {
                throw KeystoreUnavailable(e)
            }
        }

        private fun keyForDecrypt(): SecretKey? {
            try {
                val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                return ks.getKey(ALIAS, null) as? SecretKey
            } catch (e: Exception) {
                throw KeystoreUnavailable(e)
            }
        }

        override fun encrypt(plain: String): Result<String> {
            if (plain.isEmpty() || isEncrypted(plain)) return Result.success(plain)
            return try {
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.ENCRYPT_MODE, keyForEncrypt())
                val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
                Result.success(PREFIX + Base64.encodeToString(c.iv + ct, Base64.NO_WRAP))
            } catch (e: KeystoreUnavailable) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        override fun decrypt(stored: String): SecretDecryptResult {
            if (stored.isEmpty()) return SecretDecryptResult.Success("")
            if (!isEncrypted(stored)) return SecretDecryptResult.Success(stored)
            return try {
                val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
                if (raw.size <= IV_SIZE) return SecretDecryptResult.Corrupt
                val key = keyForDecrypt() ?: return SecretDecryptResult.Missing
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw.copyOfRange(0, IV_SIZE)))
                SecretDecryptResult.Success(String(c.doFinal(raw, IV_SIZE, raw.size - IV_SIZE), Charsets.UTF_8))
            } catch (e: KeystoreUnavailable) {
                SecretDecryptResult.KeystoreUnavailable
            } catch (e: AEADBadTagException) {
                SecretDecryptResult.Corrupt
            } catch (e: IllegalArgumentException) {
                SecretDecryptResult.Corrupt
            } catch (e: Exception) {
                SecretDecryptResult.Corrupt
            }
        }
    }

    /** Provider ini dapat diganti hanya oleh test/provider harness; produksi memakai Android Keystore. */
    internal var provider: SecretCryptoProvider = androidProvider

    fun isEncrypted(s: String) = s.startsWith(PREFIX)

    fun encrypt(plain: String): Result<String> = provider.encrypt(plain)

    internal fun decrypt(stored: String): SecretDecryptResult = provider.decrypt(stored)
}
