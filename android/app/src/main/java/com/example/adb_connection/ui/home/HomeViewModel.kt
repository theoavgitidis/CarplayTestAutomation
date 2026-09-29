package com.example.adb_connection.ui.home

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.adb_connection.ServiceLocator
import com.example.adb_connection.data.settings.SettingsRepository
import com.example.adb_connection.data.ssh.AndroidScpRepository
import com.example.adb_connection.data.ssh.AndroidSshRepository
import com.example.adb_connection.data.ssh.CaptureToolPlaceholderTransferCoordinator
import com.example.adb_connection.data.ssh.CaptureToolPlaceholderTransferJobStatus
import com.example.adb_connection.data.ssh.CaptureToolPlaceholderTransferStartResult
import com.example.adb_connection.data.ssh.CaptureToolPlaceholderUsbTransferPhase
import com.example.adb_connection.data.ssh.CaptureToolPlaceholderUsbTransferState
import com.example.adb_connection.data.ssh.MacScpDownloadRepository
import com.example.adb_connection.data.ssh.MacAgentRepository
import com.example.adb_connection.data.ssh.MacDiscoveryRepository
import com.example.adb_connection.data.ssh.DiscoveryDiagnostics
import com.example.adb_connection.data.ssh.MacAgentCaptureSnapshotStore
import com.example.adb_connection.data.ssh.MacAgentJob
import com.example.adb_connection.data.ssh.ScpConnectionConfig
import com.example.adb_connection.data.ssh.ScpConnectionConfigProvider
import com.example.adb_connection.data.ssh.ScpTransferProgress
import com.example.adb_connection.data.ssh.SshRepository
import com.example.adb_connection.data.ssh.SshTarget
import com.example.adb_connection.service.CaptureToolPlaceholderTransferService
import com.example.adb_connection.data.wifi.ConnectionStatus
import com.example.adb_connection.data.wifi.WifiConnectionRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

private const val TAG = "HomeViewModel"
private const val SSH_PORT = 22
private const val ADB_PORT = 5555
private const val MAC_AGENT_POLL_INTERVAL_MS = 3_000L
// The agent allows CAPTURE_TOOL_PLACEHOLDER 15 seconds to stop, then allows its final CAPTURE_TOOL_PLACEHOLDER export/validation 60 seconds.
private const val MAC_AGENT_STOP_TIMEOUT_MS = 90_000L
private const val FIREWALL_INPUT_CHAIN = "HEAD_UNIT_FIREWALL_INPUT_CHAIN_PLACEHOLDER"
private const val FIREWALL_OUTPUT_CHAIN = "HEAD_UNIT_FIREWALL_OUTPUT_CHAIN_PLACEHOLDER"
private const val FIREWALL_RULE_COMMENT = "ADB_FIREWALL_RULE_COMMENT_PLACEHOLDER"
private const val HEAD_UNIT_FIREWALL_IP = "HEAD_UNIT_FIREWALL_IP_PLACEHOLDER"
private const val HEAD_UNIT_CLIENT_RANGE = "HEAD_UNIT_CLIENT_RANGE_PLACEHOLDER"
private const val CAPTURE_TOOL_PLACEHOLDER_CLI_PATH = "CAPTURE_TOOL_PLACEHOLDER_CLI_PATH_PLACEHOLDER"
private const val CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY = "\$HOME/CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER"

private val IPV4_REGEX = Regex(
    """^((25[0-5]|2[0-4]\d|[01]?\d\d?)\.){3}(25[0-5]|2[0-4]\d|[01]?\d\d?)$"""
)

internal fun isValidIpv4(ip: String): Boolean = IPV4_REGEX.matches(ip)

internal fun parseMacSshOutput(exitCode: Int, stdout: String): MacSshTestState = when {
    exitCode != 0 -> MacSshTestState.Error("Mac SSH Status: sw_vers failed")
    stdout.isBlank() -> MacSshTestState.Error("Mac SSH Status: Empty response")
    !stdout.contains("ProductName") || !stdout.contains("ProductVersion") ->
        MacSshTestState.Error("Mac SSH Status: sw_vers failed")
    else -> MacSshTestState.Success(stdout.trim())
}

internal fun mapSshExceptionToMacStatus(message: String): String = when {
    message.contains("timeout", ignoreCase = true) ||
    message.contains("timed out", ignoreCase = true) ->
        "Mac SSH Status: Connection timeout"
    message.contains("Auth", ignoreCase = true) ||
    message.contains("password", ignoreCase = true) ->
        "Mac SSH Status: Authentication failed"
    message.contains("refused", ignoreCase = true) ->
        "Mac SSH Status: Connection refused"
    message.contains("Unresolved", ignoreCase = true) ||
    message.contains("UnknownHost", ignoreCase = true) ->
        "Mac SSH Status: Unknown host"
    else -> "Mac SSH Status: Connection failed"
}

internal fun parseCaptureToolPlaceholderCaptureFiles(output: String): List<String> =
    output.lineSequence().filter { it.endsWith(".capture_tool_placeholder") }.toList()


sealed interface AdbConnectionState {
    data object Idle : AdbConnectionState
    data object Connecting : AdbConnectionState
    data class Connected(val target: String) : AdbConnectionState
    data class Failed(val message: String) : AdbConnectionState
}

sealed interface SshTestState {
    data object Idle : SshTestState
    data object Running : SshTestState
    data class Success(val results: List<SshTestCommandResult>) : SshTestState
    data class Error(val message: String) : SshTestState
}

data class SshTestCommandResult(
    val command: String,
    val output: String,
    val isSuccess: Boolean
)

sealed interface AdbTcpTestState {
    data object Idle : AdbTcpTestState
    data object Running : AdbTcpTestState
    data class Success(val message: String) : AdbTcpTestState
    data class Error(val message: String) : AdbTcpTestState
}

sealed interface AdbHandshakeTestState {
    data object Idle : AdbHandshakeTestState
    data object Running : AdbHandshakeTestState
    data class Success(val message: String, val detail: String?) : AdbHandshakeTestState
    data class AuthRequired(val message: String) : AdbHandshakeTestState
    data class Error(val message: String) : AdbHandshakeTestState
}

sealed interface AdbShellTestState {
    data object Idle : AdbShellTestState
    data object Running : AdbShellTestState
    data class Success(val command: String, val output: String) : AdbShellTestState
    data class Error(val message: String) : AdbShellTestState
}

sealed interface PrepareFirewallState {
    data object Idle : PrepareFirewallState
    data object Running : PrepareFirewallState
    data class Success(val message: String) : PrepareFirewallState
    data class Error(val message: String) : PrepareFirewallState
}

sealed interface MacSshTestState {
    data object Idle : MacSshTestState
    data class Running(val logs: List<String> = emptyList()) : MacSshTestState
    data class Success(val output: String, val logs: List<String> = emptyList()) : MacSshTestState
    data class Error(val message: String, val logs: List<String> = emptyList()) : MacSshTestState
}

sealed interface MacAgentState {
    data object Idle : MacAgentState
    data object Running : MacAgentState
    data class Success(val status: String) : MacAgentState
    data class Error(val message: String) : MacAgentState
}

sealed interface MacAgentCaptureState {
    data object Idle : MacAgentCaptureState
    data class Starting(val deviceIdPlaceholder: String) : MacAgentCaptureState
    data class Active(val job: MacAgentJob, val recovering: Boolean = false) : MacAgentCaptureState
    data class Stopping(val job: MacAgentJob) : MacAgentCaptureState
    data class Completed(val job: MacAgentJob) : MacAgentCaptureState
    data class Failed(val message: String, val job: MacAgentJob? = null) : MacAgentCaptureState
}

sealed interface EthernetTetheringTestState {
    data object Idle : EthernetTetheringTestState
    data object Running : EthernetTetheringTestState
    data class Success(val message: String) : EthernetTetheringTestState
    data class Error(val message: String) : EthernetTetheringTestState
}

sealed interface CaptureToolPlaceholderEthernetPreflightState {
    data object Idle : CaptureToolPlaceholderEthernetPreflightState
    data object CheckingEthernet : CaptureToolPlaceholderEthernetPreflightState
    data object EthernetUnavailable : CaptureToolPlaceholderEthernetPreflightState
    data object AwaitingSettingsReturn : CaptureToolPlaceholderEthernetPreflightState
    data object ReadyToContinue : CaptureToolPlaceholderEthernetPreflightState
    data object TestingMacSsh : CaptureToolPlaceholderEthernetPreflightState
    data class MacSshFailed(val message: String) : CaptureToolPlaceholderEthernetPreflightState
}

// ── CAPTURE_TOOL_PLACEHOLDER Capture workflow state ────────────────────────────────────────────────

enum class CaptureToolPlaceholderCaptureStep {
    IDLE,
    CONFIRM_TRUST,
    LIST_DEVICES,
    CONFIRM_BT_OFF,
    STARTING_CAPTURE,
    CAPTURE_RUNNING,
    CONFIRM_BT_ON,
    CONFIRM_CAPTURE_SESSION_PLACEHOLDER_ACTIVE,
    STOPPING_CAPTURE,
    DONE
}

