package com.kita.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretStorageTest {
    private class FailingProvider : SecretCryptoProvider {
        override fun encrypt(plain: String): Result<String> =
            Result.failure(IllegalStateException("Keystore unavailable"))

        override fun decrypt(stored: String): SecretDecryptResult =
            SecretDecryptResult.KeystoreUnavailable
    }

    @Test
    fun plaintextTidakPernahDisimpanJikaEnkripsiGagal() {
        val store = mutableMapOf<String, String>()
        val result = putEncryptedValue(store, "api_key", "rahasia", FailingProvider())

        assertTrue(result.isFailure)
        assertFalse(store.containsKey("api_key"))
        assertFalse(store.values.contains("rahasia"))
    }

    @Test
    fun hasilDecryptMembedakanKeystoreTidakTersedia() {
        assertTrue(FailingProvider().decrypt("enc1:any") is SecretDecryptResult.KeystoreUnavailable)
    }
}
