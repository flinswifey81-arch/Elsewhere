package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.model.NpcLibraryEntryEntity
import com.example.di.AppContainer
import com.example.domain.repository.NpcLibraryBank
import com.example.domain.repository.NpcRepository
import com.example.domain.repository.ProfileImageKind
import com.example.domain.repository.ProfileImageRepository
import com.example.ui.components.ProfileImageAvatar
import com.example.ui.components.ProfileImageEditor
import com.example.ui.components.ResourceTextField
import com.example.ui.components.elsewhereCardBorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class LibraryTab(val label: String, val tag: String) {
    CHARACTERS("Characters", "hub_characters"),
    PERSONAS("Personas", "hub_personas"),
    LOREBOOKS("Lorebooks", "hub_lorebooks"),
    NPC_BANK("NPC Bank", "hub_npc_bank"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RedesignLibrary(container: AppContainer, onChatNpcs: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf(LibraryTab.CHARACTERS.name) }
    val holder = rememberSaveableStateHolder()
    val tab = LibraryTab.valueOf(selected)
    val header: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().testTag("library_hub")) {
            Text("Library", Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
                style = MaterialTheme.typography.headlineMedium)
            PrimaryScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 12.dp) {
                LibraryTab.entries.forEach { item ->
                    Tab(
                        selected = item == tab,
                        onClick = { selected = item.name },
                        text = { Text(item.label) },
                        modifier = Modifier.testTag(item.tag)
                    )
                }
            }
        }
    }
    holder.SaveableStateProvider(selected) {
        when (tab) {
            LibraryTab.CHARACTERS -> CharactersScreen(
                repository = container.characterRepository, profileImageRepository = container.profileImageRepository,
                libraryHeader = header, collectionsRepository = container.identityCollections, onCharacterSelected = {}
            )
            LibraryTab.PERSONAS -> PersonasScreen(
                repository = container.personaRepository, profileImageRepository = container.profileImageRepository,
                libraryHeader = header, collectionsRepository = container.identityCollections, onPersonaSelected = {}
            )
            LibraryTab.LOREBOOKS -> container.lorebookRepository?.let {
                LorebooksScreen(repository = it, libraryHeader = header)
            } ?: Column {
                header()
                Text("Lorebooks are unavailable in this environment.", Modifier.padding(16.dp))
            }
            LibraryTab.NPC_BANK -> NpcDirectory(
                repository = container.npcRepository, portraits = container.profileImageRepository,
                header = header, onChatNpcs = onChatNpcs
            )
        }
    }
}

@Composable
internal fun LibrarySearch(query: String, onQueryChange: (String) -> Unit, label: String, tag: String) {
    OutlinedTextField(
        value = query, onValueChange = onQueryChange,
        label = { Text(label) }, leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(tag)
    )
}

@Composable
internal fun LibraryNewAndImport(type: String, onNew: () -> Unit, onImport: () -> Unit) {
    val prefix = type.lowercase()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(onClick = onNew, modifier = Modifier.testTag("new_${prefix}_fab")) {
            Text("New $type")
        }
        OutlinedButton(onClick = onImport, modifier = Modifier.testTag("import_${prefix}_fab")) {
            Text("Import JSON")
        }
    }
}

internal fun String.matchesLibraryQuery(vararg fields: String): Boolean {
    val words = trim().split(Regex("\\s+")).filter(String::isNotBlank)
    return words.all { token -> fields.any { it.contains(token, ignoreCase = true) } }
}

internal data class LibraryIdentity(val id: String, val label: String, val name: String, val archived: Boolean,
    val collectionLabels: List<String> = emptyList())

