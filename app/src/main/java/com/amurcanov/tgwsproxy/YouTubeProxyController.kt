package com.amurcanov.tgwsproxy

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

/** Starts or restarts the YouTube-only foreground SOCKS5 VPN with an immutable request. */
object YouTubeProxyController {
    suspend fun start(context: Context, host: String, port: Int, username: String, password: String) {
        WireGuardYouTubeController.stop(context)
        ContextCompat.startForegroundService(
            context,
            Intent(context, YouTubeVpnService::class.java).apply {
                action = YouTubeVpnService.ACTION_START
                putExtra(YouTubeVpnService.EXTRA_HOST, host.trim())
                putExtra(YouTubeVpnService.EXTRA_PORT, port)
                putExtra(YouTubeVpnService.EXTRA_USERNAME, username)
                putExtra(YouTubeVpnService.EXTRA_PASSWORD, password)
            }
        )
    }

    fun stop(context: Context) {
        context.startService(
            Intent(context, YouTubeVpnService::class.java).apply {
                action = YouTubeVpnService.ACTION_STOP
            }
        )
    }

    suspend fun stopAndAwait(context: Context) {
        if (!YouTubeVpnService.isRunning.value) return
        stop(context)
        repeat(20) {
            if (!YouTubeVpnService.isRunning.value) return
            delay(100)
        }
    }
}
