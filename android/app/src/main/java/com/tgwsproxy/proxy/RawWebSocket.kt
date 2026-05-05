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

    @Volatile
    private var lastPongAt: Long = System.currentTimeMillis()

    @Volatile
    private var lastPingAt: Long = 0L

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

        private val sharedRandom = ThreadLocal.withInitial { java.util.Random() }

        fun connect(host: String, domain: String, timeoutMs: Int = 10000, dpiBypass: Boolean = false): RawWebSocket {
            val rawSocket = Socket()
            rawSocket.connect(InetSocketAddress(host, 443), timeoutMs)
            rawSocket.soTimeout = timeoutMs
            rawSocket.tcpNoDelay = true
            try { rawSocket.keepAlive = true } catch (_: Exception) {}

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
            val output = java.io.BufferedOutputStream(sslSocket.getOutputStream(), 65536)

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
                sslSocket.soTimeout = Constants.WS_READ_TIMEOUT_MS
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

        private fun xorMaskInPlace(buf: ByteArray, dataOff: Int, length: Int, mask: ByteArray) {
            var i = 0
            while (i < length) {
                buf[dataOff + i] = (buf[dataOff + i].toInt() xor mask[i and 3].toInt()).toByte()
                i++
            }
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
                        val buf = ByteArray(4 + length)
                        buf[0] = fb
                        buf[1] = 126.toByte()
                        buf[2] = (length ushr 8).toByte()
                        buf[3] = length.toByte()
                        System.arraycopy(data, offset, buf, 4, length)
                        buf
                    }
                    else -> {
                        val buf = ByteArray(10 + length)
                        buf[0] = fb
                        buf[1] = 127.toByte()
                        ByteBuffer.wrap(buf, 2, 8).order(ByteOrder.BIG_ENDIAN).putLong(length.toLong())
                        System.arraycopy(data, offset, buf, 10, length)
                        buf
                    }
                }
            }

            val rng = sharedRandom.get()!!
            val (headerLen, frame) = when {
                length < 126 -> {
                    val f = ByteArray(6 + length)
                    f[0] = fb
                    f[1] = (0x80 or length).toByte()
                    6 to f
                }
                length < 65536 -> {
                    val f = ByteArray(8 + length)
                    f[0] = fb
                    f[1] = (0x80 or 126).toByte()
                    f[2] = (length ushr 8).toByte()
                    f[3] = length.toByte()
                    8 to f
                }
                else -> {
                    val f = ByteArray(14 + length)
                    f[0] = fb
                    f[1] = (0x80 or 127).toByte()
                    ByteBuffer.wrap(f, 2, 8).order(ByteOrder.BIG_ENDIAN).putLong(length.toLong())
                    14 to f
                }
            }
            val maskOff = headerLen - 4
            frame[maskOff] = rng.nextInt().toByte()
            frame[maskOff + 1] = rng.nextInt().toByte()
            frame[maskOff + 2] = rng.nextInt().toByte()
            frame[maskOff + 3] = rng.nextInt().toByte()
            val maskKey = byteArrayOf(frame[maskOff], frame[maskOff + 1], frame[maskOff + 2], frame[maskOff + 3])
            System.arraycopy(data, offset, frame, headerLen, length)
            xorMaskInPlace(frame, headerLen, length, maskKey)
            return frame
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
                OP_PONG -> {
                    lastPongAt = System.currentTimeMillis()
                    continue
                }
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

        if (maskKey != null) {
            xorMaskInPlace(payload, 0, payload.size, maskKey)
        }
        return opcode to payload
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
            lastPingAt = System.currentTimeMillis()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun isPongOverdue(now: Long, timeoutMs: Long): Boolean {
        if (lastPingAt == 0L) return false
        return lastPongAt < lastPingAt && now - lastPingAt > timeoutMs
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
