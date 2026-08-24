package com.amurcanov.tgwsproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import cc.hev.socks5.tunnel.HevSocks5Tunnel
import cc.hev.socks5.tunnel.TunnelConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class YouTubeVpnService : VpnService() {
    private var tunInterface: ParcelFileDescriptor? = null
    private var tunnel: HevSocks5Tunnel? = null
    @Volatile private var stopping = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val host = intent.getStringExtra(EXTRA_HOST).orEmpty().trim()
                val port = intent.getIntExtra(EXTRA_PORT, 1080)
                val username = intent.getStringExtra(EXTRA_USERNAME).orEmpty()
                val password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty()
                startForeground(NOTIFICATION_ID, createNotification())
                startTunnel(host, port, username, password)
            }
            ACTION_STOP -> stopTunnel()
        }
        return START_STICKY
    }

    private fun startTunnel(host: String, port: Int, username: String, password: String) {
        if (_isRunning.value || tunnel != null) return
        _lastError.value = ""
        stopping = false

        if (host.isBlank() || port !in 1..65535) {
            fail("Invalid SOCKS5 endpoint")
            return
        }

        try {
            val builder = Builder()
                .setSession("YouTube Proxy")
                .setMtu(TUN_MTU)
                .addAddress("10.11.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")

            try {
                builder.addAllowedApplication(YOUTUBE_PACKAGE)
            } catch (e: PackageManager.NameNotFoundException) {
                fail(getString(R.string.youtube_proxy_not_installed))
                return
            }

            tunInterface = builder.establish()
                ?: throw IllegalStateException("VpnService.Builder.establish() returned null")

            val configBuilder = TunnelConfig.Builder()
                .setSocks5Address(host)
                .setSocks5Port(port)
                .setTunName("yt0")
                .setTunMtu(TUN_MTU)
                .setTunIPv4Address("10.11.0.2")
                .setTunIPv4Gateway("10.11.0.1")
                .setTunIPv6Address(null)
                .setTunIPv6Gateway(null)
                .setDnsServers(listOf("1.1.1.1", "8.8.8.8"))
                .setMultiQueue(2)

            if (username.isNotEmpty()) configBuilder.setSocks5Username(username)
            if (password.isNotEmpty()) configBuilder.setSocks5Password(password)

            val newTunnel = HevSocks5Tunnel()
            newTunnel.startAsync(configBuilder.build(), tunInterface!!.fileDescriptor)
            tunnel = newTunnel
            _isRunning.value = true
            Log.i(TAG, "YouTube SOCKS5 VPN started: $host:$port")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start YouTube VPN", t)
            fail(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun fail(message: String) {
        _lastError.value = message
        cleanup()
    }

    private fun stopTunnel() {
        if (stopping) return
        stopping = true
        try {
            tunnel?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "Tunnel stop failed", t)
        } finally {
            tunnel = null
            _isRunning.value = false
            cleanup()
            stopping = false
        }
    }

    private fun cleanup() {
        try { tunInterface?.close() } catch (_: Throwable) {}
        tunInterface = null
        _isRunning.value = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        stopSelf()
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        try { tunnel?.stop() } catch (_: Throwable) {}
        tunnel = null
        try { tunInterface?.close() } catch (_: Throwable) {}
        tunInterface = null
        _isRunning.value = false
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.youtube_proxy_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.youtube_proxy_title))
            .setContentText(getString(R.string.youtube_proxy_notification))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.amurcanov.tgwsproxy.YOUTUBE_PROXY_START"
        const val ACTION_STOP = "com.amurcanov.tgwsproxy.YOUTUBE_PROXY_STOP"
        const val EXTRA_HOST = "youtube_proxy_host"
        const val EXTRA_PORT = "youtube_proxy_port"
        const val EXTRA_USERNAME = "youtube_proxy_username"
        const val EXTRA_PASSWORD = "youtube_proxy_password"
        const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private const val TAG = "YouTubeVpnService"
        private const val CHANNEL_ID = "youtube_proxy_v1"
        private const val NOTIFICATION_ID = 202
        private const val TUN_MTU = 8500

        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()
        private val _lastError = MutableStateFlow("")
        val lastError = _lastError.asStateFlow()
    }
}
