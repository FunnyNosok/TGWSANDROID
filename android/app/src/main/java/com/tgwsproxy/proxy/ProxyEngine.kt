package com.tgwsproxy.proxy

import android.util.Log
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "TgProxy-Engine"

class ProxyEngine(val config: ProxyConfig) {

    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r).apply { isDaemon = true; name = "proxy-client-${id}" }
    }
    private val wsPool = WsPool(config)
    private val running = AtomicBoolean(false)
    private var acceptThread: Thread? = null

    private val wsBlacklist = ConcurrentHashMap.newKeySet<String>()
    private val dcFailUntil = ConcurrentHashMap<String, Long>()

    private val logBuffer = java.util.concurrent.ConcurrentLinkedDeque<String>()
    private val maxLogLines = 500

    val isRunning: Boolean get() = running.get()

    fun start() {
        if (running.getAndSet(true)) return

        ProxyStats.reset()
        wsBlacklist.clear()
        dcFailUntil.clear()

        if (config.fallbackCfProxy) {
            CfProxyDomainRefresh.start(config)
        }

        val secretBytes = hexToBytes(config.secret)
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(java.net.InetSocketAddress(InetAddress.getByName(config.host), config.port))
        serverSocket = ss

        logMessage("Listening on ${config.host}:${config.port}")
        logMessage("Secret: ${config.secret}")
        for ((dc, ip) in config.dcRedirects.entries.sortedBy { it.key }) {
            logMessage("  DC$dc: $ip")
        }

        val link = proxyLink()
        logMessage("Connect: $link")

        if (!config.fallbackCfProxy || !config.fallbackCfProxyPriority) {
            wsPool.warmup(config.dcRedirects)
        }

        acceptThread = Thread({
            while (running.get() && !ss.isClosed) {
                try {
                    val clientSocket = ss.accept()
                    clientSocket.tcpNoDelay = true
                    try {
                        clientSocket.sendBufferSize = config.bufferSize
                        clientSocket.receiveBufferSize = config.bufferSize
                    } catch (_: Exception) {}
                    executor.submit { handleClient(clientSocket, secretBytes) }
                } catch (e: SocketException) {
                    if (running.get()) Log.e(TAG, "Accept error: $e")
                } catch (e: Exception) {
                    Log.e(TAG, "Accept error: $e")
                }
            }
        }, "proxy-accept").also { it.start() }

        Log.i(TAG, "Proxy engine started on ${config.host}:${config.port}")
    }

    fun stop() {
        if (!running.getAndSet(false)) return

        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null

        wsPool.shutdown()
        executor.shutdownNow()

        CfProxyDomainRefresh.stop()

        try { acceptThread?.join(3000) } catch (_: Exception) {}
        acceptThread = null

        logMessage("Proxy stopped. Final stats: ${ProxyStats.summary()}")
        Log.i(TAG, "Proxy engine stopped")
    }

    fun proxyLink(): String {
        val linkHost = if (config.host == "0.0.0.0") "127.0.0.1" else config.host
        return "tg://proxy?server=$linkHost&port=${config.port}&secret=dd${config.secret}"
    }

    fun getLogs(): List<String> = logBuffer.toList()

    private fun logMessage(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        val line = "$ts  $msg"
        logBuffer.addLast(line)
        while (logBuffer.size > maxLogLines) logBuffer.pollFirst()
        Log.i(TAG, msg)
    }

    private fun handleClient(clientSocket: Socket, secret: ByteArray) {
        ProxyStats.connectionsTotal.incrementAndGet()
        ProxyStats.connectionsActive.incrementAndGet()

        val peer = clientSocket.remoteSocketAddress?.toString() ?: "?"
        val label = peer.removePrefix("/")

        try {
            val input = clientSocket.getInputStream()
            val output = clientSocket.getOutputStream()

            
            clientSocket.soTimeout = 5000
            val handshake = readExactly(input, Constants.HANDSHAKE_LEN)
            clientSocket.soTimeout = 0 

            if (handshake == null) {
                logMessage("[$label] client disconnected before handshake")
                return
            }

            val result = HandshakeParser.tryHandshake(handshake, secret)
            if (result == null) {
                ProxyStats.connectionsBad.incrementAndGet()
                logMessage("[$label] bad handshake (wrong secret or proto)")
                try { while (input.read(ByteArray(4096)) > 0) {} } catch (_: Exception) {}
                return
            }

            val (dcId, isMedia, protoTag, clientDecPrekeyIv) = result

            val protoInt = when {
                protoTag.contentEquals(Constants.PROTO_TAG_ABRIDGED) -> Constants.PROTO_ABRIDGED_INT
                protoTag.contentEquals(Constants.PROTO_TAG_INTERMEDIATE) -> Constants.PROTO_INTERMEDIATE_INT
                else -> Constants.PROTO_PADDED_INTERMEDIATE_INT
            }

            val dcIdx = if (isMedia) -dcId else dcId
            val mediaTag = if (isMedia) " media" else ""

            logMessage("[$label] handshake ok: DC$dcId$mediaTag")

            val relayInit = HandshakeParser.generateRelayInit(protoTag, dcIdx)
            val ctx = CryptoContext.build(clientDecPrekeyIv, secret, relayInit)

            val dcKey = "$dcId${if (isMedia) "m" else ""}"
            val useCfPriority = config.fallbackCfProxy && config.fallbackCfProxyPriority

            if (dcId !in config.dcRedirects || dcKey in wsBlacklist || useCfPriority) {
                val reason = when {
                    dcId !in config.dcRedirects -> "not in config"
                    useCfPriority -> "CF proxy priority"
                    else -> "WS blacklisted"
                }
                logMessage("[$label] DC$dcId$mediaTag $reason -> fallback")

                val splitter = try { MsgSplitter(relayInit, protoInt) } catch (_: Exception) { null }
                val ok = Fallback.doFallback(input, output, relayInit, label, dcId, isMedia, mediaTag, ctx, config, splitter)
                
                if (ok) {
                    if (reason != "CF proxy priority") logMessage("[$label] DC$dcId$mediaTag fallback closed")
                    return
                }
                
                if (useCfPriority && dcId in config.dcRedirects) {
                    logMessage("[$label] DC$dcId$mediaTag CF proxy priority failed, trying direct WS")
                } else {
                    logMessage("[$label] DC$dcId$mediaTag no fallback available")
                    return
                }
            }

            val now = System.currentTimeMillis()
            val failUntil = dcFailUntil[dcKey] ?: 0L
            val wsTimeout = if (now < failUntil) (Constants.WS_FAIL_TIMEOUT * 1000).toInt() else 15000

            val domains = Constants.wsDomainsForDc(dcId, isMedia)
            val target = config.dcRedirects[dcId]!!
            var ws: RawWebSocket? = null
            var wsFailedRedirect = false
            var allRedirects = true

            ws = wsPool.get(dcId, isMedia, target, domains)
            if (ws != null) {
                logMessage("[$label] DC$dcId$mediaTag -> pool hit via $target")
            } else {
                for (domain in domains) {
                    val url = "wss://$domain/apiws"
                    logMessage("[$label] DC$dcId$mediaTag -> $url via $target")
                    try {
                        ws = RawWebSocket.connect(target, domain, timeoutMs = wsTimeout, dpiBypass = config.dpiBypass)
                        allRedirects = false
                        break
                    } catch (e: WsHandshakeError) {
                        ProxyStats.wsErrors.incrementAndGet()
                        if (e.isRedirect) {
                            wsFailedRedirect = true
                            logMessage("[$label] DC$dcId$mediaTag got ${e.statusCode} from $domain -> ${e.location ?: "?"}")
                            continue
                        } else {
                            allRedirects = false
                            logMessage("[$label] DC$dcId$mediaTag WS handshake: ${e.statusLine}")
                        }
                    } catch (e: Exception) {
                        ProxyStats.wsErrors.incrementAndGet()
                        allRedirects = false
                        logMessage("[$label] DC$dcId$mediaTag WS connect failed: $e")
                    }
                }
            }

            if (ws == null) {
                if (wsFailedRedirect && allRedirects) {
                    wsBlacklist.add(dcKey)
                    logMessage("[$label] DC$dcId$mediaTag blacklisted for WS (all 302)")
                } else {
                    dcFailUntil[dcKey] = now + (Constants.DC_FAIL_COOLDOWN * 1000).toLong()
                    if (!wsFailedRedirect) {
                        logMessage("[$label] DC$dcId$mediaTag WS cooldown for ${Constants.DC_FAIL_COOLDOWN.toInt()}s")
                    }
                }

                if (!useCfPriority) {
                    val splitter = try { MsgSplitter(relayInit, protoInt) } catch (_: Exception) { null }
                    val ok = Fallback.doFallback(input, output, relayInit, label, dcId, isMedia, mediaTag, ctx, config, splitter)
                    if (ok) logMessage("[$label] DC$dcId$mediaTag fallback closed")
                }
                return
            }

            dcFailUntil.remove(dcKey)
            ProxyStats.connectionsWs.incrementAndGet()

            val splitter = try {
                MsgSplitter(relayInit, protoInt)
            } catch (_: Exception) { null }

            ws.send(relayInit)

            Bridge.bridgeWsReencrypt(input, output, ws, label, ctx, dcId, isMedia, splitter)

        } catch (e: Exception) {
            Log.e(TAG, "[$label] unexpected: $e")
        } finally {
            ProxyStats.connectionsActive.decrementAndGet()
            try { clientSocket.close() } catch (_: Exception) {}
        }
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var offset = 0
        while (offset < n) {
            val read = input.read(buf, offset, n - offset)
            if (read == -1) return null
            offset += read
        }
        return buf
    }

    companion object {
        fun hexToBytes(hex: String): ByteArray {
            val len = hex.length
            val data = ByteArray(len / 2)
            for (i in 0 until len step 2) {
                data[i / 2] = ((hex[i].digitToInt(16) shl 4) + hex[i + 1].digitToInt(16)).toByte()
            }
            return data
        }
    }
}
