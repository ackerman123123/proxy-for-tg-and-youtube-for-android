package com.amurcanov.tgwsproxy.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amurcanov.tgwsproxy.R
import com.amurcanov.tgwsproxy.SettingsStore
import com.amurcanov.tgwsproxy.WireGuardProfileStore
import com.amurcanov.tgwsproxy.WireGuardYouTubeController
import com.amurcanov.tgwsproxy.YouTubeProxyController
import com.amurcanov.tgwsproxy.YouTubeVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class YouTubeRouteMode { SOCKS5, WIREGUARD }

@Composable
fun YouTubeProxyTab(settingsStore: SettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val socksRunning by YouTubeVpnService.isRunning.collectAsStateWithLifecycle()
    val socksError by YouTubeVpnService.lastError.collectAsStateWithLifecycle()
    val wireGuardRunning by WireGuardYouTubeController.isRunning.collectAsStateWithLifecycle()
    val wireGuardError by WireGuardYouTubeController.lastError.collectAsStateWithLifecycle()
    val savedHost by settingsStore.youtubeProxyHost.collectAsStateWithLifecycle(initialValue = "")
    val savedPort by settingsStore.youtubeProxyPort.collectAsStateWithLifecycle(initialValue = "1080")
    val savedUsername by settingsStore.youtubeProxyUsername.collectAsStateWithLifecycle(initialValue = "")
    val savedPassword by settingsStore.youtubeProxyPassword.collectAsStateWithLifecycle(initialValue = "")

    var modeName by rememberSaveable { mutableStateOf(YouTubeRouteMode.SOCKS5.name) }
    val mode = YouTubeRouteMode.valueOf(modeName)
    var host by rememberSaveable(savedHost) { mutableStateOf(savedHost) }
    var portText by rememberSaveable(savedPort) { mutableStateOf(savedPort) }
    var username by rememberSaveable(savedUsername) { mutableStateOf(savedUsername) }
    var password by rememberSaveable(savedPassword) { mutableStateOf(savedPassword) }
    var profileName by remember { mutableStateOf(WireGuardProfileStore.profileName(context)) }

    val selectedRunning = if (mode == YouTubeRouteMode.SOCKS5) socksRunning else wireGuardRunning
    val selectedError = if (mode == YouTubeRouteMode.SOCKS5) socksError else wireGuardError

    fun startSocks() {
        val port = portText.toIntOrNull()
        if (host.isBlank() || port == null || port !in 1..65535) {
            Toast.makeText(context, context.getString(R.string.youtube_proxy_invalid), Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            settingsStore.saveYouTubeProxySettings(host, portText, username, password)
            YouTubeProxyController.start(context, host, port, username, password)
        }
    }

    val socksVpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) startSocks()
    }
    val wireGuardVpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            scope.launch { WireGuardYouTubeController.start(context) }
        }
    }
    val wireGuardImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    profileName = withContext(Dispatchers.IO) {
                        WireGuardProfileStore.import(context, uri)
                    }
                    Toast.makeText(
                        context,
                        context.getString(R.string.youtube_wireguard_imported),
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (_: Throwable) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.youtube_wireguard_invalid),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.youtube_proxy_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = if (mode == YouTubeRouteMode.SOCKS5) {
                stringResource(R.string.youtube_proxy_subtitle)
            } else {
                stringResource(R.string.youtube_wireguard_only_youtube)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        TabRow(selectedTabIndex = if (mode == YouTubeRouteMode.SOCKS5) 0 else 1) {
            Tab(
                selected = mode == YouTubeRouteMode.SOCKS5,
                onClick = { modeName = YouTubeRouteMode.SOCKS5.name },
                text = { Text(stringResource(R.string.youtube_route_socks5)) }
            )
            Tab(
                selected = mode == YouTubeRouteMode.WIREGUARD,
                onClick = { modeName = YouTubeRouteMode.WIREGUARD.name },
                text = { Text(stringResource(R.string.youtube_route_wireguard)) }
            )
        }

        AppSectionCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayCircle,
                    contentDescription = null,
                    modifier = Modifier.height(74.dp),
                    tint = if (selectedRunning) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Text(
                    text = if (selectedRunning) {
                        stringResource(R.string.status_connected)
                    } else {
                        stringResource(R.string.status_disconnected)
                    },
                    fontWeight = FontWeight.Bold,
                    color = if (selectedRunning) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                if (selectedError.isNotBlank() && !selectedRunning) {
                    Text(
                        selectedError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        AppSectionCard(modifier = Modifier.fillMaxWidth()) {
            if (mode == YouTubeRouteMode.SOCKS5) {
                Socks5Settings(
                    host = host,
                    onHostChange = { host = it },
                    port = portText,
                    onPortChange = { portText = it.filter(Char::isDigit).take(5) },
                    username = username,
                    onUsernameChange = { username = it },
                    password = password,
                    onPasswordChange = { password = it },
                    enabled = !selectedRunning
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = if (profileName == null) {
                            stringResource(R.string.youtube_wireguard_no_profile)
                        } else {
                            stringResource(R.string.youtube_wireguard_profile, profileName.orEmpty())
                        },
                        fontWeight = FontWeight.Medium
                    )
                    OutlinedButton(
                        onClick = {
                            wireGuardImportLauncher.launch(
                                arrayOf("text/plain", "application/octet-stream", "application/wireguard")
                            )
                        },
                        enabled = !selectedRunning,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.youtube_wireguard_import))
                    }
                    if (profileName != null) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    WireGuardYouTubeController.stop(context)
                                    withContext(Dispatchers.IO) {
                                        WireGuardProfileStore.delete(context)
                                    }
                                    profileName = null
                                }
                            },
                            enabled = !selectedRunning,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.youtube_wireguard_delete))
                        }
                    }
                    Text(
                        text = stringResource(R.string.youtube_wireguard_storage),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Button(
                onClick = {
                    if (selectedRunning) {
                        scope.launch {
                            if (mode == YouTubeRouteMode.SOCKS5) {
                                YouTubeProxyController.stop(context)
                            } else {
                                WireGuardYouTubeController.stop(context)
                            }
                        }
                    } else {
                        val prepareIntent: Intent? = VpnService.prepare(context)
                        if (prepareIntent == null) {
                            if (mode == YouTubeRouteMode.SOCKS5) {
                                startSocks()
                            } else {
                                scope.launch { WireGuardYouTubeController.start(context) }
                            }
                        } else if (mode == YouTubeRouteMode.SOCKS5) {
                            socksVpnPermissionLauncher.launch(prepareIntent)
                        } else {
                            wireGuardVpnPermissionLauncher.launch(prepareIntent)
                        }
                    }
                },
                enabled = mode != YouTubeRouteMode.WIREGUARD || profileName != null || selectedRunning,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(24.dp)
            ) {
                Text(
                    if (selectedRunning) {
                        stringResource(R.string.youtube_proxy_disconnect)
                    } else {
                        stringResource(R.string.youtube_proxy_connect)
                    }
                )
            }

            OutlinedButton(
                onClick = {
                    val launch = context.packageManager
                        .getLaunchIntentForPackage(YouTubeVpnService.YOUTUBE_PACKAGE)
                    if (launch != null) {
                        context.startActivity(launch)
                    } else {
                        Toast.makeText(
                            context,
                            context.getString(R.string.youtube_proxy_not_installed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.youtube_proxy_open))
            }

            Text(
                text = stringResource(R.string.youtube_proxy_only_youtube),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun Socks5Settings(
    host: String,
    onHostChange: (String) -> Unit,
    port: String,
    onPortChange: (String) -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    enabled: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = host,
            onValueChange = onHostChange,
            label = { Text(stringResource(R.string.youtube_proxy_host)) },
            enabled = enabled,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = port,
            onValueChange = onPortChange,
            label = { Text(stringResource(R.string.youtube_proxy_port)) },
            enabled = enabled,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            label = { Text(stringResource(R.string.youtube_proxy_username)) },
            enabled = enabled,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text(stringResource(R.string.youtube_proxy_password)) },
            enabled = enabled,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
