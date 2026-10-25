package com.example.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class BatchImportResult(
    val fileName: String,
    val error: String? = null
) {
    val succeeded: Boolean get() = error == null
}

internal data class BatchImportSummary(val results: List<BatchImportResult>) {
    val successCount: Int get() = results.count(BatchImportResult::succeeded)
    val failureCount: Int get() = results.size - successCount

    fun message(itemLabel: String): String = buildString {
        append("Imported ").append(successCount).append(" of ").append(results.size).append(' ').append(itemLabel)
        append(if (results.size == 1) "." else "s.")
        val successes = results.filter(BatchImportResult::succeeded)
        val failures = results.filterNot(BatchImportResult::succeeded)
        if (successes.isNotEmpty()) {
            append("\n\nSucceeded:\n")
            successes.forEach { append("• ").append(it.fileName).append('\n') }
        }
        if (failures.isNotEmpty()) {
            append("\nFailed:\n")
            failures.forEach { result ->
                append("• ").append(result.fileName).append(": ")
                    .append(result.error ?: "Unknown error").append('\n')
            }
        }
    }.trimEnd()
}

internal suspend fun <T> processBatchImports(
    items: List<T>,
    nameOf: suspend (T) -> String,
    importer: suspend (T) -> Unit
): BatchImportSummary {
    val results = items.mapIndexed { index, item ->
        val name = runCatching { nameOf(item) }.getOrNull()?.takeIf(String::isNotBlank)
            ?: "File ${index + 1}"
        runCatching { importer(item) }
            .fold(
                onSuccess = { BatchImportResult(name) },
                onFailure = { BatchImportResult(name, it.message ?: it::class.java.simpleName) }
            )
    }
    return BatchImportSummary(results)
}

internal suspend fun importJsonDocuments(
    context: Context,
    uris: List<Uri>,
    reader: suspend (Uri) -> String = { readJsonDocument(context, it) },
    importer: suspend (String) -> Unit
): BatchImportSummary = processBatchImports(
    items = uris,
    nameOf = { documentDisplayName(context, it) },
    importer = { uri -> importer(reader(uri)) }
)

internal suspend fun importNamedJsonDocuments(
    context: Context,
    uris: List<Uri>,
    reader: suspend (Uri) -> String = { readJsonDocument(context, it) },
    importer: suspend (fileName: String, json: String) -> Unit
): BatchImportSummary = processBatchImports(
    items = uris,
    nameOf = { documentDisplayName(context, it) },
    importer = { uri -> importer(documentDisplayName(context, uri), reader(uri)) }
)

internal suspend fun readJsonDocument(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        ?: throw IllegalArgumentException("The selected file could not be opened.")
}

internal suspend fun documentDisplayName(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()?.takeIf(String::isNotBlank)
        ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank { "JSON file" }
}

@Composable
internal fun BatchImportSummaryDialog(
    title: String,
    summary: BatchImportSummary,
    itemLabel: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(summary.message(itemLabel))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
    )
}
