package com.tgwsproxy.proxy

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

data class HandshakeResult(
    val dcId: Int,
    val isMedia: Boolean,
    val protoTag: ByteArray,
    val decPrekeyIv: ByteArray
)

object HandshakeParser {

    fun tryHandshake(handshake: ByteArray, secret: ByteArray): HandshakeResult? {
        if (handshake.size != Constants.HANDSHAKE_LEN) return null

        val decPrekeyAndIv = handshake.sliceArray(
            Constants.SKIP_LEN until Constants.SKIP_LEN + Constants.PREKEY_LEN + Constants.IV_LEN
        )
        val decPrekey = decPrekeyAndIv.sliceArray(0 until Constants.PREKEY_LEN)
        val decIv = decPrekeyAndIv.sliceArray(Constants.PREKEY_LEN until Constants.PREKEY_LEN + Constants.IV_LEN)

        val decKey = CryptoContext.sha256(decPrekey, secret)
        val decryptor = CryptoContext.createAesCtr(decKey, decIv)
        val decrypted = decryptor.update(handshake)

        val protoTag = decrypted.sliceArray(Constants.PROTO_TAG_POS until Constants.PROTO_TAG_POS + 4)

        val isValidTag = protoTag.contentEquals(Constants.PROTO_TAG_ABRIDGED) ||
                protoTag.contentEquals(Constants.PROTO_TAG_INTERMEDIATE) ||
                protoTag.contentEquals(Constants.PROTO_TAG_SECURE)

        if (!isValidTag) return null

        val dcIdxBytes = decrypted.sliceArray(Constants.DC_IDX_POS until Constants.DC_IDX_POS + 2)
        val dcIdx = ByteBuffer.wrap(dcIdxBytes).order(ByteOrder.LITTLE_ENDIAN).short.toInt()

        val dcId = kotlin.math.abs(dcIdx)
        val isMedia = dcIdx < 0

        return HandshakeResult(dcId, isMedia, protoTag, decPrekeyAndIv)
    }

    fun generateRelayInit(protoTag: ByteArray, dcIdx: Int): ByteArray {
        val random = SecureRandom()

        while (true) {
            val rnd = ByteArray(Constants.HANDSHAKE_LEN)
            random.nextBytes(rnd)

            if (rnd[0] in Constants.RESERVED_FIRST_BYTES) continue
            if (rnd.sliceArray(0 until 4).toList() in Constants.RESERVED_STARTS) continue
            if (rnd.sliceArray(4 until 8).toList() == Constants.RESERVED_CONTINUE) continue

            val encKey = rnd.sliceArray(Constants.SKIP_LEN until Constants.SKIP_LEN + Constants.PREKEY_LEN)
            val encIv = rnd.sliceArray(
                Constants.SKIP_LEN + Constants.PREKEY_LEN until
                        Constants.SKIP_LEN + Constants.PREKEY_LEN + Constants.IV_LEN
            )

            val encryptor = CryptoContext.createAesCtr(encKey, encIv)
            val encryptedFull = encryptor.update(rnd)

            val dcBytes = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(dcIdx.toShort()).array()
            val randomPad = ByteArray(2)
            random.nextBytes(randomPad)

            val tailPlain = protoTag + dcBytes + randomPad

            val result = rnd.copyOf()
            for (i in 0 until 8) {
                val keystreamByte = (encryptedFull[56 + i].toInt() xor rnd[56 + i].toInt()).toByte()
                result[56 + i] = (tailPlain[i].toInt() xor keystreamByte.toInt()).toByte()
            }

            return result
        }
    }
}
