package com.example.adb_connection.ui.capturetoolplaceholdercaptures

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import com.example.adb_connection.ui.components.DebugNavTarget
import com.example.adb_connection.ui.components.TraceMateTopBar
import com.example.adb_connection.ui.home.CaptureToolPlaceholderCopyState
import com.example.adb_connection.ui.home.CaptureToolPlaceholderDownloadState
import com.example.adb_connection.ui.home.CaptureToolPlaceholderUsbBridgeState
import com.example.adb_connection.ui.home.HomeViewModel
import com.example.adb_connection.ui.home.HomeViewModelFactory

@Composable
fun CaptureToolPlaceholderCapturesScreen(
    onNavigateBack: () -> Unit,
    debugNavTargets: List<DebugNavTarget> = emptyList()
) {
    val viewModel: HomeViewModel = viewModel(factory = HomeViewModelFactory(androidx.compose.ui.platform.LocalContext.current))
    val copyState by viewModel.captureToolPlaceholderCopyState.collectAsState()
    val downloadState by viewModel.captureToolPlaceholderDownloadState.collectAsState()
    val bridgeState by viewModel.captureToolPlaceholderUsbBridgeState.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadCaptureToolPlaceholderCaptureFiles() }

    Scaffold(
        topBar = {
            TraceMateTopBar(
                title = "Export CAPTURE_TOOL_PLACEHOLDER Captures",
                onBackClick = onNavigateBack,
                actions = {
                    val canRefresh = copyState !is CaptureToolPlaceholderCopyState.LoadingFiles &&
                            bridgeState !is CaptureToolPlaceholderUsbBridgeState.Running
                    IconButton(onClick = viewModel::loadCaptureToolPlaceholderCaptureFiles, enabled = canRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                debugNavTargets = debugNavTargets
            )
        },
        contentWindowInsets = WindowInsets(0)
    ) { padding ->
        when (val state = copyState) {
            CaptureToolPlaceholderCopyState.Idle, is CaptureToolPlaceholderCopyState.LoadingFiles -> LoadingContent(Modifier.padding(padding))
            is CaptureToolPlaceholderCopyState.Error -> ErrorContent(
                message = state.message,
                modifier = Modifier.padding(padding),
                onRetry = viewModel::loadCaptureToolPlaceholderCaptureFiles
            )
            is CaptureToolPlaceholderCopyState.SelectingFiles -> SelectionContent(
                state = state,
                downloadState = downloadState,
                bridgeState = bridgeState,
                modifier = Modifier.padding(padding),
                onToggle = viewModel::toggleCaptureToolPlaceholderCopySelection,
                onDownload = viewModel::downloadSelectedCaptureToolPlaceholderCapture,
                onExport = viewModel::bridgeSelectedCaptureToolPlaceholderCapturesToUsb,
                onCancelExport = viewModel::cancelCaptureToolPlaceholderUsbBridge
            )
        }
    }
}

@Composable
private fun LoadingContent(modifier: Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator()
            Text("Discovering CAPTURE_TOOL_PLACEHOLDER captures...", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ErrorContent(message: String, modifier: Modifier, onRetry: () -> Unit) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(message, color = MaterialTheme.colorScheme.error)
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Retry") }
    }
}

@Composable
private fun SelectionContent(
    state: CaptureToolPlaceholderCopyState.SelectingFiles,
    downloadState: CaptureToolPlaceholderDownloadState,
    bridgeState: CaptureToolPlaceholderUsbBridgeState,
    modifier: Modifier,
    onToggle: (String) -> Unit,
    onDownload: () -> Unit,
    onExport: () -> Unit,
    onCancelExport: () -> Unit
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isLandscape = maxWidth > maxHeight
        val isExporting = bridgeState is CaptureToolPlaceholderUsbBridgeState.Running
        val selectedCount = state.selected.size
        val controls: @Composable () -> Unit = {
            Text("$selectedCount selected", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            CaptureToolPlaceholderTransferStatus(bridgeState, downloadState)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onDownload, enabled = selectedCount == 1 && downloadState !is CaptureToolPlaceholderDownloadState.Downloading, modifier = Modifier.fillMaxWidth()) {
                Text("Download to Android")
            }
            Button(onClick = onExport, enabled = selectedCount > 0 && !isExporting, modifier = Modifier.fillMaxWidth()) {
                Text("Export to USB")
            }
            if (isExporting) {
                OutlinedButton(onClick = onCancelExport, modifier = Modifier.fillMaxWidth()) { Text("Cancel export") }
            }
        }

        if (isLandscape) {
            Row(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CaptureTileGrid(state.files, state.selected, onToggle, Modifier.weight(1f).fillMaxHeight())
                Column(modifier = Modifier.weight(0.35f).fillMaxHeight()) { controls() }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text("Select captures to export:", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                CaptureTileGrid(state.files, state.selected, onToggle, Modifier.weight(1f))
                Spacer(Modifier.height(8.dp))
                controls()
            }
        }
    }
}

@Composable
private fun CaptureTileGrid(files: List<String>, selected: Set<String>, onToggle: (String) -> Unit, modifier: Modifier) {
    LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 80.dp), modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(files, key = { it }) { path ->
            val isSelected = path in selected
            Card(
                onClick = { onToggle(path) },
                modifier = Modifier.aspectRatio(1f),
                colors = CardDefaults.cardColors(containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface, contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
            ) {
                Box(Modifier.fillMaxSize().padding(4.dp), contentAlignment = Alignment.Center) {
                    Text(path.substringAfterLast('/'), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun CaptureToolPlaceholderTransferStatus(bridgeState: CaptureToolPlaceholderUsbBridgeState, downloadState: CaptureToolPlaceholderDownloadState) {
    when {
        bridgeState is CaptureToolPlaceholderUsbBridgeState.Running -> Text("Exporting ${bridgeState.state.fileName ?: "capture"}...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        bridgeState is CaptureToolPlaceholderUsbBridgeState.Success -> Text("CAPTURE_TOOL_PLACEHOLDER captures exported to USB.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        bridgeState is CaptureToolPlaceholderUsbBridgeState.Error -> Text("Export failed: ${bridgeState.message}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        bridgeState == CaptureToolPlaceholderUsbBridgeState.Cancelled -> Text("Export cancelled.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        downloadState is CaptureToolPlaceholderDownloadState.Downloading -> Text("Downloading ${downloadState.fileName}...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        downloadState is CaptureToolPlaceholderDownloadState.Success -> Text("Download ready: ${downloadState.file}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        downloadState is CaptureToolPlaceholderDownloadState.Error -> Text("Download failed: ${downloadState.message}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        else -> Unit
    }
}
