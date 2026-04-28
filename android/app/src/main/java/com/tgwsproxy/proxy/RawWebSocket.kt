package com.tgwsproxy.proxy

import android.util.Base64
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class WsHandshakeError(
    val statusCode: Int,
    val statusLine: String,
    val headers: Map<String, String> = emptyMap(),
    val location: String? = null
) : Exception("HTTP $statusCode: $statusLine") {
    val isRedirect: Boolean
        get() = statusCode in listOf(301, 302, 303, 307, 308)
}

class RawWebSocket private constructor(
    private val socket: Socket,
    private val input: BufferedInputStream,
    private val output: OutputStream
) {
    @Volatile
    private var closed = false

    companion object {
        private const val OP_BINARY = 0x2
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA

        private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })

        private val sslContext: SSLContext by lazy {
            SSLContext.getInstance("TLS").apply {
                init(null, trustAllCerts, SecureRandom())
            }
        }

        fun connect(host: String, domain: String, timeoutMs: Int = 10000, dpiBypass: Boolean = false): RawWebSocket {
            val rawSocket = Socket()
            rawSocket.connect(InetSocketAddress(host, 443), timeoutMs)
            rawSocket.soTimeout = timeoutMs
            rawSocket.tcpNoDelay = true

            if (dpiBypass) {
                // Set small send buffer to encourage fragmentation
                try { rawSocket.sendBufferSize = 256 } catch (_: Exception) {}
            }

            val sslSocket = sslContext.socketFactory.createSocket(
                rawSocket, domain, 443, true
            ) as SSLSocket

            sslSocket.sslParameters = sslSocket.sslParameters.apply {
                serverNames = listOf(javax.net.ssl.SNIHostName(domain))
            }
            sslSocket.startHandshake()

            val input = BufferedInputStream(sslSocket.getInputStream(), 65536)
            val output = sslSocket.getOutputStream()

            val wsKey = Base64.encodeToString(
                ByteArray(16).also { SecureRandom().nextBytes(it) },
                Base64.NO_WRAP
            )

            val req = if (dpiBypass) {
                // DPI bypass tricks for WebSocket Handshake (zapret style)
                // 1. Host header case mixing (HOSт / hOsT)
                // 2. Extra spaces in headers
                // 3. Fake initial headers
                "GET /apiws HTTP/1.1\r\n" +
                        "hOsT:  $domain\r\n" +
                        "UpGrAdE:   websocket\r\n" +
                        "cOnNeCtIoN: Upgrade\r\n" +
                        "Sec-WebSocket-Key: $wsKey\r\n" +
                        "Sec-WebSocket-Version: 13\r\n" +
                        "Sec-WebSocket-Protocol: binary\r\n" +
                        "User-Agent: Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36\r\n" +
                        "\r\n"
            } else {
                "GET /apiws HTTP/1.1\r\n" +
                        "Host: $domain\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Key: $wsKey\r\n" +
                        "Sec-WebSocket-Version: 13\r\n" +
                        "Sec-WebSocket-Protocol: binary\r\n" +
                        "User-Agent: Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36\r\n" +
                        "\r\n"
            }

            val reqBytes = req.toByteArray(Charsets.UTF_8)
            
            if (dpiBypass) {
                // Write request in fragments to bypass some simple DPI filters (like ByeDPI / zapret does)
                val fragmentSize = 2 // Small fragments to split methods and headers
                for (i in reqBytes.indices step fragmentSize) {
                    val end = minOf(i + fragmentSize, reqBytes.size)
                    output.write(reqBytes, i, end - i)
                    output.flush()
                    Thread.sleep(1) // Tiny delay to ensure packets are sent separately
                }
            } else {
                output.write(reqBytes)
                output.flush()
            }

            val responseLines = mutableListOf<String>()
            val lineBuffer = StringBuilder()
            while (true) {
                val b = input.read()
                if (b == -1) break
                if (b == '\n'.code) {
                    val line = lineBuffer.toString().trimEnd('\r')
                    lineBuffer.clear()
                    if (line.isEmpty()) break
                    responseLines.add(line)
                } else {
                    lineBuffer.append(b.toChar())
                }
            }

            if (responseLines.isEmpty()) {
                sslSocket.close()
                throw WsHandshakeError(0, "empty response")
            }

            val firstLine = responseLines[0]
            val parts = firstLine.split(" ", limit = 3)
            val statusCode = parts.getOrNull(1)?.toIntOrNull() ?: 0

            if (statusCode == 101) {
                sslSocket.soTimeout = 90000
                return RawWebSocket(sslSocket, input, output)
            }

            val headers = mutableMapOf<String, String>()
            for (hl in responseLines.drop(1)) {
                val idx = hl.indexOf(':')
                if (idx != -1) {
                    headers[hl.substring(0, idx).trim().lowercase()] = hl.substring(idx + 1).trim()
                }
            }

            sslSocket.close()
            throw WsHandshakeError(statusCode, firstLine, headers, headers["location"])
        }

        private fun xorMask(data: ByteArray, offset: Int, length: Int, mask: ByteArray): ByteArray {
            val result = ByteArray(length)
            for (i in 0 until length) {
                result[i] = (data[offset + i].toInt() xor mask[i % 4].toInt()).toByte()
            }
            return result
        }

        private fun buildFrame(opcode: Int, data: ByteArray, offset: Int, length: Int, mask: Boolean): ByteArray {
            val fb = (0x80 or opcode).toByte()

            if (!mask) {
                return when {
                    length < 126 -> {
                        val buf = ByteArray(2 + length)
                        buf[0] = fb
                        buf[1] = length.toByte()
                        System.arraycopy(data, offset, buf, 2, length)
                        buf
                    }
                    length < 65536 -> {
                        val buf = ByteBuffer.allocate(4 + length).order(ByteOrder.BIG_ENDIAN)
                        buf.put(fb)
                        buf.put(126.toByte())
                        buf.putShort(length.toShort())
                        buf.put(data, offset, length)
                        buf.array()
                    }
                    else -> {
                        val buf = ByteBuffer.allocate(10 + length).order(ByteOrder.BIG_ENDIAN)
                        buf.put(fb)
                        buf.put(127.toByte())
                        buf.putLong(length.toLong())
                        buf.put(data, offset, length)
                        buf.array()
                    }
                }
            }

            val maskKey = ByteArray(4)
            SecureRandom().nextBytes(maskKey)
            val masked = xorMask(data, offset, length, maskKey)

            return when {
                length < 126 -> {
                    val buf = ByteArray(6 + length)
                    buf[0] = fb
                    buf[1] = (0x80 or length).toByte()
                    System.arraycopy(maskKey, 0, buf, 2, 4)
                    System.arraycopy(masked, 0, buf, 6, length)
                    buf
                }
                length < 65536 -> {
                    val buf = ByteBuffer.allocate(8 + length).order(ByteOrder.BIG_ENDIAN)
                    buf.put(fb)
                    buf.put((0x80 or 126).toByte())
                    buf.putShort(length.toShort())
                    buf.put(maskKey)
                    buf.put(masked)
                    buf.array()
                }
                else -> {
                    val buf = ByteBuffer.allocate(14 + length).order(ByteOrder.BIG_ENDIAN)
                    buf.put(fb)
                    buf.put((0x80 or 127).toByte())
                    buf.putLong(length.toLong())
                    buf.put(maskKey)
                    buf.put(masked)
                    buf.array()
                }
            }
        }
    }

    fun send(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        if (closed) throw IOException("WebSocket closed")
        val frame = buildFrame(OP_BINARY, data, offset, length, mask = true)
        synchronized(output) {
            output.write(frame)
            output.flush()
        }
    }

    fun sendBatch(parts: List<ByteArray>) {
        if (closed) throw IOException("WebSocket closed")
        synchronized(output) {
            for (part in parts) {
                output.write(buildFrame(OP_BINARY, part, 0, part.size, mask = true))
            }
            output.flush()
        }
    }

    fun recv(): ByteArray? {
        while (!closed) {
            val (opcode, payload) = readFrame() ?: return null

            when (opcode) {
                OP_CLOSE -> {
                    closed = true
                    try {
                        synchronized(output) {
                            val closePayload = if (payload.size >= 2) payload.sliceArray(0..1) else byteArrayOf()
                            output.write(
                                buildFrame(OP_CLOSE, closePayload, 0, closePayload.size, mask = true)
                            )
                            output.flush()
                        }
                    } catch (_: Exception) {}
                    return null
                }
                OP_PING -> {
                    try {
                        synchronized(output) {
                            output.write(buildFrame(OP_PONG, payload, 0, payload.size, mask = true))
                            output.flush()
                        }
                    } catch (_: Exception) {}
                    continue
                }
                OP_PONG -> continue
                0x1, 0x2 -> return payload
                else -> continue
            }
        }
        return null
    }

    private fun readFrame(): Pair<Int, ByteArray>? {
        val hdr = readExactly(input, 2) ?: return null
        val opcode = hdr[0].toInt() and 0x0F
        var length = (hdr[1].toInt() and 0x7F).toLong()

        if (length == 126L) {
            val ext = readExactly(input, 2) ?: return null
            length = ByteBuffer.wrap(ext).order(ByteOrder.BIG_ENDIAN).short.toLong() and 0xFFFF
        } else if (length == 127L) {
            val ext = readExactly(input, 8) ?: return null
            length = ByteBuffer.wrap(ext).order(ByteOrder.BIG_ENDIAN).long
        }

        val hasMask = hdr[1].toInt() and 0x80 != 0
        val maskKey = if (hasMask) readExactly(input, 4) else null

        val payload = readExactly(input, length.toInt()) ?: return null

        return if (maskKey != null) {
            opcode to xorMask(payload, 0, payload.size, maskKey)
        } else {
            opcode to payload
        }
    }

    private fun readExactly(stream: InputStream, n: Int): ByteArray? {
        if (n == 0) return byteArrayOf()
        val buf = ByteArray(n)
        var offset = 0
        while (offset < n) {
            val read = stream.read(buf, offset, n - offset)
            if (read == -1) return null
            offset += read
        }
        return buf
    }

    fun sendPing(): Boolean {
        if (closed) return false
        return try {
            synchronized(output) {
                output.write(buildFrame(OP_PING, byteArrayOf(), 0, 0, mask = true))
                output.flush()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun close() {
        if (closed) return
        closed = true
        try {
            synchronized(output) {
                output.write(buildFrame(OP_CLOSE, byteArrayOf(), 0, 0, mask = true))
                output.flush()
            }
        } catch (_: Exception) {}
        try {
            socket.close()
        } catch (_: Exception) {}
    }

    val isClosed: Boolean get() = closed || socket.isClosed
}
