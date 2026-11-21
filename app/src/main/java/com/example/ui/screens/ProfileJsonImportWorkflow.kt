package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.domain.repository.DuplicateIdException
import kotlinx.coroutines.launch

/**
 * Same-ID import is a USER decision for every file, not an implicit OR REPLACE.
 * All persistence is handled by the underlying repository and its mutation gate.
 */
@Composable
internal fun ProfileJsonImportWorkflow(
    type: String,
    showOptions: Boolean,
    onCloseOptions: () -> Unit,
    importProfile: suspend (json: String, replaceExisting: Boolean, importAsNew: Boolean) -> String,
    findExistingName: suspend (id: String) -> String?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showPaste by remember { mutableStateOf(false) }
    var pastedJson by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var summary by remember { mutableStateOf<BatchImportSummary?>(null) }
    var waitingForReview by remember { mutableStateOf<List<PendingSameIdJson>>(emptyList()) }
    var completed by remember { mutableStateOf<List<BatchImportResult>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    fun finishOne(result: BatchImportResult) {
        val allResults = completed + result
        val remaining = waitingForReview.drop(1)
        completed = allResults
        waitingForReview = remaining
        if (remaining.isEmpty()) {
            summary = BatchImportSummary(allResults)
            completed = emptyList()
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty() && !busy && waitingForReview.isEmpty()) {
            scope.launch {
                busy = true
                try {
                    val staged = stageDuplicateJsonDocuments(context, uris) { json ->
                        importProfile(json, false, false)
                    }
                    completed = staged.completed
                    waitingForReview = staged.duplicates
                    if (staged.duplicates.isEmpty()) {
                        summary = BatchImportSummary(staged.completed)
                        completed = emptyList()
                    }
                } catch (failure: Exception) {
                    error = failure.message ?: "Could not import the selected files."
                } finally {
                    busy = false
                }
            }
        }
    }

    if (showOptions) {
        AlertDialog(
            onDismissRequest = onCloseOptions,
            title = { Text("Import $type") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onCloseOptions()
                            launcher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Choose JSON Files") }
                    OutlinedButton(
                        onClick = { onCloseOptions(); showPaste = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Paste JSON") }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onCloseOptions) { Text("Cancel") }
            }
        )
    }

    if (showPaste) {
        AlertDialog(
            onDismissRequest = { if (!busy) showPaste = false },
            title = { Text("Paste $type JSON") },
            text = {
                OutlinedTextField(
                    value = pastedJson, onValueChange = { pastedJson = it },
                    label = { Text("JSON Content") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 320.dp),
                    maxLines = 12,
                    enabled = !busy
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && pastedJson.isNotBlank(),
                    onClick = {
                        if (!busy) scope.launch {
                            busy = true
                            try {
                                importProfile(pastedJson, false, false)
                                showPaste = false
                                pastedJson = ""
                                summary = BatchImportSummary(
                                    listOf(BatchImportResult("Pasted JSON"))
                                )
                            } catch (duplicate: DuplicateIdException) {
                                // Close the editor only AFTER staging the user's original
                                // JSON, which will be reused unchanged on confirmation.
                                waitingForReview = listOf(
                                    PendingSameIdJson("Pasted JSON", pastedJson, duplicate.id)
                                )
                                completed = emptyList()
                                showPaste = false
                            } catch (failure: Exception) {
                                error = failure.message ?: "JSON import failed."
                            } finally {
                                busy = false
                            }
                        }
                    }
                ) { Text(if (busy) "Importing…" else "Import") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { showPaste = false }) { Text("Cancel") }
            }
        )
    }

    val pending = waitingForReview.firstOrNull()
    var existingName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pending?.id) {
        existingName = pending?.let {
            runCatching { findExistingName(it.id) }.getOrNull()
        }
    }
    pending?.let { item ->
        AlertDialog(
            onDismissRequest = {
                if (!busy) finishOne(BatchImportResult(item.fileName, skipped = true))
            },
            title = { Text("$type ID already exists") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("File: ${item.fileName}")
                    Text("Existing $type: ${existingName ?: "(existing saved card)"}")
                    Text("Matching ID: ${item.id}")
                    Text(
                        "Replace ONLY this $type card's JSON definition? Its ID, " +
                            "chat associations, existing messages, story memories, " +
                            "and local avatar will be kept. The new card text will " +
                            "be used for future responses. Existing messages are not rewritten. " +
                            "The previous card wording is replaced, so export the old JSON first " +
                            "if you might want to revert it."
                    )
                    Text(
                        "Each duplicate is reviewed separately. Nothing is overwritten " +
                            "without choosing Replace."
                    )
                }
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("confirm_replace_${type.lowercase()}_json"),
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                importProfile(item.json, true, false)
                                finishOne(BatchImportResult(item.fileName, updated = true))
                            } catch (failure: Exception) {
                                // Never reinsert as a new card on a stale confirmation.
                                error = failure.message ?: "The original card could not be updated."
                            } finally {
                                busy = false
                            }
                        }
                    }
                ) { Text(if (busy) "Replacing…" else "Replace $type JSON") }
            },
            dismissButton = {
                Column {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                try {
                                    importProfile(item.json, false, true)
                                    finishOne(BatchImportResult(item.fileName))
                                } catch (failure: Exception) {
                                    error = failure.message ?: "Could not create a separate copy."
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    ) { Text("Import separate copy") }
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            finishOne(BatchImportResult(item.fileName, skipped = true))
                        }
                    ) { Text("Keep existing") }
                }
            }
        )
    }
    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("$type Import Error") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } }
        )
    }
    summary?.let { result ->
        BatchImportSummaryDialog("$type Import", result, type.lowercase()) {
            summary = null
        }
    }
}
