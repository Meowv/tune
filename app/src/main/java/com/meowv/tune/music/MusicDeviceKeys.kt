package com.meowv.tune.music

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

internal object MusicDeviceKeys {
    fun publicKey(id: String): ByteArray? = keyStore().getCertificate(alias(id))?.publicKey?.encoded

    fun ensure(id: String): ByteArray = publicKey(id) ?: generate(id)

    fun generate(id: String): ByteArray = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, PROVIDER)
        .apply {
            initialize(
                KeyGenParameterSpec.Builder(alias(id), KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA1)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                    .build()
            )
        }.generateKeyPair().public.encoded

    fun decrypt(id: String, ciphertext: ByteArray): ByteArray {
        val key = keyStore().getKey(alias(id), null) as? PrivateKey
            ?: throw java.security.InvalidKeyException()
        // API 28 会根据私钥选择 Keystore 的 Cipher 提供者。
        return Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding").run {
            init(Cipher.DECRYPT_MODE, key,
                OAEPParameterSpec("SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT))
            doFinal(ciphertext)
        }
    }

    fun delete(id: String) = keyStore().deleteEntry(alias(id))

    private fun keyStore() = KeyStore.getInstance(PROVIDER).apply { load(null) }
    private fun alias(id: String) = "music-device-$id"
    private const val PROVIDER = "AndroidKeyStore"
}