@Composable
internal fun LibraryIdentityGallery(
    identities: List<LibraryIdentity>,
    portraits: ProfileImageRepository?,
    kind: ProfileImageKind,
    type: String,
    modifier: Modifier,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(identities, key = { it.id }) { identity ->
            Card(
                onClick = { onEdit(identity.id) },
                border = elsewhereCardBorder(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth().testTag("${type}_item_${identity.id}")
            ) {
                Box(Modifier.fillMaxWidth().padding(12.dp)) {
                    var menu by remember { mutableStateOf(false) }
                    ProfileImageAvatar(portraits, kind, identity.id, identity.label,
                        size = 80.dp, modifier = Modifier.align(Alignment.CenterStart),
                        contentDescription = "Portrait for ${identity.label}")
                    Box(Modifier.align(Alignment.TopEnd)) {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Actions for ${identity.label}") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Edit profile") },
                                modifier = Modifier.testTag("edit_${type}_${identity.id}"),
                                onClick = { menu = false; onEdit(identity.id) })
                            DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; onDelete(identity.id) })
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    Text(identity.label, style = MaterialTheme.typography.titleMedium)
                    if (identity.label != identity.name) Text(identity.name, style = MaterialTheme.typography.bodySmall)
                    if (identity.collectionLabels.isNotEmpty()) CollectionLabels(identity.collectionLabels)
                    if (identity.archived) Text("Archived", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NpcDirectory(
    repository: NpcRepository?,
    portraits: ProfileImageRepository?,
    header: @Composable () -> Unit,
    onChatNpcs: () -> Unit,
) {
    if (repository == null) {
        Column { header(); Text("NPC Bank is unavailable in this environment.", Modifier.padding(16.dp)) }
        return
    }
    val library by repository.observeLibraryDirectory().collectAsState(initial = null)
    var query by rememberSaveable { mutableStateOf("") }
    var importOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<NpcLibraryEntryEntity?>(null) }
    Scaffold(topBar = header) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("library_npc_directory"),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { importOpen = true }) { Text("Import JSON") }
                    OutlinedButton(onClick = onChatNpcs) { Text("Chat NPCs") }
                }
            }
            item { LibrarySearch(query, { query = it }, "Search NPCs and banks", "library_npc_search") }
            val visible = library?.mapNotNull { bank ->
                val entries = bank.entries.filter { query.matchesLibraryQuery(it.displayName, it.role, bank.bank.name) }
                if (entries.isEmpty()) null else NpcLibraryBank(bank.bank, entries)
            }
            if (visible == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            else if (visible.isEmpty()) item {
                Text(
                    if (query.isBlank()) "No reusable NPCs yet. Import a bank, or save NPCs from a chat."
                    else "No matching NPCs.",
                    Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            visible.orEmpty().forEach { bank ->
                item(key = "bank:${bank.bank.bankId}") {
                    Text(bank.bank.name, Modifier.padding(start = 16.dp, top = 8.dp),
                        style = MaterialTheme.typography.titleMedium)
                }
                items(bank.entries, key = { "npc:${it.libraryNpcId}" }) { npc ->
                    Card(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("library_npc_${npc.libraryNpcId}"),
                        border = elsewhereCardBorder()
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            ProfileImageAvatar(portraits, ProfileImageKind.NPC_LIBRARY, npc.libraryNpcId,
                                npc.displayName, size = 62.dp)
                            Column(Modifier.weight(1f)) {
                                Text(npc.displayName, style = MaterialTheme.typography.titleMedium)
                                if (npc.role.isNotBlank()) Text(npc.role, style = MaterialTheme.typography.bodySmall)
                            }
                            if (portraits != null) IconButton(onClick = { editing = npc }) {
                                Icon(Icons.Default.Edit, "Edit Library portrait for ${npc.displayName}")
                            }
                        }
                    }
                }
            }
        }
    }
    if (importOpen) NpcDirectoryImport(repository) { importOpen = false }
    editing?.let { npc ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(npc.displayName) },
            text = {
                ProfileImageEditor(portraits, ProfileImageKind.NPC_LIBRARY, npc.libraryNpcId, npc.displayName,
                    savedImageHint = "New chats start with this portrait. Existing chats keep their own copies.")
            },
            confirmButton = { TextButton(onClick = { editing = null }) { Text("Done") } }
        )
    }
}

@Composable
private fun NpcDirectoryImport(repository: NpcRepository, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var json by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            error = null
            try {
                json = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { readNpcJsonBounded(it) }
                        ?: error("Couldn't open this JSON file.")
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = failure.message ?: "Couldn't read this JSON file." }
            finally { busy = false }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Import NPC Bank") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Bank name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), enabled = !busy)
                ResourceTextField(json, { json = it }, "NPC Bank JSON", maxLength = 1_000_000, enabled = !busy)
                TextButton(enabled = !busy, onClick = { picker.launch(arrayOf("application/json", "text/json", "text/plain")) }) {
                    Text("Choose JSON file")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && name.isNotBlank() && json.isNotBlank(), onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        repository.importLibraryBank(name, json)
                        onClose()
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) { error = failure.message ?: "NPCs could not be imported." }
                    finally { busy = false }
                }
            }) { Text("Import bank") }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text("Cancel") } }
    )
}
