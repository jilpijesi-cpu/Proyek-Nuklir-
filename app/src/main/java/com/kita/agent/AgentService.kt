package com.kita.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager

/**
 * Layanan latar depan: selama AI bekerja, aplikasi dianggap aktif oleh Android sehingga
 * perintah ke Termux tetap jalan walau layar mati atau Anda pindah aplikasi.
 */
class AgentService : Service() {

    private var cpuLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Layanan latar depan saja tidak mencegah CPU/Wi-Fi tidur. Saat layar mati atau pindah ke Termux,
     * koneksi streaming ke AI sering putus; kunci ini menjaganya tetap hidup selama AI bekerja.
     */
    private fun acquireLocks() {
        try {
            if (cpuLock == null) {
                val pm = applicationContext.getSystemService(PowerManager::class.java)
                cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kita:agent").apply {
                    setReferenceCounted(false)
                    acquire(6 * 60 * 60 * 1000L) // pengaman: lepas otomatis setelah 6 jam
                }
            }
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = wm.createWifiLock(mode, "kita:agent").apply { setReferenceCounted(false); acquire() }
            }
        } catch (e: Exception) {
        }
    }

    private fun releaseLocks() {
        try { cpuLock?.takeIf { it.isHeld }?.release() } catch (e: Exception) { /* cleanup best-effort: kegagalan tidak berdampak */ }
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (e: Exception) { /* cleanup best-effort: kegagalan tidak berdampak */ }
        cpuLock = null
        wifiLock = null
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, "AgentApp bekerja", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CH)
            .setContentTitle("AgentApp sedang bekerja")
            .setContentText("Ketuk untuk membuka")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, n)
        }
        acquireLocks()
        return START_NOT_STICKY
    }

    private companion object {
        const val CH = "agent_work"
    }
}
