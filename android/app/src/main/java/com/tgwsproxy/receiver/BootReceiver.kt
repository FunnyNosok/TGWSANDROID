package com.tgwsproxy.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.tgwsproxy.service.ProxyForegroundService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i("TgProxy-Boot", "Received action: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val prefs = context.getSharedPreferences("TgWsProxyPrefs", Context.MODE_PRIVATE)
            val isRunning = prefs.getBoolean("is_running", false)
            
            Log.i("TgProxy-Boot", "is_running in prefs: $isRunning")

            if (isRunning) {
                val serviceIntent = Intent(context, ProxyForegroundService::class.java).apply {
                    putExtra("host", prefs.getString("host", "127.0.0.1"))
                    putExtra("port", prefs.getInt("port", 1443))
                    putExtra("secret", prefs.getString("secret", ""))
                    putExtra("bufferSizeKb", prefs.getInt("bufferSizeKb", 256))
                    putExtra("poolSize", prefs.getInt("poolSize", 4))
                    putExtra("cfProxy", prefs.getBoolean("cfProxy", true))
                    putExtra("cfProxyPriority", prefs.getBoolean("cfProxyPriority", true))
                    putExtra("cfProxyUserDomain", prefs.getString("cfProxyUserDomain", ""))
                    
                    val dcIpsStr = prefs.getString("dcIps", "")
                    if (!dcIpsStr.isNullOrEmpty()) {
                        putStringArrayListExtra("dcIps", ArrayList(dcIpsStr.split(",")))
                    }
                }

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    Log.i("TgProxy-Boot", "Started ProxyForegroundService after boot")
                } catch (e: Exception) {
                    Log.e("TgProxy-Boot", "Failed to start service", e)
                }
            }
        }
    }
}
