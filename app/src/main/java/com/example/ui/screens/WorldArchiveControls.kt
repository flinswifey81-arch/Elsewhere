package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.model.WorldEntity
import com.example.domain.world.WorldArchiveTransferCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Explicit two-step export/import UI, enabled only in the separate Elsewhere Test build.
 *
 * File selection is not an authorization to import. The preview is read-only; the user must
 * enter the passphrase again and accept creation of a NEW World before any Room write.
 * No secret is persisted via rememberSaveable or app preferences.
 */
@Composable
fun WorldArchiveControls(
    transfer: WorldArchiveTransferCoordinator,
    world: WorldEntity?,
    onImported: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var exportPreview by remember { mutableStateOf<WorldArchiveTransferCoordinator.ExportPreview?>(null) }
    var pendingExport by remember { mutableStateOf<WorldArchiveTransferCoordinator.ExportPreview?>(null) }
    var includePrivate by remember { mutableStateOf(false) }
    var exportConfirmed by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf("") }
    var exportPasswordRepeat by remember { mutableStateOf("") }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    var importPassword by remember { mutableStateOf("") }
    var importConfirmed by remember { mutableStateOf(false) }
    var candidate by remember { mutableStateOf<WorldArchiveTransferCoordinator.ImportCandidate?>(null) }

    DisposableEffect(candidate) {
        onDispose { candidate?.close() }
    }

    fun clearExport() {
        exportPreview = null
        pendingExport = null
        includePrivate = false
        exportConfirmed = false
        exportPassword = ""
        exportPasswordRepeat = ""
    }

    fun clearImport() {
        importUri = null
        importPassword = ""
        importConfirmed = false
        candidate?.close()
        candidate = null
    }

    val createFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val reviewed = pendingExport
        pendingExport = null
        val password = exportPassword.toCharArray()
        val consent = includePrivate
        exportPassword = ""
        exportPasswordRepeat = ""
        exportPreview = null
        exportConfirmed = false
        includePrivate = false

        if (uri == null || reviewed == null) {
            password.fill('\u0000')
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            busy = true
            error = null
            try {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri, "w")
                        ?: error("Unable to open the selected World export destination")
                    output.use { stream ->
                        transfer.exportReviewed(reviewed, stream, password, consent)
                    }
                }
                notice = "Encrypted World saved. Keep the passphrase: Elsewhere cannot recover it."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = "Export failed: ${failure.message ?: "Unknown error"}. The selected file may be incomplete; delete it before trying again."
            } finally {
                password.fill('\u0000')
                busy = false
            }
        }
    }

    val openFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            clearImport()
            error = null
            importUri = uri
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Encrypted World archives · Elsewhere Test",
            style = MaterialTheme.typography.titleSmall)
        Text(
            "Experimental: exports attached chats and variants with their World. " +
                "Import creates a separate World; it never merges with an existing one.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (world != null) {
                OutlinedButton(
                    enabled = !busy,
                    modifier = Modifier.testTag("world_archive_export"),
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            try {
                                exportPreview = transfer.previewExport(world.worldId)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                error = failure.message ?: "This World cannot be exported yet."
                            } finally {
                                busy = false
                            }
                        }
                    }
                ) { Text("Export encrypted") }
            }
            OutlinedButton(
                enabled = !busy,
                modifier = Modifier.testTag("world_archive_open"),
                onClick = {
                    error = null
                    openFile.launch(arrayOf("application/octet-stream", "*/*"))
                }
            ) { Text("Inspect / import") }
        }
        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.width(24.dp))
                Spacer(Modifier.width(8.dp))
                Text("Validating World data…", style = MaterialTheme.typography.bodySmall)
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("world_archive_error")) }
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }

    exportPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { if (!busy) clearExport() },
            title = { Text("Export encrypted World") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(preview.title, style = MaterialTheme.typography.titleMedium)
                    Text("${preview.chats} chats · ${preview.messages} messages · " +
                        "${preview.alternatives} alternate variants · ${preview.attachmentCount} attachments")
                    Text("${preview.characters} Characters · ${preview.personas} Personas · " +
                        "${preview.scenarios} Scenarios · ${preview.npcDossiers} NPC dossiers")
                    if (preview.containsPrivateMaterial || preview.containsUnsentDrafts) {
                        Text(
                            "Includes private notes, NPC secrets or unsent drafts. Only include " +
                                "these in an encrypted file you control.",
                            color = MaterialTheme.colorScheme.error
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = includePrivate, onCheckedChange = { includePrivate = it },
                                enabled = !busy
                            )
                            Text("Include the private notes, secrets and drafts")
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = exportConfirmed, onCheckedChange = { exportConfirmed = it },
                            enabled = !busy
                        )
                        Text("I reviewed this World and want an encrypted copy of its RP history")
                    }
                    OutlinedTextField(
                        value = exportPassword, onValueChange = { exportPassword = it },
                        label = { Text("Archive passphrase (10+ characters)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true, enabled = !busy
                    )
                    OutlinedTextField(
                        value = exportPasswordRepeat, onValueChange = { exportPasswordRepeat = it },
                        label = { Text("Repeat passphrase") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true, enabled = !busy
                    )
                    Text(
                        "Keep Elsewhere Test open during export. If the World changes, " +
                            "export will stop rather than save an out-of-date preview.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                Button(
                    modifier = Modifier.testTag("world_archive_export_confirm"),
                    enabled = !busy && exportConfirmed &&
                        (!preview.containsPrivateMaterial && !preview.containsUnsentDrafts || includePrivate) &&
                        exportPassword.length >= 10 && exportPassword == exportPasswordRepeat,
                    onClick = {
                        pendingExport = preview
                        createFile.launch(
                            preview.title.replace(Regex("[^A-Za-z0-9_-]"), "_")
                                .take(70) + ".elsewhere-world"
                        )
                    }
                ) { Text("Choose save location") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { clearExport() }) { Text("Cancel") }
            }
        )
    }

    if (importUri != null && candidate == null) {
        AlertDialog(
            onDismissRequest = { if (!busy) clearImport() },
            title = { Text("Unlock World for inspection") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Nothing will be imported yet. Elsewhere will verify the encrypted " +
                            "file and display its contents before you decide.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = importPassword, onValueChange = { importPassword = it },
                        label = { Text("Archive passphrase") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true, enabled = !busy
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = !busy && importPassword.length >= 10,
                    modifier = Modifier.testTag("world_archive_inspect_confirm"),
                    onClick = {
                        val uri = importUri ?: return@Button
                        val password = importPassword.toCharArray()
                        importPassword = ""
                        busy = true
                        scope.launch {
                            try {
                                val inspected = withContext(Dispatchers.IO) {
                                    val input = context.contentResolver.openInputStream(uri)
                                        ?: error("Selected archive cannot be opened")
                                    transfer.inspectForImport(input, password)
                                }
                                candidate?.close()
                                candidate = inspected
                                importConfirmed = false
                                error = null
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                error = failure.message ?: "World archive inspection failed."
                            } finally {
                                password.fill('\u0000')
                                busy = false
                            }
                        }
                    }
                ) { Text("Inspect only") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { clearImport() }) { Text("Cancel") }
            }
        )
    }

    candidate?.let { inspected ->
        val preview = inspected.preview
        AlertDialog(
            onDismissRequest = { if (!busy) clearImport() },
            title = { Text("Import as NEW World?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(preview.worldTitle, style = MaterialTheme.typography.titleMedium)
                    Text("${preview.chats} chats · ${preview.messages} messages · " +
                        "${preview.alternativeVariants} alternate variants")
                    Text("${preview.characters} Characters · ${preview.personas} Personas · " +
                        "${preview.scenarios} Scenarios · ${preview.npcRevisions} NPC revisions")
                    Text("${preview.messageAttachments} chat images · " +
                        "${preview.otherImageFiles} profile images")
                    if (preview.containsUnsentDrafts || preview.containsPrivateNotes ||
                        preview.containsNpcSecrets) {
                        Text(
                            "This archive contains drafts, private notes, or NPC secrets. " +
                                "They will be restored into this device's private storage.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        "An imported copy will have separate identities. Existing Worlds, " +
                            "Characters, Personas and chats will not be merged or replaced.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = importConfirmed, onCheckedChange = { importConfirmed = it },
                            enabled = !busy
                        )
                        Text("Create a separate World with these histories")
                    }
                    OutlinedTextField(
                        value = importPassword, onValueChange = { importPassword = it },
                        label = { Text("Re-enter passphrase to import") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true, enabled = !busy
                    )
                    Text("Keep Elsewhere Test open while importing.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(
                    modifier = Modifier.testTag("world_archive_import_confirm"),
                    enabled = !busy && importConfirmed && importPassword.length >= 10,
                    onClick = {
                        val password = importPassword.toCharArray()
                        importPassword = ""
                        busy = true
                        scope.launch {
                            try {
                                val result = transfer.importReviewed(inspected, password)
                                notice = "Imported new World with ${result.chats} chats and " +
                                    "${result.messages} messages."
                                clearImport()
                                error = null
                                onImported(result.worldId)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                error = failure.message ?: "World archive import failed."
                            } finally {
                                password.fill('\u0000')
                                busy = false
                            }
                        }
                    }
                ) { Text("Import NEW World") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { clearImport() }) { Text("Cancel") }
            }
        )
    }
}
