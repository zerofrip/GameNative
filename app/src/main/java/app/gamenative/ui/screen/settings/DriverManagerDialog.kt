package app.gamenative.ui.screen.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import app.gamenative.ui.component.NoExtractOutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import app.gamenative.R
import app.gamenative.ui.theme.settingsTileColors
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsMenuLink
import app.gamenative.utils.DriverZipMetaPeek
import app.gamenative.utils.ManifestContentTypes
import app.gamenative.utils.ManifestEntry
import app.gamenative.utils.ManifestInstaller
import app.gamenative.utils.ManifestRepository
import com.winlator.contents.AdrenotoolsManager
import com.winlator.contents.PanVkDriverManager
import java.io.File
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ArrowDropDown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.material3.Surface
import app.gamenative.ui.theme.PluviaTheme
import android.content.res.Configuration
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.service.SteamService
import app.gamenative.ui.component.dialog.LoadingDialog
import java.io.IOException
import timber.log.Timber
import java.net.SocketTimeoutException


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverManagerDialog(open: Boolean, onDismiss: () -> Unit) {
    if (!open) return
    val ctx = LocalContext.current
    var lastMessage by remember { mutableStateOf<String?>(null) }
    var isImporting by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var isInstalling by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var downloadBytes by remember { mutableStateOf(0L) }
    var totalBytes by remember { mutableStateOf(-1L) }
    val scope = rememberCoroutineScope()

    // Driver manifest handling
    var driverManifest by remember { mutableStateOf<List<ManifestEntry>>(emptyList()) }
    var isLoadingManifest by remember { mutableStateOf(true) }
    var manifestError by remember { mutableStateOf<String?>(null) }

    // Dropdown state
    var isExpanded by remember { mutableStateOf(false) }
    var selectedDriverKey by remember { mutableStateOf("") }

    // Gather installed custom drivers via AdrenotoolsManager and allow refreshing
    val installedDrivers = remember { mutableStateListOf<String>() }
    val driverMeta = remember { mutableStateMapOf<String, Pair<String, String>>() }
    var driverToDelete by remember { mutableStateOf<String?>(null) }

    val installedPanvkDrivers = remember { mutableStateListOf<String>() }
    val panvkMeta = remember { mutableStateMapOf<String, Pair<String, String>>() }

    val refreshDriverList: () -> Unit = {
        installedDrivers.clear()
        driverMeta.clear()
        installedPanvkDrivers.clear()
        panvkMeta.clear()
        try {
            val list = AdrenotoolsManager(ctx).enumarateInstalledDrivers()
            installedDrivers.addAll(list)
            val mgr = AdrenotoolsManager(ctx)
            list.forEach { id ->
                val name = mgr.getDriverName(id)
                val version = mgr.getDriverVersion(id)
                driverMeta[id] = name to version
            }
            val panList = PanVkDriverManager(ctx).enumerateInstalledDrivers()
            installedPanvkDrivers.addAll(panList)
            val pvm = PanVkDriverManager(ctx)
            panList.forEach { id ->
                panvkMeta[id] = pvm.getDriverName(id) to pvm.getDriverVersion(id)
            }
        } catch (_: Exception) {}
    }

    // Load driver manifest from the remote URL
    LaunchedEffect(Unit) {
        refreshDriverList()

        // Fetch the driver manifest
        Timber.d("DriverManagerDialog: Fetching driver manifest...")
        scope.launch(Dispatchers.IO) {
            try {
                val manifest = ManifestRepository.loadManifest(ctx)
                val entries = manifest.items[ManifestContentTypes.DRIVER]
                    .orEmpty()
                    .filter { it.url.isNotBlank() }
                    .sortedBy { it.id.lowercase() }
                withContext(Dispatchers.Main) {
                    driverManifest = entries
                    isLoadingManifest = false
                    if (entries.isEmpty()) {
                        manifestError = ctx.getString(R.string.driver_error_loading, "No drivers found in manifest")
                    }
                }
                Timber.d("DriverManagerDialog: Manifest loaded with ${entries.size} driver entries")
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    manifestError = ctx.getString(R.string.driver_error_loading, e.message ?: "")
                    isLoadingManifest = false
                }
                Timber.e(e, "DriverManagerDialog: Error loading driver manifest")
            }
        }
    }

    LoadingDialog(
        visible = isDownloading,
        progress = downloadProgress,
        message = stringResource(R.string.downloading),
    )

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            scope.launch {
                isImporting = true
                val res = withContext(Dispatchers.IO) { handlePickedUri(ctx, it) }
                lastMessage = res
                if (res.startsWith("Installed driver:")) refreshDriverList()
                SnackbarManager.show(res)
                SteamService.isImporting = false
                isImporting = false
            }
        }
    }

    // Function to download and install a driver from URL
    val downloadAndInstallDriver = { entry: ManifestEntry ->
        scope.launch {
            val overallStart = System.currentTimeMillis()
            isDownloading = true
            downloadProgress = 0f
            downloadBytes = 0L
            totalBytes = -1L
            try {
                Timber.d("DriverManagerDialog: Starting install from ${entry.url}")
                var lastUpdate = 0L
                val result = ManifestInstaller.downloadAndInstallDriver(
                    context = ctx,
                    entry = entry,
                ) { progress ->
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate > 300) {
                        lastUpdate = now
                        val clamped = progress.coerceIn(0f, 1f)
                        scope.launch(Dispatchers.Main) { downloadProgress = clamped }
                    }
                }
                val durationMs = System.currentTimeMillis() - overallStart
                withContext(Dispatchers.Main) {
                    lastMessage = result.message
                    if (result.success) refreshDriverList()
                    SnackbarManager.show(result.message)
                }
                Timber.d("DriverManagerDialog: Download+Install finished in ${durationMs}ms")
            } catch (e: SocketTimeoutException) {
                val errorMessage = ctx.getString(R.string.driver_timeout)
                lastMessage = errorMessage
                SnackbarManager.show(errorMessage)
                Timber.e(e, "DriverManagerDialog: Download timeout")
            } catch (e: IOException) {
                val errorMessage = if (e.message?.contains("timeout", ignoreCase = true) == true) {
                    ctx.getString(R.string.driver_timeout)
                } else {
                    ctx.getString(R.string.driver_network_error, e.message ?: "")
                }
                lastMessage = errorMessage
                SnackbarManager.show(errorMessage)
                Timber.e(e, "DriverManagerDialog: Download failed with IO error")
            } catch (e: Exception) {
                val errorMessage = "Error downloading driver: ${e.message}"
                lastMessage = errorMessage
                SnackbarManager.show(errorMessage)
                Timber.e(e, "DriverManagerDialog: Download failed")
            } finally {
                isDownloading = false
                isInstalling = false
                downloadProgress = 0f
                downloadBytes = 0L
                totalBytes = -1L
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.driver_manager), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 450.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Import a custom graphics driver package",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                if (installedPanvkDrivers.isNotEmpty()) {
                    Text(
                        text = "Installed PanVK (manifest / Mali): ${installedPanvkDrivers.joinToString()}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }

                // Online driver selection
                if (isLoadingManifest) {
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        Text(
                            text = "Loading available drivers...",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        CircularProgressIndicator(
                            modifier = Modifier.height(20.dp),
                            strokeWidth = 2.dp
                        )
                    }
                } else if (manifestError != null) {
                    Text(
                        text = manifestError ?: "Unknown error",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else if (driverManifest.isNotEmpty()) {
                    Text(
                        text = "Available online drivers:",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )

                    ExposedDropdownMenuBox(
                        expanded = isExpanded,
                        onExpandedChange = { isExpanded = !isExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        NoExtractOutlinedTextField(
                            value = selectedDriverKey,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isExpanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            placeholder = { Text(stringResource(R.string.select_a_driver)) }
                        )

                        ExposedDropdownMenu(
                            expanded = isExpanded,
                            onDismissRequest = { isExpanded = false }
                        ) {
                            driverManifest.forEach { driverEntry ->
                                DropdownMenuItem(
                                    text = { Text(driverEntry.id) },
                                    onClick = {
                                        selectedDriverKey = driverEntry.id
                                        isExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    val selectedEntry = driverManifest.firstOrNull { it.id == selectedDriverKey }
                    if (selectedEntry != null) {
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 16.dp)
                        ) {
                            Button(
                                onClick = { downloadAndInstallDriver(selectedEntry) },
                                enabled = !isDownloading && !isImporting
                            ) {
                                Text(stringResource(R.string.download))
                            }

                            if (isDownloading) {
                                if (totalBytes > 0) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        LinearProgressIndicator(progress = downloadProgress)
                                        Row(
                                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 4.dp)
                                        ) {
                                            Text(
                                                text = "${formatBytes(downloadBytes)} / ${formatBytes(totalBytes)}"
                                            )
                                        }
                                    }
                                } else {
                                    Column(modifier = Modifier.weight(1f)) {
                                        LinearProgressIndicator() // indeterminate when total unknown
                                        Row(
                                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 4.dp)
                                        ) {
                                            Text(text = stringResource(R.string.downloading))
                                        }
                                    }
                                }
                            }
                            if (isInstalling) {
                                Row(
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.height(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Text(text = stringResource(R.string.installing))
                                }
                            }
                        }
                    }
                }

                Divider(modifier = Modifier.padding(vertical = 16.dp))

                // Local driver import section
                Text(
                    text = "Import from local storage:",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Button(
                    onClick = {
                        SteamService.isImporting = true
                        launcher.launch(arrayOf("application/zip", "application/x-zip-compressed"))
                    },
                    enabled = !isImporting && !isDownloading,
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    Text(stringResource(R.string.import_zip_from_device))
                }

                if (isImporting) {
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Text(
                            text = "Importing driver...",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        CircularProgressIndicator(
                            modifier = Modifier.height(20.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }

                if (installedDrivers.isNotEmpty()) {
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        text = "Installed custom drivers",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        for (id in installedDrivers) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                val meta = driverMeta[id]
                                val display = buildString {
                                    if (!meta?.first.isNullOrEmpty()) append(meta?.first) else append(id)
                                }
                                Text(
                                    text = display,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                IconButton(
                                    onClick = { driverToDelete = id },
                                    modifier = Modifier.padding(start = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = "Delete",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                    // Confirmation dialog for deletion
                    driverToDelete?.let { id ->
                        AlertDialog(
                            onDismissRequest = { driverToDelete = null },
                            title = { Text(text = stringResource(R.string.confirm_delete)) },
                            text = { Text(text = stringResource(R.string.remove_driver_confirmation, id)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    try {
                                        AdrenotoolsManager(ctx).removeDriver(id)
                                        lastMessage = "Removed driver: $id"
                                        SnackbarManager.show("Removed driver: $id")
                                        refreshDriverList()
                                    } catch (e: Exception) {
                                        lastMessage = "Error removing $id: ${e.message}"
                                        SnackbarManager.show("Error removing $id: ${e.message}")
                                    }
                                    driverToDelete = null
                                }) {
                                    Text(
                                        text = "Delete",
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { driverToDelete = null }) {
                                    Text(stringResource(R.string.cancel))
                                }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "Close",
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
    )
}

private fun handlePickedUri(context: Context, uri: Uri): String {
    return try {
        val stack = DriverZipMetaPeek.peekDriverStack(context, uri)?.lowercase()
        val name = when (stack) {
            "panvk" -> PanVkDriverManager(context).installDriver(uri)
            "adrenotools" -> AdrenotoolsManager(context).installDriver(uri)
            null, "" -> {
                val a = AdrenotoolsManager(context).installDriver(uri)
                if (a.isNotEmpty()) a else PanVkDriverManager(context).installDriver(uri)
            }
            else -> {
                var n = AdrenotoolsManager(context).installDriver(uri)
                if (n.isEmpty()) n = PanVkDriverManager(context).installDriver(uri)
                n
            }
        }
        if (name.isNotEmpty()) {
            "Installed driver: $name"
        } else {
            "Failed to install driver: driver already installed or .zip corrupted"
        }
    } catch (e: Exception) {
        "Error importing driver: ${e.message}"
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes} B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format("%.2f GB", gb)
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview
@Composable
private fun Preview_DriverManagerDialog() {
    PluviaTheme {
        Surface {
            DriverManagerDialog(open = true, onDismiss = { })
        }
    }
}
