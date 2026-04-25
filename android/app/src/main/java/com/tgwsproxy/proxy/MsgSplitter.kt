package com.tgwsproxy.proxy

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher

class MsgSplitter(relayInit: ByteArray, private val protoInt: Long) {

    private val dec: Cipher
    private val cipherBuf = mutableListOf<Byte>()
    private val plainBuf = mutableListOf<Byte>()
    private var disabled = false

    init {
        val key = relayInit.sliceArray(8 until 40)
        val iv = relayInit.sliceArray(40 until 56)
        dec = CryptoContext.createAesCtr(key, iv)
        dec.update(Constants.ZERO_64)
    }

    fun split(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        if (disabled) return listOf(chunk)

        for (b in chunk) cipherBuf.add(b)
        val plainChunk = dec.update(chunk)
        for (b in plainChunk) plainBuf.add(b)

        val parts = mutableListOf<ByteArray>()
        while (cipherBuf.isNotEmpty()) {
            val packetLen = nextPacketLen()
            if (packetLen == null) break
            if (packetLen <= 0) {
                parts.add(cipherBuf.toByteArray())
                cipherBuf.clear()
                plainBuf.clear()
                disabled = true
                break
            }
            parts.add(ByteArray(packetLen) { cipherBuf[it] })
            repeat(packetLen) {
                cipherBuf.removeAt(0)
                plainBuf.removeAt(0)
            }
        }
        return parts
    }

    fun flush(): List<ByteArray> {
        if (cipherBuf.isEmpty()) return emptyList()
        val tail = cipherBuf.toByteArray()
        cipherBuf.clear()
        plainBuf.clear()
        return listOf(tail)
    }

    private fun nextPacketLen(): Int? {
        if (plainBuf.isEmpty()) return null
        return when (protoInt) {
            Constants.PROTO_ABRIDGED_INT -> nextAbridgedLen()
            Constants.PROTO_INTERMEDIATE_INT, Constants.PROTO_PADDED_INTERMEDIATE_INT -> nextIntermediateLen()
            else -> 0
        }
    }

    private fun nextAbridgedLen(): Int? {
        val first = plainBuf[0].toInt() and 0xFF
        return if (first == 0x7F || first == 0xFF) {
            if (plainBuf.size < 4) return null
            val payloadLen = ((plainBuf[1].toInt() and 0xFF) or
                    ((plainBuf[2].toInt() and 0xFF) shl 8) or
                    ((plainBuf[3].toInt() and 0xFF) shl 16)) * 4
            if (payloadLen <= 0) return 0
            val packetLen = 4 + payloadLen
            if (plainBuf.size < packetLen) null else packetLen
        } else {
            val payloadLen = (first and 0x7F) * 4
            if (payloadLen <= 0) return 0
            val packetLen = 1 + payloadLen
            if (plainBuf.size < packetLen) null else packetLen
        }
    }

    private fun nextIntermediateLen(): Int? {
        if (plainBuf.size < 4) return null
        val bytes = ByteArray(4) { plainBuf[it] }
        val payloadLen = (ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0x7FFFFFFFL).toInt()
        if (payloadLen <= 0) return 0
        val packetLen = 4 + payloadLen
        return if (plainBuf.size < packetLen) null else packetLen
    }
}

private fun List<Byte>.toByteArray(): ByteArray = ByteArray(size) { this[it] }
