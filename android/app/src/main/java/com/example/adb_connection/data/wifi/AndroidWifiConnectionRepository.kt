package com.example.adb_connection.data.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.example.adb_connection.data.debug.DebugLogger
import com.example.adb_connection.data.ssh.SshNetworkProvider
import com.example.adb_connection.data.ssh.SshTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.Closeable

internal class NetworkSelectionState<T> {
    private val lock = Any()
    private var wifiNetwork: T? = null
    private var ethernetNetwork: T? = null

    fun wifiNetwork(): T? = synchronized(lock) { wifiNetwork }

    fun ethernetNetwork(): T? = synchronized(lock) { ethernetNetwork }

    fun selectWifi(network: T) {
        synchronized(lock) { wifiNetwork = network }
    }

    fun selectWifiIfNone(network: T): Boolean = synchronized(lock) {
        if (wifiNetwork != null) return@synchronized false
        wifiNetwork = network
        true
    }

    fun selectEthernet(network: T) {
        synchronized(lock) { ethernetNetwork = network }
    }

    fun isSelectedWifi(network: T): Boolean = synchronized(lock) { wifiNetwork == network }

    fun clearWifiIfSelected(network: T): Boolean = synchronized(lock) {
        if (wifiNetwork != network) return@synchronized false
        wifiNetwork = null
        true
    }

    fun clearEthernetIfSelected(network: T): Boolean = synchronized(lock) {
        if (ethernetNetwork != network) return@synchronized false
        ethernetNetwork = null
        true
    }

    fun clearWifi() {
        synchronized(lock) { wifiNetwork = null }
    }

    fun clearEthernet() {
        synchronized(lock) { ethernetNetwork = null }
    }
}

