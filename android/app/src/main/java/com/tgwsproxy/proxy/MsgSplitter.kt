package com.tgwsproxy.proxy

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher

class MsgSplitter(relayInit: ByteArray, private val protoInt: Long) {

    private val dec: Cipher
    private var cipherBuf = ByteArray(8192)
    private var plainBuf = ByteArray(8192)
    private var bufLen = 0
    private var disabled = false

    init {
        val key = relayInit.sliceArray(8 until 40)
        val iv = relayInit.sliceArray(40 until 56)
        dec = CryptoContext.createAesCtr(key, iv)
        dec.update(Constants.ZERO_64)
    }

    private fun ensureCapacity(extra: Int) {
        if (bufLen + extra > cipherBuf.size) {
            var newCap = cipherBuf.size * 2
            while (newCap < bufLen + extra) newCap *= 2
            cipherBuf = cipherBuf.copyOf(newCap)
            plainBuf = plainBuf.copyOf(newCap)
        }
    }

    fun split(chunk: ByteArray, offset: Int, length: Int): List<ByteArray> {
        if (length == 0) return emptyList()
        if (disabled) return listOf(chunk.copyOfRange(offset, offset + length))

        ensureCapacity(length)
        System.arraycopy(chunk, offset, cipherBuf, bufLen, length)
        dec.update(chunk, offset, length, plainBuf, bufLen)

        bufLen += length

        val parts = mutableListOf<ByteArray>()
        var offset = 0

        while (offset < bufLen) {
            val packetLen = nextPacketLen(offset)
            if (packetLen == null) break
            if (packetLen <= 0) {
                parts.add(cipherBuf.copyOfRange(offset, bufLen))
                offset = bufLen
                disabled = true
                break
            }
            parts.add(cipherBuf.copyOfRange(offset, offset + packetLen))
            offset += packetLen
        }

        if (offset > 0) {
            val remaining = bufLen - offset
            if (remaining > 0) {
                System.arraycopy(cipherBuf, offset, cipherBuf, 0, remaining)
                System.arraycopy(plainBuf, offset, plainBuf, 0, remaining)
            }
            bufLen = remaining
        }

        return parts
    }

    fun flush(): List<ByteArray> {
        if (bufLen == 0) return emptyList()
        val tail = cipherBuf.copyOfRange(0, bufLen)
        bufLen = 0
        return listOf(tail)
    }

    private fun nextPacketLen(offset: Int): Int? {
        val remaining = bufLen - offset
        if (remaining == 0) return null
        return when (protoInt) {
            Constants.PROTO_ABRIDGED_INT -> nextAbridgedLen(offset, remaining)
            Constants.PROTO_INTERMEDIATE_INT, Constants.PROTO_PADDED_INTERMEDIATE_INT -> nextIntermediateLen(offset, remaining)
            else -> 0
        }
    }

    private fun nextAbridgedLen(offset: Int, remaining: Int): Int? {
        val first = plainBuf[offset].toInt() and 0xFF
        return if (first == 0x7F || first == 0xFF) {
            if (remaining < 4) return null
            val payloadLen = ((plainBuf[offset + 1].toInt() and 0xFF) or
                    ((plainBuf[offset + 2].toInt() and 0xFF) shl 8) or
                    ((plainBuf[offset + 3].toInt() and 0xFF) shl 16)) * 4
            if (payloadLen <= 0) return 0
            val packetLen = 4 + payloadLen
            if (remaining < packetLen) null else packetLen
        } else {
            val payloadLen = (first and 0x7F) * 4
            if (payloadLen <= 0) return 0
            val packetLen = 1 + payloadLen
            if (remaining < packetLen) null else packetLen
        }
    }

    private fun nextIntermediateLen(offset: Int, remaining: Int): Int? {
        if (remaining < 4) return null
        val payloadLen = (ByteBuffer.wrap(plainBuf, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0x7FFFFFFFL).toInt()
        if (payloadLen <= 0) return 0
        val packetLen = 4 + payloadLen
        return if (remaining < packetLen) null else packetLen
    }
}
