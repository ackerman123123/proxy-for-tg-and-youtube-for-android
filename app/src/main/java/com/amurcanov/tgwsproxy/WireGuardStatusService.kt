package com.amurcanov.tgwsproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owns the foreground notification while the official WireGuard backend owns the VPN TUN.
 */
class WireGuardStatusService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            serviceScope.launch {
                WireGuardYouTubeController.stop(applicationContext)
                stopSelf()
            }
            return START_NOT_STICKY
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.youtube_wireguard_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun createNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopTunnel = PendingIntent.getService(
            this,
            1,
            Intent(this, WireGuardStatusService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.youtube_wireguard_title))
            .setContentText(getString(R.string.youtube_wireguard_notification))
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.youtube_proxy_disconnect), stopTunnel)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val ACTION_STOP = "com.amurcanov.tgwsproxy.WIREGUARD_STOP"
        private const val CHANNEL_ID = "youtube_wireguard_v1"
        private const val NOTIFICATION_ID = 203
        private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WireGuardStatusService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WireGuardStatusService::class.java))
        }
    }
}
