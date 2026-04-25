package com.tgwsproxy.proxy

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

private const val TAG = "TgProxy-Fallback"

object Fallback {

    fun doFallback(
        clientInput: InputStream,
        clientOutput: OutputStream,
        relayInit: ByteArray,
        label: String,
        dc: Int,
        isMedia: Boolean,
        mediaTag: String,
        ctx: CryptoContext,
        config: ProxyConfig,
        splitter: MsgSplitter? = null
    ): Boolean {
        val fallbackDst = Constants.DC_DEFAULT_IPS[dc]
        val useCf = config.fallbackCfProxy

        val methods = mutableListOf<String>()
        if (useCf) {
            if (config.fallbackCfProxyPriority) methods.add(0, "cf")
            else methods.add("cf")
        }
        methods.add(if (methods.contains("cf") && !config.fallbackCfProxyPriority) methods.indexOf("cf") else methods.size, "tcp")
        if (!methods.contains("tcp")) methods.add("tcp")

        for (method in methods.distinct()) {
            when (method) {
                "cf" -> {
                    if (cfProxyFallback(clientInput, clientOutput, relayInit, label, ctx, dc, isMedia, splitter))
                        return true
                }
                "tcp" -> {
                    if (fallbackDst != null) {
                        Log.i(TAG, "[$label] DC$dc$mediaTag -> TCP fallback to $fallbackDst:443")
                        if (tcpFallback(clientInput, clientOutput, fallbackDst, 443, relayInit, label, ctx))
                            return true
                    }
                }
            }
        }
        return false
    }

    private fun cfProxyFallback(
        clientInput: InputStream,
        clientOutput: OutputStream,
        relayInit: ByteArray,
        label: String,
        ctx: CryptoContext,
        dc: Int,
        isMedia: Boolean,
        splitter: MsgSplitter? = null
    ): Boolean {
        val mediaTag = if (isMedia) " media" else ""
        Log.i(TAG, "[$label] DC$dc$mediaTag -> trying CF proxy")

        var ws: RawWebSocket? = null
        var chosenDomain: String? = null

        for (baseDomain in Balancer.getDomainsForDc(dc)) {
            val domain = "kws$dc.$baseDomain"
            try {
                ws = RawWebSocket.connect(domain, domain, timeoutMs = 10000)
                chosenDomain = baseDomain
                break
            } catch (e: Exception) {
                Log.w(TAG, "[$label] DC$dc$mediaTag CF proxy failed: $e")
            }
        }

        if (ws == null) return false

        if (chosenDomain != null && Balancer.updateDomainForDc(dc, chosenDomain)) {
            Log.i(TAG, "[$label] Switched active CF domain")
        }

        ProxyStats.connectionsCfProxy.incrementAndGet()
        ws.send(relayInit)
        Bridge.bridgeWsReencrypt(clientInput, clientOutput, ws, label, ctx, dc, isMedia, splitter)
        return true
    }

    private fun tcpFallback(
        clientInput: InputStream,
        clientOutput: OutputStream,
        dst: String,
        port: Int,
        relayInit: ByteArray,
        label: String,
        ctx: CryptoContext
    ): Boolean {
        return try {
            val remoteSocket = Socket()
            remoteSocket.connect(InetSocketAddress(dst, port), 10000)
            remoteSocket.tcpNoDelay = true

            val remoteInput = remoteSocket.getInputStream()
            val remoteOutput = remoteSocket.getOutputStream()

            ProxyStats.connectionsTcpFallback.incrementAndGet()
            remoteOutput.write(relayInit)
            remoteOutput.flush()

            Bridge.bridgeTcpReencrypt(clientInput, clientOutput, remoteInput, remoteOutput, label, ctx)
            try { remoteSocket.close() } catch (_: Exception) {}
            true
        } catch (e: Exception) {
            Log.w(TAG, "[$label] TCP fallback to $dst:$port failed: $e")
            false
        }
    }
}