sealed interface CaptureToolPlaceholderCaptureState {
    data object Idle : CaptureToolPlaceholderCaptureState
    data class WaitingForUserConfirmation(
        val step: CaptureToolPlaceholderCaptureStep,
        val message: String,
        val logs: List<String> = emptyList()
    ) : CaptureToolPlaceholderCaptureState
    data class Running(
        val step: CaptureToolPlaceholderCaptureStep,
        val message: String = "",
        val logs: List<String> = emptyList()
    ) : CaptureToolPlaceholderCaptureState
    data class Information(val message: String, val logs: List<String> = emptyList()) : CaptureToolPlaceholderCaptureState
}


// ── CAPTURE_TOOL_PLACEHOLDER → USB copy state ──────────────────────────────────────────────────────

sealed interface CaptureToolPlaceholderCopyState {
    data object Idle : CaptureToolPlaceholderCopyState
    data class LoadingFiles(val logs: List<String> = emptyList()) : CaptureToolPlaceholderCopyState
    data class SelectingFiles(
        val files: List<String>,
        val selected: Set<String> = emptySet()
    ) : CaptureToolPlaceholderCopyState
    data class Error(val message: String, val logs: List<String> = emptyList()) : CaptureToolPlaceholderCopyState
}

sealed interface CaptureToolPlaceholderDownloadState {
    data object Idle : CaptureToolPlaceholderDownloadState
    data class Downloading(val fileName: String, val progress: ScpTransferProgress) : CaptureToolPlaceholderDownloadState
    data class Success(val file: String, val bytes: Long, val sha256: String) : CaptureToolPlaceholderDownloadState
    data class Error(val message: String) : CaptureToolPlaceholderDownloadState
}

sealed interface CaptureToolPlaceholderUsbBridgeState {
    data object Idle : CaptureToolPlaceholderUsbBridgeState
    data class Running(val state: CaptureToolPlaceholderUsbTransferState) : CaptureToolPlaceholderUsbBridgeState
    data class Success(val files: List<String>) : CaptureToolPlaceholderUsbBridgeState
    data class Error(val phase: CaptureToolPlaceholderUsbTransferPhase, val message: String) : CaptureToolPlaceholderUsbBridgeState
    data object Cancelled : CaptureToolPlaceholderUsbBridgeState
}

// ── Automatic validation workflow state ──────────────────────────────────────

enum class ConnectionValidationStep { SSH, ADB_FIREWALL, ADB_TCP, ADB_HANDSHAKE, ADB_SHELL }

enum class ConnectionValidationError {
    SSH_FAILED, ADB_FIREWALL_FAILED, ADB_TCP_FAILED,
    ADB_HANDSHAKE_FAILED, ADB_AUTH_REQUIRED, ADB_SHELL_FAILED,
    NETWORK_DISCONNECTED, UNKNOWN_ERROR
}

sealed interface ConnectionValidationState {
    data object Idle : ConnectionValidationState
    data class Running(val step: ConnectionValidationStep) : ConnectionValidationState
    data class Success(val ssid: String?) : ConnectionValidationState
    data class Error(
        val error: ConnectionValidationError,
        val technicalMessage: String? = null
    ) : ConnectionValidationState
}

private sealed interface StepResult {
    data object Ok : StepResult
    data class Fail(
        val error: ConnectionValidationError,
        val technicalMessage: String?
    ) : StepResult
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

class HomeViewModel(
    private val appContext: Context,
    private val wifiRepository: WifiConnectionRepository,
    private val settingsRepository: SettingsRepository,
    private val sshRepository: SshRepository,
    private val macDiscoveryRepository: MacDiscoveryRepository,
    private val macAgentRepository: MacAgentRepository,
    private val macAgentCaptureSnapshotStore: MacAgentCaptureSnapshotStore,
    private val macScpDownloadRepository: MacScpDownloadRepository,
    private val captureToolPlaceholderTransferCoordinator: CaptureToolPlaceholderTransferCoordinator
) : ViewModel() {

    init {
        viewModelScope.launch { captureToolPlaceholderTransferCoordinator.recoverLastSnapshot() }
        viewModelScope.launch { recoverOrDiscoverMacAgentCapture() }
    }

    val currentSsid: StateFlow<String?> = wifiRepository.currentSsid
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val connectionStatus: StateFlow<ConnectionStatus> = wifiRepository.connectionStatus
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConnectionStatus.IDLE)

    val isAutoNavigationEnabled: StateFlow<Boolean> = settingsRepository.isAutoNavigationEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isDebugModeEnabled: StateFlow<Boolean> = settingsRepository.isDebugModeEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _connectionValidationState =
        MutableStateFlow<ConnectionValidationState>(ConnectionValidationState.Idle)
    val connectionValidationState: StateFlow<ConnectionValidationState> =
        _connectionValidationState.asStateFlow()

    private val _adbConnectionState = MutableStateFlow<AdbConnectionState>(AdbConnectionState.Idle)
    val adbConnectionState: StateFlow<AdbConnectionState> = _adbConnectionState.asStateFlow()

    private val _sshTestState = MutableStateFlow<SshTestState>(SshTestState.Idle)
    val sshTestState: StateFlow<SshTestState> = _sshTestState.asStateFlow()

    private val _prepareFirewallState = MutableStateFlow<PrepareFirewallState>(PrepareFirewallState.Idle)
    val prepareFirewallState: StateFlow<PrepareFirewallState> = _prepareFirewallState.asStateFlow()

    private val _macSshTestState = MutableStateFlow<MacSshTestState>(MacSshTestState.Idle)
    val macSshTestState: StateFlow<MacSshTestState> = _macSshTestState.asStateFlow()

    private val _macAgentState = MutableStateFlow<MacAgentState>(MacAgentState.Idle)
    val macAgentState: StateFlow<MacAgentState> = _macAgentState.asStateFlow()

    private val _macAgentCaptureState = MutableStateFlow<MacAgentCaptureState>(MacAgentCaptureState.Idle)
    val macAgentCaptureState: StateFlow<MacAgentCaptureState> = _macAgentCaptureState.asStateFlow()
    private var macAgentCapturePollingJob: Job? = null

    private val _ethernetTetheringTestState =
        MutableStateFlow<EthernetTetheringTestState>(EthernetTetheringTestState.Idle)
    val ethernetTetheringTestState: StateFlow<EthernetTetheringTestState> =
        _ethernetTetheringTestState.asStateFlow()

    private val _captureToolPlaceholderEthernetPreflightState = MutableStateFlow<CaptureToolPlaceholderEthernetPreflightState>(CaptureToolPlaceholderEthernetPreflightState.Idle)
    val captureToolPlaceholderEthernetPreflightState: StateFlow<CaptureToolPlaceholderEthernetPreflightState> =
        _captureToolPlaceholderEthernetPreflightState.asStateFlow()

    private val _captureToolPlaceholderCaptureState = MutableStateFlow<CaptureToolPlaceholderCaptureState>(CaptureToolPlaceholderCaptureState.Idle)
    val captureToolPlaceholderCaptureState: StateFlow<CaptureToolPlaceholderCaptureState> = _captureToolPlaceholderCaptureState.asStateFlow()
    private var captureToolPlaceholderAgentPollingJob: Job? = null

    // Signalled by the UI when the user confirms a manual step
    private var _captureToolPlaceholderConfirmation: CompletableDeferred<Boolean>? = null

    private val _captureToolPlaceholderCopyState = MutableStateFlow<CaptureToolPlaceholderCopyState>(CaptureToolPlaceholderCopyState.Idle)
    val captureToolPlaceholderCopyState: StateFlow<CaptureToolPlaceholderCopyState> = _captureToolPlaceholderCopyState.asStateFlow()

    private val _captureToolPlaceholderDownloadState = MutableStateFlow<CaptureToolPlaceholderDownloadState>(CaptureToolPlaceholderDownloadState.Idle)
    val captureToolPlaceholderDownloadState: StateFlow<CaptureToolPlaceholderDownloadState> = _captureToolPlaceholderDownloadState.asStateFlow()

