package com.example.adb_connection.ui.wifi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.example.adb_connection.ui.components.DebugNavTarget
import com.example.adb_connection.ui.components.TraceMateTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.adb_connection.ui.theme.Adb_ConnectionTheme

@Composable
fun WifiSetupScreen(
    onNavigateToSettings: () -> Unit = {},
    onNavigateToHome: () -> Unit = {},
    debugNavTargets: List<DebugNavTarget> = emptyList(),
    eRelease: String? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewModel: WifiSetupViewModel = viewModel(factory = WifiSetupViewModelFactory(context))
    val uiState by viewModel.uiState.collectAsState()

    val ssidPermission = Manifest.permission.ACCESS_FINE_LOCATION

    val locationServicesEnabled = {
        context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true
    }

    val ssidPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) return@rememberLauncherForActivityResult

        if (!locationServicesEnabled()) {
            viewModel.showLocationServicesDialog()
            return@rememberLauncherForActivityResult
        }

        viewModel.onSsidPermissionGranted()
    }

    LaunchedEffect(Unit) {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, ssidPermission
        ) == PackageManager.PERMISSION_GRANTED

        when {
            !hasPermission -> ssidPermissionLauncher.launch(ssidPermission)
            !locationServicesEnabled() -> viewModel.showLocationServicesDialog()
            else -> viewModel.onSsidPermissionGranted()
        }
    }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context, ssidPermission
            ) == PackageManager.PERMISSION_GRANTED

            if (hasPermission && locationServicesEnabled()) {
                viewModel.onLocationServicesReturned()
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                WifiSetupEvent.OpenNativeCamera -> {
                    val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                    context.startActivity(intent)
                }
                WifiSetupEvent.NavigateToHome -> {
                    onNavigateToHome()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TraceMateTopBar(
                title = "WiFi Setup",
                onBackClick = onNavigateToHome,
                debugNavTargets = debugNavTargets,
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Einstellungen")
                    }
                }
            )
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0)
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        viewModel.onScanClick(Settings.System.canWrite(context))
                    },
                    modifier = Modifier.size(220.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.PhotoCamera, 
                            contentDescription = null, 
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "WiFi QR-Code\nmit Kamera scannen",
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = "Aktuelles WLAN:",
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    text = uiState.currentSsid ?: "Nicht verbunden",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Status: ${uiState.connectionStatus}",
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.weight(1f))

                if (uiState.isDebugModeEnabled) {
                    Spacer(modifier = Modifier.height(32.dp))
                    HorizontalDivider()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "WiFi Logs",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = viewModel::clearLogs) {
                            Icon(Icons.Default.Delete, contentDescription = "Logs löschen")
                        }
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Color.DarkGray.copy(alpha = 0.1f))
                            .padding(4.dp)
                    ) {
                        items(uiState.logs) { log ->
                            Text(
                                text = log,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 10.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                ),
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }

            // Success Animation Overlay
            AnimatedVisibility(
                visible = uiState.showSuccessAnimation,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.85f))
                        .clickable { viewModel.onSuccessAnimationClicked() },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color.Green,
                            modifier = Modifier.size(140.dp)
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "Verbunden mit\n${uiState.connectedNetworkName}",
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp)
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        Text(
                            text = eRelease?.let { "E-Release $it" } ?: "E-Release wird ermittelt...",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White.copy(alpha = 0.7f),
                            fontWeight = FontWeight.Light
                        )
                    }
                }
            }

            // Disconnected Animation Overlay
            AnimatedVisibility(
                visible = uiState.showDisconnectedAnimation,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.85f))
                        .clickable { viewModel.dismissAnimations() },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Cancel,
                            contentDescription = null,
                            tint = Color.Red,
                            modifier = Modifier.size(140.dp)
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "Verbindung getrennt",
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
        }
    }

    if (uiState.showConnectionErrorDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissErrorDialog,
            title = { Text("Verbindung fehlgeschlagen") },
            text = { Text("Die Verbindung zum WLAN konnte nicht hergestellt werden. Bitte versuchen Sie es erneut.") },
            confirmButton = {
                TextButton(onClick = viewModel::dismissErrorDialog) {
                    Text("OK")
                }
            }
        )
    }

    if (uiState.showPermissionDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissPermissionDialog,
            title = { Text("Berechtigung erforderlich") },
            text = { Text("Diese App benötigt die Berechtigung 'Systemeinstellungen ändern', um die WLAN-Verbindung verwalten zu können. Bitte aktivieren Sie diese in den Einstellungen.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissPermissionDialog()
                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                }) {
                    Text("Zu den Einstellungen")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissPermissionDialog) {
                    Text("Abbrechen")
                }
            }
        )
    }

    if (uiState.showLocationServicesDialog) {
        AlertDialog(
            onDismissRequest = viewModel::dismissLocationServicesDialog,
            title = { Text("Standortdienste erforderlich") },
            text = { Text("Um den WLAN-Namen (SSID) anzeigen zu können, müssen die Standortdienste aktiviert sein. Bitte aktivieren Sie diese in den Einstellungen.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissLocationServicesDialog()
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }) {
                    Text("Zu den Einstellungen")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissLocationServicesDialog) {
                    Text("Abbrechen")
                }
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
fun WifiSetupScreenPreview() {
    Adb_ConnectionTheme {
        WifiSetupScreen()
    }
}
