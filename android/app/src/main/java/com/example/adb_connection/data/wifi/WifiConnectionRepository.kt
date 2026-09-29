package com.example.adb_connection.data.wifi

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Independent transport states for the two links used by TraceMate.
 * A link changing state must never imply a state change for the other link.
 */
data class ConnectivityState(
    val headunitWifiStatus: ConnectionStatus = ConnectionStatus.IDLE,
    val macEthernetStatus: ConnectionStatus = ConnectionStatus.IDLE
) {
    fun withHeadunitWifiStatus(status: ConnectionStatus): ConnectivityState =
        copy(headunitWifiStatus = status)

    fun withMacEthernetStatus(status: ConnectionStatus): ConnectivityState =
        copy(macEthernetStatus = status)
}

enum class ConnectionStatus {
    IDLE,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    ERROR
}

interface WifiConnectionRepository {
    val connectionStatus: Flow<ConnectionStatus>
    val currentSsid: StateFlow<String?>
    val connectivityState: StateFlow<ConnectivityState>
    fun disconnect()
    fun refreshSsid()
    fun refreshMacEthernetStatus(): ConnectionStatus
}
