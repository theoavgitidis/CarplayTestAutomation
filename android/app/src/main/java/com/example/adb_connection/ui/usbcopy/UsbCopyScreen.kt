package com.example.adb_connection.ui.usbcopy

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import com.example.adb_connection.domain.model.ArchiveTileState
import com.example.adb_connection.domain.model.SourceType
import com.example.adb_connection.domain.model.TriggerArchive
import com.example.adb_connection.domain.model.TriggerTransferResult
import com.example.adb_connection.domain.model.UsbDetectionResult
import com.example.adb_connection.domain.model.UsbMount
import com.example.adb_connection.ui.components.DebugNavTarget
import com.example.adb_connection.ui.components.TraceMateTopBar

private val Green = Color(0xFF2E7D32)
private val Amber = Color(0xFFFF8F00)
private val Grey = Color(0xFF757575)


@Composable
fun UsbCopyScreen(
    onNavigateBack: () -> Unit,
    debugNavTargets: List<DebugNavTarget> = emptyList()
) {
    val context = LocalContext.current
    val viewModel: UsbCopyViewModel = viewModel(factory = UsbCopyViewModelFactory(context))
    val uiState by viewModel.uiState.collectAsState()
    val showLeaveDialog by viewModel.showLeaveDialog.collectAsState()

    // Navigate home whenever the ViewModel fires a dismiss event.
    LaunchedEffect(viewModel) {
        viewModel.navigateHomeEvent.collect { onNavigateBack() }
    }

    var isStopping by remember { mutableStateOf(false) }

    LaunchedEffect(uiState) {
        if (uiState !is UsbCopyUiState.Transferring) isStopping = false
    }

    // Temporary UI safeguard while the coordinator-owned transfer is Running (Prompt 2 Task D).
    // This is additional protection only — the primary fix against screen-off/recreation killing
    // the transfer is that the batch itself lives in the application-scoped UsbTransferCoordinator,
    // not in this screen or its ViewModel.
    val view = LocalView.current
    val isTransferring = uiState is UsbCopyUiState.Transferring
    DisposableEffect(isTransferring) {
        view.keepScreenOn = isTransferring
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(Unit) {
        viewModel.loadArchives()
    }

    val guardedNavigateBack: () -> Unit = {
        if (viewModel.isTransferActive()) {
            viewModel.requestLeave()
        } else {
            onNavigateBack()
        }
    }

    BackHandler(enabled = uiState is UsbCopyUiState.Transferring) {
        viewModel.requestLeave()
    }

    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissLeaveDialog() },
            title = { Text("Transfer active") },
            text = { Text("A transfer is still running. Cancel the transfer and leave?") },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmLeaveAndCancel() }) {
                    Text("Cancel transfer")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissLeaveDialog() }) {
                    Text("Continue")
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TraceMateTopBar(
                    title = "Export Traces to USB",
                    onBackClick = guardedNavigateBack,
                    actions = {
                        val canRefresh = uiState !is UsbCopyUiState.Discovering &&
                                uiState !is UsbCopyUiState.Transferring &&
                                uiState !is UsbCopyUiState.Completed
                        IconButton(onClick = viewModel::refreshArchives, enabled = canRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    },
                    debugNavTargets = debugNavTargets.map { target ->
                        DebugNavTarget(target.label) {
                            if (viewModel.isTransferActive()) {
                                viewModel.requestLeave()
                            } else {
                                target.onClick()
                            }
                        }
                    }
                )
            },
            contentWindowInsets = WindowInsets(0)
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                when (val state = uiState) {
                    is UsbCopyUiState.Idle -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }

                    is UsbCopyUiState.Discovering -> {
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = "Discovering archives...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    is UsbCopyUiState.DiscoveryFailed -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Button(
                                onClick = { viewModel.reset(); viewModel.loadArchives() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Retry")
                            }
                        }
                    }

                    is UsbCopyUiState.Ready -> {
                        ResponsiveReadyContent(
                            state = state,
                            onToggle = { viewModel.toggleSelection(it) },
                            onStartTransfer = { viewModel.startTransfer() },
                            onEject = { viewModel.ejectUsb() },
                            onRequestRemount = { viewModel.requestRemount() },
                            onConfirmRemount = { viewModel.confirmRemount() },
                            onDismissRemount = { viewModel.dismissRemountConfirmation() }
                        )
                    }

                    is UsbCopyUiState.Transferring -> {
                        ResponsiveTransferringContent(
                            state = state,
                            isStopping = isStopping,
                            onStop = {
                                isStopping = true
                                viewModel.requestCancel()
                            }
                        )
                    }

                    is UsbCopyUiState.Completed -> { /* shown as overlay below */ }
                }
            }
        }

        // Full-screen result overlay — covers top bar intentionally.
        val completedState = uiState as? UsbCopyUiState.Completed
        if (completedState != null) {
            // Start the 15-second auto-dismiss timer as soon as the overlay appears.
            LaunchedEffect(completedState) {
                viewModel.startAutoDismissTimer()
            }
            ResultOverlay(
                state = completedState,
                onDismiss = { viewModel.dismissCompleted() }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Ready content
// ---------------------------------------------------------------------------

@Composable
private fun ResponsiveReadyContent(
    state: UsbCopyUiState.Ready,
    onToggle: (TriggerArchive) -> Unit,
    onStartTransfer: () -> Unit,
    onEject: () -> Unit,
    onRequestRemount: () -> Unit = {},
    onConfirmRemount: () -> Unit = {},
    onDismissRemount: () -> Unit = {}
) {
    if (state.remountState is RemountState.ConfirmPending) {
        AlertDialog(
            onDismissRequest = onDismissRemount,
            title = { Text("Make USB writable?") },
            text = {
                Text(
                    "The USB stick is mounted read-only. " +
                    "Running 'mount -o remount,rw' requires root access on the head unit. " +
                    "This is safe for exFAT/FAT32 sticks that were mounted ro after formatting. " +
                    "Proceed?"
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmRemount) { Text("Remount rw") }
            },
            dismissButton = {
                TextButton(onClick = onDismissRemount) { Text("Cancel") }
            }
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isLandscape = maxWidth > maxHeight
        val canStart = state.tiles.any { it.isSelected && !it.isAlreadyOnUsb } && state.usbMount != null

        if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ArchiveTileGrid(
                    tiles = state.tiles,
                    onToggle = onToggle,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
                Column(
                    modifier = Modifier
                        .weight(0.35f)
                        .fillMaxHeight()
                ) {
                    val selectedCount = state.tiles.count { it.isSelected && !it.isAlreadyOnUsb }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        UsbStatusText(usbDetectionResult = state.usbDetectionResult)
                        UsbEjectSection(ejectState = state.ejectState, usbDetectionResult = state.usbDetectionResult, onEject = onEject)
                        RemountSection(remountState = state.remountState, usbDetectionResult = state.usbDetectionResult, onRequestRemount = onRequestRemount)
                        Text(
                            text = "$selectedCount selected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (state.tiles.isEmpty()) {
                            Text(
                                text = "No archives found.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Button(
                        onClick = onStartTransfer,
                        enabled = canStart,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Begin")
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                UsbStatusText(usbDetectionResult = state.usbDetectionResult)
                UsbEjectSection(ejectState = state.ejectState, usbDetectionResult = state.usbDetectionResult, onEject = onEject)
                RemountSection(remountState = state.remountState, usbDetectionResult = state.usbDetectionResult, onRequestRemount = onRequestRemount)
                if (state.tiles.isEmpty()) {
                    Text(
                        text = "No diagnostic archives found at HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "Select archives to export:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    ArchiveTileGrid(
                        tiles = state.tiles,
                        onToggle = onToggle,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = onStartTransfer,
                        enabled = canStart,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Begin")
                    }
                }
            }
        }
    }
}

@Composable
private fun UsbEjectSection(
    ejectState: UsbEjectState,
    usbDetectionResult: UsbDetectionResult,
    onEject: () -> Unit
) {
    if (usbDetectionResult !is UsbDetectionResult.Writable && usbDetectionResult !is UsbDetectionResult.ReadOnly) return
    when (ejectState) {
        is UsbEjectState.Idle -> OutlinedButton(
            onClick = onEject,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Eject USB safely")
        }
        is UsbEjectState.Ejecting -> OutlinedButton(
            onClick = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Ejecting...")
        }
        is UsbEjectState.Ejected -> Text(
            text = "USB ejected — safe to remove.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
        is UsbEjectState.Failed -> Text(
            text = "Eject failed: ${ejectState.message}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

// Explicit "Prepare USB for export" control for the ReadOnly case. Never remounts
// silently — tapping the button only opens the confirmation dialog (see
// ResponsiveReadyContent); the actual `mount -o remount,rw` call only runs after
// the user confirms.
@Composable
private fun RemountSection(
    remountState: RemountState,
    usbDetectionResult: UsbDetectionResult,
    onRequestRemount: () -> Unit
) {
    if (usbDetectionResult !is UsbDetectionResult.ReadOnly) return

    when (remountState) {
        RemountState.Idle, RemountState.ConfirmPending, RemountState.Success -> OutlinedButton(
            onClick = onRequestRemount,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Prepare USB for export")
        }
        RemountState.Remounting -> OutlinedButton(
            onClick = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Remounting...")
        }
        is RemountState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Remount failed: ${remountState.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            OutlinedButton(
                onClick = onRequestRemount,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Try again")
            }
        }
    }
}

@Composable
private fun UsbStatusText(usbDetectionResult: UsbDetectionResult) {
    when (usbDetectionResult) {
        is UsbDetectionResult.Writable -> Text(
            text = "USB: ${usbDetectionResult.mount.mountPath}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        is UsbDetectionResult.ReadOnly -> Text(
            text = "USB: ${usbDetectionResult.mount.mountPath} (read-only, cannot write)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        is UsbDetectionResult.MultipleWritableMounts -> Text(
            text = "Multiple writable USB sticks detected. Disconnect all but one and retry.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        is UsbDetectionResult.MultipleReadOnlyMounts -> Text(
            text = "Multiple read-only USB sticks detected. Disconnect all but one before preparing it for export.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        is UsbDetectionResult.UnsupportedMountLayout -> Text(
            text = "USB storage was found, but its mount layout is not supported.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        is UsbDetectionResult.QueryFailed -> Text(
            text = "Could not verify USB: ${usbDetectionResult.message ?: "ADB communication failed."}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        UsbDetectionResult.NotFound -> Text(
            text = "No USB stick detected.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun ArchiveTileGrid(
    tiles: List<ArchiveTileState>,
    onToggle: (TriggerArchive) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 80.dp),
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(tiles, key = { it.archive.displayLabel }) { tile ->
            ArchiveTile(tile = tile, onToggle = onToggle)
        }
    }
}

@Composable
private fun ArchiveTile(
    tile: ArchiveTileState,
    onToggle: (TriggerArchive) -> Unit
) {
    val containerColor = when {
        tile.isAlreadyOnUsb -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        tile.isSelected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surface
    }
    val contentColor = when {
        tile.isAlreadyOnUsb -> Grey
        tile.isSelected -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }

    Card(
        onClick = { if (!tile.isAlreadyOnUsb) onToggle(tile.archive) },
        enabled = !tile.isAlreadyOnUsb,
        modifier = Modifier
            .aspectRatio(1f)
            .semantics {
                contentDescription = tile.archive.displayLabel +
                        if (tile.isAlreadyOnUsb) ", Already on USB" else ""
            },
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = containerColor,
            disabledContentColor = contentColor
        )
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(4.dp)
            ) {
                Text(
                    text = tile.archive.displayLabel,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (tile.isAlreadyOnUsb) {
                    Text(
                        text = "On USB",
                        style = MaterialTheme.typography.labelSmall,
                        color = Grey
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Transferring content
// ---------------------------------------------------------------------------

@Composable
private fun ResponsiveTransferringContent(
    state: UsbCopyUiState.Transferring,
    isStopping: Boolean,
    onStop: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isLandscape = maxWidth > maxHeight

        if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                TransferTileGrid(
                    tiles = state.tiles,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
                Column(
                    modifier = Modifier
                        .weight(0.35f)
                        .fillMaxHeight()
                ) {
                    val selectedCount = state.progress.totalSelected
                    Text(
                        text = "$selectedCount selected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TransferProgressInfo(state = state)
                    Spacer(modifier = Modifier.weight(1f))
                    TransferStopButton(isStopping = isStopping, onStop = onStop)
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TransferProgressInfo(state = state)
                TransferStopButton(isStopping = isStopping, onStop = onStop)
                Spacer(modifier = Modifier.height(8.dp))
                TransferTileGrid(
                    tiles = state.tiles,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun TransferProgressInfo(state: UsbCopyUiState.Transferring) {
    val progress = state.progress
    val label = operationLabel(state.phase)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            // Indeterminate spinner: current-item work is ongoing
            CircularProgressIndicator(
                modifier = Modifier
                    .width(16.dp)
                    .height(16.dp),
                strokeWidth = 2.dp
            )
        }
        // Determinate bar: counts only fully completed items
        LinearProgressIndicator(
            progress = { progress.fraction },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "${progress.processed} / ${progress.totalSelected} (${progress.percentComplete}%)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TransferStopButton(isStopping: Boolean, onStop: () -> Unit) {
    if (isStopping) {
        OutlinedButton(
            onClick = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                disabledContentColor = MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
            )
        ) {
            Text("Stopping...")
        }
    } else {
        OutlinedButton(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Text("Stop copying")
        }
    }
}

@Composable
private fun TransferTileGrid(
    tiles: List<ArchiveTileState>,
    modifier: Modifier = Modifier
) {
    val visible = tiles.filter { it.isSelected || it.isAlreadyOnUsb }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 80.dp),
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(visible, key = { it.archive.displayLabel }) { tile ->
            TransferStatusTile(tile = tile)
        }
    }
}

@Composable
private fun TransferStatusTile(tile: ArchiveTileState) {
    val result = tile.result
    val (containerColor, label) = when {
        tile.isAlreadyOnUsb && result == null -> Grey.copy(alpha = 0.2f) to "On USB"
        result is TriggerTransferResult.Success -> Green.copy(alpha = 0.2f) to "Done"
        result is TriggerTransferResult.AlreadyPresent -> Grey.copy(alpha = 0.2f) to "Present"
        result is TriggerTransferResult.Failed -> MaterialTheme.colorScheme.errorContainer to "Failed"
        result is TriggerTransferResult.Cancelled -> Amber.copy(alpha = 0.2f) to "Cancelled"
        else -> MaterialTheme.colorScheme.surfaceVariant to "..."
    }

    Card(
        modifier = Modifier.aspectRatio(1f),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(4.dp)
            ) {
                Text(
                    text = tile.archive.displayLabel,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(text = label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Full-screen result overlay (covers top bar)
// ---------------------------------------------------------------------------

@Composable
private fun ResultOverlay(
    state: UsbCopyUiState.Completed,
    onDismiss: () -> Unit
) {
    val p = state.progress

    // Tap anywhere on the background dismisses the overlay.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClick = onDismiss)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 32.dp)
                // Intercept clicks inside the content column so they don't
                // bubble up and trigger a second dismiss via the outer Box.
                .clickable(enabled = false, onClick = {}),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = headline(p),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }

            // Export directory path when available.
            if (state.sessionDir != null) {
                item {
                    Text(
                        text = state.sessionDir,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // 5-category summary row.
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    SummaryItem("Copied", p.succeeded.toString(), Green, Modifier.weight(1f))
                    SummaryItem("Present", p.alreadyPresent.toString(), Grey, Modifier.weight(1f))
                    SummaryItem("Failed", p.failed.toString(), MaterialTheme.colorScheme.error, Modifier.weight(1f))
                    SummaryItem("Cancelled", p.cancelled.toString(), Amber, Modifier.weight(1f))
                    SummaryItem("Not started", p.notStarted.toString(), MaterialTheme.colorScheme.onSurfaceVariant, Modifier.weight(1f))
                }
            }

            item { HorizontalDivider(modifier = Modifier.fillMaxWidth()) }

            // One row per selected trigger (all selected tiles regardless of result).
            val resultTiles = state.tiles.filter { it.isSelected || it.isAlreadyOnUsb || it.result != null }
            items(resultTiles, key = { it.archive.stem }) { tile -> TileResultRow(tile = tile) }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(0.6f)
                ) {
                    Text("Done")
                }
            }
        }
    }
}

@Composable
private fun SummaryItem(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = color,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun TileResultRow(tile: ArchiveTileState) {
    val result = tile.result
    val (color, statusText, detailText) = when {
        tile.isAlreadyOnUsb && result == null ->
            Triple(Grey, "Already on USB", "No copy was required")
        result is TriggerTransferResult.Success -> {
            val detail = when (result.sourceType) {
                SourceType.EXISTING_EXTRACTED_DIRECTORY -> "Existing extracted folder copied"
                SourceType.ARCHIVE_EXTRACTED_TO_USB -> "Archive extracted to USB"
            }
            Triple(Green, "Successful", detail)
        }
        result is TriggerTransferResult.AlreadyPresent ->
            Triple(Grey, "Already on USB", "No copy was required")
        result is TriggerTransferResult.Failed ->
            Triple(MaterialTheme.colorScheme.error, "Failed", result.message ?: result.reason.name)
        result is TriggerTransferResult.Cancelled ->
            Triple(Amber, "Cancelled", "Incomplete output removed")
        else ->
            Triple(MaterialTheme.colorScheme.onSurfaceVariant, "Not started", "Copy process was stopped")
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = tile.archive.displayLabel,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.width(40.dp)
        )
        Column {
            Text(text = statusText, style = MaterialTheme.typography.bodyMedium, color = color)
            if (detailText.isNotBlank()) {
                Text(
                    text = detailText,
                    style = MaterialTheme.typography.bodySmall,
                    color = color.copy(alpha = 0.8f)
                )
            }
            if (tile.normalResult != null || tile.offlineResult != null) {
                Text(
                    text = "Normal: ${variantResultText(tile.normalResult)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Offline trace: ${variantResultText(tile.offlineResult)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
