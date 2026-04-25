package com.tgwsproxy.bridge

import android.content.Intent
import android.os.Build
import com.facebook.react.bridge.*
import com.tgwsproxy.proxy.ProxyConfig
import com.tgwsproxy.proxy.ProxyEngine
import com.tgwsproxy.proxy.ProxyStats
import com.tgwsproxy.service.ProxyForegroundService

class ProxyModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    private var engine: ProxyEngine? = null

    override fun getName(): String = "ProxyModule"

    @ReactMethod
    fun startProxy(config: ReadableMap, promise: Promise) {
        try {
            val proxyConfig = ProxyConfig(
                host = config.getString("host") ?: "127.0.0.1",
                port = if (config.hasKey("port")) config.getInt("port") else 1443,
                secret = config.getString("secret") ?: ProxyConfig.generateSecret(),
                bufferSize = (if (config.hasKey("bufferSizeKb")) config.getInt("bufferSizeKb") else 256) * 1024,
                poolSize = if (config.hasKey("poolSize")) config.getInt("poolSize") else 4,
                fallbackCfProxy = if (config.hasKey("cfProxy")) config.getBoolean("cfProxy") else true,
                fallbackCfProxyPriority = if (config.hasKey("cfProxyPriority")) config.getBoolean("cfProxyPriority") else true,
                cfProxyUserDomain = config.getString("cfProxyUserDomain") ?: ""
            )

            if (config.hasKey("dcIps")) {
                val arr = config.getArray("dcIps")
                if (arr != null) {
                    val list = (0 until arr.size()).mapNotNull { arr.getString(it) }
                    proxyConfig.dcRedirects = ProxyConfig.parseDcIpList(list)
                }
            }

            val intent = Intent(reactContext, ProxyForegroundService::class.java).apply {
                putExtra("host", proxyConfig.host)
                putExtra("port", proxyConfig.port)
                putExtra("secret", proxyConfig.secret)
                putExtra("bufferSizeKb", proxyConfig.bufferSize / 1024)
                putExtra("poolSize", proxyConfig.poolSize)
                putExtra("cfProxy", proxyConfig.fallbackCfProxy)
                putExtra("cfProxyPriority", proxyConfig.fallbackCfProxyPriority)
                putExtra("cfProxyUserDomain", proxyConfig.cfProxyUserDomain)
                putStringArrayListExtra("dcIps",
                    ArrayList(proxyConfig.dcRedirects.map { "${it.key}:${it.value}" })
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                reactContext.startForegroundService(intent)
            } else {
                reactContext.startService(intent)
            }

            engine = ProxyEngine(proxyConfig)
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("START_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun stopProxy(promise: Promise) {
        try {
            val intent = Intent(reactContext, ProxyForegroundService::class.java)
            reactContext.stopService(intent)
            engine = null
            promise.resolve(true)
        } catch (e: Exception) {
            promise.reject("STOP_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun getStats(promise: Promise) {
        try {
            val map = Arguments.createMap()
            val stats = ProxyStats.toMap()
            for ((key, value) in stats) {
                map.putDouble(key, value.toDouble())
            }
            map.putBoolean("isRunning", engine != null)
            promise.resolve(map)
        } catch (e: Exception) {
            promise.reject("STATS_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun isRunning(promise: Promise) {
        promise.resolve(engine != null)
    }

    @ReactMethod
    fun getProxyLink(config: ReadableMap, promise: Promise) {
        val host = config.getString("host") ?: "127.0.0.1"
        val port = if (config.hasKey("port")) config.getInt("port") else 1443
        val secret = config.getString("secret") ?: ""
        val linkHost = if (host == "0.0.0.0") "127.0.0.1" else host
        promise.resolve("tg://proxy?server=$linkHost&port=$port&secret=dd$secret")
    }

    @ReactMethod
    fun getLogs(promise: Promise) {
        try {
            val logs = Arguments.createArray()
            engine?.getLogs()?.forEach { logs.pushString(it) }
            promise.resolve(logs)
        } catch (e: Exception) {
            promise.reject("LOGS_ERROR", e.message, e)
        }
    }
}
