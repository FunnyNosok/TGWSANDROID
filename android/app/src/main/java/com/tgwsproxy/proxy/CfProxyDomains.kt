package com.tgwsproxy.proxy

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "TgProxy-CfDomains"

private const val CFPROXY_DOMAINS_URL =
    "https://raw.githubusercontent.com/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt"

private val CFPROXY_ENC = listOf(
    "virkgj.com", "vmmzovy.com", "mkuosckvso.com",
    "zaewayzmplad.com", "twdmbzcm.com"
)

private val S = charArrayOf('.', 'c', 'o', '.', 'u', 'k').concatToString()

private fun decodeDomain(s: String): String {
    if (!s.endsWith(".com")) return s
    val p = s.dropLast(4)
    val n = p.count { it.isLetter() }
    val decoded = p.map { c ->
        if (c.isLetter()) {
            val base = if (c > '`') 'a' else 'A'
            ((c.code - base.code - n).mod(26) + base.code).toChar()
        } else c
    }.toCharArray().concatToString()
    return decoded + S
}

val CFPROXY_DEFAULT_DOMAINS: List<String> = CFPROXY_ENC.map { decodeDomain(it) }

object Balancer {
    var domains: List<String> = emptyList()
        private set
    private val dcToDomain = ConcurrentHashMap<Int, String>()

    fun updateDomainsList(newDomains: List<String>) {
        if (domains.toSet() == newDomains.toSet()) return
        domains = newDomains.toList()
        for (dc in listOf(1, 2, 3, 4, 5, 203)) {
            dcToDomain[dc] = newDomains.random()
        }
    }

    fun updateDomainForDc(dc: Int, domain: String): Boolean {
        if (dcToDomain[dc] == domain) return false
        dcToDomain[dc] = domain
        return true
    }

    fun getDomainsForDc(dc: Int): Sequence<String> = sequence {
        val current = dcToDomain[dc]
        if (current != null) yield(current)
        val shuffled = domains.shuffled()
        for (d in shuffled) {
            if (d != current) yield(d)
        }
    }
}

object CfProxyDomainRefresh {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r).apply { isDaemon = true; name = "cfproxy-refresh" }
    }
    private val running = AtomicBoolean(false)

    fun start(config: ProxyConfig) {
        if (config.cfProxyUserDomain.isNotBlank()) {
            Balancer.updateDomainsList(listOf(config.cfProxyUserDomain))
            return
        }

        Balancer.updateDomainsList(CFPROXY_DEFAULT_DOMAINS)

        if (running.getAndSet(true)) return

        scheduler.scheduleAtFixedRate({
            try {
                refreshFromGitHub()
            } catch (e: Exception) {
                Log.w(TAG, "CF domain refresh failed: $e")
            }
        }, 0, 3600, TimeUnit.SECONDS)
    }

    fun stop() {
        running.set(false)
        scheduler.shutdownNow()
    }

    private fun refreshFromGitHub() {
        try {
            val suffix = (1..7).map { ('a'..'z').random() }.toCharArray().concatToString()
            val url = URL("$CFPROXY_DOMAINS_URL?$suffix")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "tg-ws-proxy")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            val text = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val encoded = text.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }

            val decoded = encoded.map { decodeDomain(it) }.distinct()
            if (decoded.isNotEmpty()) {
                Balancer.updateDomainsList(decoded)
                Log.i(TAG, "CF proxy domain pool updated (${decoded.size} domains)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch CF proxy domain list: $e")
        }
    }
}