    val captureToolPlaceholderUsbBridgeState: StateFlow<CaptureToolPlaceholderUsbBridgeState> = captureToolPlaceholderTransferCoordinator.state
        .map { snapshot ->
            when (snapshot?.status) {
                null -> CaptureToolPlaceholderUsbBridgeState.Idle
                CaptureToolPlaceholderTransferJobStatus.RUNNING -> CaptureToolPlaceholderUsbBridgeState.Running(
                    CaptureToolPlaceholderUsbTransferState(
                        snapshot.phase, snapshot.fileName,
                        ScpTransferProgress(snapshot.transferredBytes, snapshot.totalBytes), snapshot.error
                    )
                )
                CaptureToolPlaceholderTransferJobStatus.COMPLETED -> CaptureToolPlaceholderUsbBridgeState.Success(emptyList())
                CaptureToolPlaceholderTransferJobStatus.CANCELLED -> CaptureToolPlaceholderUsbBridgeState.Cancelled
                CaptureToolPlaceholderTransferJobStatus.FAILED, CaptureToolPlaceholderTransferJobStatus.INTERRUPTED -> CaptureToolPlaceholderUsbBridgeState.Error(
                    snapshot.phase, snapshot.error ?: "CAPTURE_TOOL_PLACEHOLDER USB bridge transfer failed"
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CaptureToolPlaceholderUsbBridgeState.Idle)

    private val _adbTcpTestState = MutableStateFlow<AdbTcpTestState>(AdbTcpTestState.Idle)
    val adbTcpTestState: StateFlow<AdbTcpTestState> = _adbTcpTestState.asStateFlow()

    private val _adbHandshakeTestState = MutableStateFlow<AdbHandshakeTestState>(AdbHandshakeTestState.Idle)
    val adbHandshakeTestState: StateFlow<AdbHandshakeTestState> = _adbHandshakeTestState.asStateFlow()

    private val _adbShellTestState = MutableStateFlow<AdbShellTestState>(AdbShellTestState.Idle)
    val adbShellTestState: StateFlow<AdbShellTestState> = _adbShellTestState.asStateFlow()

    // ── E-Release ─────────────────────────────────────────────────────────────

    private val _eReleaseValue = MutableStateFlow<String?>(null)
    val eReleaseValue: StateFlow<String?> = _eReleaseValue.asStateFlow()

    private val _eReleaseLoading = MutableStateFlow(false)
    val eReleaseLoading: StateFlow<Boolean> = _eReleaseLoading.asStateFlow()

    private var eReleaseJob: Job? = null

    // ── Auto-validation trigger ───────────────────────────────────────────────

    private var lastValidatedSsid: String? = null
    private var validationJob: Job? = null

    init {
        observeNetworkForAutoValidation()
    }

    private fun observeNetworkForAutoValidation() {
        viewModelScope.launch {
            combine(connectionStatus, currentSsid) { status, ssid -> status to ssid }
                .collect { (status, ssid) ->
                    when {
                        status == ConnectionStatus.CONNECTED && ssid != null && ssid != lastValidatedSsid -> {
                            validationJob?.cancel()
                            lastValidatedSsid = ssid
                            validationJob = viewModelScope.launch {
                                runAutomaticConnectionValidation(ssid)
                            }
                        }
                        status == ConnectionStatus.CONNECTED && ssid == null && lastValidatedSsid == null -> {
                            // Connected but SSID not yet readable — use empty string as sentinel
                            validationJob?.cancel()
                            lastValidatedSsid = ""
                            validationJob = viewModelScope.launch {
                                runAutomaticConnectionValidation(null)
                            }
                        }
                        status == ConnectionStatus.DISCONNECTED -> {
                            validationJob?.cancel()
                            eReleaseJob?.cancel()
                            lastValidatedSsid = null
                            _eReleaseValue.value = null
                            _eReleaseLoading.value = false
                            _connectionValidationState.value = ConnectionValidationState.Error(
                                ConnectionValidationError.NETWORK_DISCONNECTED
                            )
                        }
                    }
                }
        }
    }

    private suspend fun runAutomaticConnectionValidation(ssid: String?) {
        if (connectionStatus.value != ConnectionStatus.CONNECTED) {
            _connectionValidationState.value = ConnectionValidationState.Error(
                ConnectionValidationError.NETWORK_DISCONNECTED
            )
            return
        }

        val steps = listOf(
            ConnectionValidationStep.SSH to ::executeSshTest,
            ConnectionValidationStep.ADB_FIREWALL to ::executeFirewallPreparation,
            ConnectionValidationStep.ADB_TCP to ::executeAdbTcpTest,
            ConnectionValidationStep.ADB_HANDSHAKE to ::executeAdbHandshakeTest,
            ConnectionValidationStep.ADB_SHELL to ::executeAdbShellTest,
        )

        for ((step, fn) in steps) {
            _connectionValidationState.value = ConnectionValidationState.Running(step)
            val result = fn()
            if (result is StepResult.Fail) {
                Log.e(TAG, "Validation failed at $step: ${result.technicalMessage}")
                _connectionValidationState.value = ConnectionValidationState.Error(
                    result.error, result.technicalMessage
                )
                return
            }
        }

        Log.d(TAG, "Automatic connection validation succeeded for SSID: $ssid")
        _connectionValidationState.value = ConnectionValidationState.Success(ssid)
        loadERelease()
    }

    fun retryConnectionValidation() {
        if (_connectionValidationState.value is ConnectionValidationState.Running) return
        if (connectionStatus.value != ConnectionStatus.CONNECTED) return

        validationJob?.cancel()
        validationJob = viewModelScope.launch {
            runAutomaticConnectionValidation(currentSsid.value)
        }
    }

    // ── Internal suspend helpers ──────────────────────────────────────────────

    private val sshTestCommands = listOf("id", "hostname", "uname -a", "ip addr", "whoami")

    private suspend fun executeSshTest(): StepResult {
        _sshTestState.value = SshTestState.Running

        val host = settingsRepository.sshHost.first()
        val user = settingsRepository.sshUser.first()
        val password = settingsRepository.sshPassword.first()

        val results = mutableListOf<SshTestCommandResult>()

        for (command in sshTestCommands) {
            val result = sshRepository.executeCommand(
                target = SshTarget.HEADUNIT,
                host = host, port = SSH_PORT, user = user, password = password, command = command
            )
            result.fold(
                onSuccess = { cmdResult ->
                    results.add(
                        SshTestCommandResult(
                            command = command,
                            output = cmdResult.allOutput.trim(),
                            isSuccess = cmdResult.isSuccess
                        )
                    )
                },
                onFailure = { e ->
                    Log.e(TAG, "SSH command '$command' failed: ${e.message}")
                    val msg = "SSH connection failed: ${e.message ?: "Unknown error"}"
                    _sshTestState.value = SshTestState.Error(msg)
                    return StepResult.Fail(ConnectionValidationError.SSH_FAILED, msg)
                }
            )
        }

        val idResult = results.firstOrNull { it.command == "id" }
        if (idResult == null || !idResult.isSuccess || idResult.output.isBlank() || !idResult.output.contains("uid=")) {
            val msg = "SSH 'id' output invalid: '${idResult?.output}'"
            Log.e(TAG, msg)
            _sshTestState.value = SshTestState.Error(msg)
            return StepResult.Fail(ConnectionValidationError.SSH_FAILED, msg)
        }

        Log.d(TAG, "SSH test completed with ${results.size} commands")
        _sshTestState.value = SshTestState.Success(results)
        return StepResult.Ok
    }

    // Opens ADB port via nftables for the target WLAN subnet. Placeholder constants
    // must be replaced by deployment configuration before this workflow is usable.
    private suspend fun executeFirewallPreparation(): StepResult {
        _prepareFirewallState.value = PrepareFirewallState.Running

        val host = settingsRepository.sshHost.first()
        val user = settingsRepository.sshUser.first()
        val password = settingsRepository.sshPassword.first()

        suspend fun ssh(command: String): Result<com.example.adb_connection.data.ssh.SshCommandResult> =
            sshRepository.executeCommand(
                target = SshTarget.HEADUNIT,
                host = host, port = SSH_PORT, user = user, password = password, command = command
            )

        return try {
            val checkInput = ssh("nft list chain inet filter $FIREWALL_INPUT_CHAIN | grep -q '$FIREWALL_RULE_COMMENT'")
            val inputExists = checkInput.isSuccess && checkInput.getOrNull()?.exitCode == 0

            if (!inputExists) {
                Log.d(TAG, "Firewall: INPUT rule missing, inserting...")
                val setInput = ssh(
                    "nft insert rule inet filter $FIREWALL_INPUT_CHAIN " +
                    "ip daddr $HEAD_UNIT_FIREWALL_IP " +
                    "ip saddr $HEAD_UNIT_CLIENT_RANGE " +
                    "tcp dport 5555 " +
                    "ct state { established, new } " +
                    "accept comment \\\"$FIREWALL_RULE_COMMENT\\\""
                )
                if (setInput.isFailure) {
                    val msg = "Failed to set INPUT rule: ${setInput.exceptionOrNull()?.message}"
                    _prepareFirewallState.value = PrepareFirewallState.Error(msg)
                    return StepResult.Fail(ConnectionValidationError.ADB_FIREWALL_FAILED, msg)
                }
                val setResult = setInput.getOrNull()!!
                if (!setResult.isSuccess) {
                    val msg = "INPUT rule failed (exit ${setResult.exitCode}): ${setResult.allOutput}"
                    _prepareFirewallState.value = PrepareFirewallState.Error(msg)
                    return StepResult.Fail(ConnectionValidationError.ADB_FIREWALL_FAILED, msg)
                }
            }

            val checkOutput = ssh("nft list chain inet filter $FIREWALL_OUTPUT_CHAIN | grep -q '$FIREWALL_RULE_COMMENT'")
            val outputExists = checkOutput.isSuccess && checkOutput.getOrNull()?.exitCode == 0

            if (!outputExists) {
                Log.d(TAG, "Firewall: OUTPUT rule missing, inserting...")
                val setOutput = ssh(
                    "nft insert rule inet filter $FIREWALL_OUTPUT_CHAIN " +
                    "ip saddr $HEAD_UNIT_FIREWALL_IP " +
                    "ip daddr $HEAD_UNIT_CLIENT_RANGE " +
                    "tcp sport 5555 " +
                    "ct state established " +
                    "accept comment \\\"$FIREWALL_RULE_COMMENT\\\""
                )
                if (setOutput.isFailure) {
                    val msg = "Failed to set OUTPUT rule: ${setOutput.exceptionOrNull()?.message}"
                    _prepareFirewallState.value = PrepareFirewallState.Error(msg)
                    return StepResult.Fail(ConnectionValidationError.ADB_FIREWALL_FAILED, msg)
                }
                val setResult = setOutput.getOrNull()!!
                if (!setResult.isSuccess) {
                    val msg = "OUTPUT rule failed (exit ${setResult.exitCode}): ${setResult.allOutput}"
                    _prepareFirewallState.value = PrepareFirewallState.Error(msg)
                    return StepResult.Fail(ConnectionValidationError.ADB_FIREWALL_FAILED, msg)
                }
            }

            // Verify both rules are actually present
            val verifyInput = ssh("nft list chain inet filter $FIREWALL_INPUT_CHAIN | grep '$FIREWALL_RULE_COMMENT'")
            val verifyOutput = ssh("nft list chain inet filter $FIREWALL_OUTPUT_CHAIN | grep '$FIREWALL_RULE_COMMENT'")

            val inputRule = verifyInput.getOrNull()?.stdout?.trim() ?: ""
            val outputRule = verifyOutput.getOrNull()?.stdout?.trim() ?: ""

            if (inputRule.isBlank()) {
                val msg = "INPUT firewall rule not found after insertion"
                _prepareFirewallState.value = PrepareFirewallState.Error(msg)
                return StepResult.Fail(ConnectionValidationError.ADB_FIREWALL_FAILED, msg)
            }
            if (outputRule.isBlank()) {
                val msg = "OUTPUT firewall rule not found after insertion"
                _prepareFirewallState.value = PrepareFirewallState.Error(msg)
                return StepResult.Fail(ConnectionValidationError.ADB_FIREWALL_FAILED, msg)
            }

            val summary = buildString {
                append("INPUT: ").append(if (inputExists) "already present" else "set ✓").append("\n")
                append("OUTPUT: ").append(if (outputExists) "already present" else "set ✓")
                append("\n\n").append(inputRule).append("\n").append(outputRule)
            }
            Log.d(TAG, "Firewall preparation complete")
            _prepareFirewallState.value = PrepareFirewallState.Success(summary)
            StepResult.Ok
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = "SSH connection failed: ${e.message ?: "Unknown error"}"
            Log.e(TAG, "Firewall preparation failed: ${e.message}")
            _prepareFirewallState.value = PrepareFirewallState.Error(msg)
            StepResult.Fail(ConnectionValidationError.ADB_FIREWALL_FAILED, msg)
        }
    }

    private suspend fun executeAdbTcpTest(): StepResult {
        _adbTcpTestState.value = AdbTcpTestState.Running
        val host = settingsRepository.sshHost.first()
        return checkAdbTcpReachable(host, ADB_PORT).fold(
            onSuccess = {
                Log.d(TAG, "ADB TCP reachable: $host:$ADB_PORT")
                _adbTcpTestState.value = AdbTcpTestState.Success("ADB TCP reachable: $host:$ADB_PORT")
                StepResult.Ok
            },
            onFailure = { e ->
                val msg = "ADB TCP failed: ${e.message ?: "Unknown error"}"
                Log.e(TAG, msg)
                _adbTcpTestState.value = AdbTcpTestState.Error(msg)
                StepResult.Fail(ConnectionValidationError.ADB_TCP_FAILED, msg)
            }
        )
    }

    private suspend fun executeAdbHandshakeTest(): StepResult {
        _adbHandshakeTestState.value = AdbHandshakeTestState.Running
        val host = settingsRepository.sshHost.first()
        val result = com.example.adb_connection.data.adb.AdbProtocol.performHandshake(host, ADB_PORT)
        return when {
            result.success -> {
                Log.d(TAG, "ADB Handshake success: ${result.payload}")
                _adbHandshakeTestState.value = AdbHandshakeTestState.Success(
                    message = result.message,
                    detail = result.payload?.let { "Device: $it" }
                )
                StepResult.Ok
            }
            result.receivedCommand == "AUTH" -> {
                Log.d(TAG, "ADB Handshake requires auth")
                _adbHandshakeTestState.value = AdbHandshakeTestState.AuthRequired(result.message)
                StepResult.Fail(ConnectionValidationError.ADB_AUTH_REQUIRED, result.message)
            }
            else -> {
                Log.e(TAG, "ADB Handshake failed: ${result.message}")
                _adbHandshakeTestState.value = AdbHandshakeTestState.Error(result.message)
                StepResult.Fail(ConnectionValidationError.ADB_HANDSHAKE_FAILED, result.message)
            }
        }
    }

    private suspend fun executeAdbShellTest(): StepResult {
        _adbShellTestState.value = AdbShellTestState.Running
        val host = settingsRepository.sshHost.first()
        val result = com.example.adb_connection.data.adb.AdbProtocol.executeShellCommand(host, ADB_PORT, "id")
        return if (result.success && result.output.isNotBlank() && result.output.contains("uid=")) {
            Log.d(TAG, "ADB Shell test success: ${result.output}")
            _adbShellTestState.value = AdbShellTestState.Success(command = "id", output = result.output)
            StepResult.Ok
        } else {
            val msg = result.errorMessage ?: "Output invalid: '${result.output}'"
            Log.e(TAG, "ADB Shell test failed: $msg")
            _adbShellTestState.value = AdbShellTestState.Error(msg)
            StepResult.Fail(ConnectionValidationError.ADB_SHELL_FAILED, msg)
        }
    }

    // ── Public manual functions (delegate to internal helpers) ────────────────

    fun runSshTest() {
        if (_connectionValidationState.value is ConnectionValidationState.Running) return
        if (_sshTestState.value is SshTestState.Running) return
        viewModelScope.launch { executeSshTest() }
    }

    fun dismissSshTest() {
        _sshTestState.value = SshTestState.Idle
    }

    fun prepareAdbFirewall() {
        if (_connectionValidationState.value is ConnectionValidationState.Running) return
        if (_prepareFirewallState.value is PrepareFirewallState.Running) return
        viewModelScope.launch { executeFirewallPreparation() }
    }

    fun dismissPrepareFirewall() {
        _prepareFirewallState.value = PrepareFirewallState.Idle
    }

    fun runAdbTcpTest() {
        if (_connectionValidationState.value is ConnectionValidationState.Running) return
        if (_adbTcpTestState.value is AdbTcpTestState.Running) return
        viewModelScope.launch { executeAdbTcpTest() }
    }

    fun dismissAdbTcpTest() {
        _adbTcpTestState.value = AdbTcpTestState.Idle
    }

    fun runAdbHandshakeTest() {
        if (_connectionValidationState.value is ConnectionValidationState.Running) return
        if (_adbHandshakeTestState.value is AdbHandshakeTestState.Running) return
        viewModelScope.launch { executeAdbHandshakeTest() }
    }

    fun dismissAdbHandshakeTest() {
        _adbHandshakeTestState.value = AdbHandshakeTestState.Idle
    }

    fun runAdbShellTest() {
        if (_connectionValidationState.value is ConnectionValidationState.Running) return
        if (_adbShellTestState.value is AdbShellTestState.Running) return
        viewModelScope.launch { executeAdbShellTest() }
    }

    fun dismissAdbShellTest() {
        _adbShellTestState.value = AdbShellTestState.Idle
    }

    fun runMacSshTest() {
        if (_macSshTestState.value is MacSshTestState.Running) return
        viewModelScope.launch { executeMacSshTest() }
    }

    fun dismissMacSshTest() {
        _macSshTestState.value = MacSshTestState.Idle
    }

    fun discoveryDiagnostics(): String = DiscoveryDiagnostics.export()

    fun checkMacAgent() {
        if (_macAgentState.value is MacAgentState.Running) return
        viewModelScope.launch { executeMacAgent(start = false) }
    }

    fun startMacAgent() {
        if (_macAgentState.value is MacAgentState.Running) return
        viewModelScope.launch { executeMacAgent(start = true) }
    }

    fun dismissMacAgent() {
        _macAgentState.value = MacAgentState.Idle
    }

    fun startMacAgentCapture(deviceIdPlaceholder: String) {
        if (_macAgentCaptureState.value !is MacAgentCaptureState.Idle &&
            _macAgentCaptureState.value !is MacAgentCaptureState.Completed &&
            _macAgentCaptureState.value !is MacAgentCaptureState.Failed
        ) return
        viewModelScope.launch {
            try {
                _macAgentCaptureState.value = MacAgentCaptureState.Starting(deviceIdPlaceholder)
                val start = macAgentRepository.startCaptureEnsuringAgent(deviceIdPlaceholder).getOrThrow()
                val job = start.job
                macAgentCaptureSnapshotStore.save(job.jobId)
                publishMacAgentJob(job)
                pollMacAgentCapture(job.jobId)
            } catch (e: CancellationException) {
                _macAgentCaptureState.value = MacAgentCaptureState.Idle
                throw e
            } catch (e: Exception) {
                _macAgentCaptureState.value = MacAgentCaptureState.Failed(
                    e.message ?: "Could not start Mac agent capture"
                )
            }
        }
    }

    fun stopMacAgentCapture() {
        val job = (_macAgentCaptureState.value as? MacAgentCaptureState.Active)?.job ?: return
        viewModelScope.launch {
            try {
                stopMacAgentCapture(job)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _macAgentCaptureState.value = MacAgentCaptureState.Failed(
                    e.message ?: "Could not stop Mac agent capture", job
                )
            }
        }
    }

    fun stopActiveMacAgentCapture() {
        if (_macAgentCaptureState.value is MacAgentCaptureState.Starting ||
            _macAgentCaptureState.value is MacAgentCaptureState.Stopping
        ) return
        viewModelScope.launch {
            try {
                val job = macAgentRepository.captureJobs().getOrThrow().firstOrNull { !it.isTerminal }
                    ?: throw IllegalStateException("No active Mac CAPTURE_TOOL_PLACEHOLDER capture was found")
                stopMacAgentCapture(job)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _macAgentCaptureState.value = MacAgentCaptureState.Failed(
                    e.message ?: "Could not find an active Mac CAPTURE_TOOL_PLACEHOLDER capture"
                )
            }
        }
    }

    fun checkEthernetTethering() {
        if (_ethernetTetheringTestState.value is EthernetTetheringTestState.Running) return
        viewModelScope.launch {
            try {
                _ethernetTetheringTestState.value = EthernetTetheringTestState.Running
                val status = wifiRepository.refreshMacEthernetStatus()
                _ethernetTetheringTestState.value = when (status) {
                    ConnectionStatus.CONNECTED -> EthernetTetheringTestState.Success(
                        "Ethernet tethering is active and an Ethernet device is connected."
                    )
                    else -> EthernetTetheringTestState.Error(
                        "No active Ethernet connection detected. Enable Ethernet tethering and connect the Mac."
                    )
                }
            } catch (e: CancellationException) {
                _ethernetTetheringTestState.value = EthernetTetheringTestState.Idle
                throw e
            }
        }
    }

    fun dismissEthernetTetheringTest() {
        _ethernetTetheringTestState.value = EthernetTetheringTestState.Idle
    }

    fun startCaptureToolPlaceholderCapture() {
        if (_captureToolPlaceholderCaptureState.value is CaptureToolPlaceholderCaptureState.Running ||
            _captureToolPlaceholderCaptureState.value is CaptureToolPlaceholderCaptureState.WaitingForUserConfirmation ||
            _captureToolPlaceholderEthernetPreflightState.value !is CaptureToolPlaceholderEthernetPreflightState.Idle
        ) return
        viewModelScope.launch { checkCaptureToolPlaceholderEthernetPreflight() }
    }

    fun openCaptureToolPlaceholderEthernetTetheringSettings() {
        if (_captureToolPlaceholderEthernetPreflightState.value is CaptureToolPlaceholderEthernetPreflightState.EthernetUnavailable) {
            _captureToolPlaceholderEthernetPreflightState.value = CaptureToolPlaceholderEthernetPreflightState.AwaitingSettingsReturn
        }
    }

    fun onCaptureToolPlaceholderEthernetTetheringSettingsReturned() {
        if (_captureToolPlaceholderEthernetPreflightState.value !is CaptureToolPlaceholderEthernetPreflightState.AwaitingSettingsReturn) return
        viewModelScope.launch { checkCaptureToolPlaceholderEthernetPreflight() }
    }

    fun continueCaptureToolPlaceholderAfterEthernetPreflight() {
        if (_captureToolPlaceholderEthernetPreflightState.value !is CaptureToolPlaceholderEthernetPreflightState.ReadyToContinue) return
        viewModelScope.launch {
            _captureToolPlaceholderEthernetPreflightState.value = CaptureToolPlaceholderEthernetPreflightState.TestingMacSsh
            if (executeMacSshTest()) {
                _captureToolPlaceholderEthernetPreflightState.value = CaptureToolPlaceholderEthernetPreflightState.Idle
                executeCaptureToolPlaceholderCapture()
            } else {
                val message = (_macSshTestState.value as? MacSshTestState.Error)?.message
                    ?: "Could not connect to the Mac over Ethernet."
                _captureToolPlaceholderEthernetPreflightState.value = CaptureToolPlaceholderEthernetPreflightState.MacSshFailed(message)
            }
        }
    }

    fun dismissCaptureToolPlaceholderEthernetPreflight() {
        _captureToolPlaceholderEthernetPreflightState.value = CaptureToolPlaceholderEthernetPreflightState.Idle
    }

    fun confirmCaptureToolPlaceholderStep() {
        _captureToolPlaceholderConfirmation?.complete(true)
    }

    fun cancelCaptureToolPlaceholderCapture() {
        _captureToolPlaceholderConfirmation?.complete(false)
        captureToolPlaceholderAgentPollingJob?.cancel()
        _captureToolPlaceholderCaptureState.value = CaptureToolPlaceholderCaptureState.Idle
    }

    fun dismissCaptureToolPlaceholderCapture() {
        captureToolPlaceholderAgentPollingJob?.cancel()
        _captureToolPlaceholderCaptureState.value = CaptureToolPlaceholderCaptureState.Idle
    }

    // ── CAPTURE_TOOL_PLACEHOLDER captures → USB copy ───────────────────────────────────────────────

    fun loadCaptureToolPlaceholderCaptureFiles() {
        if (_captureToolPlaceholderCopyState.value is CaptureToolPlaceholderCopyState.LoadingFiles) return
        viewModelScope.launch { executeLoadCaptureToolPlaceholderCaptureFiles() }
    }

    fun toggleCaptureToolPlaceholderCopySelection(file: String) {
        val current = _captureToolPlaceholderCopyState.value as? CaptureToolPlaceholderCopyState.SelectingFiles ?: return
        val newSelected = if (file in current.selected) current.selected - file
                          else current.selected + file
        _captureToolPlaceholderCopyState.value = current.copy(selected = newSelected)
    }

    fun downloadSelectedCaptureToolPlaceholderCapture() {
        val selected = (_captureToolPlaceholderCopyState.value as? CaptureToolPlaceholderCopyState.SelectingFiles)?.selected ?: return
        if (selected.size != 1 || _captureToolPlaceholderDownloadState.value is CaptureToolPlaceholderDownloadState.Downloading) return
        val remotePath = selected.single()
        viewModelScope.launch {
            val fileName = remotePath.substringAfterLast('/')
            _captureToolPlaceholderDownloadState.value = CaptureToolPlaceholderDownloadState.Downloading(
                fileName,
                ScpTransferProgress(0, 0)
            )
            macScpDownloadRepository.download(remotePath) { progress ->
                _captureToolPlaceholderDownloadState.value = CaptureToolPlaceholderDownloadState.Downloading(fileName, progress)
            }.fold(
                onSuccess = { result ->
                    _captureToolPlaceholderDownloadState.value = CaptureToolPlaceholderDownloadState.Success(
                        result.file.path,
                        result.bytes,
                        result.sha256
                    )
                },
                onFailure = { error ->
                    _captureToolPlaceholderDownloadState.value = CaptureToolPlaceholderDownloadState.Error(
                        error.message ?: "Mac SCP download failed"
                    )
                }
            )
        }
    }

    fun bridgeSelectedCaptureToolPlaceholderCapturesToUsb() {
        val selected = (_captureToolPlaceholderCopyState.value as? CaptureToolPlaceholderCopyState.SelectingFiles)?.selected ?: return
        if (selected.isEmpty()) return
        viewModelScope.launch {
            if (captureToolPlaceholderTransferCoordinator.start(selected.toList()) == CaptureToolPlaceholderTransferStartResult.Started) {
                ContextCompat.startForegroundService(appContext, CaptureToolPlaceholderTransferService.startIntent(appContext))
            }
        }
    }

    fun dismissCaptureToolPlaceholderUsbBridge() {
        // The durable last state deliberately remains observable after UI recreation.
    }

    fun cancelCaptureToolPlaceholderUsbBridge() {
        captureToolPlaceholderTransferCoordinator.requestCancel()
    }

    fun dismissCaptureToolPlaceholderDownload() {
        _captureToolPlaceholderDownloadState.value = CaptureToolPlaceholderDownloadState.Idle
    }

    fun dismissCaptureToolPlaceholderCopy() {
        _captureToolPlaceholderCopyState.value = CaptureToolPlaceholderCopyState.Idle
    }

    private suspend fun executeLoadCaptureToolPlaceholderCaptureFiles() {
        val logs = mutableListOf<String>()
        fun log(msg: String) {
            logs.add(msg)
            _captureToolPlaceholderCopyState.value = CaptureToolPlaceholderCopyState.LoadingFiles(logs.toList())
        }
        try {
            val macLanIp = settingsRepository.macLanIp.first()
            val macSshPort = settingsRepository.macSshPort.first()
            val macSshUser = settingsRepository.macSshUser.first()
            val macSshPassword = settingsRepository.macSshPassword.first()

            log("Listing CAPTURE_TOOL_PLACEHOLDER captures on Mac LAN $macLanIp:$macSshPort via Ethernet...")
            val listResult = sshRepository.executeCommand(
                target = SshTarget.MAC,
                host = macLanIp, port = macSshPort,
                user = macSshUser, password = macSshPassword,
                command = "ls -1 $CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY/*.capture_tool_placeholder 2>/dev/null || echo NO_FILES"
            )
            val listOut = listResult.getOrNull()?.allOutput?.trim() ?: ""
            if (listResult.isFailure || listOut == "NO_FILES" || listOut.isBlank()) {
                _captureToolPlaceholderCopyState.value = CaptureToolPlaceholderCopyState.Error("No .capture_tool_placeholder files found in $CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY/", logs.toList())
                return
            }
            val files = parseCaptureToolPlaceholderCaptureFiles(listOut)
            if (files.isEmpty()) {
                _captureToolPlaceholderCopyState.value = CaptureToolPlaceholderCopyState.Error("No .capture_tool_placeholder files found in $CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY/", logs.toList())
                return
            }
            log("Found ${files.size} capture(s).")
            _captureToolPlaceholderCopyState.value = CaptureToolPlaceholderCopyState.SelectingFiles(files)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logs.add("Exception: ${e::class.simpleName}: ${e.message}")
            _captureToolPlaceholderCopyState.value = CaptureToolPlaceholderCopyState.Error(e.message ?: "Unknown error", logs.toList())
        }
    }

    private suspend fun waitForUserConfirmation(
        step: CaptureToolPlaceholderCaptureStep,
        message: String,
        logs: List<String>
    ): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        _captureToolPlaceholderConfirmation = deferred
        _captureToolPlaceholderCaptureState.value = CaptureToolPlaceholderCaptureState.WaitingForUserConfirmation(step, message, logs)
        val confirmed = deferred.await()
        _captureToolPlaceholderConfirmation = null
        return confirmed
        }

    private suspend fun executeCaptureToolPlaceholderCapture() {
        val logs = mutableListOf<String>()
        fun log(msg: String) {
            logs.add(msg)
            val current = _captureToolPlaceholderCaptureState.value
            val currentLogs = logs.toList()
            _captureToolPlaceholderCaptureState.value = when (current) {
                is CaptureToolPlaceholderCaptureState.Running -> current.copy(logs = currentLogs)
                is CaptureToolPlaceholderCaptureState.WaitingForUserConfirmation -> current.copy(logs = currentLogs)
                else -> CaptureToolPlaceholderCaptureState.Running(CaptureToolPlaceholderCaptureStep.IDLE, logs = currentLogs)
            }
            Log.d(TAG, "[CAPTURE_TOOL_PLACEHOLDER] $msg")
        }

        try {
            val macLanIp = settingsRepository.macLanIp.first()
            val macSshPort = settingsRepository.macSshPort.first()
            val macSshUser = settingsRepository.macSshUser.first()
            val macSshPassword = settingsRepository.macSshPassword.first()

            suspend fun macSsh(command: String) = sshRepository.executeCommand(
                target = SshTarget.MAC,
                host = macLanIp,
                port = macSshPort,
                user = macSshUser,
                password = macSshPassword,
                command = command
            )

            // Step 1: Confirm prerequisites (trust, profiles, tools)
            val trustOk = waitForUserConfirmation(
                CaptureToolPlaceholderCaptureStep.CONFIRM_TRUST,
                "Before starting:\n\n" +
                "• MobileDevicePlaceholder is connected to the Mac via USB/Lightning\n" +
                "• MobileDevicePlaceholder is unlocked and trusted (\"Trust This Computer\" confirmed)\n" +
                "• MobileDevicePlaceholder Wi-Fi is ON and connected to the target vehicle hotspot\n" +
                "• MOBILE_DIAGNOSTIC_PROFILE_PLACEHOLDER Mode and Bluetooth Logging profiles are installed on the MobileDevicePlaceholder\n" +
                "• CAPTURE_TOOL_SUPPORT_PACKAGE_PLACEHOLDER are installed on the Mac (CAPTURE_TOOL_PLACEHOLDER → Utilities → Install CAPTURE_TOOL_SUPPORT_PACKAGE_PLACEHOLDER)\n\n" +
                "Confirm all prerequisites are met.",
                logs
            )
            if (!trustOk) { _captureToolPlaceholderCaptureState.value = CaptureToolPlaceholderCaptureState.Idle; return }

            // Step 2: List connected devices via CAPTURE_TOOL_PLACEHOLDER CLI
            log("Listing CAPTURE_TOOL_PLACEHOLDER devices on Mac LAN $macLanIp:$macSshPort via Ethernet...")
            _captureToolPlaceholderCaptureState.value = CaptureToolPlaceholderCaptureState.Running(CaptureToolPlaceholderCaptureStep.LIST_DEVICES, logs = logs.toList())

            val wifiListResult = macSsh("$CAPTURE_TOOL_PLACEHOLDER_CLI_PATH list --transport=wifi")
            val btListResult = macSsh("$CAPTURE_TOOL_PLACEHOLDER_CLI_PATH list --transport=bluetooth")

            val wifiOut = wifiListResult.getOrNull()?.allOutput?.trim() ?: ""
            val btOut = btListResult.getOrNull()?.allOutput?.trim() ?: ""

            val deviceIdPlaceholderRegex = Regex("""[0-9A-Fa-f]{8}-?[0-9A-Fa-f]{16}""")
            val deviceIdPlaceholder = (wifiOut.lines() + btOut.lines())
                .mapNotNull { deviceIdPlaceholderRegex.find(it.trim())?.value }
                .firstOrNull()

            if (deviceIdPlaceholder == null) {
                log("Wi-Fi devices: ${wifiOut.ifBlank { "(none)" }}")
                log("Bluetooth devices: ${btOut.ifBlank { "(none)" }}")
                log("No MobileDevicePlaceholder DEVICE_ID_PLACEHOLDER found. Check cable/trust/unlock.")
                _captureToolPlaceholderCaptureState.value = CaptureToolPlaceholderCaptureState.Information(
                    "No MobileDevicePlaceholder found. Check cable, unlock, and trust.", logs.toList()
                )
                return
            }
            log("Found device DEVICE_ID_PLACEHOLDER: $deviceIdPlaceholder")

            // Step 3: Confirm Bluetooth off
            val btOffOk = waitForUserConfirmation(
                CaptureToolPlaceholderCaptureStep.CONFIRM_BT_OFF,
                "Turn Bluetooth OFF on the MobileDevicePlaceholder now:\n\nSettings → Bluetooth → Off\n\nConfirm when done.",
                logs
            )
            if (!btOffOk) { _captureToolPlaceholderCaptureState.value = CaptureToolPlaceholderCaptureState.Idle; return }

            // Step 4: Start the managed capture so activity is observable through the agent.
            log("Starting CAPTURE_TOOL_PLACEHOLDER capture through the Mac agent (DEVICE_ID_PLACEHOLDER: $deviceIdPlaceholder)...")
            _captureToolPlaceholderCaptureState.value = CaptureToolPlaceholderCaptureState.Running(CaptureToolPlaceholderCaptureStep.STARTING_CAPTURE, logs = logs.toList())
            val start = macAgentRepository.startCaptureEnsuringAgent(deviceIdPlaceholder).getOrElse { error ->
                val message = "CAPTURE_TOOL_PLACEHOLDER capture could not start: ${error.message ?: "Unknown reason"}"
                log(message)
                _captureToolPlaceholderCaptureState.value = CaptureToolPlaceholderCaptureState.Information(message, logs.toList())
                return
            }
            if (start.agentRestarted) log("Mac agent was unavailable and was restarted before capture.")
            val job = start.job
            log("CAPTURE_TOOL_PLACEHOLDER capture started. Job: ${job.jobId}")
            macAgentCaptureSnapshotStore.save(job.jobId)
            publishMacAgentJob(job)
            _captureToolPlaceholderCaptureState.value = CaptureToolPlaceholderCaptureState.Running(CaptureToolPlaceholderCaptureStep.CAPTURE_RUNNING, logs = logs.toList())

            // Step 5: Confirm Bluetooth back on
            val btOnOk = waitForUserConfirmation(
                CaptureToolPlaceholderCaptureStep.CONFIRM_BT_ON,
                "CAPTURE_TOOL_PLACEHOLDER capture is running.\n\nNow turn Bluetooth ON on the MobileDevicePlaceholder:\n\nSettings → Bluetooth → On\n\nThen connect to the vehicle and start CaptureSessionPlaceholder.\n\nConfirm when Bluetooth is on.",
                logs
            )
            if (!btOnOk) {
                stopCaptureToolPlaceholderCaptureWithAgent(job.jobId, logs)
                _captureToolPlaceholderCaptureState.value = CaptureToolPlaceholderCaptureState.Idle; return
            }

            // Step 6: Confirm CaptureSessionPlaceholder is active
            val capture_session_placeholderOk = waitForUserConfirmation(
                CaptureToolPlaceholderCaptureStep.CONFIRM_CAPTURE_SESSION_PLACEHOLDER_ACTIVE,
                "Confirm that CAPTURE_SESSION_PLACEHOLDER is now active and connected to the vehicle.",
                logs
            )
            if (!capture_session_placeholderOk) {
                stopCaptureToolPlaceholderCaptureWithAgent(job.jobId, logs)
                _captureToolPlaceholderCaptureState.value = CaptureToolPlaceholderCaptureState.Idle; return
            }
            log("CaptureSessionPlaceholder connection confirmed by user")
            observeCaptureToolPlaceholderAgentCapture(job.jobId, logs)

            // Step 7: Wait for user to decide when to stop
            val stopRequested = waitForUserConfirmation(
                CaptureToolPlaceholderCaptureStep.CAPTURE_RUNNING,
                "CAPTURE_TOOL_PLACEHOLDER capture is running and CaptureSessionPlaceholder is active.\n\nTap \"Stop Capture\" when you are done.",
                logs
            )
            if (!stopRequested) return

            // Step 8: Stop capture
            log("Stopping CAPTURE_TOOL_PLACEHOLDER capture...")
            _captureToolPlaceholderCaptureState.value = CaptureToolPlaceholderCaptureState.Running(CaptureToolPlaceholderCaptureStep.STOPPING_CAPTURE, logs = logs.toList())
            val completedJob = stopCaptureToolPlaceholderCaptureWithAgent(job.jobId, logs)
            val message = completedJob?.let(::captureToolPlaceholderAgentInformation) ?: "CAPTURE_TOOL_PLACEHOLDER capture stop status is unavailable."
            _captureToolPlaceholderCaptureState.value = CaptureToolPlaceholderCaptureState.Information(message, logs.toList())

        } catch (e: kotlinx.coroutines.CancellationException) {
            logs.add("[CAPTURE_TOOL_PLACEHOLDER] Cancelled")
            _captureToolPlaceholderCaptureState.value = CaptureToolPlaceholderCaptureState.Idle
            throw e
        } catch (e: Exception) {
            val msg = "CAPTURE_TOOL_PLACEHOLDER capture update: ${e.message ?: "Unknown reason"}"
            Log.e(TAG, "[CAPTURE_TOOL_PLACEHOLDER] $msg")
            logs.add(msg)
            _captureToolPlaceholderCaptureState.value = CaptureToolPlaceholderCaptureState.Information(msg, logs.toList())
        }
    }

    private suspend fun checkCaptureToolPlaceholderEthernetPreflight() {
        try {
            _captureToolPlaceholderEthernetPreflightState.value = CaptureToolPlaceholderEthernetPreflightState.CheckingEthernet
            _captureToolPlaceholderEthernetPreflightState.value = if (
                wifiRepository.refreshMacEthernetStatus() == ConnectionStatus.CONNECTED
            ) {
                CaptureToolPlaceholderEthernetPreflightState.ReadyToContinue
            } else {
                CaptureToolPlaceholderEthernetPreflightState.EthernetUnavailable
            }
        } catch (e: CancellationException) {
            _captureToolPlaceholderEthernetPreflightState.value = CaptureToolPlaceholderEthernetPreflightState.Idle
            throw e
        }
    }

    private fun observeCaptureToolPlaceholderAgentCapture(jobId: String, logs: MutableList<String>) {
        captureToolPlaceholderAgentPollingJob?.cancel()
        captureToolPlaceholderAgentPollingJob = viewModelScope.launch {
            while (true) {
                try {
                    val job = macAgentRepository.captureStatus(jobId).getOrThrow()
                    publishMacAgentJob(job)
                    if (job.isTerminal) {
                        _captureToolPlaceholderCaptureState.value = CaptureToolPlaceholderCaptureState.Information(captureToolPlaceholderAgentInformation(job), logs.toList())
                        _captureToolPlaceholderConfirmation?.complete(false)
                        return@launch
                    }
                    val current = _captureToolPlaceholderCaptureState.value as? CaptureToolPlaceholderCaptureState.WaitingForUserConfirmation
                    if (current?.step == CaptureToolPlaceholderCaptureStep.CAPTURE_RUNNING) {
                        _captureToolPlaceholderCaptureState.value = current.copy(message = captureToolPlaceholderAgentInformation(job))
                    }
                    delay(MAC_AGENT_POLL_INTERVAL_MS)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _captureToolPlaceholderCaptureState.value = CaptureToolPlaceholderCaptureState.Information(
                        "CAPTURE_TOOL_PLACEHOLDER capture activity is unavailable: ${e.message ?: "Unknown reason"}", logs.toList()
                    )
                    _captureToolPlaceholderConfirmation?.complete(false)
                    return@launch
                }
            }
        }
    }

    private suspend fun stopCaptureToolPlaceholderCaptureWithAgent(jobId: String, logs: MutableList<String>): MacAgentJob? {
        captureToolPlaceholderAgentPollingJob?.cancel()
        return try {
            var job = macAgentRepository.stopCapture(jobId).getOrThrow()
            publishMacAgentJob(job)
            var waitedMs = 0L
            while (!job.isTerminal && waitedMs < MAC_AGENT_STOP_TIMEOUT_MS) {
                delay(MAC_AGENT_POLL_INTERVAL_MS)
                waitedMs += MAC_AGENT_POLL_INTERVAL_MS
                job = macAgentRepository.captureStatus(jobId).getOrThrow()
                publishMacAgentJob(job)
            }
            if (!job.isTerminal) {
                throw IllegalStateException("Timed out waiting for the Mac agent to finalize the capture")
            }
            logs.add(captureToolPlaceholderAgentInformation(job))
            job
        } catch (e: Exception) {
            logs.add("CAPTURE_TOOL_PLACEHOLDER capture stop status is unavailable: ${e.message ?: "Unknown reason"}")
            null
        }
    }

    private fun captureToolPlaceholderAgentInformation(job: MacAgentJob): String = when (job.state) {
        "COMPLETED", "STOPPED" -> "CAPTURE_TOOL_PLACEHOLDER capture stopped. Output: ${job.outputPath}"
        "FAILED" -> "CAPTURE_TOOL_PLACEHOLDER capture stopped: ${job.lastError ?: "The Mac agent reported no detail."}"
        else -> when {
            job.observedLiveEvents > 0 ->
                "CaptureSessionPlaceholder activity observed: ${job.captureBytes} bytes, ${job.observedLiveEvents} live events"
            else -> "No CaptureSessionPlaceholder activity observed yet: ${job.captureBytes} bytes captured"
        }
    }

    private suspend fun executeMacSshTest(): Boolean {
        val logs = mutableListOf<String>()
        fun log(msg: String) {
            logs.add("[MAC SSH TEST] $msg")
            _macSshTestState.value = MacSshTestState.Running(logs.toList())
            Log.d(TAG, "[MAC SSH TEST] $msg")
        }

        _macSshTestState.value = MacSshTestState.Running()
        try {
            log("Starting")

            val macLanIp = try {
                log("Discovering TraceMate Mac over Ethernet")
                val mac = macDiscoveryRepository.discover().getOrThrow()
                settingsRepository.setDiscoveredMacLanIp(mac.host)
                log("Discovered TraceMate Mac ${mac.id} at ${mac.host}")
                mac.host
            } catch (e: Exception) {
                throw IllegalStateException(
                    "Mac discovery failed: ${e.message ?: e::class.simpleName}. SSH was not attempted."
                )
            }
            val macSshPort = settingsRepository.macSshPort.first()
            val macSshUser = settingsRepository.macSshUser.first()
            val macSshPassword = settingsRepository.macSshPassword.first()

            log("Target: Mac LAN $macLanIp:$macSshPort via Ethernet")

            val result = sshRepository.executeCommand(
                target = SshTarget.MAC,
                host = macLanIp,
                port = macSshPort,
                user = macSshUser,
                password = macSshPassword,
                command = "sw_vers"
            )

            result.fold(
                onSuccess = { cmdResult ->
                    log("SSH connection established")
                    log("Executing: sw_vers")
                    cmdResult.stdout.trim().lines().forEach { log(it) }
                    log("Command exit code: ${cmdResult.exitCode}")
                    log("SSH session closed")
                    val parsed = parseMacSshOutput(cmdResult.exitCode, cmdResult.stdout)
                    if (parsed is MacSshTestState.Success) {
                        log("Success")
                        _macSshTestState.value = MacSshTestState.Success(cmdResult.stdout.trim(), logs.toList())
                    } else {
                        val errMsg = (parsed as MacSshTestState.Error).message
                        log(errMsg)
                        _macSshTestState.value = MacSshTestState.Error(errMsg, logs.toList())
                    }
                },
                onFailure = { e ->
                    // Log the full exception class + message so no information is lost
                    log("Exception: ${e::class.simpleName}: ${e.message}")
                    val msg = e.message ?: ""
                    when {
                        msg.contains("timeout", ignoreCase = true) ||
                        msg.contains("timed out", ignoreCase = true) -> {
                            log("Connection timed out — Mac reachable but SSH slow/overloaded")
                        }
                        msg.contains("No route to host", ignoreCase = true) ||
                        msg.contains("NoRouteToHost", ignoreCase = true) -> {
                            log("No route to Mac LAN host over Ethernet")
                        }
                        msg.contains("Auth", ignoreCase = true) ||
                        msg.contains("password", ignoreCase = true) -> {
                            log("SSH authentication failed — check Mac username/password")
                        }
                        msg.contains("refused", ignoreCase = true) -> {
                            log("Connection refused — enable Remote Login on the Mac")
                            log("System Settings → General → Sharing → Remote Login")
                        }
                        else -> log("Connection failed: $msg")
                    }
                    val status = mapSshExceptionToMacStatus(msg)
                    _macSshTestState.value = MacSshTestState.Error(status, logs.toList())
                }
            )
            return _macSshTestState.value is MacSshTestState.Success
        } catch (e: kotlinx.coroutines.CancellationException) {
            logs.add("[MAC SSH TEST] Cancelled")
            _macSshTestState.value = MacSshTestState.Idle
            throw e
        } catch (e: Exception) {
            val message = e.message ?: "Could not connect to the Mac over Ethernet."
            logs.add("[MAC SSH TEST] Exception: ${e::class.simpleName}: $message")
            _macSshTestState.value = MacSshTestState.Error(message, logs.toList())
            return false
        }
    }

    private suspend fun executeMacAgent(start: Boolean) {
        _macAgentState.value = MacAgentState.Running
        try {
            val result = if (start) macAgentRepository.start() else macAgentRepository.report()
            result.fold(
                onSuccess = { status -> _macAgentState.value = MacAgentState.Success(status) },
                onFailure = { error ->
                    _macAgentState.value = MacAgentState.Error(
                        error.message ?: "Mac agent is unavailable"
                    )
                }
            )
        } catch (e: CancellationException) {
            _macAgentState.value = MacAgentState.Idle
            throw e
        }
    }

    private suspend fun recoverOrDiscoverMacAgentCapture() {
        try {
            val jobId = macAgentCaptureSnapshotStore.load()
                ?: macAgentRepository.captureJobs().getOrThrow().firstOrNull { !it.isTerminal }?.jobId
                ?: return
            _macAgentCaptureState.value = MacAgentCaptureState.Active(
                MacAgentJob(jobId, "", "UNKNOWN", "UNKNOWN", 0, 0, null),
                recovering = true
            )
            macAgentCaptureSnapshotStore.save(jobId)
            pollMacAgentCapture(jobId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _macAgentCaptureState.value = MacAgentCaptureState.Failed(
                e.message ?: "Could not read Mac agent capture status"
            )
        }
    }

    private fun pollMacAgentCapture(jobId: String) {
        macAgentCapturePollingJob?.cancel()
        macAgentCapturePollingJob = viewModelScope.launch {
            while (true) {
                try {
                    val job = macAgentRepository.captureStatus(jobId).getOrThrow()
                    publishMacAgentJob(job)
                    if (job.isTerminal) return@launch
                    delay(MAC_AGENT_POLL_INTERVAL_MS)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val current = (_macAgentCaptureState.value as? MacAgentCaptureState.Active)?.job
                    _macAgentCaptureState.value = MacAgentCaptureState.Failed(
                        e.message ?: "Could not read Mac agent capture status", current
                    )
                    return@launch
                }
            }
        }
    }

    private suspend fun stopMacAgentCapture(job: MacAgentJob) {
        _macAgentCaptureState.value = MacAgentCaptureState.Stopping(job)
        publishMacAgentJob(macAgentRepository.stopCapture(job.jobId).getOrThrow())
        pollMacAgentCapture(job.jobId)
    }

    private suspend fun publishMacAgentJob(job: MacAgentJob) {
        when (job.state) {
            "COMPLETED", "STOPPED" -> {
                macAgentCaptureSnapshotStore.clear()
                _macAgentCaptureState.value = MacAgentCaptureState.Completed(job)
            }
            "FAILED" -> {
                macAgentCaptureSnapshotStore.clear()
                _macAgentCaptureState.value = MacAgentCaptureState.Failed(
                    job.lastError ?: "Mac agent capture failed", job
                )
            }
            "STOP_REQUESTED", "STOPPING", "VERIFYING" ->
                _macAgentCaptureState.value = MacAgentCaptureState.Stopping(job)
            else -> _macAgentCaptureState.value = MacAgentCaptureState.Active(job)
        }
    }

    // ── E-Release ─────────────────────────────────────────────────────────────

    fun loadERelease() {
        eReleaseJob?.cancel()
        eReleaseJob = viewModelScope.launch {
            _eReleaseLoading.value = true
            _eReleaseValue.value = null

            try {
                val host = settingsRepository.sshHost.first()

                // Step 1: Discover schema to find the timestamp column.
                val schemaResult = com.example.adb_connection.data.adb.AdbProtocol.executeShellCommand(
                    host, ADB_PORT,
                    com.example.adb_connection.domain.parser.EReleaseParser.SCHEMA_QUERY
                )
                val timestampCol = if (schemaResult.success) {
                    com.example.adb_connection.domain.parser.EReleaseParser
                        .discoverTimestampColumn(schemaResult.output)
                } else null

                // Step 2: Query E-Release using the discovered column (or fallback to id).
                val query = if (timestampCol != null) {
                    Log.d(TAG, "E-Release: using timestamp column '$timestampCol'")
                    com.example.adb_connection.domain.parser.EReleaseParser
                        .buildEReleaseQuery(timestampCol)
                } else {
                    Log.w(TAG, "E-Release: no timestamp column found, falling back to id")
                    com.example.adb_connection.domain.parser.EReleaseParser
                        .buildFallbackQuery()
                }

                val result = com.example.adb_connection.data.adb.AdbProtocol.executeShellCommand(
                    host, ADB_PORT, query
                )

                if (result.success && result.output.isNotBlank()) {
                    val eRelease = com.example.adb_connection.domain.parser.EReleaseParser
                        .extractERelease(result.output)
                    if (eRelease != null) {
                        _eReleaseValue.value = eRelease
                    } else {
                        Log.w(TAG, "E-Release query returned unparseable output: ${result.output.trim()}")
                        _eReleaseValue.value = null
                    }
                } else {
                    Log.w(TAG, "E-Release load failed: ${result.errorMessage ?: result.output}")
                    _eReleaseValue.value = null
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "E-Release load exception: ${e.message}")
                _eReleaseValue.value = null
            }

            _eReleaseLoading.value = false
        }
    }

    // Placeholder build info — replace individual values with real getprop output in a future iteration.
    val placeholderBuildInfo: List<Pair<String, String>> = listOf(
        "Android Version"   to "Placeholder",
        "SDK Version"       to "Placeholder",
        "Build ID"          to "Placeholder",
        "Build Fingerprint" to "Placeholder",
        "Manufacturer"      to "Placeholder",
        "Model"             to "Placeholder",
        "Product Name"      to "Placeholder",
        "Device"            to "Placeholder",
        "Hardware"          to "Placeholder",
        "Serial Number"     to "Placeholder"
    )

    // ── ADB TCP reachability (shared) ─────────────────────────────────────────

    private suspend fun checkAdbTcpReachable(host: String, port: Int, timeoutMs: Int = 3000): Result<Unit> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(host, port), timeoutMs)
                }
                Result.success(Unit)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    fun dismissAdbPopup() {
        _adbConnectionState.value = AdbConnectionState.Idle
    }

