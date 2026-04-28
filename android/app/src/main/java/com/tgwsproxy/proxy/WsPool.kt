package com.tgwsproxy.proxy

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "TgProxy-WsPool"

class WsPool(private val config: ProxyConfig) {

    private data class PoolEntry(val ws: RawWebSocket, val createdAt: Long)
    private data class PoolKey(val dc: Int, val isMedia: Boolean)

    private val idle = ConcurrentHashMap<PoolKey, ConcurrentLinkedDeque<PoolEntry>>()
    private val refilling = ConcurrentHashMap.newKeySet<PoolKey>()
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r).apply { isDaemon = true; name = "ws-pool-${id}" }
    }
    private val keepAliveExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r).apply { isDaemon = true; name = "ws-pool-keepalive" }
    }

    init {
        keepAliveExecutor.scheduleWithFixedDelay({
            pingAll()
        }, 30, 30, TimeUnit.SECONDS)
    }

    private fun pingAll() {
        val now = System.currentTimeMillis()
        for ((_, bucket) in idle) {
            val it = bucket.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                val age = now - entry.createdAt
                if (age > Constants.WS_POOL_MAX_AGE || entry.ws.isClosed) {
                    it.remove()
                    try { entry.ws.close() } catch (_: Exception) {}
                } else {
                    if (!entry.ws.sendPing()) {
                        it.remove()
                        try { entry.ws.close() } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    fun get(dc: Int, isMedia: Boolean, targetIp: String, domains: List<String>): RawWebSocket? {
        val key = PoolKey(dc, isMedia)
        val now = System.currentTimeMillis()
        val bucket = idle.getOrPut(key) { ConcurrentLinkedDeque() }

        while (true) {
            val entry = bucket.pollFirst() ?: break
            val age = now - entry.createdAt
            if (age > Constants.WS_POOL_MAX_AGE || entry.ws.isClosed) {
                try { entry.ws.close() } catch (_: Exception) {}
                continue
            }
            ProxyStats.poolHits.incrementAndGet()
            Log.d(TAG, "WS pool hit DC$dc${if (isMedia) "m" else ""} (age=${age}ms, left=${bucket.size})")
            scheduleRefill(key, targetIp, domains)
            return entry.ws
        }

        ProxyStats.poolMisses.incrementAndGet()
        scheduleRefill(key, targetIp, domains)
        return null
    }

    private fun scheduleRefill(key: PoolKey, targetIp: String, domains: List<String>) {
        if (!refilling.add(key)) return
        executor.submit {
            try {
                val bucket = idle.getOrPut(key) { ConcurrentLinkedDeque() }
                val needed = config.poolSize - bucket.size
                if (needed <= 0) return@submit

                for (i in 0 until needed) {
                    val ws = connectOne(targetIp, domains)
                    if (ws != null) {
                        bucket.addLast(PoolEntry(ws, System.currentTimeMillis()))
                    }
                }
                Log.d(TAG, "WS pool refilled DC${key.dc}${if (key.isMedia) "m" else ""}: ${bucket.size} ready")
            } finally {
                refilling.remove(key)
            }
        }
    }

    private fun connectOne(targetIp: String, domains: List<String>): RawWebSocket? {
        for (domain in domains) {
            try {
                return RawWebSocket.connect(targetIp, domain, timeoutMs = 12000, dpiBypass = config.dpiBypass)
            } catch (e: WsHandshakeError) {
                if (e.isRedirect) continue
                return null
            } catch (_: Exception) {
                return null
            }
        }
        return null
    }

    fun warmup(dcRedirects: Map<Int, String>) {
        for ((dc, targetIp) in dcRedirects) {
            for (isMedia in listOf(false, true)) {
                val domains = Constants.wsDomainsForDc(dc, isMedia)
                scheduleRefill(PoolKey(dc, isMedia), targetIp, domains)
            }
        }
        Log.i(TAG, "WS pool warmup started for ${dcRedirects.size} DC(s)")
    }

    fun reset() {
        for ((_, bucket) in idle) {
            while (true) {
                val entry = bucket.pollFirst() ?: break
                try { entry.ws.close() } catch (_: Exception) {}
            }
        }
        idle.clear()
        refilling.clear()
    }

    fun shutdown() {
        reset()
        executor.shutdownNow()
        keepAliveExecutor.shutdownNow()
    }
}
