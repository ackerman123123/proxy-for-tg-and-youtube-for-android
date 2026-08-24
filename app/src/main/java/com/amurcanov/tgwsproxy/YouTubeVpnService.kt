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
import hev.htproxy.TProxyService
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * YouTube-only Android VPN backed by the unmodified official
 * heiher/hev-socks5-tunnel JNI library.
 */
class YouTubeVpnService : VpnService() {
    private val stateLock = Any()
    private var tunInterface: ParcelFileDescriptor? = null
    private var activeRequest: TunnelRequest? = null
    private var queuedRestart: TunnelRequest? = null
    private var stopping = false
    private var destroyed = false
    private var generation = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val request = TunnelRequest(
                    intent.getStringExtra(EXTRA_HOST).orEmpty().trim(),
                    intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT),
                    intent.getStringExtra(EXTRA_USERNAME).orEmpty(),
                    intent.getStringExtra(EXTRA_PASSWORD).orEmpty()
                )
                startForeground(NOTIFICATION_ID, createNotification())
                startOrRestart(request)
            }
            ACTION_STOP -> requestStop(null)
        }
        // Android must never restart this service without the SOCKS credentials.
        return START_NOT_STICKY
    }

    private fun startOrRestart(request: TunnelRequest) {
        val startNow = synchronized(stateLock) {
            when {
                destroyed -> false
                stopping -> {
                    queuedRestart = request
                    false
                }
                activeRequest != null -> {
                    queuedRestart = request
                    beginStopLocked()
                    false
                }
                else -> true
            }
        }
        if (startNow) startTunnel(request)
    }

    private fun startTunnel(request: TunnelRequest) {
        if (request.host.isBlank() || request.port !in 1..65535) {
            fail(getString(R.string.youtube_proxy_invalid))
            return
        }
        if (request.username.isBlank() != request.password.isBlank()) {
            fail("SOCKS5 authentication requires both username and password.")
            return
        }

        try {
            val builder = Builder()
                .setSession(getString(R.string.youtube_proxy_title))
                .setMtu(TUN_MTU)
                .addAddress(TUN_IPV4, 32)
                .addAddress(TUN_IPV6, 128)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer(DNS_IPV4)
                .addDnsServer(DNS_IPV6)

            try {
                // This allow-list is the split-VPN boundary.
                builder.addAllowedApplication(YOUTUBE_PACKAGE)
            } catch (_: PackageManager.NameNotFoundException) {
                fail(getString(R.string.youtube_proxy_not_installed))
                return
            }

            val tun = builder.establish()
                ?: throw IllegalStateException("Could not establish the Android VPN interface")
            val config = writeTunnelConfig(request)
            if (!TProxyService.TProxyStartService(config.absolutePath, tun.fd)) {
                tun.close()
                throw IllegalStateException("The official SOCKS5 tunnel did not start")
            }

            val activeGeneration = synchronized(stateLock) {
                tunInterface = tun
                activeRequest = request
                generation += 1
                generation
            }
            _lastError.value = ""
            _isRunning.value = true
            Log.i(TAG, "YouTube VPN started via " + request.host + ":" + request.port)
            watchNativeTunnel(activeGeneration)
        } catch (error: Throwable) {
            Log.e(TAG, "[ERROR] YouTube VPN start failed", error)
            fail(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun requestStop(restart: TunnelRequest?) {
        synchronized(stateLock) {
            queuedRestart = restart
            if (stopping) return
            if (activeRequest == null) {
                queuedRestart = null
                cleanupAfterStop()
                return
            }
            beginStopLocked()
        }
    }

    private fun beginStopLocked() {
        if (stopping) return
        stopping = true
        Thread {
            try {
                if (TProxyService.TProxyIsRunning()) TProxyService.TProxyStopService()
            } catch (error: Throwable) {
                Log.w(TAG, "[WARN] YouTube VPN native stop failed", error)
            } finally {
                val restart = synchronized(stateLock) {
                    activeRequest = null
                    stopping = false
                    val result = queuedRestart
                    queuedRestart = null
                    result
                }
                _isRunning.value = false
                closeTun()
                if (restart != null && !destroyed) {
                    Log.i(TAG, "Restarting YouTube VPN with updated SOCKS5 settings")
                    startTunnel(restart)
                } else {
                    Log.i(TAG, "YouTube VPN stopped")
                    cleanupAfterStop()
                }
            }
        }.apply {
            name = "YouTubeVpnStop"
            start()
        }
    }

    private fun watchNativeTunnel(watchedGeneration: Long) {
        Thread {
            try {
                Thread.sleep(250)
                while (
                    TProxyService.TProxyIsRunning() &&
                    synchronized(stateLock) {
                        !destroyed && !stopping && generation == watchedGeneration
                    }
                ) {
                    Thread.sleep(500)
                }

                val failed = synchronized(stateLock) {
                    !destroyed && !stopping && generation == watchedGeneration && activeRequest != null
                }
                if (failed) {
                    _lastError.value = "SOCKS5 tunnel stopped unexpectedly"
                    Log.e(TAG, "[ERROR] Official SOCKS5 tunnel stopped unexpectedly")
                    synchronized(stateLock) { activeRequest = null }
                    _isRunning.value = false
                    closeTun()
                    cleanupAfterStop()
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (error: Throwable) {
                Log.w(TAG, "[WARN] YouTube VPN monitor stopped", error)
            }
        }.apply {
            name = "YouTubeVpnMonitor"
            isDaemon = true
            start()
        }
    }

    private fun writeTunnelConfig(request: TunnelRequest): File {
        val config = File(filesDir, TUNNEL_CONFIG_FILE)
        config.writeText(buildString {
            appendLine("tunnel:")
            appendLine("  name: yt0")
            appendLine("  mtu: " + TUN_MTU)
            appendLine("  multi-queue: false")
            appendLine("  ipv4: " + TUN_IPV4)
            appendLine("  ipv6: '" + TUN_IPV6 + "'")
            appendLine("  icmp: 'off'")
            appendLine("socks5:")
            appendLine("  address: " + yamlQuoted(request.host))
            appendLine("  port: " + request.port)
            appendLine("  udp: 'udp'")
            if (request.username.isNotBlank()) {
                appendLine("  username: " + yamlQuoted(request.username))
                appendLine("  password: " + yamlQuoted(request.password))
            }
            appendLine("misc:")
            appendLine("  log-file: 'null'")
            appendLine("  log-level: 'info'")
        })
        return config
    }

    private fun yamlQuoted(value: String) = "'" + value.replace("'", "''") + "'"

    private fun fail(message: String) {
        _lastError.value = message
        _isRunning.value = false
        Log.e(TAG, "[ERROR] " + message)
        try {
            if (TProxyService.TProxyIsRunning()) TProxyService.TProxyStopService()
        } catch (_: Throwable) {
        }
        synchronized(stateLock) {
            activeRequest = null
            queuedRestart = null
            stopping = false
        }
        closeTun()
        cleanupAfterStop()
    }

    private fun closeTun() {
        try {
            tunInterface?.close()
        } catch (_: Throwable) {
        } finally {
            tunInterface = null
        }
    }

    private fun cleanupAfterStop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onRevoke() {
        _lastError.value = "VPN permission was revoked"
        Log.w(TAG, "[WARN] Android revoked YouTube VPN permission")
        requestStop(null)
        super.onRevoke()
    }

    override fun onDestroy() {
        destroyed = true
        synchronized(stateLock) { queuedRestart = null }
        try {
            if (TProxyService.TProxyIsRunning()) TProxyService.TProxyStopService()
        } catch (error: Throwable) {
            Log.w(TAG, "[WARN] YouTube VPN shutdown failed", error)
        }
        _isRunning.value = false
        closeTun()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.youtube_proxy_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopService = PendingIntent.getService(
            this, 1, Intent(this, YouTubeVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.youtube_proxy_title))
            .setContentText(getString(R.string.youtube_proxy_notification))
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.youtube_proxy_disconnect), stopService)
            .setOngoing(true)
            .build()
    }

    private data class TunnelRequest(
        val host: String,
        val port: Int,
        val username: String,
        val password: String
    )

    companion object {
        const val ACTION_START = "com.amurcanov.tgwsproxy.YOUTUBE_PROXY_START"
        const val ACTION_STOP = "com.amurcanov.tgwsproxy.YOUTUBE_PROXY_STOP"
        const val EXTRA_HOST = "youtube_proxy_host"
        const val EXTRA_PORT = "youtube_proxy_port"
        const val EXTRA_USERNAME = "youtube_proxy_username"
        const val EXTRA_PASSWORD = "youtube_proxy_password"
        const val YOUTUBE_PACKAGE = "com.google.android.youtube"

        private const val TAG = "TgWsProxy"
        private const val CHANNEL_ID = "youtube_proxy_v1"
        private const val NOTIFICATION_ID = 202
        private const val DEFAULT_PORT = 1080
        private const val TUN_MTU = 8500
        private const val TUN_IPV4 = "10.11.0.2"
        private const val TUN_IPV6 = "fd00:11::2"
        private const val DNS_IPV4 = "1.1.1.1"
        private const val DNS_IPV6 = "2606:4700:4700::1111"
        private const val TUNNEL_CONFIG_FILE = "youtube-hev-socks5.yml"

        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()
        private val _lastError = MutableStateFlow("")
        val lastError = _lastError.asStateFlow()
    }
}