    fun refreshSsid() {
        wifiRepository.refreshSsid()
    }

}

class HomeViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val appCtx = context.applicationContext
        return HomeViewModel(
            appContext = appCtx,
            wifiRepository = ServiceLocator.provideWifiRepository(appCtx),
            settingsRepository = SettingsRepository(appCtx),
            sshRepository = AndroidSshRepository(ServiceLocator.provideSshNetworkProvider(appCtx)),
            macDiscoveryRepository = ServiceLocator.provideMacDiscoveryRepository(appCtx),
            macAgentRepository = createMacAgentRepository(appCtx),
            macAgentCaptureSnapshotStore = ServiceLocator.provideMacAgentCaptureSnapshotStore(appCtx),
            macScpDownloadRepository = createMacScpDownloadRepository(appCtx),
            captureToolPlaceholderTransferCoordinator = ServiceLocator.provideCaptureToolPlaceholderTransferCoordinator(appCtx)
        ) as T
    }

    private fun createMacAgentRepository(context: Context): MacAgentRepository {
        val settings = SettingsRepository(context)
        return MacAgentRepository(
            sshRepository = AndroidSshRepository(ServiceLocator.provideSshNetworkProvider(context)),
            configProvider = ScpConnectionConfigProvider {
                ScpConnectionConfig(
                    host = settings.macLanIp.first(),
                    port = settings.macSshPort.first(),
                    user = settings.macSshUser.first(),
                    password = settings.macSshPassword.first()
                )
            }
        )
    }

    private fun createMacScpDownloadRepository(context: Context): MacScpDownloadRepository {
        val settings = SettingsRepository(context)
        val networkProvider = ServiceLocator.provideSshNetworkProvider(context)
        val configProvider = ScpConnectionConfigProvider {
            ScpConnectionConfig(
                host = settings.macLanIp.first(),
                port = settings.macSshPort.first(),
                user = settings.macSshUser.first(),
                password = settings.macSshPassword.first()
            )
        }
        return MacScpDownloadRepository(
            filesDir = context.filesDir,
            sshRepository = AndroidSshRepository(networkProvider),
            scpRepository = AndroidScpRepository(networkProvider, configProvider),
            configProvider = configProvider
        )
    }

}
