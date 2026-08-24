package com.amurcanov.tgwsproxy.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.amurcanov.tgwsproxy.YouTubeProxyController
import com.amurcanov.tgwsproxy.YouTubeVpnService
import kotlinx.coroutines.launch

@Composable
fun YouTubeProxyTab(settingsStore: SettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val running by YouTubeVpnService.isRunning.collectAsStateWithLifecycle()
    val lastError by YouTubeVpnService.lastError.collectAsStateWithLifecycle()
    val savedHost by settingsStore.youtubeProxyHost.collectAsStateWithLifecycle(initialValue = "")
    val savedPort by settingsStore.youtubeProxyPort.collectAsStateWithLifecycle(initialValue = "1080")
    val savedUsername by settingsStore.youtubeProxyUsername.collectAsStateWithLifecycle(initialValue = "")
    val savedPassword by settingsStore.youtubeProxyPassword.collectAsStateWithLifecycle(initialValue = "")

    var host by rememberSaveable(savedHost) { mutableStateOf(savedHost) }
    var portText by rememberSaveable(savedPort) { mutableStateOf(savedPort) }
    var username by rememberSaveable(savedUsername) { mutableStateOf(savedUsername) }
    var password by rememberSaveable(savedPassword) { mutableStateOf(savedPassword) }

    fun startCurrent() {
        val port = portText.toIntOrNull()
        if (host.isBlank() || port == null || port !in 1..65535) {
            Toast.makeText(context, context.getString(R.string.youtube_proxy_invalid), Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch { settingsStore.saveYouTubeProxySettings(host, portText, username, password) }
        YouTubeProxyController.start(context, host, port, username, password)
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) startCurrent()
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
            text = stringResource(R.string.youtube_proxy_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        AppSectionCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayCircle,
                    contentDescription = null,
                    modifier = Modifier.size(74.dp),
                    tint = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (running) stringResource(R.string.status_connected) else stringResource(R.string.status_disconnected),
                    fontWeight = FontWeight.Bold,
                    color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (lastError.isNotBlank() && !running) {
                    Text(lastError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        AppSectionCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.youtube_proxy_host)) },
                    enabled = !running,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.youtube_proxy_port)) },
                    enabled = !running,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.youtube_proxy_username)) },
                    enabled = !running,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.youtube_proxy_password)) },
                    enabled = !running,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        if (running) {
                            YouTubeProxyController.stop(context)
                        } else {
                            val prepareIntent: Intent? = VpnService.prepare(context)
                            if (prepareIntent == null) startCurrent() else vpnPermissionLauncher.launch(prepareIntent)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text(if (running) stringResource(R.string.youtube_proxy_disconnect) else stringResource(R.string.youtube_proxy_connect))
                }

                OutlinedButton(
                    onClick = {
                        val launch = context.packageManager.getLaunchIntentForPackage(YouTubeVpnService.YOUTUBE_PACKAGE)
                        if (launch != null) context.startActivity(launch)
                        else Toast.makeText(context, context.getString(R.string.youtube_proxy_not_installed), Toast.LENGTH_SHORT).show()
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
}
