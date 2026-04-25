package com.tgwsproxy.proxy

import java.util.concurrent.atomic.AtomicLong

object ProxyStats {
    val connectionsTotal = AtomicLong(0)
    val connectionsActive = AtomicLong(0)
    val connectionsWs = AtomicLong(0)
    val connectionsTcpFallback = AtomicLong(0)
    val connectionsCfProxy = AtomicLong(0)
    val connectionsBad = AtomicLong(0)
    val connectionsMasked = AtomicLong(0)
    val wsErrors = AtomicLong(0)
    val bytesUp = AtomicLong(0)
    val bytesDown = AtomicLong(0)
    val poolHits = AtomicLong(0)
    val poolMisses = AtomicLong(0)

    fun reset() {
        connectionsTotal.set(0)
        connectionsActive.set(0)
        connectionsWs.set(0)
        connectionsTcpFallback.set(0)
        connectionsCfProxy.set(0)
        connectionsBad.set(0)
        connectionsMasked.set(0)
        wsErrors.set(0)
        bytesUp.set(0)
        bytesDown.set(0)
        poolHits.set(0)
        poolMisses.set(0)
    }

    fun toMap(): Map<String, Long> = mapOf(
        "connectionsTotal" to connectionsTotal.get(),
        "connectionsActive" to connectionsActive.get(),
        "connectionsWs" to connectionsWs.get(),
        "connectionsTcpFallback" to connectionsTcpFallback.get(),
        "connectionsCfProxy" to connectionsCfProxy.get(),
        "connectionsBad" to connectionsBad.get(),
        "connectionsMasked" to connectionsMasked.get(),
        "wsErrors" to wsErrors.get(),
        "bytesUp" to bytesUp.get(),
        "bytesDown" to bytesDown.get(),
        "poolHits" to poolHits.get(),
        "poolMisses" to poolMisses.get()
    )

    fun summary(): String {
        val poolTotal = poolHits.get() + poolMisses.get()
        val poolStr = if (poolTotal > 0) "${poolHits.get()}/$poolTotal" else "n/a"
        return "total=${connectionsTotal.get()} active=${connectionsActive.get()} " +
                "ws=${connectionsWs.get()} tcp_fb=${connectionsTcpFallback.get()} " +
                "cf=${connectionsCfProxy.get()} bad=${connectionsBad.get()} " +
                "err=${wsErrors.get()} pool=$poolStr " +
                "up=${Constants.humanBytes(bytesUp.get())} down=${Constants.humanBytes(bytesDown.get())}"
    }
}
