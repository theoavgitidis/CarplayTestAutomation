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
import com.example.adb_connection.data.ssh.CaptureToolPlaceholderTransferCoordinator
import com.example.adb_connection.data.ssh.CaptureToolPlaceholderTransferJobStatus
import com.example.adb_connection.data.ssh.CaptureToolPlaceholderTransferSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

private const val CHANNEL_ID = "capture_tool_placeholder_transfer_channel"
private const val NOTIFICATION_ID = 4202

/** Keeps the application-scoped CAPTURE_TOOL_PLACEHOLDER SCP coordinator alive while an explicit user job runs. */
class CaptureToolPlaceholderTransferService : Service() {
    companion object {
        const val ACTION_START = "com.example.adb_connection.capture_tool_placeholder.action.START"
        const val ACTION_CANCEL = "com.example.adb_connection.capture_tool_placeholder.action.CANCEL"

        fun startIntent(context: Context): Intent =
            Intent(context, CaptureToolPlaceholderTransferService::class.java).setAction(ACTION_START)
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var coordinator: CaptureToolPlaceholderTransferCoordinator
    private var observerJob: Job? = null
    private var lastNotificationBytes = 0L
    private var lastNotificationPhase: String? = null
    private var lastNotificationAtMs = 0L

    override fun onCreate() {
        super.onCreate()
        coordinator = ServiceLocator.provideCaptureToolPlaceholderTransferCoordinator(applicationContext)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        observe()
        when (intent?.action) {
            ACTION_CANCEL -> coordinator.requestCancel()
            null -> serviceScope.launch { coordinator.recoverLastSnapshot() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observerJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun observe() {
        if (observerJob?.isActive == true) return
        observerJob = serviceScope.launch {
            coordinator.state.filterNotNull().collect { snapshot ->
                if (shouldUpdateNotification(snapshot)) {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(snapshot))
                }
                if (snapshot.status != CaptureToolPlaceholderTransferJobStatus.RUNNING) {
                    @Suppress("DEPRECATION")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun shouldUpdateNotification(snapshot: CaptureToolPlaceholderTransferSnapshot): Boolean {
        if (snapshot.status != CaptureToolPlaceholderTransferJobStatus.RUNNING) return true
        val now = System.currentTimeMillis()
        val phaseChanged = snapshot.phase.name != lastNotificationPhase
        val progressAdvanced = snapshot.transferredBytes - lastNotificationBytes >= 1L * 1024 * 1024
        if (!phaseChanged && !progressAdvanced && now - lastNotificationAtMs < 1_000L) return false
        lastNotificationPhase = snapshot.phase.name
        lastNotificationBytes = snapshot.transferredBytes
        lastNotificationAtMs = now
        return true
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "CAPTURE_TOOL_PLACEHOLDER SCP transfer", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shows CAPTURE_TOOL_PLACEHOLDER transfer progress while the app is in the background" })
        }
    }

    private fun notification(snapshot: CaptureToolPlaceholderTransferSnapshot?): Notification {
        val running = snapshot?.status == CaptureToolPlaceholderTransferJobStatus.RUNNING
        val text = snapshot?.let {
            buildString {
                append(it.phase.name.replace('_', ' '))
                it.fileName?.let { name -> append(": ").append(name) }
            }
        } ?: "Preparing CAPTURE_TOOL_PLACEHOLDER transfer"
        val cancelIntent = Intent(this, CaptureToolPlaceholderTransferService::class.java).setAction(ACTION_CANCEL)
        val cancelPendingIntent = PendingIntent.getService(
            this, 0, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(if (running) "Transferring CAPTURE_TOOL_PLACEHOLDER to USB" else "CAPTURE_TOOL_PLACEHOLDER transfer")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(running)
            .apply {
                if (snapshot != null && snapshot.totalBytes > 0) {
                    setProgress(snapshot.totalBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), snapshot.transferredBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), false)
                }
                if (running) addAction(0, "Cancel", cancelPendingIntent)
            }
            .build()
    }
}
