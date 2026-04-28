package com.tgwsproxy.bridge

import android.content.Context
import android.content.Intent
import android.os.Build
import android.net.Uri
import android.provider.Settings
import com.facebook.react.bridge.*
import com.tgwsproxy.proxy.ProxyConfig
import com.tgwsproxy.proxy.ProxyStats
import com.tgwsproxy.service.ProxyForegroundService

class ProxyModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

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
                cfProxyUserDomain = config.getString("cfProxyUserDomain") ?: "",
                dpiBypass = if (config.hasKey("dpiBypass")) config.getBoolean("dpiBypass") else false
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
                putExtra("dpiBypass", proxyConfig.dpiBypass)
                putStringArrayListExtra("dcIps",
                    ArrayList(proxyConfig.dcRedirects.map { "${it.key}:${it.value}" })
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                reactContext.startForegroundService(intent)
            } else {
                reactContext.startService(intent)
            }

            // Save to SharedPreferences for autostart
            val prefs = reactContext.getSharedPreferences("TgWsProxyPrefs", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean("is_running", true)
                putString("host", proxyConfig.host)
                putInt("port", proxyConfig.port)
                putString("secret", proxyConfig.secret)
                putInt("bufferSizeKb", proxyConfig.bufferSize / 1024)
                putInt("poolSize", proxyConfig.poolSize)
                putBoolean("cfProxy", proxyConfig.fallbackCfProxy)
                putBoolean("cfProxyPriority", proxyConfig.fallbackCfProxyPriority)
                putString("cfProxyUserDomain", proxyConfig.cfProxyUserDomain)
                putBoolean("dpiBypass", proxyConfig.dpiBypass)
                putString("dcIps", proxyConfig.dcRedirects.map { "${it.key}:${it.value}" }.joinToString(","))
                apply()
            }

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

            val prefs = reactContext.getSharedPreferences("TgWsProxyPrefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("is_running", false).apply()

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
            map.putBoolean("isRunning", ProxyForegroundService.currentEngine?.isRunning == true)
            promise.resolve(map)
        } catch (e: Exception) {
            promise.reject("STATS_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun isRunning(promise: Promise) {
        promise.resolve(ProxyForegroundService.currentEngine?.isRunning == true)
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
            ProxyForegroundService.currentEngine?.getLogs()?.forEach { logs.pushString(it) }
            promise.resolve(logs)
        } catch (e: Exception) {
            promise.reject("LOGS_ERROR", e.message, e)
        }
    }

    @ReactMethod
    fun checkAndRequestBatteryOptimizations(promise: Promise) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = reactContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                val packageName = reactContext.packageName
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    reactContext.startActivity(intent)
                    promise.resolve(false) // Means it wasn't ignoring, requested now
                } else {
                    promise.resolve(true) // Already ignoring
                }
            } else {
                promise.resolve(true) // Not needed for old Android versions
            }
        } catch (e: Exception) {
            promise.reject("BATTERY_OPT_ERROR", e.message, e)
        }
    }
}
