package com.example.ui.screens

import com.example.ui.components.ResourceTextField
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.ManualPersonaFields
import com.example.data.model.PersonaEntity
import com.example.domain.repository.PersonaRepository
import com.example.domain.repository.ProfileImageKind
import com.example.domain.repository.ProfileImageRepository
import com.example.domain.repository.ProfileDeletionResult
import com.example.ui.components.ProfileLibraryActions
import com.example.ui.components.ElsewhereEmptyState
import com.example.ui.components.elsewhereCardBorder
import com.example.ui.components.elsewhereDestructiveButtonColors
import com.example.ui.components.elsewhereTextFieldColors
import com.example.ui.components.elsewhereTopAppBarColors
import com.example.ui.components.ProfileImageAvatar
import com.example.ui.components.ProfileImageEditor
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonasScreen(
    repository: PersonaRepository,
    profileImageRepository: ProfileImageRepository? = null,
    modifier: Modifier = Modifier,
    libraryHeader: (@Composable () -> Unit)? = null,
    collectionsRepository: com.example.domain.repository.IdentityCollectionRepository? = null,
    onPersonaSelected: (String) -> Unit
) {
    val personas by repository.getAllPersonas().collectAsState(initial = emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var gallery by rememberSaveable { mutableStateOf(true) }
    val collections = observeCollections(collectionsRepository)
    val collectionKind = com.example.data.model.CollectionIdentityKind.PERSONA
    var collectionId by rememberSaveable { mutableStateOf<String?>(null) }
    val collection = collections.firstOrNull { it.collection.collectionId == collectionId }
    val visible = if (libraryHeader == null) personas else remember(personas, query, collection, collections) {
        personas.filter {
            (collection == null || collection.contains(collectionKind, it.personaId)) &&
                query.matchesLibraryQuery(it.displayName, it.personaName, *it.aliases.toTypedArray(),
                    *collections.labelsFor(collectionKind, it.personaId).toTypedArray())
        }
    }
    val coroutineScope = rememberCoroutineScope()
    
    var showImportOptions by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var editingPersona by remember { mutableStateOf<PersonaEntity?>(null) }
    var personaPendingDeletion by remember { mutableStateOf<PersonaEntity?>(null) }
    var deletionInProgress by remember { mutableStateOf(false) }
    var deletionNotice by remember { mutableStateOf<String?>(null) }
    var deletionSucceeded by remember { mutableStateOf(false) }
    
    ProfileJsonImportWorkflow(
        type = "Persona",
        showOptions = showImportOptions,
        onCloseOptions = { showImportOptions = false },
        importProfile = { json, replaceExisting, importAsNew ->
            repository.importPersonaJson(json, replaceExisting, importAsNew)
        },
        findExistingName = { id -> repository.getPersonaById(id)?.displayName }
    )
    importError?.let { message ->
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text("Persona Editor Error") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { importError = null }) { Text("OK") }
            }
        )
    }

    personaPendingDeletion?.let { persona ->
        AlertDialog(
            onDismissRequest = {
                if (!deletionInProgress) personaPendingDeletion = null
            },
            title = { Text("Delete Persona Permanently?") },
            text = {
                Text(
                    "Permanently delete \"${persona.displayName}\"? This cannot be undone. " +
                        "The Persona will be removed from every chat, but all historical messages " +
                        "and speaker names will remain. A chat with no remaining Persona will become history-only."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deletionInProgress = true
                        coroutineScope.launch {
                            deletionNotice = try {
                                when (val result = repository.deletePersonaPermanently(persona.personaId)) {
                                    is ProfileDeletionResult.Deleted -> {
                                        deletionSucceeded = true
                                        if (result.affectedChatCount == 0) {
                                            null
                                        } else {
                                            val chatLabel = if (result.affectedChatCount == 1) "chat" else "chats"
                                            val historyOnly = if (result.historyOnlyChatCount == 0) "" else {
                                                " ${result.historyOnlyChatCount} now ${if (result.historyOnlyChatCount == 1) "opens" else "open"} as history-only."
                                            }
                                            "Deleted \"${persona.displayName}\" and removed the Persona from " +
                                                "${result.affectedChatCount} $chatLabel. All historical messages were kept.$historyOnly"
                                        }
                                    }
                                    ProfileDeletionResult.NotFound -> {
                                        deletionSucceeded = false
                                        "This Persona no longer exists."
                                    }
                                }
                            } catch (_: Exception) {
                                deletionSucceeded = false
                                "The Persona could not be deleted. No chat or message was changed."
                            }
                            deletionInProgress = false
                            personaPendingDeletion = null
                        }
                    },
                    enabled = !deletionInProgress,
                    colors = elsewhereDestructiveButtonColors(),
                    modifier = Modifier.testTag("confirm_delete_persona")
                ) {
                    Text(if (deletionInProgress) "Deleting…" else "Delete Permanently")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { personaPendingDeletion = null },
                    enabled = !deletionInProgress
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    deletionNotice?.let { notice ->
        AlertDialog(
            onDismissRequest = { deletionNotice = null },
            title = { Text(if (deletionSucceeded) "Persona Deleted" else "Persona Not Deleted") },
            text = { Text(notice) },
            confirmButton = {
                TextButton(onClick = { deletionNotice = null }) { Text("OK") }
            }
        )
    }

    if (showEditor) {
        ManualPersonaEditor(
            initialFields = editingPersona?.let(repository::getManualFields) ?: ManualPersonaFields(),
            profileId = editingPersona?.personaId,
            profileImageRepository = profileImageRepository,
            isEditing = editingPersona != null,
            onCancel = {
                showEditor = false
                editingPersona = null
            },
            onSave = { fields ->
                coroutineScope.launch {
                    try {
                        val existing = editingPersona
                        if (existing == null) {
                            repository.createManualPersona(fields)
                        } else {
                            repository.updateManualPersona(existing.personaId, fields)
                        }
                        showEditor = false
                        editingPersona = null
                        importError = null
                    } catch (e: Exception) {
                        importError = e.localizedMessage ?: "Unable to save Persona."
                    }
                }
            },
            modifier = modifier
        )
        return
    }

    Scaffold(
        topBar = {
            if (libraryHeader != null) Column {
                libraryHeader()
                LibrarySearch(query, { query = it }, "Search personas", "library_persona_search")
                LibraryNewAndImport("Persona", onNew = { editingPersona = null; showEditor = true }, onImport = { showImportOptions = true })
                if (collectionsRepository != null) {
                    LibraryCollectionActions(collections, collectionsRepository, collectionId, { collectionId = it },
                        collectionKind, personas.map { it.storyIdentity() }, profileImageRepository, gallery, { gallery = !gallery })
                } else if (personas.isNotEmpty()) {
                    TextButton(onClick = { gallery = !gallery }, modifier = Modifier.padding(start = 8.dp)) {
                        Text(if (gallery) "List view" else "Gallery view")
                    }
                }
            } else CenterAlignedTopAppBar(
                title = { Text("Personas", style = MaterialTheme.typography.titleLarge) },
                colors = elsewhereTopAppBarColors()
            )
        },
        floatingActionButton = {
            if (libraryHeader == null) ProfileLibraryActions(
                profileType = "Persona",
                onNew = {
                    editingPersona = null
                    showEditor = true
                },
                onImport = { showImportOptions = true }
            )
        },
        modifier = modifier.testTag("personas_screen"),
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (visible.isEmpty() && (query.isNotBlank() || collection != null) && libraryHeader != null) {
            Box(Modifier.fillMaxSize().padding(padding).padding(16.dp), contentAlignment = Alignment.Center) {
                Text("No matching personas.")
            }
        } else if (personas.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    ElsewhereEmptyState(
                        icon = Icons.Default.Face,
                        title = "Your library is empty.",
                        message = "Create a Persona or import a persona (JSON) to start."
                    )
                    if (libraryHeader == null) Button(
                        onClick = {
                            editingPersona = null
                            showEditor = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text("New Persona", color = MaterialTheme.colorScheme.onSecondary)
                    }
                    if (libraryHeader == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { showImportOptions = true }) {
                            Text("Choose JSON Files")
                        }
                        OutlinedButton(onClick = { showImportOptions = true }) {
                            Text("Paste JSON")
                        }
                    }
                }
            }
        } else if (libraryHeader != null && gallery) {
            LibraryIdentityGallery(
                identities = visible.map { LibraryIdentity(it.personaId, it.displayName, it.personaName, it.isArchived, collections.labelsFor(collectionKind, it.personaId)) },
                portraits = profileImageRepository, kind = ProfileImageKind.PERSONA, type = "persona",
                modifier = Modifier.fillMaxSize().padding(padding).testTag("persona_gallery"),
                onEdit = { id -> visible.firstOrNull { it.personaId == id }?.let {
                    editingPersona = it
                    showEditor = true
                } },
                onDelete = { id -> personaPendingDeletion = visible.firstOrNull { it.personaId == id } }
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).testTag("persona_list"),
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(visible, key = { it.personaId }) { persona ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("persona_item_${persona.personaId}"),
                        onClick = {
                            if (libraryHeader == null) onPersonaSelected(persona.personaId)
                            else { editingPersona = persona; showEditor = true }
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                        border = elsewhereCardBorder()
                    ) {
                        Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            ProfileImageAvatar(
                                repository = profileImageRepository,
                                kind = ProfileImageKind.PERSONA,
                                profileId = persona.personaId,
                                displayName = persona.displayName,
                                size = if (libraryHeader != null) 62.dp else 48.dp
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = persona.displayName,
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (persona.isArchived) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(
                                                text = "Archived",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = persona.personaName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                val labels = collections.labelsFor(collectionKind, persona.personaId)
                                if (labels.isNotEmpty()) CollectionLabels(labels)
                                if (persona.aliases.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Aliases: ${persona.aliases.joinToString(", ")}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    editingPersona = persona
                                    showEditor = true
                                },
                                modifier = Modifier.testTag("edit_persona_${persona.personaId}")
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit ${persona.displayName}")
                            }
                            IconButton(
                                onClick = { personaPendingDeletion = persona },
                                modifier = Modifier.testTag("delete_persona_${persona.personaId}")
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete ${persona.displayName}",
                                    tint = MaterialTheme.colorScheme.error
                                )
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
private fun ManualPersonaEditor(
    initialFields: ManualPersonaFields,
    profileId: String?,
    profileImageRepository: ProfileImageRepository?,
    isEditing: Boolean,
    onCancel: () -> Unit,
    onSave: (ManualPersonaFields) -> Unit,
    modifier: Modifier = Modifier
) {
    var fields by remember(initialFields) { mutableStateOf(initialFields) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEditing) "Edit Persona" else "New Persona") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { onSave(fields) },
                        enabled = fields.name.isNotBlank(),
                        modifier = Modifier.testTag("save_persona")
                    ) {
                        Text("Save")
                    }
                },
                colors = elsewhereTopAppBarColors()
            )
        },
        modifier = modifier.testTag("manual_persona_editor"),
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Card(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            border = elsewhereCardBorder()
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ProfileImageEditor(
                    repository = profileImageRepository,
                    kind = ProfileImageKind.PERSONA,
                    profileId = profileId,
                    displayName = fields.name.ifBlank { "Persona" },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = fields.name,
                    onValueChange = { fields = fields.copy(name = it) },
                    label = { Text("Name") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    colors = elsewhereTextFieldColors(),
                    modifier = Modifier.fillMaxWidth().testTag("persona_field_Name")
                )
                ResourceTextField(
                    value = fields.backstoryInstructions,
                    onValueChange = { fields = fields.copy(backstoryInstructions = it) },
                    label = "Backstory / Persona Instructions",
                    minLines = 6,
                    modifier = Modifier.fillMaxWidth().weight(1f).testTag("persona_field_Backstory / Persona Instructions")
                )
            }
        }
    }
}
