package com.tgwsproxy.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tgwsproxy.MainActivity
import com.tgwsproxy.MainApplication
import com.tgwsproxy.R
import com.tgwsproxy.proxy.ProxyConfig
import com.tgwsproxy.proxy.ProxyEngine

class ProxyForegroundService : Service() {

    private var engine: ProxyEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val config = extractConfig(intent)
        startForeground(NOTIFICATION_ID, buildNotification(config))
        acquireWakeLock()
        acquireWifiLock()

        engine?.stop()
        engine = ProxyEngine(config).also { it.start() }
        currentEngine = engine

        Log.i(TAG, "Foreground service started")
        return START_STICKY
    }

    override fun onDestroy() {
        engine?.stop()
        engine = null
        currentEngine = null
        releaseWakeLock()
        releaseWifiLock()
        Log.i(TAG, "Foreground service destroyed")
        super.onDestroy()
    }

    private fun buildNotification(config: ProxyConfig): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ProxyForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, MainApplication.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("TG WS Proxy")
            .setContentText("Proxy running on ${config.host}:${config.port}")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(pendingOpen)
            .addAction(0, "Stop", pendingStop)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TgWsProxy::ProxyWakeLock"
        ).apply { acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TgWsProxy::WifiLock")
            .apply { acquire() }
    }

    private fun releaseWifiLock() {
        wifiLock?.let {
            if (it.isHeld) it.release()
        }
        wifiLock = null
    }

    private fun extractConfig(intent: Intent?): ProxyConfig {
        val config = ProxyConfig()
        var hasExtras = false
        intent?.extras?.let { b ->
            if (b.containsKey("host")) {
                hasExtras = true
                config.host = b.getString("host", config.host)
                config.port = b.getInt("port", config.port)
                config.secret = b.getString("secret", config.secret)
                config.bufferSize = b.getInt("bufferSizeKb", 256) * 1024
                config.poolSize = b.getInt("poolSize", config.poolSize)
                config.fallbackCfProxy = b.getBoolean("cfProxy", config.fallbackCfProxy)
                config.fallbackCfProxyPriority = b.getBoolean("cfProxyPriority", config.fallbackCfProxyPriority)
                config.cfProxyUserDomain = b.getString("cfProxyUserDomain", config.cfProxyUserDomain)
                config.dpiBypass = b.getBoolean("dpiBypass", config.dpiBypass)

                val dcIps = b.getStringArrayList("dcIps")
                if (!dcIps.isNullOrEmpty()) {
                    try {
                        config.dcRedirects = ProxyConfig.parseDcIpList(dcIps)
                    } catch (_: Exception) {}
                }
            }
        }
        
        if (!hasExtras) {
            // Read from SharedPreferences if intent is null or empty (e.g., restarted by system)
            val prefs = getSharedPreferences("TgWsProxyPrefs", android.content.Context.MODE_PRIVATE)
            config.host = prefs.getString("host", config.host) ?: config.host
            config.port = prefs.getInt("port", config.port)
            config.secret = prefs.getString("secret", config.secret) ?: config.secret
            config.bufferSize = prefs.getInt("bufferSizeKb", 256) * 1024
            config.poolSize = prefs.getInt("poolSize", config.poolSize)
            config.fallbackCfProxy = prefs.getBoolean("cfProxy", config.fallbackCfProxy)
            config.fallbackCfProxyPriority = prefs.getBoolean("cfProxyPriority", config.fallbackCfProxyPriority)
            config.cfProxyUserDomain = prefs.getString("cfProxyUserDomain", config.cfProxyUserDomain) ?: config.cfProxyUserDomain
            config.dpiBypass = prefs.getBoolean("dpiBypass", config.dpiBypass)
            
            val dcIpsStr = prefs.getString("dcIps", "")
            if (!dcIpsStr.isNullOrEmpty()) {
                try {
                    config.dcRedirects = ProxyConfig.parseDcIpList(dcIpsStr.split(","))
                } catch (_: Exception) {}
            }
        }
        
        return config
    }

    companion object {
        private const val TAG = "TgProxy-Service"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.tgwsproxy.STOP_PROXY"

        var currentEngine: ProxyEngine? = null
            private set
    }
}
