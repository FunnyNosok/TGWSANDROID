package com.tgwsproxy.proxy

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class CryptoContext(
    val clientDecryptor: Cipher,
    val clientEncryptor: Cipher,
    val tgEncryptor: Cipher,
    val tgDecryptor: Cipher
) {
    companion object {
        fun sha256(data: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(data)

        fun sha256(vararg parts: ByteArray): ByteArray {
            val md = MessageDigest.getInstance("SHA-256")
            for (p in parts) md.update(p)
            return md.digest()
        }

        fun createAesCtr(key: ByteArray, iv: ByteArray): Cipher {
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            return cipher
        }

        fun build(
            clientDecPrekeyIv: ByteArray,
            secret: ByteArray,
            relayInit: ByteArray
        ): CryptoContext {
            val cltDecPrekey = clientDecPrekeyIv.sliceArray(0 until Constants.PREKEY_LEN)
            val cltDecIv = clientDecPrekeyIv.sliceArray(Constants.PREKEY_LEN until Constants.PREKEY_LEN + Constants.IV_LEN)
            val cltDecKey = sha256(cltDecPrekey, secret)

            val cltEncPrekeyIv = clientDecPrekeyIv.reversedArray()
            val cltEncKey = sha256(
                cltEncPrekeyIv.sliceArray(0 until Constants.PREKEY_LEN),
                secret
            )
            val cltEncIv = cltEncPrekeyIv.sliceArray(Constants.PREKEY_LEN until Constants.PREKEY_LEN + Constants.IV_LEN)

            val cltDecryptor = createAesCtr(cltDecKey, cltDecIv)
            val cltEncryptor = createAesCtr(cltEncKey, cltEncIv)

            // fast-forward client decryptor past the 64-byte init
            cltDecryptor.update(Constants.ZERO_64)

            // relay side: raw key from relay_init (no secret hash)
            val relayEncKey = relayInit.sliceArray(
                Constants.SKIP_LEN until Constants.SKIP_LEN + Constants.PREKEY_LEN
            )
            val relayEncIv = relayInit.sliceArray(
                Constants.SKIP_LEN + Constants.PREKEY_LEN until
                        Constants.SKIP_LEN + Constants.PREKEY_LEN + Constants.IV_LEN
            )

            val relayDecPrekeyIv = relayInit.sliceArray(
                Constants.SKIP_LEN until Constants.SKIP_LEN + Constants.PREKEY_LEN + Constants.IV_LEN
            ).reversedArray()
            val relayDecKey = relayDecPrekeyIv.sliceArray(0 until Constants.KEY_LEN)
            val relayDecIv = relayDecPrekeyIv.sliceArray(Constants.KEY_LEN until Constants.KEY_LEN + Constants.IV_LEN)

            val tgEncryptor = createAesCtr(relayEncKey, relayEncIv)
            val tgDecryptor = createAesCtr(relayDecKey, relayDecIv)

            // fast-forward TG encryptor past the 64-byte init
            tgEncryptor.update(Constants.ZERO_64)

            return CryptoContext(cltDecryptor, cltEncryptor, tgEncryptor, tgDecryptor)
        }
    }
}
