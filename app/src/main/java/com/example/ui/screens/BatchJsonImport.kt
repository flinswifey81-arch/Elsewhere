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
import com.example.domain.repository.DuplicateIdException

internal data class BatchImportResult(
    val fileName: String,
    val error: String? = null,
    val skipped: Boolean = false,
    val updated: Boolean = false
) {
    val succeeded: Boolean get() = error == null && !skipped
}

internal data class BatchImportSummary(val results: List<BatchImportResult>) {
    val successCount: Int get() = results.count(BatchImportResult::succeeded)
    val failureCount: Int get() = results.count { it.error != null }
    val skippedCount: Int get() = results.count { it.skipped }
    val updatedCount: Int get() = results.count { it.succeeded && it.updated }

    fun message(itemLabel: String): String = buildString {
        append("Imported or updated ").append(successCount).append(" of ")
            .append(results.size).append(' ').append(itemLabel)
        append(if (results.size == 1) "." else "s.")
        val successes = results.filter(BatchImportResult::succeeded)
        val failures = results.filter { it.error != null }
        val skipped = results.filter { it.skipped }
        if (successes.isNotEmpty()) {
            append("\n\nSucceeded:\n")
            successes.forEach {
                append("• ").append(it.fileName)
                    .append(if (it.updated) " (updated existing)" else " (imported)")
                    .append('\n')
            }
        }
        if (skipped.isNotEmpty()) {
            append("\nKept unchanged:\n")
            skipped.forEach { append("• ").append(it.fileName).append('\n') }
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

/** An ID collision stays pending until the user chooses EACH file's disposition. */
internal data class PendingSameIdJson(
    val fileName: String,
    val json: String,
    val id: String
)

internal data class ReviewableJsonBatch(
    val completed: List<BatchImportResult>,
    val duplicates: List<PendingSameIdJson>
)

/**
 * Imports only unique IDs automatically. Duplicates are staged, never silently
 * overwritten or discarded; the caller must display an explicit decision dialog.
 * A later file whose ID collides with an earlier imported file is also staged.
 */
internal suspend fun <T> stageDuplicateJsonImports(
    items: List<T>,
    nameOf: suspend (T) -> String,
    read: suspend (T) -> String,
    importer: suspend (String) -> Unit,
    duplicateId: (Throwable) -> String? = {
        (it as? DuplicateIdException)?.id
    }
): ReviewableJsonBatch {
    val completed = mutableListOf<BatchImportResult>()
    val pending = mutableListOf<PendingSameIdJson>()
    items.forEachIndexed { index, item ->
        val name = runCatching { nameOf(item) }.getOrNull()?.takeIf(String::isNotBlank)
            ?: "File ${index + 1}"
        try {
            val json = read(item)
            try {
                importer(json)
                completed += BatchImportResult(name)
            } catch (failure: Exception) {
                val id = duplicateId(failure)
                if (id != null) pending += PendingSameIdJson(name, json, id)
                else completed += BatchImportResult(name, failure.message ?: "Invalid JSON.")
            }
        } catch (failure: Exception) {
            completed += BatchImportResult(name, failure.message ?: "File could not be read.")
        }
    }
    return ReviewableJsonBatch(completed, pending)
}

internal suspend fun stageDuplicateJsonDocuments(
    context: Context,
    uris: List<Uri>,
    importer: suspend (String) -> Unit
): ReviewableJsonBatch = stageDuplicateJsonImports(
    items = uris,
    nameOf = { documentDisplayName(context, it) },
    read = { readJsonDocument(context, it) },
    importer = importer
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
    val input = context.contentResolver.openInputStream(uri)
        ?: throw IllegalArgumentException("The selected file could not be opened.")
    input.bufferedReader().use { reader ->
        val output = StringBuilder()
        val block = CharArray(8192)
        while (true) {
            val n = reader.read(block)
            if (n < 0) break
            require(output.length + n <= 2_000_000) { "JSON is larger than the 2 MB import limit." }
            output.append(block, 0, n)
        }
        output.toString()
    }
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
