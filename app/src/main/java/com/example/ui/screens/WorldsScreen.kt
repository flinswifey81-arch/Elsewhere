package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.example.domain.world.WorldDefinitionCodec
import com.example.domain.world.WorldArchiveTransferCoordinator
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.model.WorldCastEntity
import com.example.data.model.WorldContinuityEventEntity
import com.example.data.model.WorldEntity
import com.example.domain.repository.WorldRepository
import com.example.ui.components.elsewhereCardBorder
import com.example.ui.components.elsewhereTextFieldColors
import com.example.ui.components.elsewhereTopAppBarColors
import kotlinx.coroutines.launch

/**
 * Manual Worlds editor with limited World-definition JSON import/export.
 * Complete archives with linked resources and episode history remain a separate milestone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldsScreen(
    repository: WorldRepository,
    archiveTransfer: WorldArchiveTransferCoordinator? = null
) {
    val worlds by repository.observeWorlds().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = worlds.firstOrNull { it.worldId == selectedId }
    var editingWorld by remember { mutableStateOf<WorldEntity?>(null) }
    var isCreatingWorld by remember { mutableStateOf(false) }
    var editingCast by remember { mutableStateOf<WorldCastEntity?>(null) }
    var editingEvent by remember { mutableStateOf<WorldContinuityEventEntity?>(null) }
    var deletingWorld by remember { mutableStateOf<WorldEntity?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshVersion by remember { mutableIntStateOf(0) }
    var cast by remember { mutableStateOf<List<WorldCastEntity>>(emptyList()) }
    var events by remember { mutableStateOf<List<WorldContinuityEventEntity>>(emptyList()) }

    val context = LocalContext.current
    var showImportJson by remember { mutableStateOf(false) }
    var importJsonText by remember { mutableStateOf("") }
    var pendingExportJson by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        val json = pendingExportJson
        pendingExportJson = null
        if (uri != null && json != null) {
            scope.launch {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json) }
                        ?: throw IllegalStateException("Unable to open the selected export location.")
                }.onSuccess { notice = "World definition saved. Chats, episodes, images and draft events are NOT in this JSON." }
                    .onFailure { error = it.message ?: "Unable to save World JSON." }
            }
        }
    }
    val importFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) scope.launch {
            runCatching {
                // Bounded read to avoid loading giant or malicious content into the editor.
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Unable to open the selected file.")
                input.bufferedReader().use { reader ->
                    val content = StringBuilder()
                    val block = CharArray(8192)
                    while (true) {
                        val length = reader.read(block)
                        if (length < 0) break
                        require(content.length + length <= WorldDefinitionCodec.MAX_JSON_CHARS) {
                            "World JSON exceeds size limit."
                        }
                        content.append(block, 0, length)
                    }
                    content.toString()
                }
            }.onSuccess {
                importJsonText = it
                showImportJson = true
                error = null
            }.onFailure { error = it.message ?: "Unable to read World JSON." }
        }
    }

    LaunchedEffect(selectedId, refreshVersion) {
        val id = selectedId
        if (id == null) {
            cast = emptyList()
            events = emptyList()
        } else {
            try {
                cast = repository.getCast(id)
                events = repository.getEvents(id)
            } catch (failure: Exception) {
                error = failure.message ?: "Unable to load World details."
            }
        }
    }

    fun submit(action: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            try {
                action()
                refreshVersion++
            } catch (failure: Exception) {
                error = failure.message ?: "World change could not be saved."
            } finally {
                busy = false
            }
        }
    }

    if (showImportJson) AlertDialog(
        onDismissRequest = { if (!busy) showImportJson = false },
        title = { Text("Import World Definition JSON") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Creates a separate World. Includes setting, cast, relationships and accepted continuity. This is not an episode/chat-history restore.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = importJsonText,
                    onValueChange = { importJsonText = it },
                    label = { Text("Paste Elsewhere World JSON") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 190.dp, max = 330.dp),
                    maxLines = 12,
                    colors = elsewhereTextFieldColors(),
                    enabled = !busy
                )
                Text("Embedded Character/Persona cards, Scenarios and Lorebooks are not supported by this first importer.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && importJsonText.isNotBlank(), onClick = {
                submit {
                    val created = repository.importDefinition(importJsonText)
                    selectedId = created.worldId
                    importJsonText = ""
                    showImportJson = false
                    notice = "Imported World: " + created.title
                }
            }, modifier = Modifier.testTag("import_world_definition_confirm")) { Text(if (busy) "Importing…" else "Import") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = { showImportJson = false }) { Text("Cancel") }
        }
    )

    editingWorld?.let { draft ->
        WorldDetailsDialog(
            value = draft,
            isNew = isCreatingWorld,
            busy = busy,
            onChange = { editingWorld = it },
            onDismiss = { if (!busy) editingWorld = null },
            onSave = {
                submit {
                    if (isCreatingWorld) {
                        val created = repository.createWorld(draft.title, draft.settingSummary)
                        repository.updateWorld(created.copy(description = draft.description, worldRules = draft.worldRules))
                        selectedId = created.worldId
                    } else {
                        repository.updateWorld(draft)
                    }
                    editingWorld = null
                }
            }
        )
    }
    editingCast?.let { draft ->
        WorldCastDialog(
            value = draft,
            isNew = cast.none { it.worldCharacterId == draft.worldCharacterId },
            busy = busy,
            onChange = { editingCast = it },
            onDismiss = { if (!busy) editingCast = null },
            onSave = {
                submit {
                    if (cast.any { it.worldCharacterId == draft.worldCharacterId }) {
                        repository.updateCastMember(draft)
                    } else {
                        repository.createCastMember(draft)
                    }
                    editingCast = null
                }
            }
        )
    }
    editingEvent?.let { draft ->
        WorldEventDialog(
            value = draft,
            isNew = events.none { it.eventId == draft.eventId },
            busy = busy,
            onChange = { editingEvent = it },
            onDismiss = { if (!busy) editingEvent = null },
            onSave = {
                submit {
                    if (events.any { it.eventId == draft.eventId }) {
                        repository.updateDraftEvent(draft)
                    } else {
                        repository.draftEvent(draft.worldId, draft.title, draft.facts)
                    }
                    editingEvent = null
                }
            }
        )
    }
    deletingWorld?.let { world ->
        AlertDialog(
            onDismissRequest = { if (!busy) deletingWorld = null },
            title = { Text("Delete World?") },
            text = { Text("Delete " + world.title + " and its World Cast and continuity? Linked chats, Characters, Personas, Scenarios and Lorebooks survive. This cannot be undone.") },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    submit {
                        repository.deleteWorld(world.worldId)
                        selectedId = null
                        deletingWorld = null
                    }
                }) { Text("Delete World", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { deletingWorld = null }) { Text("Cancel") } }
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Worlds") }, colors = elsewhereTopAppBarColors()) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("worlds_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (selected == null) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("A World organizes its setting, recurring cast and continuity independently of any one chat or episode.")
                        Text("World definitions and complete encrypted history archives are different formats. The full-history archive workflow is currently available only in Elsewhere Test.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = {
                            isCreatingWorld = true
                            editingWorld = WorldEntity(title = "")
                        }, modifier = Modifier.testTag("create_world")) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("New World")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { importJsonText = ""; showImportJson = true },
                                modifier = Modifier.testTag("paste_world_json")) { Text("Paste World JSON") }
                            TextButton(onClick = {
                                importFileLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                            }) { Text("Import file") }
                        }
                    }
                }
                if (archiveTransfer != null) item(key = "archive_import") {
                    WorldArchiveControls(archiveTransfer, world = null, onImported = { selectedId = it })
                }
                if (worlds.isEmpty()) item { Text("No Worlds yet. Create your first setting by hand.") }
                items(worlds, key = { it.worldId }) { world ->
                    Card(
                        modifier = Modifier.fillMaxWidth().testTag("world_card_" + world.worldId),
                        onClick = { selectedId = world.worldId },
                        border = elsewhereCardBorder()
                    ) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Public, contentDescription = null)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(world.title, style = MaterialTheme.typography.titleMedium)
                                if (world.settingSummary.isNotBlank()) Text(world.settingSummary, maxLines = 3, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            } else {
                item(key = "world_header") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(onClick = { selectedId = null }) { Text("← All Worlds") }
                        Text(selected.title, style = MaterialTheme.typography.headlineSmall)
                        if (selected.description.isNotBlank()) Text(selected.description)
                        if (selected.settingSummary.isNotBlank()) {
                            Text("Setting", style = MaterialTheme.typography.titleSmall)
                            Text(selected.settingSummary)
                        }
                        if (selected.worldRules.isNotBlank()) {
                            Text("World rules", style = MaterialTheme.typography.titleSmall)
                            Text(selected.worldRules)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { isCreatingWorld = false; editingWorld = selected.copy() }) { Text("Edit World") }
                            TextButton(onClick = { deletingWorld = selected }) { Text("Delete") }
                        }
                        OutlinedButton(enabled = !busy, modifier = Modifier.testTag("export_world_definition"),
                            onClick = {
                                submit {
                                    val json = repository.exportDefinition(selected.worldId)
                                    pendingExportJson = json
                                    exportLauncher.launch(selected.title.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".world.json")
                                }
                            }
                        ) { Text("Export World Definition JSON") }
                        if (archiveTransfer != null) {
                            WorldArchiveChatLinks(repository, archiveTransfer, selected)
                            WorldArchiveControls(archiveTransfer, selected, onImported = { selectedId = it })
                        }
                        Text("Definition JSON excludes episode history. Encrypted World archives include chat history and files when available in Elsewhere Test.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item(key = "cast_header") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("World Cast (" + cast.size + ")", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = { editingCast = WorldCastEntity(worldId = selected.worldId, name = "") }) { Text("Add") }
                    }
                }
                if (cast.isEmpty()) item {
                    Text("World Cast members are reusable identities, not yet AI bots or Personas.", style = MaterialTheme.typography.bodySmall)
                }
                items(cast, key = { it.worldCharacterId }) { member ->
                    Card(border = elsewhereCardBorder(), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(member.name, style = MaterialTheme.typography.titleMedium)
                            if (member.role.isNotBlank()) Text(member.role, style = MaterialTheme.typography.bodySmall)
                            if (member.identity.isNotBlank()) Text(member.identity, maxLines = 4)
                            TextButton(onClick = { editingCast = member.copy() }) { Text("Edit profile") }
                        }
                    }
                }
                item(key = "event_header") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Continuity (" + events.size + ")", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            editingEvent = WorldContinuityEventEntity(worldId = selected.worldId, title = "", facts = "")
                        }) { Text("Draft event") }
                    }
                }
                item { Text("Continuity is reviewed manually. Drafts do not become shared Canon until accepted.", style = MaterialTheme.typography.bodySmall) }
                items(events, key = { it.eventId }) { event ->
                    Card(modifier = Modifier.fillMaxWidth(), border = elsewhereCardBorder()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(event.title, style = MaterialTheme.typography.titleMedium)
                            Text(event.facts)
                            Text(event.status, style = MaterialTheme.typography.labelSmall)
                            if (event.status == "DRAFT") {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { editingEvent = event.copy() }) { Text("Edit") }
                                    TextButton(enabled = !busy, onClick = {
                                        submit { repository.acceptEvent(selected.worldId, event.eventId) }
                                    }) { Text("Accept as Canon") }
                                }
                            }
                        }
                    }
                }
                item {
                    HorizontalDivider()
                    Text("Linking existing Lorebooks, Scenarios and chats is a separate milestone. World fields do not yet affect model prompts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            notice?.let { message ->
                item { Text(message, color = MaterialTheme.colorScheme.primary) }
            }
            error?.let { message ->
                item { Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("worlds_error")) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
internal fun WorldDetailsDialog(
    value: WorldEntity, isNew: Boolean, busy: Boolean, onChange: (WorldEntity) -> Unit,
    onDismiss: () -> Unit, onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Create World" else "Edit World") },
        text = {
            Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                WorldField("World title", value.title, true) { onChange(value.copy(title = it)) }
                WorldField("Description", value.description) { onChange(value.copy(description = it)) }
                WorldField("Setting and overview", value.settingSummary) { onChange(value.copy(settingSummary = it)) }
                WorldField("World rules", value.worldRules) { onChange(value.copy(worldRules = it)) }
                Text("Worlds are local records. These edits do not change existing chats.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onSave, enabled = !busy && value.title.isNotBlank() && value.title.length <= 300,
            modifier = Modifier.testTag("save_world")) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } }
    )
}

@Composable
internal fun WorldCastDialog(
    value: WorldCastEntity, isNew: Boolean, busy: Boolean, onChange: (WorldCastEntity) -> Unit,
    onDismiss: () -> Unit, onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New World Character" else "Edit World Character") },
        text = {
            Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                WorldField("Name", value.name, true) { onChange(value.copy(name = it)) }
                WorldField("Role", value.role) { onChange(value.copy(role = it)) }
                WorldField("Identity", value.identity) { onChange(value.copy(identity = it)) }
                WorldField("Appearance", value.appearance) { onChange(value.copy(appearance = it)) }
                WorldField("Personality", value.personality) { onChange(value.copy(personality = it)) }
                WorldField("Voice", value.voice) { onChange(value.copy(voice = it)) }
                WorldField("Background", value.backstory) { onChange(value.copy(backstory = it)) }
                WorldField("Motivations", value.motivations) { onChange(value.copy(motivations = it)) }
                WorldField("Knowledge limits", value.knowledgeLimits) { onChange(value.copy(knowledgeLimits = it)) }
                Text("A World character is not automatically a Character bot or player Persona.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onSave, enabled = !busy && value.name.isNotBlank() && value.name.length <= 300,
            modifier = Modifier.testTag("save_world_cast")) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } }
    )
}

@Composable
internal fun WorldEventDialog(
    value: WorldContinuityEventEntity, isNew: Boolean, busy: Boolean, onChange: (WorldContinuityEventEntity) -> Unit,
    onDismiss: () -> Unit, onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Draft Continuity Event" else "Edit Draft Event") },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                WorldField("Event title", value.title, true) { onChange(value.copy(title = it)) }
                WorldField("What happened", value.facts) { onChange(value.copy(facts = it)) }
                Text("Save as draft. Accept as Canon separately after reviewing.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onSave, enabled = !busy && value.title.isNotBlank() && value.facts.isNotBlank(),
            modifier = Modifier.testTag("save_world_event")) { Text("Save draft") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } }
    )
}

@Composable
private fun WorldField(label: String, value: String, single: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
        singleLine = single, maxLines = if (single) 1 else 8, colors = elsewhereTextFieldColors())
}
