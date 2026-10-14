package com.sokolovskiy.insider

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** Process-wide holder so the Activity and the Service share one server. */
object InsiderHost {
    @Volatile var server: GameServer? = null

    @Synchronized
    fun ensure(context: Context): GameServer =
        server ?: GameServer(context.applicationContext).also { it.start(); server = it }

    @Synchronized
    fun shutdown() {
        server?.stop()
        server = null
    }
}

/**
 * Foreground service: keeps the game server (and Wi-Fi) alive while the host app
 * is in the background or the screen is off, so players never get disconnected.
 */
class GameService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            InsiderHost.shutdown()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            // Ending the game closes the whole app (WebView) cleanly.
            android.os.Process.killProcess(android.os.Process.myPid())
            return START_NOT_STICKY
        }
        InsiderHost.ensure(this)
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
        acquireLocks()
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "insider:server").apply {
                setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L)
            }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "insider:wifi").apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Гра «Інсайдер»", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, GameService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Інсайдер: гра запущена")
            .setContentText("Гравці підключені до твого телефону")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Завершити гру", stop).build())
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "insider"
        const val ACTION_STOP = "com.sokolovskiy.insider.STOP"
    }
}
