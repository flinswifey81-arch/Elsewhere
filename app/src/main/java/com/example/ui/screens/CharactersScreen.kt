package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.CharacterEntity
import com.example.data.model.ManualCharacterFields
import com.example.domain.repository.CharacterRepository
import com.example.ui.components.ElsewhereEmptyState
import com.example.ui.components.elsewhereCardBorder
import com.example.ui.components.elsewhereTextFieldColors
import com.example.ui.components.elsewhereTopAppBarColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharactersScreen(
    repository: CharacterRepository,
    modifier: Modifier = Modifier,
    onCharacterSelected: (String) -> Unit
) {
    val characters by repository.getAllCharacters().collectAsState(initial = emptyList())
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    
    var showImportOptions by remember { mutableStateOf(false) }
    var showPasteDialog by remember { mutableStateOf(false) }
    var pasteContent by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var editingCharacter by remember { mutableStateOf<CharacterEntity?>(null) }
    
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            coroutineScope.launch {
                try {
                    val inputStream = context.contentResolver.openInputStream(it)
                    val json = inputStream?.bufferedReader()?.use { reader -> reader.readText() }
                    if (json != null) {
                        repository.importCharacterJson(json, replaceExisting = false, importAsNew = false)
                        importError = null
                    } else {
                        importError = "Failed to read file."
                    }
                } catch (e: Exception) {
                    importError = e.localizedMessage ?: "Unknown error"
                }
            }
        }
    }

    if (showImportOptions) {
        AlertDialog(
            onDismissRequest = { showImportOptions = false },
            title = { Text("Import Character") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { 
                            showImportOptions = false
                            launcher.launch(arrayOf("application/json", "*/*"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Import JSON File")
                    }
                    Button(
                        onClick = { 
                            showImportOptions = false
                            showPasteDialog = true 
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Paste JSON")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showImportOptions = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showPasteDialog) {
        AlertDialog(
            onDismissRequest = { showPasteDialog = false },
            title = { Text("Paste JSON") },
            text = {
                OutlinedTextField(
                    value = pasteContent,
                    onValueChange = { pasteContent = it },
                    label = { Text("JSON Content") },
                    modifier = Modifier.fillMaxWidth().height(200.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    coroutineScope.launch {
                        try {
                            repository.importCharacterJson(pasteContent, replaceExisting = false, importAsNew = false)
                            showPasteDialog = false
                            pasteContent = ""
                            importError = null
                        } catch (e: Exception) {
                            importError = e.localizedMessage ?: "Unknown error"
                        }
                    }
                }) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPasteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    if (importError != null) {
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text("Import Error") },
            text = { Text(importError!!) },
            confirmButton = {
                TextButton(onClick = { importError = null }) {
                    Text("OK")
                }
            }
        )
    }

    if (showEditor) {
        ManualCharacterEditor(
            initialFields = editingCharacter?.let(repository::getManualFields) ?: ManualCharacterFields(),
            isEditing = editingCharacter != null,
            onCancel = {
                showEditor = false
                editingCharacter = null
            },
            onSave = { fields ->
                coroutineScope.launch {
                    try {
                        val existing = editingCharacter
                        if (existing == null) {
                            repository.createManualCharacter(fields)
                        } else {
                            repository.updateManualCharacter(existing.characterId, fields)
                        }
                        showEditor = false
                        editingCharacter = null
                        importError = null
                    } catch (e: Exception) {
                        importError = e.localizedMessage ?: "Unable to save Character."
                    }
                }
            },
            modifier = modifier
        )
        return
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Characters", style = MaterialTheme.typography.titleLarge) },
                colors = elsewhereTopAppBarColors()
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ExtendedFloatingActionButton(
                    onClick = {
                        editingCharacter = null
                        showEditor = true
                    },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("New Character") },
                    modifier = Modifier.testTag("new_character_fab")
                )
                ExtendedFloatingActionButton(
                    onClick = { showImportOptions = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Import JSON") },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.testTag("import_character_fab")
                )
            }
        },
        modifier = modifier.testTag("characters_screen"),
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (characters.isEmpty()) {
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
                        icon = Icons.Default.Person,
                        title = "Your library is empty.",
                        message = "Create a Character or import a character card (JSON) to start."
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            editingCharacter = null
                            showEditor = true
                        }) {
                            Text("New Character")
                        }
                        OutlinedButton(onClick = { launcher.launch(arrayOf("application/json", "*/*")) }) {
                            Text("Import JSON File")
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(characters) { character ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("character_item_${character.characterId}"),
                        onClick = { onCharacterSelected(character.characterId) },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        border = elsewhereCardBorder()
                    ) {
                        Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = character.displayName,
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (character.isArchived) {
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
                                    text = character.characterName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (character.aliases.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Aliases: ${character.aliases.joinToString(", ")}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    editingCharacter = character
                                    showEditor = true
                                },
                                modifier = Modifier.testTag("edit_character_${character.characterId}")
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit ${character.displayName}")
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
private fun ManualCharacterEditor(
    initialFields: ManualCharacterFields,
    isEditing: Boolean,
    onCancel: () -> Unit,
    onSave: (ManualCharacterFields) -> Unit,
    modifier: Modifier = Modifier
) {
    var fields by remember(initialFields) { mutableStateOf(initialFields) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEditing) "Edit Character" else "New Character") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { onSave(fields) },
                        enabled = fields.name.isNotBlank(),
                        modifier = Modifier.testTag("save_character")
                    ) {
                        Text("Save")
                    }
                },
                colors = elsewhereTopAppBarColors()
            )
        },
        modifier = modifier.testTag("manual_character_editor"),
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { EditorSectionTitle("Identity & Opening") }
            item {
                CharacterTextField("Name", fields.name, { fields = fields.copy(name = it) }, singleLine = true)
            }
            item {
                CharacterTextField("Short Backstory", fields.shortBackstory, { fields = fields.copy(shortBackstory = it) })
            }
            item {
                CharacterTextField("Initial Message", fields.initialMessage, { fields = fields.copy(initialMessage = it) })
            }
            item {
                CharacterTextField("System Instructions", fields.systemInstructions, { fields = fields.copy(systemInstructions = it) })
            }
            item { EditorSectionTitle("Voice & Personality") }
            item {
                CharacterTextField("Personality", fields.personality, { fields = fields.copy(personality = it) })
            }
            item {
                CharacterTextField("Tone", fields.tone, { fields = fields.copy(tone = it) })
            }
            item {
                CharacterTextField("Age", fields.age, { fields = fields.copy(age = it) }, singleLine = true)
            }
            item {
                CharacterTextField("Birthday", fields.birthday, { fields = fields.copy(birthday = it) }, singleLine = true)
            }
            item { EditorSectionTitle("Story & Preferences") }
            item {
                CharacterTextField("Story", fields.story, { fields = fields.copy(story = it) })
            }
            item {
                CharacterTextField("Likes", fields.likes, { fields = fields.copy(likes = it) })
            }
            item {
                CharacterTextField("Dislikes", fields.dislikes, { fields = fields.copy(dislikes = it) })
            }
            item {
                CharacterTextField("Conversational Goals", fields.conversationalGoals, { fields = fields.copy(conversationalGoals = it) })
            }
            item {
                CharacterTextField("Conversational Examples", fields.conversationalExamples, { fields = fields.copy(conversationalExamples = it) })
            }
            item {
                CharacterTextField("Appearance", fields.appearance, { fields = fields.copy(appearance = it) })
            }
            item { EditorSectionTitle("Knowledge") }
            item {
                CharacterTextField("Knowledge: Relationships", fields.knowledgeRelationships, { fields = fields.copy(knowledgeRelationships = it) })
            }
            item {
                CharacterTextField("Knowledge: General", fields.knowledgeGeneral, { fields = fields.copy(knowledgeGeneral = it) })
            }
        }
    }
}

@Composable
private fun CharacterTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        shape = MaterialTheme.shapes.medium,
        colors = elsewhereTextFieldColors(),
        modifier = Modifier.fillMaxWidth().testTag("character_field_$label")
    )
}

@Composable
private fun EditorSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp)
    )
}
