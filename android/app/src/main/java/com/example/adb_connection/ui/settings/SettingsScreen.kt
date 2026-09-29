package com.example.adb_connection.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.example.adb_connection.ui.components.DebugNavTarget
import com.example.adb_connection.ui.components.TraceMateTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun SettingsScreen(
    onNavigateToHome: () -> Unit = {},
    onNavigateToWifi: () -> Unit = {},
    debugNavTargets: List<DebugNavTarget> = emptyList()
) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModelFactory(context))
    DisposableEffect(viewModel) {
        onDispose(viewModel::flushPendingWrites)
    }
    val isDebugEnabled by viewModel.isDebugModeEnabled.collectAsState()
    val isAutoNavEnabled by viewModel.isAutoNavigationEnabled.collectAsState()

    val sshHostInitial by viewModel.sshHost.collectAsState()
    val sshUserInitial by viewModel.sshUser.collectAsState()
    val sshPortInitial by viewModel.sshPort.collectAsState()
    val sshPasswordInitial by viewModel.sshPassword.collectAsState()
    val macLanIpInitial by viewModel.macLanIp.collectAsState()
    val macSshPortInitial by viewModel.macSshPort.collectAsState()
    val macSshUserInitial by viewModel.macSshUser.collectAsState()
    val macSshPasswordInitial by viewModel.macSshPassword.collectAsState()

    var sshHost by rememberSaveable(sshHostInitial) { mutableStateOf(sshHostInitial) }
    var sshUser by rememberSaveable(sshUserInitial) { mutableStateOf(sshUserInitial) }
    var sshPort by rememberSaveable(sshPortInitial) { mutableStateOf(sshPortInitial.toString()) }
    var sshPassword by rememberSaveable(sshPasswordInitial) { mutableStateOf(sshPasswordInitial) }
    var macLanIp by rememberSaveable(macLanIpInitial) { mutableStateOf(macLanIpInitial) }
    var macSshPort by rememberSaveable(macSshPortInitial) { mutableStateOf(macSshPortInitial.toString()) }
    var macSshUser by rememberSaveable(macSshUserInitial) { mutableStateOf(macSshUserInitial) }
    var macSshPassword by rememberSaveable(macSshPasswordInitial) { mutableStateOf(macSshPasswordInitial) }

    Scaffold(
        topBar = {
            TraceMateTopBar(
                title = "Einstellungen",
                onBackClick = onNavigateToHome,
                debugNavTargets = debugNavTargets,
                actions = {
                    IconButton(onClick = onNavigateToWifi) {
                        Icon(Icons.Default.Wifi, contentDescription = "WLAN Setup")
                    }
                }
            )
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Optionen", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Debug Modus", modifier = Modifier.weight(1f))
                Switch(
                    checked = isDebugEnabled,
                    onCheckedChange = { viewModel.setDebugModeEnabled(it) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Keine Navigationsleiste\n(Automatische Navigation)", modifier = Modifier.weight(1f))
                Switch(
                    checked = isAutoNavEnabled,
                    onCheckedChange = { viewModel.setAutoNavigationEnabled(it) }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text("Headunit SSH", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = sshHost,
                onValueChange = { sshHost = it; viewModel.setSshHost(it) },
                label = { Text("Headunit IP-Adresse") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = sshUser,
                onValueChange = { sshUser = it; viewModel.setSshUser(it) },
                label = { Text("Headunit SSH-Benutzer") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = sshPort,
                onValueChange = {
                    sshPort = it
                    it.toIntOrNull()?.takeIf { port -> port in 1..65535 }
                        ?.let(viewModel::setSshPort)
                },
                label = { Text("Headunit SSH-Port") },
                singleLine = true,
                isError = sshPort.isNotEmpty() && (sshPort.toIntOrNull() !in 1..65535),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = sshPassword,
                onValueChange = { sshPassword = it; viewModel.setSshPassword(it) },
                label = { Text("Headunit SSH-Passwort") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text("Mac LAN SSH", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = macLanIp,
                onValueChange = { macLanIp = it; viewModel.setMacLanIp(it) },
                label = { Text("Mac LAN-IP-Adresse (Fallback)") },
                singleLine = true,
                isError = macLanIp.isNotEmpty() && !com.example.adb_connection.ui.home.isValidIpv4(macLanIp),
                supportingText = if (macLanIp.isNotEmpty() && !com.example.adb_connection.ui.home.isValidIpv4(macLanIp)) {
                    { Text("Ungültige IPv4-Adresse") }
                } else {
                    { Text("Wird nur verwendet, wenn die Ethernet-Erkennung nicht verfügbar ist.") }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = macSshPort,
                onValueChange = {
                    macSshPort = it
                    it.toIntOrNull()?.takeIf { port -> port in 1..65535 }
                        ?.let(viewModel::setMacSshPort)
                },
                label = { Text("Mac SSH-Port") },
                singleLine = true,
                isError = macSshPort.isNotEmpty() && (macSshPort.toIntOrNull() !in 1..65535),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = macSshUser,
                onValueChange = { macSshUser = it; viewModel.setMacSshUser(it) },
                label = { Text("Mac SSH-Benutzer") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = macSshPassword,
                onValueChange = { macSshPassword = it; viewModel.setMacSshPassword(it) },
                label = { Text("Mac SSH-Passwort") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
