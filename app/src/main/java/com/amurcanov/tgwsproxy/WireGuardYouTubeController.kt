package com.amurcanov.tgwsproxy

import android.content.Context
import android.util.Log
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

/**
 * Runs an imported WireGuard profile through the official WireGuard Android tunnel library.
 * Its interface configuration is rebuilt so that Android sends only YouTube through the VPN.
 */
object WireGuardYouTubeController {
    private const val TAG = "TgWsProxy/WireGuard"
    private const val TUNNEL_NAME = "youtube-wg"

    private val operationMutex = Mutex()
    private var backend: GoBackend? = null
    private var appContext: Context? = null
    private var stopRequested = false

    private val _isRunning = MutableStateFlow(false)
    val isRunning = _isRunning.asStateFlow()

    private val _lastError = MutableStateFlow("")
    val lastError = _lastError.asStateFlow()

    private val tunnel = object : Tunnel {
        override fun getName(): String = TUNNEL_NAME

        override fun onStateChange(newState: Tunnel.State) {
            val running = newState == Tunnel.State.UP
            _isRunning.value = running
            if (running) {
                Log.i(TAG, "YouTube WireGuard tunnel is up")
            } else {
                if (!stopRequested && _lastError.value.isBlank()) {
                    _lastError.value = "WireGuard tunnel disconnected"
                    Log.w(TAG, "YouTube WireGuard tunnel was disconnected")
                } else {
                    Log.i(TAG, "YouTube WireGuard tunnel is down")
                }
                appContext?.let(WireGuardStatusService::stop)
            }
        }
    }

    suspend fun start(context: Context) = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val application = context.applicationContext
            appContext = application
            stopRequested = false
            _lastError.value = ""

            if (application.packageManager.getLaunchIntentForPackage(YouTubeVpnService.YOUTUBE_PACKAGE) == null) {
                fail(application.getString(R.string.youtube_proxy_not_installed))
                return@withLock
            }

            val rawProfile = WireGuardProfileStore.read(application)
            if (rawProfile == null) {
                fail("No WireGuard profile has been imported")
                return@withLock
            }

            try {
                YouTubeProxyController.stopAndAwait(application)
                val config = parseForYoutube(rawProfile)
                WireGuardStatusService.start(application)
                val engine = backend ?: GoBackend(application).also { backend = it }
                engine.setState(tunnel, Tunnel.State.UP, config)
                _isRunning.value = true
                Log.i(TAG, "YouTube WireGuard tunnel started")
            } catch (error: Throwable) {
                Log.e(TAG, "Unable to start YouTube WireGuard tunnel", error)
                WireGuardStatusService.stop(application)
                fail("WireGuard connection error")
            }
        }
    }

    suspend fun stop(context: Context) = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            stopRequested = true
            try {
                backend?.setState(tunnel, Tunnel.State.DOWN, null)
            } catch (error: Throwable) {
                Log.w(TAG, "Unable to stop YouTube WireGuard tunnel", error)
            } finally {
                _isRunning.value = false
                context.applicationContext.let(WireGuardStatusService::stop)
            }
        }
    }

    fun parseForYoutube(profile: String): Config {
        val imported = Config.parse(ByteArrayInputStream(profile.toByteArray(Charsets.UTF_8)))
        val source = imported.getInterface()
        val interfaceBuilder = Interface.Builder()
            .addAddresses(source.getAddresses())
            .addDnsServers(source.getDnsServers())
            .addDnsSearchDomains(source.getDnsSearchDomains())
            .setKeyPair(source.getKeyPair())
            .includeApplication(YouTubeVpnService.YOUTUBE_PACKAGE)

        if (source.getListenPort().isPresent) {
            interfaceBuilder.setListenPort(source.getListenPort().get())
        }
        if (source.getMtu().isPresent) {
            interfaceBuilder.setMtu(source.getMtu().get())
        }

        return Config.Builder()
            .setInterface(interfaceBuilder.build())
            .addPeers(imported.getPeers())
            .build()
    }

    private fun fail(message: String) {
        _lastError.value = message
        _isRunning.value = false
        Log.e(TAG, message)
    }
}
