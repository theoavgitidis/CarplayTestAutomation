package com.example.adb_connection.ui.usbfiles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.adb_connection.domain.model.UsbFileEntry
import com.example.adb_connection.domain.model.UsbFileType
import com.example.adb_connection.ui.components.DebugNavTarget
import com.example.adb_connection.ui.components.TraceMateTopBar

@Composable
fun UsbFilesScreen(
    onNavigateBack: () -> Unit,
    debugNavTargets: List<DebugNavTarget> = emptyList()
) {
    val exportDirectory = "HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER"
    val captureToolPlaceholderDirectory = "CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER"
    val context = LocalContext.current
    val viewModel: UsbFilesViewModel = viewModel(factory = UsbFilesViewModelFactory(context))
    val uiState by viewModel.uiState.collectAsState()
    val currentDirectory by viewModel.currentDirectory.collectAsState()

    var showDeleteConfirm by remember { mutableStateOf(false) }
    val canDelete = uiState is UsbFilesUiState.Loaded &&
        (currentDirectory == exportDirectory || currentDirectory.startsWith("$exportDirectory/"))

    // BackHandler handles in-browser navigation before handing off to the screen back
    BackHandler(enabled = currentDirectory.isNotEmpty()) {
        viewModel.navigateUp()
    }

    val handleBack: () -> Unit = {
        if (!viewModel.navigateUp()) {
            onNavigateBack()
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete all files?") },
            text = { Text("This permanently removes the managed export directory from the USB stick. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.deleteAllFiles()
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TraceMateTopBar(
                title = "Files on USB",
                onBackClick = handleBack,
                debugNavTargets = debugNavTargets,
                actions = {
                    val canRefresh = uiState !is UsbFilesUiState.Loading &&
                            uiState !is UsbFilesUiState.Refreshing &&
                            uiState !is UsbFilesUiState.Deleting
                    IconButton(onClick = viewModel::refresh, enabled = canRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { showDeleteConfirm = true }, enabled = canDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete all")
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
                is UsbFilesUiState.Loading -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "Detecting USB…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                is UsbFilesUiState.Refreshing -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "Refreshing…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                is UsbFilesUiState.Deleting -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Deleting all files…",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        }
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.7f))
                    }
                }

                is UsbFilesUiState.UsbNotConnected -> {
                    EmptyMessage(
                        modifier = Modifier.align(Alignment.Center),
                        text = "No USB stick detected."
                    )
                }

                is UsbFilesUiState.TraceMateDirectoryAbsent -> {
                    EmptyMessage(
                        modifier = Modifier.align(Alignment.Center),
                        text = "USB connected (${state.mount.mountPath})\nNo $exportDirectory or $captureToolPlaceholderDirectory directory found."
                    )
                }

                is UsbFilesUiState.NoExports -> {
                    EmptyMessage(
                        modifier = Modifier.align(Alignment.Center),
                        text = "USB connected (${state.mount.mountPath})\nNo exported files found."
                    )
                }

                is UsbFilesUiState.UsbRemovedDuringLoad -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = "USB removed during listing — showing partial results.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        FileList(entries = state.partialEntries, onDirectoryClick = viewModel::openDirectory)
                    }
                }

                is UsbFilesUiState.Loaded -> {
                        val displayedPath = if (currentDirectory.isEmpty()) "USB files"
                                        else currentDirectory
                    Column(modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = displayedPath,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                        HorizontalDivider()
                        FileList(entries = state.entries, onDirectoryClick = viewModel::openDirectory)
                    }
                }

                is UsbFilesUiState.AdbError -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "ADB error",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                is UsbFilesUiState.DeleteError -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Delete failed",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(onClick = viewModel::refresh) { Text("Retry") }
                    }
                }

                is UsbFilesUiState.ListingTimedOut -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "USB listing timed out",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(onClick = viewModel::refresh) { Text("Retry") }
                    }
                }

                is UsbFilesUiState.CopyActive -> {
                    EmptyMessage(
                        modifier = Modifier.align(Alignment.Center),
                        text = "A USB transfer is in progress.\nFile listing is unavailable until it completes."
                    )
                }
            }
        }
    }
}

@Composable
private fun FileList(
    entries: List<UsbFileEntry>,
    onDirectoryClick: (UsbFileEntry) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(entries, key = { it.relativePath }) { entry ->
            val isManagedDirectory = entry.type == UsbFileType.DIRECTORY && isManagedDirectory(entry.relativePath)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (isManagedDirectory) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface
                    )
                    .clickable(enabled = entry.type == UsbFileType.DIRECTORY) {
                        onDirectoryClick(entry)
                    }
                    .padding(0.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BoxWithConstraints(modifier = Modifier.weight(1f)) {
                    val isLandscape = maxWidth > 480.dp
                    if (isLandscape) {
                        FileRowLandscape(entry = entry)
                    } else {
                        FileRowPortrait(entry = entry)
                    }
                }
                if (entry.type == UsbFileType.DIRECTORY) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Open folder",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun FileRowPortrait(entry: UsbFileEntry) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = typeLabel(entry.type),
                style = MaterialTheme.typography.labelSmall,
                color = typeColor(entry.type),
                modifier = Modifier.width(32.dp)
            )
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (entry.type == UsbFileType.FILE && entry.sizeBytes != null) {
                Text(
                    text = formatSize(entry.sizeBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (entry.relativePath != entry.name) {
            Text(
                text = entry.relativePath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 40.dp)
            )
        }
    }
}

@Composable
private fun FileRowLandscape(entry: UsbFileEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = typeLabel(entry.type),
            style = MaterialTheme.typography.labelSmall,
            color = typeColor(entry.type),
            modifier = Modifier.width(32.dp)
        )
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.3f)
        )
        Text(
            text = entry.relativePath,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.5f)
        )
        Text(
            text = if (entry.type == UsbFileType.FILE && entry.sizeBytes != null)
                formatSize(entry.sizeBytes) else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
            maxLines = 1
        )
    }
}

@Composable
private fun EmptyMessage(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(24.dp)
    )
}

@Composable
private fun typeColor(type: UsbFileType) = when (type) {
    UsbFileType.DIRECTORY -> MaterialTheme.colorScheme.primary
    UsbFileType.FILE -> MaterialTheme.colorScheme.onSurface
}

private fun typeLabel(type: UsbFileType) = when (type) {
    UsbFileType.DIRECTORY -> "DIR"
    UsbFileType.FILE -> "FILE"
}

private fun isManagedDirectory(relativePath: String): Boolean =
    relativePath == "HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER" ||
        relativePath == "CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER"

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024L -> "%.1f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}
