package com.tgwsproxy.proxy

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

object FakeTls {

    private const val CLIENT_RANDOM_OFFSET = 11
    private const val CLIENT_RANDOM_LEN = 32
    private const val SESSION_ID_OFFSET = 44
    private const val SESSION_ID_LEN = 32
    private const val TIMESTAMP_TOLERANCE = 120

    private val SH_TEMPLATE: ByteArray = byteArrayOf(
        0x16, 0x03, 0x03, 0x00, 0x7a,
        0x02, 0x00, 0x00, 0x76,
        0x03, 0x03
    ) + ByteArray(32) +  // server random placeholder
            byteArrayOf(0x20) +
            ByteArray(32) +          // session id placeholder
            byteArrayOf(0x13, 0x01, 0x00) +
            byteArrayOf(0x00, 0x2e) +
            byteArrayOf(0x00, 0x33, 0x00, 0x24, 0x00, 0x1d, 0x00, 0x20) +
            ByteArray(32) +          // public key placeholder
            byteArrayOf(0x00, 0x2b, 0x00, 0x02, 0x03, 0x04)

    private const val SH_RANDOM_OFF = 11
    private const val SH_SESSID_OFF = 44
    private const val SH_PUBKEY_OFF = 89

    private val CCS_FRAME = byteArrayOf(0x14, 0x03, 0x03, 0x00, 0x01, 0x01)

    data class TlsVerifyResult(
        val clientRandom: ByteArray,
        val sessionId: ByteArray,
        val timestamp: Int
    )

    fun verifyClientHello(data: ByteArray, secret: ByteArray): TlsVerifyResult? {
        if (data.size < 43) return null
        if (data[0] != Constants.TLS_RECORD_HANDSHAKE) return null
        if (data[5] != 0x01.toByte()) return null

        val clientRandom = data.sliceArray(CLIENT_RANDOM_OFFSET until CLIENT_RANDOM_OFFSET + CLIENT_RANDOM_LEN)

        val zeroed = data.copyOf()
        for (i in CLIENT_RANDOM_OFFSET until CLIENT_RANDOM_OFFSET + CLIENT_RANDOM_LEN) {
            zeroed[i] = 0
        }

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        val expected = mac.doFinal(zeroed)

        for (i in 0 until 28) {
            if (expected[i] != clientRandom[i]) return null
        }

        val tsXor = ByteArray(4) { i -> (clientRandom[28 + i].toInt() xor expected[28 + i].toInt()).toByte() }
        val timestamp = ByteBuffer.wrap(tsXor).order(ByteOrder.LITTLE_ENDIAN).int

        val now = (System.currentTimeMillis() / 1000).toInt()
        if (abs(now - timestamp) > TIMESTAMP_TOLERANCE) return null

        val sessionId = if (data.size >= SESSION_ID_OFFSET + SESSION_ID_LEN && data[43] == 0x20.toByte()) {
            data.sliceArray(SESSION_ID_OFFSET until SESSION_ID_OFFSET + SESSION_ID_LEN)
        } else {
            ByteArray(SESSION_ID_LEN)
        }

        return TlsVerifyResult(clientRandom, sessionId, timestamp)
    }

    fun buildServerHello(secret: ByteArray, clientRandom: ByteArray, sessionId: ByteArray): ByteArray {
        val sh = SH_TEMPLATE.copyOf()
        System.arraycopy(sessionId, 0, sh, SH_SESSID_OFF, 32)
        val pubKey = ByteArray(32)
        SecureRandom().nextBytes(pubKey)
        System.arraycopy(pubKey, 0, sh, SH_PUBKEY_OFF, 32)

        val encryptedSize = (1900..2100).random()
        val encryptedData = ByteArray(encryptedSize)
        SecureRandom().nextBytes(encryptedData)

        val appRecord = byteArrayOf(0x17, 0x03, 0x03) +
                ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(encryptedSize.toShort()).array() +
                encryptedData

        val response = sh + CCS_FRAME + appRecord

        val hmacInput = clientRandom + response
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        val serverRandom = mac.doFinal(hmacInput)

        val final = response.copyOf()
        System.arraycopy(serverRandom, 0, final, SH_RANDOM_OFF, 32)

        return final
    }

    fun wrapTlsRecord(data: ByteArray): ByteArray {
        val parts = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < data.size) {
            val chunkLen = minOf(Constants.TLS_APPDATA_MAX, data.size - offset)
            val chunk = data.sliceArray(offset until offset + chunkLen)
            parts.add(
                byteArrayOf(0x17, 0x03, 0x03) +
                        ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(chunkLen.toShort()).array() +
                        chunk
            )
            offset += chunkLen
        }
        return parts.fold(byteArrayOf()) { acc, arr -> acc + arr }
    }
}

class FakeTlsStream(
    private val rawInput: InputStream,
    private val rawOutput: OutputStream
) {
    private val readBuf = mutableListOf<Byte>()
    private var readLeft = 0

    fun readExactly(n: Int): ByteArray {
        while (readBuf.size < n) {
            val payload = readTlsPayload() ?: throw java.io.IOException("TLS stream ended")
            readBuf.addAll(payload.toList())
        }
        val result = ByteArray(n) { readBuf[it] }
        repeat(n) { readBuf.removeAt(0) }
        return result
    }

    fun read(n: Int): ByteArray {
        if (readBuf.isNotEmpty()) {
            val take = minOf(n, readBuf.size)
            val result = ByteArray(take) { readBuf[it] }
            repeat(take) { readBuf.removeAt(0) }
            return result
        }
        val payload = readTlsPayload() ?: return byteArrayOf()
        if (payload.size > n) {
            readBuf.addAll(payload.drop(n).map { it })
            return payload.sliceArray(0 until n)
        }
        return payload
    }

    private fun readTlsPayload(): ByteArray? {
        if (readLeft > 0) {
            val data = readFromStream(rawInput, minOf(readLeft, 65536))
            if (data.isEmpty()) return null
            readLeft -= data.size
            return data
        }

        while (true) {
            val hdr = readExactlyFromStream(rawInput, 5) ?: return null
            val rtype = hdr[0]
            val recLen = ByteBuffer.wrap(hdr, 3, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF

            if (rtype == Constants.TLS_RECORD_CCS) {
                if (recLen > 0) readExactlyFromStream(rawInput, recLen)
                continue
            }
            if (rtype != Constants.TLS_RECORD_APPDATA) return null

            val data = readFromStream(rawInput, minOf(recLen, 65536))
            if (data.isEmpty()) return null
            val remaining = recLen - data.size
            if (remaining > 0) readLeft = remaining
            return data
        }
    }

    fun write(data: ByteArray) {
        rawOutput.write(FakeTls.wrapTlsRecord(data))
        rawOutput.flush()
    }

    private fun readFromStream(stream: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        val read = stream.read(buf, 0, n)
        return if (read <= 0) byteArrayOf() else buf.sliceArray(0 until read)
    }

    private fun readExactlyFromStream(stream: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var offset = 0
        while (offset < n) {
            val read = stream.read(buf, offset, n - offset)
            if (read == -1) return null
            offset += read
        }
        return buf
    }
}
