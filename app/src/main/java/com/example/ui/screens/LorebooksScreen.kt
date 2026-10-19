package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.LorebookEntity
import com.example.data.model.LorebookEntryEntity
import com.example.data.model.LorebookWithEntries
import com.example.domain.repository.LorebookImportMode
import com.example.domain.repository.LorebookImportPreview
import com.example.domain.repository.LorebookRepository
import com.example.domain.repository.LorebookValidationException
import com.example.ui.components.ElsewhereEmptyState
import com.example.ui.components.elsewhereCardBorder
import com.example.ui.components.elsewhereDestructiveButtonColors
import com.example.ui.components.elsewhereTextFieldColors
import com.example.ui.components.elsewhereTopAppBarColors
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LorebooksScreen(
    repository: LorebookRepository,
    modifier: Modifier = Modifier
) {
    val books by repository.observeLorebooks().collectAsState(initial = emptyList())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editingDefinition by remember { mutableStateOf<LorebookWithEntries?>(null) }
    var loadingEditor by remember { mutableStateOf(false) }
    var showImportOptions by remember { mutableStateOf(false) }
    var showPasteDialog by remember { mutableStateOf(false) }
    var pasteJson by remember { mutableStateOf("") }
    var pendingImportJson by remember { mutableStateOf<String?>(null) }
    var importPreview by remember { mutableStateOf<LorebookImportPreview?>(null) }
    var pendingDelete by remember { mutableStateOf<LorebookEntity?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var noticeMessage by remember { mutableStateOf<String?>(null) }
    var pendingExportJson by remember { mutableStateOf<String?>(null) }

    fun previewJson(json: String) {
        scope.launch {
            try {
                pendingImportJson = json
                importPreview = repository.previewImport(json)
                errorMessage = null
            } catch (error: Exception) {
                pendingImportJson = null
                importPreview = null
                errorMessage = error.message ?: "Unable to read this Lorebook."
            }
        }
    }

    val importFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            scope.launch {
                try {
                    previewJson(readBoundedLorebookJson(context, it))
                } catch (error: Exception) {
                    errorMessage = error.message ?: "Unable to read the selected file."
                }
            }
        }
    }
    val exportFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        val json = pendingExportJson
        if (uri != null && json != null) {
            scope.launch {
                try {
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json) }
                        ?: throw IllegalStateException("Unable to open the selected destination")
                    noticeMessage = "Lorebook exported."
                } catch (error: Exception) {
                    errorMessage = error.message ?: "Unable to export this Lorebook."
                } finally {
                    pendingExportJson = null
                }
            }
        } else {
            pendingExportJson = null
        }
    }

    fun exportLorebook(book: LorebookEntity) {
        scope.launch {
            try {
                pendingExportJson = repository.exportJson(book.lorebookId)
                val safeName = book.name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifBlank { "lorebook" }
                exportFileLauncher.launch("$safeName.json")
            } catch (error: Exception) {
                errorMessage = error.message ?: "Unable to export this Lorebook."
            }
        }
    }

    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text("Lorebook Error") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { errorMessage = null }) { Text("OK") } }
        )
    }
    noticeMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { noticeMessage = null },
            title = { Text("Lorebooks") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { noticeMessage = null }) { Text("OK") } }
        )
    }

    editingDefinition?.let { definition ->
        LorebookEditor(
            definition = definition,
            onBack = { editingDefinition = null },
            onSave = { lorebook, entries ->
                scope.launch {
                    try {
                        repository.saveDefinition(lorebook, entries)
                        editingDefinition = null
                        noticeMessage = "Lorebook saved."
                    } catch (error: Exception) {
                        errorMessage = error.message ?: "Unable to save this Lorebook."
                    }
                }
            },
            modifier = modifier
        )
        return
    }

    if (showImportOptions) {
        AlertDialog(
            onDismissRequest = { showImportOptions = false },
            title = { Text("Import Lorebook") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            showImportOptions = false
                            importFileLauncher.launch(arrayOf("application/json", "text/json", "*/*"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Choose JSON File") }
                    OutlinedButton(
                        onClick = {
                            showImportOptions = false
                            showPasteDialog = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Paste JSON") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showImportOptions = false }) { Text("Cancel") } }
        )
    }

    if (showPasteDialog) {
        AlertDialog(
            onDismissRequest = { showPasteDialog = false },
            title = { Text("Paste Lorebook JSON") },
            text = {
                OutlinedTextField(
                    value = pasteJson,
                    onValueChange = { pasteJson = it },
                    label = { Text("JSON") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp),
                    colors = elsewhereTextFieldColors()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPasteDialog = false
                        previewJson(pasteJson)
                    },
                    enabled = pasteJson.isNotBlank()
                ) { Text("Validate & Preview") }
            },
            dismissButton = { TextButton(onClick = { showPasteDialog = false }) { Text("Cancel") } }
        )
    }

    importPreview?.let { preview ->
        val json = pendingImportJson
        AlertDialog(
            onDismissRequest = {
                importPreview = null
                pendingImportJson = null
            },
            title = { Text("Import Preview") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(preview.lorebook.name, style = MaterialTheme.typography.titleMedium)
                    if (preview.lorebook.description.isNotBlank()) Text(preview.lorebook.description)
                    Text("${preview.entries.size} entries")
                    Text("ID: ${preview.lorebook.lorebookId}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Scope: story · ${preview.lorebook.storyId?.let { "story_id $it" } ?: "unbound (explicit attachment required)"}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("Book enabled: ${preview.lorebook.enabled}", style = MaterialTheme.typography.bodySmall)
                    preview.entries.forEach { entry ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(entry.title, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${entry.activationMode} · ${entry.matchMode} · priority ${entry.priority} · ${if (entry.enabled) "enabled" else "disabled"}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (entry.keys.isNotEmpty()) Text("Keywords: ${entry.keys.joinToString()}", style = MaterialTheme.typography.bodySmall)
                                if (entry.secondaryKeys.isNotEmpty()) Text("Additional: ${entry.secondaryKeys.joinToString()}", style = MaterialTheme.typography.bodySmall)
                                Text("Case sensitive: ${entry.caseSensitive} · Whole words: ${entry.wholeWords}", style = MaterialTheme.typography.bodySmall)
                                Text("Canon ${if (entry.canonLocked) "locked" else "unlocked"}: ${entry.canonContent.take(160)}", style = MaterialTheme.typography.bodySmall)
                                Text("Current: ${entry.currentContent.take(160).ifEmpty { "(intentionally empty)" }}", style = MaterialTheme.typography.bodySmall)
                                if (entry.tags.isNotEmpty()) Text("Tags: ${entry.tags.joinToString()}", style = MaterialTheme.typography.bodySmall)
                                if (entry.notes.isNotEmpty()) Text("Private notes included (${entry.notes.length} characters)", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (preview.duplicateLorebookId) {
                        Text(
                            "A Lorebook with this ID already exists. New Copy preserves it. Replace updates it transactionally; attachments and overlays for matching entry IDs stay intact, while omitted entries are removed.",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (preview.conflictingEntryIds.isNotEmpty()) {
                        Text(
                            "These entry IDs already belong to another Lorebook: ${preview.conflictingEntryIds.joinToString()}. Import as New Copy to generate safe new IDs.",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Row {
                    if (preview.duplicateLorebookId || preview.conflictingEntryIds.isNotEmpty()) {
                        TextButton(onClick = {
                            if (json != null) {
                                scope.launch {
                                    runCatching { repository.importJson(json, LorebookImportMode.IMPORT_AS_NEW_COPY) }
                                        .onSuccess { noticeMessage = "Imported as a new copy." }
                                        .onFailure { errorMessage = it.message ?: "Import failed." }
                                    importPreview = null
                                    pendingImportJson = null
                                }
                            }
                        }) { Text("New Copy") }
                        if (preview.duplicateLorebookId && preview.conflictingEntryIds.isEmpty()) {
                            TextButton(
                                onClick = {
                                    if (json != null) {
                                        scope.launch {
                                            runCatching { repository.importJson(json, LorebookImportMode.REPLACE_EXISTING) }
                                                .onSuccess { noticeMessage = "Existing Lorebook replaced." }
                                                .onFailure { errorMessage = it.message ?: "Import failed." }
                                            importPreview = null
                                            pendingImportJson = null
                                        }
                                    }
                                },
                                colors = elsewhereDestructiveButtonColors()
                            ) { Text("Replace") }
                        }
                    } else {
                        TextButton(onClick = {
                            if (json != null) {
                                scope.launch {
                                    runCatching { repository.importJson(json, LorebookImportMode.REJECT_DUPLICATE) }
                                        .onSuccess { noticeMessage = "Lorebook imported." }
                                        .onFailure { errorMessage = it.message ?: "Import failed." }
                                    importPreview = null
                                    pendingImportJson = null
                                }
                            }
                        }) { Text("Import") }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    importPreview = null
                    pendingImportJson = null
                }) { Text("Cancel") }
            }
        )
    }

    pendingDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete Lorebook Permanently?") },
            text = { Text("Delete \"${book.name}\", its entries, and its chat attachments? Chat messages are not changed.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            runCatching { repository.deleteLorebook(book.lorebookId) }
                                .onFailure { errorMessage = it.message ?: "Unable to delete Lorebook." }
                            pendingDelete = null
                        }
                    },
                    colors = elsewhereDestructiveButtonColors()
                ) { Text("Delete Permanently") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Lorebooks") },
                colors = elsewhereTopAppBarColors()
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExtendedFloatingActionButton(
                    onClick = { editingDefinition = repository.newLorebook() },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("New Lorebook") },
                    modifier = Modifier.testTag("new_lorebook_fab")
                )
                ExtendedFloatingActionButton(
                    onClick = { showImportOptions = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Import JSON") },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.testTag("import_lorebook_fab")
                )
            }
        },
        modifier = modifier.testTag("lorebooks_screen"),
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (books.isEmpty() && !loadingEditor) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                ElsewhereEmptyState(
                    icon = Icons.Default.MenuBook,
                    title = "No Lorebooks yet.",
                    message = "Create one manually or import Elsewhere Lorebook JSON. Attach it from a chat when you want to use it."
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 120.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(books, key = { it.lorebookId }) { book ->
                    Card(
                        modifier = Modifier.fillMaxWidth().testTag("lorebook_item_${book.lorebookId}"),
                        border = elsewhereCardBorder(),
                        onClick = {
                            loadingEditor = true
                            scope.launch {
                                editingDefinition = repository.getLorebook(book.lorebookId)
                                loadingEditor = false
                            }
                        }
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(book.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    if (book.description.isNotBlank()) Text(book.description, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                                    Text(if (book.enabled) "Enabled" else "Disabled", style = MaterialTheme.typography.labelSmall)
                                }
                                TextButton(onClick = { exportLorebook(book) }) { Text("Export") }
                                IconButton(onClick = { pendingDelete = book }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete ${book.name}", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LorebookEditor(
    definition: LorebookWithEntries,
    onBack: () -> Unit,
    onSave: (LorebookEntity, List<LorebookEntryEntity>) -> Unit,
    modifier: Modifier = Modifier
) {
    var name by remember(definition.lorebook.lorebookId) { mutableStateOf(definition.lorebook.name) }
    var description by remember(definition.lorebook.lorebookId) { mutableStateOf(definition.lorebook.description) }
    var storyId by remember(definition.lorebook.lorebookId) { mutableStateOf(definition.lorebook.storyId.orEmpty()) }
    var enabled by remember(definition.lorebook.lorebookId) { mutableStateOf(definition.lorebook.enabled) }
    val entries = remember(definition.lorebook.lorebookId) { definition.entries.toMutableStateList() }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var newEntry by remember { mutableStateOf<LorebookEntryEntity?>(null) }
    var deletingIndex by remember { mutableStateOf<Int?>(null) }
    val canSave = name.isNotBlank()

    val activeDraft = editingIndex?.let { entries.getOrNull(it) } ?: newEntry
    activeDraft?.let { draft ->
        LorebookEntryEditorDialog(
            initial = draft,
            isNew = newEntry != null,
            onDismiss = {
                editingIndex = null
                newEntry = null
            },
            onSave = { saved ->
                val index = editingIndex
                if (index == null) entries.add(saved) else entries[index] = saved
                editingIndex = null
                newEntry = null
            }
        )
    }
    deletingIndex?.let { index ->
        val entry = entries.getOrNull(index)
        if (entry != null) {
            AlertDialog(
                onDismissRequest = { deletingIndex = null },
                title = { Text("Delete Entry?") },
                text = { Text("Delete \"${entry.title}\" and any chat-local overlay for it?") },
                confirmButton = {
                    TextButton(
                        onClick = { entries.removeAt(index); deletingIndex = null },
                        colors = elsewhereDestructiveButtonColors()
                    ) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { deletingIndex = null }) { Text("Cancel") } }
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (definition.entries.isEmpty() && definition.lorebook.name == "New Lorebook") "New Lorebook" else "Edit Lorebook") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    TextButton(
                        onClick = {
                            onSave(
                                definition.lorebook.copy(
                                    name = name.trim(),
                                    description = description,
                                    enabled = enabled,
                                    storyId = storyId.trim().ifEmpty { null },
                                    updatedAt = System.currentTimeMillis()
                                ),
                                entries.toList()
                            )
                        },
                        enabled = canSave,
                        modifier = Modifier.testTag("save_lorebook")
                    ) { Text("Save") }
                },
                colors = elsewhereTopAppBarColors()
            )
        },
        modifier = modifier.testTag("lorebook_editor"),
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("lorebook_editor_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Lorebook name") },
                    modifier = Modifier.fillMaxWidth().testTag("lorebook_name"),
                    singleLine = true,
                    colors = elsewhereTextFieldColors()
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Scope type: story", style = MaterialTheme.typography.labelMedium)
                    OutlinedTextField(
                        value = storyId,
                        onValueChange = { storyId = it },
                        label = { Text("Story ID (optional metadata)") },
                        supportingText = { Text("Blank means unbound. Attachment to each chat is always explicit.") },
                        modifier = Modifier.fillMaxWidth().testTag("lorebook_story_id"),
                        singleLine = true,
                        colors = elsewhereTextFieldColors()
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    colors = elsewhereTextFieldColors()
                )
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Lorebook enabled", style = MaterialTheme.typography.titleSmall)
                        Text("A disabled book stays attached but injects no entries.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Entries (${entries.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Button(
                        onClick = { newEntry = LorebookEntryEntity(lorebookId = definition.lorebook.lorebookId, title = "New Entry") },
                        modifier = Modifier.testTag("add_lorebook_entry")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Add Entry")
                    }
                }
            }
            items(entries.size, key = { entries[it].entryId }) { index ->
                val entry = entries[index]
                Card(Modifier.fillMaxWidth(), border = elsewhereCardBorder()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text("${entry.activationMode} · priority ${entry.priority}${if (!entry.enabled) " · disabled" else ""}", style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { editingIndex = index }) { Icon(Icons.Default.Edit, contentDescription = "Edit ${entry.title}") }
                            IconButton(onClick = { deletingIndex = index }) { Icon(Icons.Default.Delete, contentDescription = "Delete ${entry.title}", tint = MaterialTheme.colorScheme.error) }
                        }
                        Text(entry.currentContent.ifEmpty { "Current content is intentionally empty." }, style = MaterialTheme.typography.bodySmall, maxLines = 4)
                    }
                }
            }
        }
    }
}

@Composable
private fun LorebookEntryEditorDialog(
    initial: LorebookEntryEntity,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (LorebookEntryEntity) -> Unit
) {
    var title by remember { mutableStateOf(initial.title) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var activationMode by remember { mutableStateOf(initial.activationMode) }
    var keys by remember { mutableStateOf(initial.keys.joinToString("\n")) }
    var secondaryKeys by remember { mutableStateOf(initial.secondaryKeys.joinToString("\n")) }
    var matchMode by remember { mutableStateOf(initial.matchMode) }
    var caseSensitive by remember { mutableStateOf(initial.caseSensitive) }
    var wholeWords by remember { mutableStateOf(initial.wholeWords) }
    var canonContent by remember { mutableStateOf(initial.canonContent) }
    var canonLocked by remember { mutableStateOf(initial.canonLocked) }
    var currentContent by remember { mutableStateOf(initial.currentContent) }
    var priorityText by remember { mutableStateOf(initial.priority.toString()) }
    var tags by remember { mutableStateOf(initial.tags.joinToString("\n")) }
    var notes by remember { mutableStateOf(initial.notes) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showCanonUpdate by remember { mutableStateOf(false) }
    var canonCandidate by remember { mutableStateOf(initial.canonContent) }
    var resetPerformed by remember { mutableStateOf(false) }
    var canonExplicitlyUpdated by remember { mutableStateOf(false) }
    val priority = priorityText.toIntOrNull()

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset Current to Canon?") },
            text = { Text("This replaces only Current content with the Canon baseline. Activation settings, notes, tags, IDs, and timestamps are unchanged.") },
            confirmButton = {
                TextButton(onClick = {
                    currentContent = canonContent
                    resetPerformed = true
                    showResetConfirm = false
                }) { Text("Reset Current") }
            },
            dismissButton = { TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") } }
        )
        return
    }
    if (showCanonUpdate) {
        AlertDialog(
            onDismissRequest = { showCanonUpdate = false },
            title = { Text("Update Canon Baseline") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Canon is the reusable baseline. This does not automatically change Current.")
                    OutlinedTextField(
                        value = canonCandidate,
                        onValueChange = { canonCandidate = it },
                        label = { Text("New Canon content") },
                        minLines = 5,
                        modifier = Modifier.fillMaxWidth().testTag("new_lore_entry_canon"),
                        colors = elsewhereTextFieldColors()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    canonContent = canonCandidate
                    canonExplicitlyUpdated = true
                    showCanonUpdate = false
                }) { Text("Update Canon") }
            },
            dismissButton = { TextButton(onClick = { showCanonUpdate = false }) { Text("Cancel") } }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New Lore Entry" else "Edit Lore Entry") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth().testTag("lore_entry_title"), colors = elsewhereTextFieldColors())
                LabeledSwitch("Entry enabled", enabled, "lore_entry_enabled") { enabled = it }
                Text("Activation", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("keyword", "always", "manual").forEach { mode ->
                        FilterChip(selected = activationMode == mode, onClick = { activationMode = mode }, label = { Text(mode.replaceFirstChar(Char::uppercase)) })
                    }
                }
                if (activationMode == "keyword") {
                    OutlinedTextField(keys, { keys = it }, label = { Text("Keywords (one per line)") }, modifier = Modifier.fillMaxWidth(), minLines = 3, colors = elsewhereTextFieldColors())
                    OutlinedTextField(secondaryKeys, { secondaryKeys = it }, label = { Text("Additional keywords (one per line)") }, modifier = Modifier.fillMaxWidth(), minLines = 2, colors = elsewhereTextFieldColors())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = matchMode == "any", onClick = { matchMode = "any" }, label = { Text("Match any") })
                        FilterChip(selected = matchMode == "all", onClick = { matchMode = "all" }, label = { Text("Match all") })
                    }
                    LabeledSwitch("Case sensitive", caseSensitive) { caseSensitive = it }
                    LabeledSwitch("Whole words", wholeWords) { wholeWords = it }
                }
                HorizontalDivider()
                Text("Canon baseline", style = MaterialTheme.typography.titleSmall)
                if (isNew) {
                    OutlinedTextField(canonContent, { canonContent = it }, label = { Text("Canon content") }, modifier = Modifier.fillMaxWidth().testTag("lore_entry_canon"), minLines = 5, colors = elsewhereTextFieldColors())
                } else {
                    OutlinedTextField(canonContent, {}, readOnly = true, label = { Text("Canon content") }, modifier = Modifier.fillMaxWidth(), minLines = 4, colors = elsewhereTextFieldColors())
                    OutlinedButton(
                        onClick = { canonCandidate = canonContent; showCanonUpdate = true },
                        enabled = !canonLocked,
                        modifier = Modifier.fillMaxWidth().testTag("update_lore_entry_canon")
                    ) { Text(if (canonLocked) "Unlock Canon to Update" else "Update Canon Explicitly") }
                }
                LabeledSwitch("Canon locked", canonLocked, "lore_entry_canon_locked") { canonLocked = it }
                Text("Current content", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    currentContent,
                    { currentContent = it; resetPerformed = false },
                    label = { Text("Current content sent when active") },
                    modifier = Modifier.fillMaxWidth().testTag("lore_entry_current"),
                    minLines = 5,
                    colors = elsewhereTextFieldColors()
                )
                OutlinedButton(onClick = { showResetConfirm = true }, modifier = Modifier.fillMaxWidth().testTag("reset_lore_entry_current")) { Text("Reset Current to Canon") }
                Text("Injection", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(25, 50, 75, 100).forEach { preset ->
                        FilterChip(selected = priorityText == preset.toString(), onClick = { priorityText = preset.toString() }, label = { Text(when (preset) { 25 -> "Low"; 50 -> "Normal"; 75 -> "High"; else -> "Critical" }) })
                    }
                }
                OutlinedTextField(
                    priorityText,
                    { priorityText = it },
                    label = { Text("Priority (any 32-bit integer)") },
                    isError = priority == null,
                    supportingText = { if (priority == null) Text("Enter a whole number.") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = elsewhereTextFieldColors()
                )
                Text("Position: lore (the only supported v1 position)", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(tags, { tags = it }, label = { Text("Tags (one per line; private metadata)") }, modifier = Modifier.fillMaxWidth(), minLines = 2, colors = elsewhereTextFieldColors())
                OutlinedTextField(notes, { notes = it }, label = { Text("Private notes (never sent to the model)") }, modifier = Modifier.fillMaxWidth(), minLines = 3, colors = elsewhereTextFieldColors())
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val parsedPriority = priority ?: return@TextButton
                    val candidate = initial.copy(
                        title = title.trim(),
                        enabled = enabled,
                        activationMode = activationMode,
                        keys = linesToList(keys),
                        secondaryKeys = linesToList(secondaryKeys),
                        matchMode = matchMode,
                        caseSensitive = caseSensitive,
                        wholeWords = wholeWords,
                        canonContent = canonContent,
                        canonLocked = canonLocked,
                        currentContent = currentContent,
                        priority = parsedPriority,
                        tags = linesToList(tags),
                        notes = notes,
                        updatedAt = initial.updatedAt
                    )
                    val changedExceptCurrent = candidate.copy(currentContent = initial.currentContent, updatedAt = initial.updatedAt) != initial
                    val timestamp = if (resetPerformed && !changedExceptCurrent && !canonExplicitlyUpdated) initial.updatedAt else System.currentTimeMillis()
                    onSave(candidate.copy(updatedAt = timestamp))
                },
                enabled = title.isNotBlank() && priority != null,
                modifier = Modifier.testTag("save_lore_entry")
            ) { Text("Save Entry") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun LabeledSwitch(
    label: String,
    checked: Boolean,
    testTag: String? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = if (testTag == null) Modifier else Modifier.testTag(testTag)
        )
    }
}

private fun linesToList(value: String): List<String> =
    value.lineSequence().map(String::trim).filter(String::isNotEmpty).distinct().toList()

private suspend fun readBoundedLorebookJson(context: android.content.Context, uri: Uri): String =
    withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Unable to open the selected file.")
        stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > LorebookRepository.MAX_JSON_BYTES) {
                    throw LorebookValidationException("Lorebook JSON exceeds the 2 MB limit")
                }
                output.write(buffer, 0, read)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }
