package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.model.NpcDossier
import com.example.data.model.NpcLibraryBankEntity
import com.example.data.model.NpcLibraryEntryEntity
import com.example.domain.repository.NpcImportCollisionMode
import com.example.domain.repository.NpcRepository
import com.example.domain.repository.ChatRepository
import com.example.data.model.ChatSummary
import com.example.ui.components.elsewhereDestructiveButtonColors
import com.example.ui.components.elsewhereTextFieldColors
import com.example.ui.components.elsewhereTopAppBarColors
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

data class NpcBankUiState(
    val isEnabled: Boolean = false,
    val dossiers: List<NpcDossier> = emptyList(),
    val isLoaded: Boolean = false,
    val notice: String? = null,
    val libraryBanks: List<NpcLibraryBankEntity> = emptyList()
)

class NpcBankViewModel(
    private val chatId: String,
    private val repository: NpcRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(NpcBankUiState())
    val uiState: StateFlow<NpcBankUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.getSettings(chatId)
            combine(
                repository.observeProjectedDossiers(chatId),
                repository.observeSettings(chatId)
            ) { dossiers, settings -> dossiers to (settings?.isEnabled ?: false) }
                .collect { (dossiers, enabled) ->
                    _uiState.value = _uiState.value.copy(
                        dossiers = dossiers,
                        isEnabled = enabled,
                        isLoaded = true
                    )
                }
        }
        viewModelScope.launch {
            repository.observeLibraryBanks().collect { banks ->
                _uiState.value = _uiState.value.copy(libraryBanks = banks)
            }
        }
    }

    fun setEnabled(enabled: Boolean) = viewModelScope.launch {
        runCatching { repository.setEnabled(chatId, enabled) }
            .onFailure { notice(it.message ?: "NPC Bank setting could not be saved.") }
    }

    fun save(dossier: NpcDossier) = viewModelScope.launch {
        runCatching { repository.saveManual(dossier.copy(chatId = chatId)) }
            .onSuccess { notice("NPC dossier saved.") }
            .onFailure { notice(it.message ?: "NPC dossier could not be saved.") }
    }

    fun delete(npcId: String) = viewModelScope.launch {
        runCatching { repository.deleteDossier(npcId) }
            .onFailure { notice(it.message ?: "NPC dossier could not be deleted.") }
    }

    fun setKeepInScene(npcId: String, keep: Boolean) = viewModelScope.launch {
        runCatching { repository.setKeepInScene(npcId, keep) }
            .onFailure { notice(it.message ?: "NPC pin could not be changed.") }
    }

    fun importJson(json: String) = viewModelScope.launch {
        runCatching { repository.importJson(chatId, json, NpcImportCollisionMode.IMPORT_AS_NEW) }
            .onSuccess { count -> notice("Imported $count NPC dossier${if (count == 1) "" else "s"}.") }
            .onFailure { notice(it.message ?: "NPC JSON could not be imported.") }
    }

    fun clearNotice() { _uiState.value = _uiState.value.copy(notice = null) }

    fun importLibrary(name: String, json: String) = viewModelScope.launch {
        runCatching { repository.importLibraryBank(name, json) }
            .onSuccess { notice("NPC bank saved to the library.") }
            .onFailure { notice(it.message ?: "NPC bank could not be saved.") }
    }

    fun saveChatToLibrary(name: String) = viewModelScope.launch {
        runCatching { repository.saveChatToLibrary(chatId, name) }
            .onSuccess { notice("This chat's NPCs were copied to the library.") }
            .onFailure { notice(it.message ?: "NPCs could not be saved to the library.") }
    }

    fun addFromLibrary(bankId: String, selectedIds: Set<String>?) = viewModelScope.launch {
        runCatching { repository.addLibraryNpcsToChat(chatId, bankId, selectedIds) }
            .onSuccess { count -> notice("Added $count independent NPC cop${if (count == 1) "y" else "ies"} to this chat.") }
            .onFailure { notice(it.message ?: "Library NPCs could not be added.") }
    }

    fun reportImportFailure(error: Throwable) {
        notice(error.message ?: "NPC JSON could not be read.")
    }

    private fun notice(text: String) { _uiState.value = _uiState.value.copy(notice = text) }

    class Factory(private val chatId: String, private val repository: NpcRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = NpcBankViewModel(chatId, repository) as T
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NpcBankScreen(
    chatId: String,
    repository: NpcRepository,
    chatRepository: ChatRepository,
    onNavigateBack: () -> Unit
) {
    val viewModel: NpcBankViewModel = viewModel(factory = NpcBankViewModel.Factory(chatId, repository))
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<NpcDossier?>(null) }
    var showPaste by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<NpcDossier?>(null) }
    var showExportOptions by remember { mutableStateOf(false) }
    var showLibrary by remember { mutableStateOf(false) }
    var libraryImportJson by remember { mutableStateOf("") }
    val chatSummary by androidx.compose.runtime.produceState<ChatSummary?>(null, chatId) {
        value = chatRepository.getChatSummaryById(chatId)
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use(::readNpcJsonBounded)
                        ?: throw IllegalArgumentException("The selected NPC JSON could not be opened.")
                }
            }
            result.onSuccess(viewModel::importJson).onFailure(viewModel::reportImportFailure)
        }
    }
    val libraryImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use(::readNpcJsonBounded) ?: error("The selected NPC JSON could not be opened.") }
            }
            result.onSuccess { libraryImportJson = it; showLibrary = true }.onFailure(viewModel::reportImportFailure)
        }
    }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val json = pendingExport
        pendingExport = null
        if (uri != null && json != null) {
            scope.launch(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(json) }
            }
        }
    }

    state.notice?.let { notice ->
        LaunchedEffect(notice) {
            kotlinx.coroutines.delay(3500)
            viewModel.clearNotice()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NPC Bank") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Text("Enabled", style = MaterialTheme.typography.labelMedium)
                    Switch(checked = state.isEnabled, onCheckedChange = viewModel::setEnabled)
                },
                colors = elsewhereTopAppBarColors()
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "A private cast list for this chat. Automatic discovery is opt-in. Private author notes are never sent. Story secrets may guide storyteller continuity, but never become Character knowledge unless you explicitly add them to that Character's knowledge.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { editing = newNpc(chatId) }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("Add")
                }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/json", "text/plain")) }) {
                    Icon(Icons.Filled.FileUpload, contentDescription = null)
                    Text("Import")
                }
                OutlinedButton(onClick = { showPaste = true }) { Text("Paste JSON") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { showExportOptions = true }) {
                    Icon(Icons.Filled.FileDownload, contentDescription = null)
                    Text("Export")
                }
                OutlinedButton(onClick = { showLibrary = true }) { Text("Library") }
            }
            state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            if (!state.isEnabled) {
                Text("NPC discovery and context injection are disabled for this chat. Existing dossiers remain local and editable.")
            }
            if (state.dossiers.isEmpty() && state.isLoaded) {
                Text("No supporting cast yet. Add one manually, import JSON, or let the storyteller discover named NPCs in future replies.")
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.dossiers, key = { it.npcId }) { dossier ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(dossier.displayName, style = MaterialTheme.typography.titleMedium)
                                    if (dossier.role.isNotBlank()) Text(dossier.role, style = MaterialTheme.typography.bodySmall)
                                }
                                IconButton(onClick = { viewModel.setKeepInScene(dossier.npcId, !dossier.keepInScene) }) {
                                    Icon(
                                        Icons.Filled.PushPin,
                                        contentDescription = if (dossier.keepInScene) "Stop keeping in scene" else "Keep in scene",
                                        tint = if (dossier.keepInScene) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { editing = dossier }) {
                                    Icon(Icons.Filled.Edit, contentDescription = "Edit NPC")
                                }
                                IconButton(onClick = { deleteTarget = dossier }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete NPC", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            if (dossier.relationshipToPersona.isNotBlank()) Text("Relationship: ${dossier.relationshipToPersona}")
                            if (dossier.goals.isNotBlank()) Text("Goals: ${dossier.goals}")
                            if (dossier.keepInScene) Text("Kept in scene", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }

    editing?.let { dossier ->
        NpcEditorDialog(
            initial = dossier,
            chatSummary = chatSummary,
            onDismiss = { editing = null },
            onSave = { viewModel.save(it); editing = null }
        )
    }
    if (showPaste) {
        AlertDialog(
            onDismissRequest = { showPaste = false },
            title = { Text("Paste NPC Bank JSON") },
            text = {
                OutlinedTextField(
                    value = pasteText,
                    onValueChange = { if (it.length <= 1_000_000) pasteText = it },
                    modifier = Modifier.fillMaxWidth().height(300.dp),
                    colors = elsewhereTextFieldColors()
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.importJson(pasteText); showPaste = false; pasteText = "" }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { showPaste = false }) { Text("Cancel") } }
        )
    }
    if (showExportOptions) {
        AlertDialog(
            onDismissRequest = { showExportOptions = false },
            title = { Text("Export NPC Bank") },
            text = {
                Text(
                    "Share-safe export omits story secrets and private author notes. Full private export includes both; save it only to a trusted location. Neither option contains API keys."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showExportOptions = false
                    scope.launch {
                        pendingExport = repository.exportJson(chatId, includePrivateFields = true)
                        exportLauncher.launch("elsewhere-npc-bank-private.json")
                    }
                }) { Text("Full private export") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showExportOptions = false }) { Text("Cancel") }
                    TextButton(onClick = {
                        showExportOptions = false
                        scope.launch {
                            pendingExport = repository.exportJson(chatId, includePrivateFields = false)
                            exportLauncher.launch("elsewhere-npc-bank.json")
                        }
                    }) { Text("Share-safe") }
                }
            }
        )
    }
    if (showLibrary) {
        NpcLibraryDialog(
            banks = state.libraryBanks,
            repository = repository,
            initialJson = libraryImportJson,
            onPickFile = { libraryImportLauncher.launch(arrayOf("application/json", "text/json", "text/plain")) },
            onDismiss = { showLibrary = false },
            onImport = viewModel::importLibrary,
            onSaveChat = viewModel::saveChatToLibrary,
            onAdd = viewModel::addFromLibrary
        )
    }
    deleteTarget?.let { dossier ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${dossier.displayName}?") },
            text = { Text("This removes the dossier and its automatic update history from this chat.") },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.delete(dossier.npcId); deleteTarget = null },
                    colors = elsewhereDestructiveButtonColors()
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun NpcLibraryDialog(
    banks: List<NpcLibraryBankEntity>,
    repository: NpcRepository,
    initialJson: String,
    onPickFile: () -> Unit,
    onDismiss: () -> Unit,
    onImport: (String, String) -> Unit,
    onSaveChat: (String) -> Unit,
    onAdd: (String, Set<String>?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var json by remember { mutableStateOf("") }
    var selectedBank by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<NpcLibraryEntryEntity>>(emptyList()) }
    var selectedEntries by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(selectedBank) {
        entries = selectedBank?.let { repository.getLibraryBank(it)?.entries }.orEmpty()
        selectedEntries = entries.mapTo(mutableSetOf()) { it.libraryNpcId }
    }
    LaunchedEffect(initialJson) { if (initialJson.isNotBlank()) json = initialJson }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reusable NPC Library") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Text("Import a bank once, or save this chat's current NPCs. Adding NPCs creates independent chat copies.", style = MaterialTheme.typography.bodySmall) }
                item { OutlinedTextField(name, { name = it.take(120) }, label = { Text("Library bank name") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(json, { json = it.take(1_000_000) }, label = { Text("NPC Bank JSON") }, minLines = 3, modifier = Modifier.fillMaxWidth()) }
                item { TextButton(onClick = onPickFile) { Text("Choose JSON file") } }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = name.isNotBlank() && json.isNotBlank(), onClick = { onImport(name, json); name = ""; json = "" }) { Text("Save import") }
                        OutlinedButton(enabled = name.isNotBlank(), onClick = { onSaveChat(name); name = "" }) { Text("Save chat NPCs") }
                    }
                }
                if (banks.isEmpty()) item { Text("No reusable banks saved yet.") }
                items(banks, key = { it.bankId }) { bank ->
                    OutlinedButton(onClick = { selectedBank = bank.bankId }, modifier = Modifier.fillMaxWidth()) { Text(bank.name) }
                }
                if (entries.isNotEmpty()) {
                    item { Text("Choose NPCs", style = MaterialTheme.typography.titleSmall) }
                    items(entries, key = { it.libraryNpcId }) { npc ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = npc.libraryNpcId in selectedEntries, onCheckedChange = { checked ->
                                selectedEntries = if (checked) selectedEntries + npc.libraryNpcId else selectedEntries - npc.libraryNpcId
                            })
                            Text(npc.displayName)
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { selectedBank?.let { onAdd(it, null) }; onDismiss() }) { Text("Add entire bank") }
                            OutlinedButton(enabled = selectedEntries.isNotEmpty(), onClick = { selectedBank?.let { onAdd(it, selectedEntries) }; onDismiss() }) { Text("Add selected") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

internal fun readNpcJsonBounded(input: InputStream, maxBytes: Int = 1_000_000): String {
    val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        require(total <= maxBytes) { "NPC JSON is too large." }
        output.write(buffer, 0, read)
    }
    return output.toString(Charsets.UTF_8.name())
}

@Composable
private fun NpcEditorDialog(
    initial: NpcDossier,
    chatSummary: ChatSummary?,
    onDismiss: () -> Unit,
    onSave: (NpcDossier) -> Unit
) {
    var name by remember(initial.npcId) { mutableStateOf(initial.displayName) }
    var aliases by remember(initial.npcId) { mutableStateOf(initial.aliases.joinToString(", ")) }
    var role by remember(initial.npcId) { mutableStateOf(initial.role) }
    var appearance by remember(initial.npcId) { mutableStateOf(initial.appearance) }
    var personality by remember(initial.npcId) { mutableStateOf(initial.personality) }
    var relationship by remember(initial.npcId) { mutableStateOf(initial.relationshipToPersona) }
    var personaRelationships by remember(initial.npcId) { mutableStateOf(initial.personaRelationships) }
    var characterKnowledge by remember(initial.npcId) { mutableStateOf(initial.characterKnowledge) }
    var goals by remember(initial.npcId) { mutableStateOf(initial.goals) }
    var secrets by remember(initial.npcId) { mutableStateOf(initial.secrets) }
    var authorNotes by remember(initial.npcId) { mutableStateOf(initial.authorNotes) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.displayName.isBlank()) "Add NPC" else "Edit NPC") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { NpcField("Display name", name) { name = it.take(120) } }
                item { NpcField("Aliases (comma separated)", aliases) { aliases = it.take(1000) } }
                item { NpcField("Role", role) { role = it.take(4000) } }
                item { NpcField("Appearance", appearance) { appearance = it.take(4000) } }
                item { NpcField("Personality", personality) { personality = it.take(4000) } }
                item { NpcField("General storyteller relationship (solo/legacy)", relationship) { relationship = it.take(4000) } }
                chatSummary?.personas.orEmpty().forEach { persona ->
                    item(key = "persona-${persona.personaId}") {
                        NpcField(
                            "Relationship with ${persona.displayName}",
                            personaRelationships[persona.personaId].orEmpty()
                        ) { value ->
                            personaRelationships = personaRelationships.toMutableMap().apply {
                                if (value.isBlank()) remove(persona.personaId) else put(persona.personaId, value.take(4000))
                            }
                        }
                    }
                }
                chatSummary?.characters.orEmpty().forEach { character ->
                    item(key = "character-${character.characterId}") {
                        NpcField(
                            "What ${character.displayName} knows",
                            characterKnowledge[character.characterId].orEmpty()
                        ) { value ->
                            characterKnowledge = characterKnowledge.toMutableMap().apply {
                                if (value.isBlank()) remove(character.characterId) else put(character.characterId, value.take(4000))
                            }
                        }
                    }
                }
                item { NpcField("Goals", goals) { goals = it.take(4000) } }
                item { NpcField("Storyteller secrets (not Character knowledge)", secrets) { secrets = it.take(4000) } }
                item { NpcField("Private author notes (never sent)", authorNotes) { authorNotes = it.take(4000) } }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        initial.copy(
                            displayName = name.trim(),
                            aliases = aliases.split(',').map(String::trim).filter(String::isNotEmpty).distinct().take(20),
                            role = role,
                            appearance = appearance,
                            personality = personality,
                            relationshipToPersona = relationship,
                            personaRelationships = personaRelationships,
                            characterKnowledge = characterKnowledge,
                            goals = goals,
                            secrets = secrets,
                            authorNotes = authorNotes,
                            isManualBase = true,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun NpcField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        colors = elsewhereTextFieldColors()
    )
}

private fun newNpc(chatId: String): NpcDossier = NpcDossier(
    npcId = UUID.randomUUID().toString(),
    chatId = chatId,
    displayName = "",
    aliases = emptyList(),
    role = "",
    appearance = "",
    personality = "",
    relationshipToPersona = "",
    personaRelationships = emptyMap(),
    characterKnowledge = emptyMap(),
    goals = "",
    secrets = "",
    authorNotes = "",
    keepInScene = false,
    isManualBase = true,
    createdAt = System.currentTimeMillis(),
    updatedAt = System.currentTimeMillis()
)
