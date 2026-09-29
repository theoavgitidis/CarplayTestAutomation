package com.example.adb_connection.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.adb_connection.ServiceLocator
import com.example.adb_connection.data.settings.SettingsRepository
import com.example.adb_connection.domain.model.TransferPhase
import com.example.adb_connection.domain.model.UsbTransferCoordinatorState
import com.example.adb_connection.domain.usb.UsbTransferCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val CHANNEL_ID = "usb_transfer_channel"
private const val NOTIFICATION_ID = 4201

/**
 * App-internal foreground service that keeps the [UsbTransferCoordinator]'s batch alive and
 * visible to the user while the app communicates with the head unit, independent of any
 * Activity/ViewModel lifecycle. Started only from the visible "Begin" user action in
 * `UsbCopyScreen`/`UsbCopyViewModel` — never started implicitly in the background.
 *
 * Uses foreground service type `connectedDevice` since this app continuously talks to the head
 * unit over the local network for the duration of the transfer (targetSdk 36).
 */
class UsbTransferService : Service() {

    companion object {
        const val ACTION_START = "com.example.adb_connection.usb.action.START"
        const val ACTION_CANCEL = "com.example.adb_connection.usb.action.CANCEL"

        fun startIntent(context: Context): Intent =
            Intent(context, UsbTransferService::class.java).setAction(ACTION_START)
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observerJob: Job? = null
    private lateinit var coordinator: UsbTransferCoordinator

    override fun onCreate() {
        super.onCreate()
        coordinator = ServiceLocator.provideUsbTransferCoordinator(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Start the foreground promotion immediately regardless of why onStartCommand ran —
        // required within a few seconds of Context.startForegroundService, including on a
        // system-triggered restart with a null intent (START_STICKY).
        startForegroundWithNotification()
        ensureObserving()

        when (intent?.action) {
            ACTION_CANCEL -> coordinator.requestCancel()
            ACTION_START -> { /* Coordinator.start() is invoked by the caller before/around
                                  starting this service; nothing further to do here. */ }
            null -> {
                // Service process was restarted by the platform (START_STICKY) with no intent —
                // attempt to reattach to any persisted in-flight batch.
                serviceScope.launch {
                    val host = SettingsRepository(applicationContext).sshHost.first()
                    coordinator.attachOrRecover(host)
                }
            }
        }
        return START_STICKY
    }

    private fun ensureObserving() {
        if (observerJob?.isActive == true) return
        observerJob = serviceScope.launch {
            coordinator.state.collect { state ->
                updateNotification(state)
                if (state is UsbTransferCoordinatorState.Idle || state is UsbTransferCoordinatorState.Completed) {
                    // Both are terminal from the service's perspective: Completed remains
                    // observable via the coordinator's own StateFlow (an application-scoped
                    // singleton) independent of this service's lifecycle, so there is no need
                    // to keep holding the foreground promotion once no work is in flight.
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observerJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "USB export transfer",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows progress while exporting trigger archives to the USB stick"
        }
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundWithNotification() {
        startForeground(
            NOTIFICATION_ID,
            buildNotification(UsbTransferCoordinatorState.Idle),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
    }

    private fun updateNotification(state: UsbTransferCoordinatorState) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun stopForegroundCompat() {
        @Suppress("DEPRECATION")
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun buildNotification(state: UsbTransferCoordinatorState): Notification {
        val title: String
        val text: String
        val max: Int
        val progress: Int
        when (state) {
            is UsbTransferCoordinatorState.Idle -> {
                title = "USB export"; text = "Preparing…"; max = 0; progress = 0
            }
            is UsbTransferCoordinatorState.Running -> {
                title = "Exporting to USB"
                text = state.phase?.let { describePhase(it) }
                    ?: "${state.progress.processed}/${state.progress.totalSelected} done"
                max = state.progress.totalSelected
                progress = state.progress.processed
            }
            is UsbTransferCoordinatorState.Completed -> {
                title = "USB export finished"
                text = "${state.progress.succeeded} succeeded, ${state.progress.failed} failed"
                max = state.progress.totalSelected
                progress = state.progress.totalSelected
            }
        }

        val cancelIntent = Intent(this, UsbTransferService::class.java).setAction(ACTION_CANCEL)
        val cancelPendingIntent = PendingIntent.getService(
            this, 0, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOnlyAlertOnce(true)
            .setOngoing(state is UsbTransferCoordinatorState.Running)

        if (max > 0) {
            builder.setProgress(max, progress, false)
        }
        if (state is UsbTransferCoordinatorState.Running) {
            builder.addAction(0, "Cancel", cancelPendingIntent)
        }
        return builder.build()
    }

    private fun describePhase(phase: TransferPhase): String = when (phase) {
        is TransferPhase.Checking -> "Checking ${phase.archive.displayLabel}…"
        is TransferPhase.CopyingExisting -> "Copying ${phase.archive.displayLabel}…"
        is TransferPhase.Extracting -> "Extracting ${phase.archive.displayLabel}…"
        is TransferPhase.Stopping -> "Stopping ${phase.archive.displayLabel}…"
    }
}
