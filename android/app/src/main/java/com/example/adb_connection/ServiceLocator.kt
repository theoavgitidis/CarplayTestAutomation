package com.example.adb_connection

import android.content.Context
import com.example.adb_connection.data.usb.DataStoreTransferSnapshotStore
import com.example.adb_connection.data.usb.DefaultUsbTransferCoordinator
import com.example.adb_connection.data.usb.TransferSnapshotStore
import com.example.adb_connection.data.usb.UsbTransferRepository
import com.example.adb_connection.data.usb.UsbTransferRepositoryImpl
import com.example.adb_connection.data.wifi.AndroidWifiConnectionRepository
import com.example.adb_connection.data.wifi.WifiConnectionRepository
import com.example.adb_connection.data.ssh.AndroidScpRepository
import com.example.adb_connection.data.ssh.AndroidSshRepository
import com.example.adb_connection.data.ssh.CaptureToolPlaceholderTransferCoordinator
import com.example.adb_connection.data.ssh.CaptureToolPlaceholderTransferSnapshotStore
import com.example.adb_connection.data.ssh.CaptureToolPlaceholderUsbBridgeRepository
import com.example.adb_connection.data.ssh.DataStoreCaptureToolPlaceholderTransferSnapshotStore
import com.example.adb_connection.data.ssh.DefaultCaptureToolPlaceholderTransferCoordinator
import com.example.adb_connection.data.ssh.MacScpDownloadRepository
import com.example.adb_connection.data.ssh.MacAgentCaptureSnapshotStore
import com.example.adb_connection.data.ssh.DataStoreMacAgentCaptureSnapshotStore
import com.example.adb_connection.data.ssh.ScpConnectionConfig
import com.example.adb_connection.data.ssh.ScpConnectionConfigProvider
import com.example.adb_connection.data.ssh.SshTarget
import com.example.adb_connection.data.settings.SettingsRepository
import com.example.adb_connection.data.ssh.SshNetworkProvider
import com.example.adb_connection.data.ssh.AndroidMacDiscoveryRepository
import com.example.adb_connection.data.ssh.MacDiscoveryRepository
import com.example.adb_connection.domain.usb.UsbTransferCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first

/**
 * Simple service locator that provides shared singleton instances.
 * Initialize once from Application/Activity context.
 */
object ServiceLocator {

    @Volatile
    private var wifiRepository: AndroidWifiConnectionRepository? = null

    @Volatile
    private var usbTransferRepository: UsbTransferRepository? = null

    @Volatile
    private var usbTransferCoordinator: UsbTransferCoordinator? = null

    @Volatile
    private var captureToolPlaceholderTransferCoordinator: CaptureToolPlaceholderTransferCoordinator? = null

    @Volatile
    private var macAgentCaptureSnapshotStore: MacAgentCaptureSnapshotStore? = null

    @Volatile
    private var macDiscoveryRepository: MacDiscoveryRepository? = null

    fun provideMacDiscoveryRepository(context: Context): MacDiscoveryRepository {
        return macDiscoveryRepository ?: synchronized(this) {
            macDiscoveryRepository ?: AndroidMacDiscoveryRepository(
                context.applicationContext,
                provideSshNetworkProvider(context)
            ).also { macDiscoveryRepository = it }
        }
    }

    fun provideMacAgentCaptureSnapshotStore(context: Context): MacAgentCaptureSnapshotStore {
        return macAgentCaptureSnapshotStore ?: synchronized(this) {
            macAgentCaptureSnapshotStore ?: DataStoreMacAgentCaptureSnapshotStore(context.applicationContext).also {
                macAgentCaptureSnapshotStore = it
            }
        }
    }

    fun provideWifiRepository(context: Context): WifiConnectionRepository {
        return wifiRepository ?: synchronized(this) {
            wifiRepository ?: AndroidWifiConnectionRepository(context.applicationContext).also {
                wifiRepository = it
            }
        }
    }

