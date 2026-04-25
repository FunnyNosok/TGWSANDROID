package com.tgwsproxy.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
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

        engine?.stop()
        engine = ProxyEngine(config).also { it.start() }

        Log.i(TAG, "Foreground service started")
        return START_STICKY
    }

    override fun onDestroy() {
        engine?.stop()
        engine = null
        releaseWakeLock()
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
            .setPriority(NotificationCompat.PRIORITY_LOW)
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

    private fun extractConfig(intent: Intent?): ProxyConfig {
        val config = ProxyConfig()
        intent?.extras?.let { b ->
            config.host = b.getString("host", config.host)
            config.port = b.getInt("port", config.port)
            config.secret = b.getString("secret", config.secret)
            config.bufferSize = b.getInt("bufferSizeKb", 256) * 1024
            config.poolSize = b.getInt("poolSize", config.poolSize)
            config.fallbackCfProxy = b.getBoolean("cfProxy", config.fallbackCfProxy)
            config.fallbackCfProxyPriority = b.getBoolean("cfProxyPriority", config.fallbackCfProxyPriority)
            config.cfProxyUserDomain = b.getString("cfProxyUserDomain", config.cfProxyUserDomain)

            val dcIps = b.getStringArrayList("dcIps")
            if (!dcIps.isNullOrEmpty()) {
                try {
                    config.dcRedirects = ProxyConfig.parseDcIpList(dcIps)
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