class AndroidWifiConnectionRepository(
    private val context: Context
) : WifiConnectionRepository, SshNetworkProvider, Closeable {

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val selectedNetworks = NetworkSelectionState<Network>()

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.IDLE)
    override val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _currentSsid = MutableStateFlow<String?>(null)
    override val currentSsid: StateFlow<String?> = _currentSsid.asStateFlow()

    private val _connectivityState = MutableStateFlow(ConnectivityState())
    override val connectivityState: StateFlow<ConnectivityState> = _connectivityState.asStateFlow()

    private fun updateHeadunitWifiStatus(status: ConnectionStatus) {
        _connectivityState.update { it.withHeadunitWifiStatus(status) }
        _connectionStatus.value = status
    }

    private fun updateMacEthernetStatus(status: ConnectionStatus) {
        _connectivityState.update { it.withMacEthernetStatus(status) }
    }

    override fun networkFor(target: SshTarget): Network? = when (target) {
        SshTarget.HEADUNIT -> selectedNetworks.wifiNetwork()
        SshTarget.MAC -> currentMacEthernetNetwork()
    }

    private fun currentMacEthernetNetwork(): Network? {
        selectedNetworks.ethernetNetwork()?.takeIf(::isUsableMacEthernetNetwork)?.let { return it }

        // Some tethering implementations do not deliver onAvailable to an already
        // registered request. Re-scan when SSH is requested so enabling tethering
        // after app launch does not require restarting the app.
        val ethernetNetwork = connectivityManager.allNetworks.firstOrNull { network ->
            connectivityManager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        } ?: return null

        DebugLogger.log("Mac Ethernet callback missed; recovered network $ethernetNetwork during SSH request")
        handleEthernetAvailable(ethernetNetwork)
        return ethernetNetwork.takeIf(::isUsableMacEthernetNetwork)
    }

    private fun handleWifiAvailable(network: Network) {
        selectedNetworks.selectWifi(network)
        updateHeadunitWifiStatus(ConnectionStatus.CONNECTED)
    }

    private fun handleWifiCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
        if (!selectedNetworks.isSelectedWifi(network) ||
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        ) return
        val ssid = extractSsid(capabilities)
        DebugLogger.log("WiFi capabilities changed. SSID: $ssid")
        updateHeadunitWifiStatus(ConnectionStatus.CONNECTED)
        _currentSsid.value = ssid
    }

    private fun handleWifiLost(network: Network) {
        if (selectedNetworks.clearWifiIfSelected(network)) {
            DebugLogger.log("Headunit WiFi network lost")
            refreshWifiState(excluding = network, onlyIfNoneSelected = true)
        }
    }

    private fun handleEthernetAvailable(network: Network) {
        selectedNetworks.selectEthernet(network)
        updateMacEthernetStatus(ConnectionStatus.CONNECTED)
        logNetwork("Mac Ethernet available", network)
    }

    private fun handleEthernetLost(network: Network) {
        if (selectedNetworks.clearEthernetIfSelected(network)) {
            DebugLogger.log("Mac Ethernet network lost")
            updateMacEthernetStatus(ConnectionStatus.DISCONNECTED)
        }
    }

    private fun isUsableMacEthernetNetwork(network: Network): Boolean {
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) != true) {
            DebugLogger.log("Mac Ethernet network $network is no longer a valid Ethernet network")
            return false
        }
        logNetwork("Using Mac Ethernet", network, capabilities)
        return true
    }

    private fun logNetwork(
        prefix: String,
        network: Network,
        capabilities: NetworkCapabilities? = connectivityManager.getNetworkCapabilities(network)
    ) {
        val linkProperties = connectivityManager.getLinkProperties(network)
        val addresses = linkProperties?.linkAddresses?.joinToString { it.address.hostAddress ?: "unknown" } ?: "none"
        val routes = linkProperties?.routes?.joinToString { it.toString() } ?: "none"
        DebugLogger.log(
            "$prefix: id=$network interface=${linkProperties?.interfaceName ?: "unknown"} " +
                "addresses=$addresses routes=$routes capabilities=$capabilities"
        )
    }

    private val networkCallback: ConnectivityManager.NetworkCallback =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(
                ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
            ) {
                override fun onAvailable(network: Network) = handleWifiAvailable(network)
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
                    handleWifiCapabilitiesChanged(network, networkCapabilities)
                override fun onLost(network: Network) = handleWifiLost(network)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = handleWifiAvailable(network)
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
                    handleWifiCapabilitiesChanged(network, networkCapabilities)
                override fun onLost(network: Network) = handleWifiLost(network)
            }
        }

    private val ethernetNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = handleEthernetAvailable(network)
        override fun onLost(network: Network) = handleEthernetLost(network)
    }

    init {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
        connectivityManager.registerNetworkCallback(ethernetRequest(), ethernetNetworkCallback)
        refreshNetworkStates()
    }

    private fun extractSsid(capabilities: NetworkCapabilities): String? {
        val wifiInfo = capabilities.transportInfo as? WifiInfo
        val ssid = wifiInfo?.ssid
        DebugLogger.log("Raw SSID from capabilities: '$ssid'")
        val isUnknown = ssid == null || ssid == "<unknown ssid>"
        return if (!isUnknown) {
            ssid!!.removeSurrounding("\"").takeIf { it.isNotBlank() }
        } else {
            if (ssid != null) DebugLogger.log("SSID is unknown/redacted: '$ssid' (check GPS/Location Perms)")
            getSsidLegacy()
        }
    }

    @Suppress("DEPRECATION")
    private fun getSsidLegacy(): String? {
        val ssid = wifiManager.connectionInfo?.ssid
        return ssid
            ?.removeSurrounding("\"")
            ?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }

    override fun refreshSsid() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: IllegalArgumentException) {
            // Callback was not registered.
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
        refreshWifiState()
    }

    override fun refreshMacEthernetStatus(): ConnectionStatus {
        val ethernetNetwork = connectivityManager.allNetworks.firstOrNull { network ->
            connectivityManager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        }
        if (ethernetNetwork != null) {
            handleEthernetAvailable(ethernetNetwork)
        } else {
            selectedNetworks.clearEthernet()
            updateMacEthernetStatus(ConnectionStatus.DISCONNECTED)
        }
        return _connectivityState.value.macEthernetStatus
    }

    private fun ethernetRequest(): NetworkRequest = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
        .build()

    private fun refreshNetworkStates() {
        refreshWifiState()
        refreshMacEthernetStatus()
    }

    private fun refreshWifiState(
        excluding: Network? = null,
        onlyIfNoneSelected: Boolean = false
    ) {
        val wifiNetwork = connectivityManager.allNetworks.firstOrNull { network ->
            network != excluding && connectivityManager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        if (wifiNetwork != null) {
            val wasSelected = if (onlyIfNoneSelected) {
                selectedNetworks.selectWifiIfNone(wifiNetwork)
            } else {
                selectedNetworks.selectWifi(wifiNetwork)
                true
            }
            if (!wasSelected) return
            val capabilities = connectivityManager.getNetworkCapabilities(wifiNetwork)
            updateHeadunitWifiStatus(ConnectionStatus.CONNECTED)
            _currentSsid.value = capabilities?.let(::extractSsid) ?: getSsidLegacy()
        } else if (!onlyIfNoneSelected || selectedNetworks.wifiNetwork() == null) {
            selectedNetworks.clearWifi()
            updateHeadunitWifiStatus(ConnectionStatus.DISCONNECTED)
            _currentSsid.value = null
        }
    }

    override fun disconnect() {
        DebugLogger.log("Removing network suggestions...")
        wifiManager.removeNetworkSuggestions(emptyList())
        selectedNetworks.clearWifi()
        updateHeadunitWifiStatus(ConnectionStatus.DISCONNECTED)
        _currentSsid.value = null
    }

    override fun close() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: IllegalArgumentException) {
            // Callback was already unregistered
        }
        try {
            connectivityManager.unregisterNetworkCallback(ethernetNetworkCallback)
        } catch (_: IllegalArgumentException) {
            // Callback was already unregistered.
        }
    }
}