    fun provideSshNetworkProvider(context: Context): SshNetworkProvider =
        provideWifiRepository(context) as AndroidWifiConnectionRepository

    fun provideUsbTransferRepository(): UsbTransferRepository {
        return usbTransferRepository ?: synchronized(this) {
            usbTransferRepository ?: UsbTransferRepositoryImpl().also {
                usbTransferRepository = it
            }
        }
    }

    /**
     * Application-scoped [UsbTransferCoordinator] — the single owner of any in-flight USB
     * export batch. Deliberately NOT recreated per-Activity/ViewModel: the whole point of the
     * Prompt 2 refactor is that this instance (and the coroutine scope backing it) outlives
     * screen-off, activity recreation, and ViewModel destruction. Only process death loses it,
     * which is what the persisted [TransferSnapshotStore] + `attachOrRecover` exist to recover
     * from.
     */
    fun provideUsbTransferCoordinator(context: Context): UsbTransferCoordinator {
        return usbTransferCoordinator ?: synchronized(this) {
            usbTransferCoordinator ?: DefaultUsbTransferCoordinator(
                repository = provideUsbTransferRepository(),
                snapshotStore = provideTransferSnapshotStore(context),
                coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            ).also {
                usbTransferCoordinator = it
            }
        }
    }

    fun provideCaptureToolPlaceholderTransferCoordinator(context: Context): CaptureToolPlaceholderTransferCoordinator {
        val appContext = context.applicationContext
        return captureToolPlaceholderTransferCoordinator ?: synchronized(this) {
            captureToolPlaceholderTransferCoordinator ?: run {
                val settings = SettingsRepository(appContext)
                val networkProvider = provideSshNetworkProvider(appContext)
                val configProvider = ScpConnectionConfigProvider { target ->
                    when (target) {
                        SshTarget.MAC -> ScpConnectionConfig(
                            settings.macLanIp.first(), settings.macSshPort.first(),
                            settings.macSshUser.first(), settings.macSshPassword.first()
                        )
                        SshTarget.HEADUNIT -> ScpConnectionConfig(
                            settings.sshHost.first(), settings.sshPort.first(),
                            settings.sshUser.first(), settings.sshPassword.first()
                        )
                    }
                }
                val sshRepository = AndroidSshRepository(networkProvider)
                val macDownload = MacScpDownloadRepository(
                    appContext.filesDir,
                    sshRepository,
                    AndroidScpRepository(networkProvider, configProvider),
                    configProvider
                )
                DefaultCaptureToolPlaceholderTransferCoordinator(
                    CaptureToolPlaceholderUsbBridgeRepository(macDownload, AndroidScpRepository(networkProvider, configProvider), sshRepository, configProvider),
                    provideCaptureToolPlaceholderTransferSnapshotStore(appContext),
                    CoroutineScope(SupervisorJob() + Dispatchers.Default)
                ).also { captureToolPlaceholderTransferCoordinator = it }
            }
        }
    }

    @Volatile
    private var transferSnapshotStore: TransferSnapshotStore? = null

    @Volatile
    private var captureToolPlaceholderTransferSnapshotStore: CaptureToolPlaceholderTransferSnapshotStore? = null

    private fun provideTransferSnapshotStore(context: Context): TransferSnapshotStore {
        return transferSnapshotStore ?: synchronized(this) {
            transferSnapshotStore ?: DataStoreTransferSnapshotStore(context.applicationContext).also {
                transferSnapshotStore = it
            }
        }
    }

    private fun provideCaptureToolPlaceholderTransferSnapshotStore(context: Context): CaptureToolPlaceholderTransferSnapshotStore {
        return captureToolPlaceholderTransferSnapshotStore ?: synchronized(this) {
            captureToolPlaceholderTransferSnapshotStore ?: DataStoreCaptureToolPlaceholderTransferSnapshotStore(context.applicationContext).also {
                captureToolPlaceholderTransferSnapshotStore = it
            }
        }
    }
}
