package com.example.adb_connection.ui.home

import android.Manifest
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.example.adb_connection.ui.components.DebugNavTarget
import com.example.adb_connection.ui.components.TraceMateTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.adb_connection.data.wifi.ConnectionStatus
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

private val Green = Color(0xFF2E7D32)
private val WarningYellow = Color(0xFFFFA000)

@Composable
fun HomeScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToWifi: () -> Unit,
    onNavigateToUsbCopy: () -> Unit,
    onNavigateToCaptureToolPlaceholderCaptures: () -> Unit,
    onNavigateToUsbFiles: () -> Unit = {},
    debugNavTargets: List<DebugNavTarget> = emptyList()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewModel: HomeViewModel = viewModel(factory = HomeViewModelFactory(context))
    val currentSsid by viewModel.currentSsid.collectAsState()
    val status by viewModel.connectionStatus.collectAsState()
    val isAutoNavEnabled by viewModel.isAutoNavigationEnabled.collectAsState()
    val isDebugMode by viewModel.isDebugModeEnabled.collectAsState()
    val adbConnectionState by viewModel.adbConnectionState.collectAsState()
    val sshTestState by viewModel.sshTestState.collectAsState()
    val prepareFirewallState by viewModel.prepareFirewallState.collectAsState()
    val adbTcpTestState by viewModel.adbTcpTestState.collectAsState()
    val adbHandshakeTestState by viewModel.adbHandshakeTestState.collectAsState()
    val adbShellTestState by viewModel.adbShellTestState.collectAsState()
    val macSshTestState by viewModel.macSshTestState.collectAsState()
    val macAgentState by viewModel.macAgentState.collectAsState()
    val macAgentCaptureState by viewModel.macAgentCaptureState.collectAsState()
    val ethernetTetheringTestState by viewModel.ethernetTetheringTestState.collectAsState()
    val connectionValidationState by viewModel.connectionValidationState.collectAsState()
    val captureToolPlaceholderCaptureState by viewModel.captureToolPlaceholderCaptureState.collectAsState()
    val captureToolPlaceholderEthernetPreflightState by viewModel.captureToolPlaceholderEthernetPreflightState.collectAsState()

    val ssidPermission = Manifest.permission.ACCESS_FINE_LOCATION

    val locationServicesEnabled = {
        context.getSystemService(LocationManager::class.java)?.isLocationEnabled == true
    }

    var showLocationServicesDialog by remember { mutableStateOf(false) }
    var showMacEthernetConfirmation by remember { mutableStateOf(false) }
    var showCaptureToolPlaceholderStopConfirmation by remember { mutableStateOf(false) }
    var showActiveMacCaptureStopConfirmation by remember { mutableStateOf(false) }
    var showNoCaptureToolPlaceholderActivityDialog by remember { mutableStateOf(false) }
    var noCaptureToolPlaceholderActivityWarningJobId by remember { mutableStateOf<String?>(null) }

    val ssidPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) return@rememberLauncherForActivityResult

        if (!locationServicesEnabled()) {
            showLocationServicesDialog = true
            return@rememberLauncherForActivityResult
        }

        viewModel.refreshSsid()
    }

    val nearbyWifiPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.NEARBY_WIFI_DEVICES
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            nearbyWifiPermissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        val hasPermission = ContextCompat.checkSelfPermission(
            context, ssidPermission
        ) == PackageManager.PERMISSION_GRANTED

        when {
            !hasPermission -> ssidPermissionLauncher.launch(ssidPermission)
            !locationServicesEnabled() -> showLocationServicesDialog = true
            else -> viewModel.refreshSsid()
        }
    }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context, ssidPermission
            ) == PackageManager.PERMISSION_GRANTED

            if (hasPermission && locationServicesEnabled()) {
                showLocationServicesDialog = false
                viewModel.refreshSsid()
            }
            viewModel.onCaptureToolPlaceholderEthernetTetheringSettingsReturned()
        }
    }

    if (showLocationServicesDialog) {
        AlertDialog(
            onDismissRequest = { showLocationServicesDialog = false },
            title = { Text("Standortdienste erforderlich") },
            text = { Text("Um den WLAN-Namen (SSID) anzeigen zu können, müssen die Standortdienste aktiviert sein. Bitte aktivieren Sie diese in den Einstellungen.") },
            confirmButton = {
                TextButton(onClick = {
                    showLocationServicesDialog = false
                    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }) {
                    Text("Zu den Einstellungen")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLocationServicesDialog = false }) {
                    Text("Abbrechen")
                }
            }
        )
    }

    if (showMacEthernetConfirmation) {
        AlertDialog(
            onDismissRequest = { showMacEthernetConfirmation = false },
            title = { Text("Mac Ethernet vorbereiten") },
            text = {
                Text(
                    "Ist Ethernet-Tethering auf dem Android-Gerät aktiviert und der Mac per Ethernet verbunden?\n\n" +
                        "Prüfe außerdem, dass die aktuelle Mac-Ethernet-IP in den Mac-LAN-SSH-Einstellungen eingetragen ist."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showMacEthernetConfirmation = false
                    viewModel.runMacSshTest()
                }) { Text("OK, Test starten") }
            },
            dismissButton = {
                TextButton(onClick = { showMacEthernetConfirmation = false }) { Text("Abbrechen") }
            }
        )
    }

    LaunchedEffect(status, isAutoNavEnabled) {
        if (isAutoNavEnabled && status == ConnectionStatus.DISCONNECTED) {
            onNavigateToWifi()
        }
    }

    // Auto-dismiss the ADB connected popup after 15 seconds
    LaunchedEffect(adbConnectionState) {
        if (adbConnectionState is AdbConnectionState.Connected) {
            delay(15.seconds)
            viewModel.dismissAdbPopup()
        }
    }

    LaunchedEffect(ethernetTetheringTestState) {
        if (ethernetTetheringTestState !is EthernetTetheringTestState.Idle &&
            ethernetTetheringTestState !is EthernetTetheringTestState.Running
        ) {
            delay(5.seconds)
            viewModel.dismissEthernetTetheringTest()
        }
    }

    val activeMacAgentJob = (macAgentCaptureState as? MacAgentCaptureState.Active)?.job
    LaunchedEffect(activeMacAgentJob?.jobId, activeMacAgentJob?.activityState) {
        if (activeMacAgentJob?.activityState == "NO_CAPTURE_SESSION_PLACEHOLDER_ACTIVITY" &&
            noCaptureToolPlaceholderActivityWarningJobId != activeMacAgentJob.jobId
        ) {
            noCaptureToolPlaceholderActivityWarningJobId = activeMacAgentJob.jobId
            showNoCaptureToolPlaceholderActivityDialog = true
        }
    }


    val isValidationRunning = connectionValidationState is ConnectionValidationState.Running

    when (val preflight = captureToolPlaceholderEthernetPreflightState) {
        CaptureToolPlaceholderEthernetPreflightState.EthernetUnavailable -> {
            AlertDialog(
                onDismissRequest = viewModel::dismissCaptureToolPlaceholderEthernetPreflight,
                title = { Text("Ethernet tethering is not enabled") },
                text = {
                    Text("Enable Ethernet tethering and connect the Mac by Ethernet before starting CAPTURE_TOOL_PLACEHOLDER capture.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.openCaptureToolPlaceholderEthernetTetheringSettings()
                        context.startActivity(Intent("android.settings.TETHER_SETTINGS"))
                    }) { Text("Go to Settings") }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissCaptureToolPlaceholderEthernetPreflight) { Text("Dismiss") }
                }
            )
        }
        CaptureToolPlaceholderEthernetPreflightState.ReadyToContinue -> {
            AlertDialog(
                onDismissRequest = viewModel::dismissCaptureToolPlaceholderEthernetPreflight,
                title = { Text("Ethernet tethering enabled") },
                text = {
                    Text("Ethernet tethering is active and the Mac Ethernet connection was detected. Continue to test the Mac SSH connection before starting CAPTURE_TOOL_PLACEHOLDER capture.")
                },
                confirmButton = {
                    TextButton(onClick = viewModel::continueCaptureToolPlaceholderAfterEthernetPreflight) { Text("Continue") }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissCaptureToolPlaceholderEthernetPreflight) { Text("Dismiss") }
                }
            )
        }
        CaptureToolPlaceholderEthernetPreflightState.TestingMacSsh -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Testing Mac SSH") },
                text = { Text("Checking the Mac SSH connection over Ethernet.") },
                confirmButton = {}
            )
        }
        is CaptureToolPlaceholderEthernetPreflightState.MacSshFailed -> {
            AlertDialog(
                onDismissRequest = viewModel::dismissCaptureToolPlaceholderEthernetPreflight,
                title = { Text("Mac SSH test failed") },
                text = { Text(preflight.message) },
                confirmButton = {
                    TextButton(onClick = viewModel::dismissCaptureToolPlaceholderEthernetPreflight) { Text("Dismiss") }
                }
            )
        }
        else -> Unit
    }

    // CAPTURE_TOOL_PLACEHOLDER step confirmation dialog
    val captureToolPlaceholderWaiting = captureToolPlaceholderCaptureState as? CaptureToolPlaceholderCaptureState.WaitingForUserConfirmation
    if (captureToolPlaceholderWaiting != null && captureToolPlaceholderWaiting.step != CaptureToolPlaceholderCaptureStep.CAPTURE_RUNNING) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelCaptureToolPlaceholderCapture() },
            title = {
                Text(
                    text = when (captureToolPlaceholderWaiting.step) {
                        CaptureToolPlaceholderCaptureStep.CONFIRM_TRUST -> "CAPTURE_TOOL_PLACEHOLDER: Prerequisites"
                        CaptureToolPlaceholderCaptureStep.CONFIRM_BT_OFF -> "CAPTURE_TOOL_PLACEHOLDER: Turn Bluetooth Off"
                        CaptureToolPlaceholderCaptureStep.CONFIRM_BT_ON -> "CAPTURE_TOOL_PLACEHOLDER: Turn Bluetooth On"
                        CaptureToolPlaceholderCaptureStep.CONFIRM_CAPTURE_SESSION_PLACEHOLDER_ACTIVE -> "CAPTURE_TOOL_PLACEHOLDER: Confirm CaptureSessionPlaceholder Active"
                        CaptureToolPlaceholderCaptureStep.CAPTURE_RUNNING -> "CAPTURE_TOOL_PLACEHOLDER: Capture Running"
                        else -> "CAPTURE_TOOL_PLACEHOLDER: Confirm Step"
                    }
                )
            },
            text = { Text(captureToolPlaceholderWaiting.message) },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmCaptureToolPlaceholderStep() }) {
                    Text("Confirmed")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelCaptureToolPlaceholderCapture() }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showCaptureToolPlaceholderStopConfirmation) {
        AlertDialog(
            onDismissRequest = { showCaptureToolPlaceholderStopConfirmation = false },
            title = { Text("Stop CAPTURE_TOOL_PLACEHOLDER capture?") },
            text = { Text("The active capture will be stopped and finalized on the Mac.") },
            confirmButton = {
                TextButton(onClick = {
                    showCaptureToolPlaceholderStopConfirmation = false
                    viewModel.confirmCaptureToolPlaceholderStep()
                }) {
                    Text("Stop and finalize", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCaptureToolPlaceholderStopConfirmation = false }) {
                    Text("Keep capturing")
                }
            }
        )
    }

    if (showActiveMacCaptureStopConfirmation) {
        AlertDialog(
            onDismissRequest = { showActiveMacCaptureStopConfirmation = false },
            title = { Text("Stop active Mac capture?") },
            text = { Text("TraceMate will find the active CAPTURE_TOOL_PLACEHOLDER capture on the Mac, stop it, and wait for finalization.") },
            confirmButton = {
                TextButton(onClick = {
                    showActiveMacCaptureStopConfirmation = false
                    viewModel.stopActiveMacAgentCapture()
                }) {
                    Text("Stop and finalize", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showActiveMacCaptureStopConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showNoCaptureToolPlaceholderActivityDialog) {
        AlertDialog(
            onDismissRequest = { showNoCaptureToolPlaceholderActivityDialog = false },
            title = { Text("No CAPTURE_TOOL_PLACEHOLDER activity") },
            text = { Text("Check if all CAPTURE_TOOL_PLACEHOLDER prerequisites are met. Not capturing any events.") },
            confirmButton = {
                TextButton(onClick = { showNoCaptureToolPlaceholderActivityDialog = false }) { Text("OK") }
            }
        )
    }

    Scaffold(
        topBar = {
            TraceMateTopBar(
                title = "TraceMate",
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = when {
                        status == ConnectionStatus.CONNECTED && currentSsid != null -> "Connected to: $currentSsid"
                        status == ConnectionStatus.CONNECTED -> "Connected (SSID-Berechtigung fehlt)"
                        status == ConnectionStatus.CONNECTING -> "Connecting..."
                        else -> "Disconnected"
                    },
                    style = MaterialTheme.typography.bodyLarge
                )

                // ── E-Release (directly below SSID) ──────────────────────────
                val eReleaseValue by viewModel.eReleaseValue.collectAsState()
                val eReleaseLoading by viewModel.eReleaseLoading.collectAsState()
                val eReleaseText = when {
                    eReleaseLoading -> "Loading..."
                    eReleaseValue != null -> eReleaseValue!!
                    else -> "Not available"
                }
                Text(
                    text = "E-Release: $eReleaseText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // ── SSH/ADB Status (always visible) ───────────────────────────
                ConnectionValidationStatusDisplay(
                    state = connectionValidationState,
                    isDebugMode = isDebugMode,
                    onRetry = viewModel::retryConnectionValidation
                )

                Spacer(modifier = Modifier.height(8.dp))

                MacAgentCaptureStateDisplay(
                    state = macAgentCaptureState
                )

                Spacer(modifier = Modifier.height(16.dp))

                // ── WiFi Netzwerk ändern ──────────────────────────────────────
                Button(
                    onClick = onNavigateToWifi,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("WiFi Netzwerk ändern")
                }

                // ── Debug-only diagnostic buttons ─────────────────────────────
                if (isDebugMode) {
                    Spacer(modifier = Modifier.height(16.dp))

                    val isSshTestRunning = sshTestState is SshTestState.Running
                    Button(
                        onClick = { viewModel.runSshTest() },
                        enabled = !isSshTestRunning && !isValidationRunning,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isSshTestRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(20.dp).padding(end = 8.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        Text("SSH Test")
                    }
                    SshTestStateDisplay(sshTestState)

                    Spacer(modifier = Modifier.height(16.dp))

                    val isFirewallRunning = prepareFirewallState is PrepareFirewallState.Running
                    Button(
                        onClick = { viewModel.prepareAdbFirewall() },
                        enabled = !isFirewallRunning && !isValidationRunning,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isFirewallRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(20.dp).padding(end = 8.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        Text("Prepare ADB Firewall")
                    }
                    PrepareFirewallStateDisplay(prepareFirewallState)

                    Spacer(modifier = Modifier.height(16.dp))

                    val isAdbTcpTestRunning = adbTcpTestState is AdbTcpTestState.Running
                    Button(
                        onClick = { viewModel.runAdbTcpTest() },
                        enabled = !isAdbTcpTestRunning && !isValidationRunning,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isAdbTcpTestRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(20.dp).padding(end = 8.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        Text("ADB TCP Test")
                    }
                    AdbTcpTestStateDisplay(adbTcpTestState)

                    Spacer(modifier = Modifier.height(16.dp))

                    val isAdbHandshakeRunning = adbHandshakeTestState is AdbHandshakeTestState.Running
                    Button(
                        onClick = { viewModel.runAdbHandshakeTest() },
                        enabled = !isAdbHandshakeRunning && !isValidationRunning,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isAdbHandshakeRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(20.dp).padding(end = 8.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        Text("ADB Handshake Test")
                    }
                    AdbHandshakeTestStateDisplay(adbHandshakeTestState)

                    Spacer(modifier = Modifier.height(16.dp))

                    val isAdbShellTestRunning = adbShellTestState is AdbShellTestState.Running
                    Button(
                        onClick = { viewModel.runAdbShellTest() },
                        enabled = !isAdbShellTestRunning && !isValidationRunning,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isAdbShellTestRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(20.dp).padding(end = 8.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        Text("ADB Shell Test")
                    }
                    AdbShellTestStateDisplay(adbShellTestState)

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            context.startActivity(Intent("android.settings.TETHER_SETTINGS"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Ethernet-Tethering öffnen")
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    val isEthernetTetheringCheckRunning =
                        ethernetTetheringTestState is EthernetTetheringTestState.Running
                    Button(
                        onClick = { viewModel.checkEthernetTethering() },
                        enabled = !isEthernetTetheringCheckRunning,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isEthernetTetheringCheckRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(20.dp).padding(end = 8.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        Text("Ethernet-Tethering prüfen")
                    }
                    EthernetTetheringTestStateDisplay(ethernetTetheringTestState)

                    Spacer(modifier = Modifier.height(16.dp))

                    val isMacSshTestRunning = macSshTestState is MacSshTestState.Running
                    Button(
                        onClick = { showMacEthernetConfirmation = true },
                        enabled = !isMacSshTestRunning,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isMacSshTestRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(20.dp).padding(end = 8.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        Text("Mac SSH Test")
                    }
                    MacSshTestStateDisplay(
                        state = macSshTestState,
                        onCopyDiagnostics = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(ClipData.newPlainText("TraceMate discovery diagnostics", viewModel.discoveryDiagnostics()))
                        },
                        onShareDiagnostics = {
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, viewModel.discoveryDiagnostics())
                                    },
                                    "Share discovery diagnostics"
                                )
                            )
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    val isMacAgentRunning = macAgentState is MacAgentState.Running
                    Text(
                        text = "Mac Agent",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = viewModel::checkMacAgent,
                            enabled = !isMacAgentRunning,
                            modifier = Modifier.weight(1f)
                        ) { Text("Check Agent") }
                        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                        Button(
                            onClick = viewModel::startMacAgent,
                            enabled = !isMacAgentRunning,
                            modifier = Modifier.weight(1f)
                        ) { Text("Start Agent") }
                    }
                    MacAgentStateDisplay(macAgentState, viewModel::dismissMacAgent)

                }

                Spacer(modifier = Modifier.height(16.dp))

                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "CAPTURE_TOOL_PLACEHOLDER Capture",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))

                val isCaptureToolPlaceholderRunning = captureToolPlaceholderCaptureState is CaptureToolPlaceholderCaptureState.Running
                val isCaptureToolPlaceholderWaiting = captureToolPlaceholderCaptureState is CaptureToolPlaceholderCaptureState.WaitingForUserConfirmation
                val isCaptureToolPlaceholderPreflightRunning = captureToolPlaceholderEthernetPreflightState is CaptureToolPlaceholderEthernetPreflightState.CheckingEthernet ||
                    captureToolPlaceholderEthernetPreflightState is CaptureToolPlaceholderEthernetPreflightState.AwaitingSettingsReturn ||
                    captureToolPlaceholderEthernetPreflightState is CaptureToolPlaceholderEthernetPreflightState.TestingMacSsh
                Button(
                    onClick = { viewModel.startCaptureToolPlaceholderCapture() },
                    enabled = !isCaptureToolPlaceholderRunning && !isCaptureToolPlaceholderWaiting && !isCaptureToolPlaceholderPreflightRunning,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isCaptureToolPlaceholderRunning || isCaptureToolPlaceholderPreflightRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(20.dp).padding(end = 8.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Text("Start CAPTURE_TOOL_PLACEHOLDER Capture")
                }
                CaptureToolPlaceholderCaptureStateDisplay(state = captureToolPlaceholderCaptureState)
                if (captureToolPlaceholderWaiting?.step == CaptureToolPlaceholderCaptureStep.CAPTURE_RUNNING) {
                    CaptureToolPlaceholderCaptureActiveCard(onStop = { showCaptureToolPlaceholderStopConfirmation = true })
                }
                val isMacAgentCaptureBusy = macAgentCaptureState is MacAgentCaptureState.Starting ||
                    macAgentCaptureState is MacAgentCaptureState.Stopping
                Button(
                    onClick = { showActiveMacCaptureStopConfirmation = true },
                    enabled = !isMacAgentCaptureBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isMacAgentCaptureBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(20.dp).padding(end = 8.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    Text("Stop Active Mac CAPTURE_TOOL_PLACEHOLDER Capture")
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ── Show USB Files ────────────────────────────────────────────
                Button(
                    onClick = onNavigateToUsbFiles,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Show USB Files")
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ── Export Traces to USB ──────────────────────────────────────
                Button(
                    onClick = onNavigateToUsbCopy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Export Traces to USB")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = onNavigateToCaptureToolPlaceholderCaptures,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Export CAPTURE_TOOL_PLACEHOLDER Captures")
                }

            }

            // ── ADB connected popup ───────────────────────────────────────────
            AnimatedVisibility(
                visible = adbConnectionState is AdbConnectionState.Connected,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                val connectedState = adbConnectionState as? AdbConnectionState.Connected
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.92f))
                        .clickable { viewModel.dismissAdbPopup() },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "ADB verbunden",
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = connectedState?.target ?: "",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFF81C784),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        HorizontalDivider(color = Color.White.copy(alpha = 0.3f))
                        Spacer(modifier = Modifier.height(16.dp))

                        viewModel.placeholderBuildInfo.forEach { (label, value) ->
                            BuildInfoRow(label = label, value = value)
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "Tippen zum Schließen",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}

// ── Status composables ────────────────────────────────────────────────────────

@Composable
private fun MacAgentCaptureStateDisplay(
    state: MacAgentCaptureState
) {
    val text = when (state) {
        is MacAgentCaptureState.Idle -> "Mac CAPTURE_TOOL_PLACEHOLDER capture: No running capture"
        is MacAgentCaptureState.Starting -> "Starting agent capture for ${state.deviceIdPlaceholder}..."
        is MacAgentCaptureState.Active -> buildString {
            append("Agent capture ${state.job.state}: ${state.job.activityState}")
            append("\nJob: ${state.job.jobId}")
            val captureSize = state.job.captureBytes.takeIf { it > 0 }?.let { "$it bytes" } ?: "pending"
            append("\nCapture file size: $captureSize; live export records: ${state.job.observedLiveEvents}")
            if (state.recovering) append("\nRecovering persisted job status...")
        }
        is MacAgentCaptureState.Stopping -> "Agent capture ${state.job.state}; waiting for validation..."
        is MacAgentCaptureState.Completed -> "Agent capture completed. Output: ${state.job.outputPath}"
        is MacAgentCaptureState.Failed -> state.message
    }
    val color = when (state) {
        is MacAgentCaptureState.Completed -> Green
        is MacAgentCaptureState.Failed -> WarningYellow
        is MacAgentCaptureState.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = color,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ConnectionValidationStatusDisplay(
    state: ConnectionValidationState,
    isDebugMode: Boolean,
    onRetry: () -> Unit
) {
    val stepLabel = { step: ConnectionValidationStep ->
        when (step) {
            ConnectionValidationStep.SSH -> "SSH"
            ConnectionValidationStep.ADB_FIREWALL -> "ADB Firewall"
            ConnectionValidationStep.ADB_TCP -> "ADB TCP"
            ConnectionValidationStep.ADB_HANDSHAKE -> "ADB Handshake"
            ConnectionValidationStep.ADB_SHELL -> "ADB Shell"
        }
    }

    when (state) {
        is ConnectionValidationState.Idle -> {
            Text(
                text = "SSH/ADB Status: Nicht geprüft",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
        }
        is ConnectionValidationState.Running -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.height(16.dp).padding(end = 8.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "SSH/ADB Status: Prüfe ${stepLabel(state.step)} …",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        is ConnectionValidationState.Success -> {
            Text(
                text = "SSH/ADB Status: OK",
                style = MaterialTheme.typography.bodyMedium,
                color = Green,
                modifier = Modifier.fillMaxWidth()
            )
        }
        is ConnectionValidationState.Error -> {
            Text(
                text = validationErrorMessage(state.error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth()
            )
            if (isDebugMode && state.technicalMessage != null) {
                Text(
                    text = state.technicalMessage,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp)
                )
            }
            if (state.error != ConnectionValidationError.NETWORK_DISCONNECTED) {
                TextButton(onClick = onRetry) {
                    Text("Verbindung erneut prüfen")
                }
            }
        }
    }
}

private fun validationErrorMessage(error: ConnectionValidationError): String = when (error) {
    ConnectionValidationError.NETWORK_DISCONNECTED ->
        "WLAN-Verbindung getrennt. Bitte mit dem Headunit-WLAN verbinden."
    ConnectionValidationError.SSH_FAILED ->
        "Headunit über dieses WLAN nicht erreichbar. Bitte Headunit-WLAN prüfen."
    ConnectionValidationError.ADB_FIREWALL_FAILED ->
        "Headunit erreicht, aber Diagnosezugang konnte nicht vorbereitet werden."
    ConnectionValidationError.ADB_TCP_FAILED ->
        "Headunit erreicht, aber der Diagnosezugang ist nicht verfügbar."
    ConnectionValidationError.ADB_AUTH_REQUIRED ->
        "Headunit erfordert eine ADB-Autorisierung."
    ConnectionValidationError.ADB_HANDSHAKE_FAILED,
    ConnectionValidationError.ADB_SHELL_FAILED ->
        "Headunit erreicht, aber die Diagnoseverbindung konnte nicht hergestellt werden."
    ConnectionValidationError.UNKNOWN_ERROR ->
        "Headunit-Verbindung konnte nicht geprüft werden."
}

@Composable
private fun BuildInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun PrepareFirewallStateDisplay(state: PrepareFirewallState) {
    when (state) {
        is PrepareFirewallState.Idle -> Unit
        is PrepareFirewallState.Running -> {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Prüfe Firewall-Regeln...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth()
            )
        }
        is PrepareFirewallState.Success -> {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = Green,
                modifier = Modifier.fillMaxWidth()
            )
        }
        is PrepareFirewallState.Error -> {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SshTestStateDisplay(state: SshTestState) {
    when (state) {
        is SshTestState.Idle -> Unit
        is SshTestState.Running -> {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Verbinde per SSH...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth()
            )
        }
        is SshTestState.Error -> {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth()
            )
        }
        is SshTestState.Success -> {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "SSH test successful",
                style = MaterialTheme.typography.bodySmall,
                color = Green,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun AdbTcpTestStateDisplay(state: AdbTcpTestState) {
    when (state) {
        is AdbTcpTestState.Idle -> Unit
        is AdbTcpTestState.Running -> {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Prüfe TCP-Verbindung...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth()
            )
        }
        is AdbTcpTestState.Success -> {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodySmall,
                color = Green,
                modifier = Modifier.fillMaxWidth()
            )
        }
        is AdbTcpTestState.Error -> {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun AdbHandshakeTestStateDisplay(state: AdbHandshakeTestState) {
    when (state) {
        is AdbHandshakeTestState.Idle -> Unit
        is AdbHandshakeTestState.Running -> {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "ADB Handshake läuft...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth()
            )
        }
        is AdbHandshakeTestState.Success -> {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodySmall,
                color = Green,
                modifier = Modifier.fillMaxWidth()
            )
            if (state.detail != null) {
                Text(
                    text = state.detail,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = Green,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        is AdbHandshakeTestState.AuthRequired -> {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFFF8F00),
                modifier = Modifier.fillMaxWidth()
            )
        }
        is AdbHandshakeTestState.Error -> {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun AdbShellTestStateDisplay(state: AdbShellTestState) {
    when (state) {
        is AdbShellTestState.Idle -> Unit
        is AdbShellTestState.Running -> {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Führe ADB shell:id aus...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth()
            )
        }
        is AdbShellTestState.Success -> {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "ADB shell:${state.command} successful",
                style = MaterialTheme.typography.bodySmall,
                color = Green,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = state.output,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, top = 4.dp)
            )
        }
        is AdbShellTestState.Error -> {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun MacSshTestStateDisplay(
    state: MacSshTestState,
    onCopyDiagnostics: () -> Unit,
    onShareDiagnostics: () -> Unit
) {
    val logs: List<String> = when (state) {
        is MacSshTestState.Idle -> return
        is MacSshTestState.Running -> state.logs
        is MacSshTestState.Success -> state.logs
        is MacSshTestState.Error -> state.logs
    }
    if (logs.isEmpty() && state is MacSshTestState.Running) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Connecting to Mac via SSH...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth()
        )
        return
    }
    if (logs.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        val headerColor = when (state) {
            is MacSshTestState.Success -> Green
            is MacSshTestState.Error -> WarningYellow
            else -> MaterialTheme.colorScheme.primary
        }
        val headerText = when (state) {
            is MacSshTestState.Success -> "Mac SSH Status: OK"
            is MacSshTestState.Error -> state.message
            else -> "Connecting to Mac via SSH..."
        }
        Text(
            text = headerText,
            style = MaterialTheme.typography.bodySmall,
            color = headerColor,
            modifier = Modifier.fillMaxWidth()
        )
        LogBlock(logs = logs)
        if (state is MacSshTestState.Error || state is MacSshTestState.Success) {
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onCopyDiagnostics) { Text("Copy discovery diagnostics") }
                TextButton(onClick = onShareDiagnostics) { Text("Share discovery diagnostics") }
            }
        }
    }
}

@Composable
private fun MacAgentStateDisplay(state: MacAgentState, onDismiss: () -> Unit) {
    when (state) {
        MacAgentState.Idle -> return
        MacAgentState.Running -> {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Contacting Mac agent...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        is MacAgentState.Success -> {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Mac agent: available", style = MaterialTheme.typography.bodySmall, color = Green)
            Text(state.status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
        is MacAgentState.Error -> {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Mac agent: ${state.message}", style = MaterialTheme.typography.bodySmall, color = WarningYellow)
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun EthernetTetheringTestStateDisplay(state: EthernetTetheringTestState) {
    when (state) {
        EthernetTetheringTestState.Idle -> return
        EthernetTetheringTestState.Running -> {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Checking Ethernet tethering...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth()
            )
        }
        is EthernetTetheringTestState.Success -> {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodySmall,
                color = Green,
                modifier = Modifier.fillMaxWidth()
            )
        }
        is EthernetTetheringTestState.Error -> {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodySmall,
                color = WarningYellow,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun CaptureToolPlaceholderCaptureStateDisplay(state: CaptureToolPlaceholderCaptureState) {
    val logs: List<String> = when (state) {
        is CaptureToolPlaceholderCaptureState.Idle -> return
        is CaptureToolPlaceholderCaptureState.WaitingForUserConfirmation -> state.logs
        is CaptureToolPlaceholderCaptureState.Running -> state.logs
        is CaptureToolPlaceholderCaptureState.Information -> state.logs
    }

    Spacer(modifier = Modifier.height(8.dp))

    val stepLabel = when (state) {
        is CaptureToolPlaceholderCaptureState.Running -> when (state.step) {
            CaptureToolPlaceholderCaptureStep.LIST_DEVICES -> "Listing CAPTURE_TOOL_PLACEHOLDER devices..."
            CaptureToolPlaceholderCaptureStep.STARTING_CAPTURE -> "Starting capture..."
            CaptureToolPlaceholderCaptureStep.CAPTURE_RUNNING -> state.message.ifBlank { "Capture running - waiting for CaptureSessionPlaceholder confirmation" }
            CaptureToolPlaceholderCaptureStep.STOPPING_CAPTURE -> "Stopping capture..."
            else -> "CAPTURE_TOOL_PLACEHOLDER running..."
        }
        is CaptureToolPlaceholderCaptureState.WaitingForUserConfirmation -> when (state.step) {
            CaptureToolPlaceholderCaptureStep.CAPTURE_RUNNING -> state.message
            else -> "Waiting for confirmation..."
        }
        is CaptureToolPlaceholderCaptureState.Information -> state.message
        else -> ""
    }
    Text(
        text = stepLabel,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth()
    )
    if (logs.isNotEmpty()) {
        LogBlock(logs = logs)
    }
}

@Composable
private fun CaptureToolPlaceholderCaptureActiveCard(onStop: () -> Unit) {
    Spacer(modifier = Modifier.height(8.dp))
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "CAPTURE_TOOL_PLACEHOLDER capture active",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "You can continue using the app. Stop the capture when the drive is complete.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
                Text("Stop capture")
            }
        }
    }
}

@Composable
private fun CaptureToolPlaceholderCopyStateDisplay(
    state: CaptureToolPlaceholderCopyState,
    onToggle: (String) -> Unit,
    onDownloadToAndroid: () -> Unit,
    onBridgeToUsb: () -> Unit,
    onDismiss: () -> Unit
) {
    when (state) {
        is CaptureToolPlaceholderCopyState.Idle -> return
        is CaptureToolPlaceholderCopyState.LoadingFiles -> {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Loading files...", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth())
            if (state.logs.isNotEmpty()) LogBlock(state.logs)
        }
        is CaptureToolPlaceholderCopyState.SelectingFiles -> {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Select captures to copy (${state.selected.size} selected):",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(4.dp))
            state.files.forEach { filePath ->
                val fileName = filePath.substringAfterLast('/')
                val isSelected = filePath in state.selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(filePath) }
                        .background(
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${if (isSelected) "☑" else "☐"} $fileName",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
            Button(
                onClick = onDownloadToAndroid,
                enabled = state.selected.size == 1,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Download selected capture to Android")
            }
            Button(
                onClick = onBridgeToUsb,
                enabled = state.selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Bridge selected capture(s) to USB")
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
        is CaptureToolPlaceholderCopyState.Error -> {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Error: ${state.message}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth())
            if (state.logs.isNotEmpty()) LogBlock(state.logs)
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Dismiss") }
        }
    }
}

@Composable
private fun CaptureToolPlaceholderUsbBridgeStateDisplay(
    state: CaptureToolPlaceholderUsbBridgeState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    when (state) {
        CaptureToolPlaceholderUsbBridgeState.Idle -> Unit
        is CaptureToolPlaceholderUsbBridgeState.Running -> {
            Spacer(modifier = Modifier.height(8.dp))
            val progress = state.state.progress
            val bytes = progress?.let { if (it.totalBytes > 0) " ${it.transferredBytes}/${it.totalBytes} bytes" else " ${it.transferredBytes} bytes" } ?: ""
            Text(
                text = "CAPTURE_TOOL_PLACEHOLDER USB bridge: ${state.state.phase}$bytes",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth()
            )
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel transfer") }
        }
        is CaptureToolPlaceholderUsbBridgeState.Success -> {
            Spacer(modifier = Modifier.height(8.dp))
            Text("CAPTURE_TOOL_PLACEHOLDER USB bridge complete", style = MaterialTheme.typography.bodySmall, color = Green)
            state.files.forEach { Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)) }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Dismiss") }
        }
        is CaptureToolPlaceholderUsbBridgeState.Error -> {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "CAPTURE_TOOL_PLACEHOLDER USB bridge failed during ${state.phase}: ${state.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth()
            )
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Dismiss") }
        }
        CaptureToolPlaceholderUsbBridgeState.Cancelled -> {
            Spacer(modifier = Modifier.height(8.dp))
            Text("CAPTURE_TOOL_PLACEHOLDER USB bridge cancelled", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Dismiss") }
        }
    }
}

@Composable
private fun CaptureToolPlaceholderDownloadStateDisplay(state: CaptureToolPlaceholderDownloadState, onDismiss: () -> Unit) {
    when (state) {
        CaptureToolPlaceholderDownloadState.Idle -> Unit
        is CaptureToolPlaceholderDownloadState.Downloading -> {
            Spacer(modifier = Modifier.height(8.dp))
            val total = state.progress.totalBytes
            val transferred = state.progress.transferredBytes
            val detail = if (total > 0) "$transferred / $total bytes" else "$transferred bytes"
            Text(
                text = "Downloading ${state.fileName} to Android: $detail",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth()
            )
        }
        is CaptureToolPlaceholderDownloadState.Success -> {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Verified CAPTURE_TOOL_PLACEHOLDER download ready: ${state.file}",
                style = MaterialTheme.typography.bodySmall,
                color = Green,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "${state.bytes} bytes, SHA-256 ${state.sha256}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth()
            )
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Dismiss") }
        }
        is CaptureToolPlaceholderDownloadState.Error -> {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "CAPTURE_TOOL_PLACEHOLDER download failed: ${state.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth()
            )
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Dismiss") }
        }
    }
}

@Composable
private fun LogBlock(logs: List<String>) {
    androidx.compose.foundation.layout.Column(
        modifier = androidx.compose.ui.Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small
            )
            .padding(8.dp)
    ) {
        logs.takeLast(100).forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
