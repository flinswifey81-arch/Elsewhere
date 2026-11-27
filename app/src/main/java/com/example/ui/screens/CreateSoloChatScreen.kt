package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.domain.repository.CharacterRepository
import com.example.domain.repository.ChatRepository
import com.example.domain.repository.ProfileImageKind
import com.example.domain.repository.ProfileImageRepository
import com.example.domain.repository.PersonaRepository
import com.example.ui.components.elsewhereCardBorder
import com.example.ui.components.elsewhereTextFieldColors
import com.example.ui.components.elsewhereTopAppBarColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateSoloChatScreen(
    chatRepository: ChatRepository,
    personaRepository: PersonaRepository,
    characterRepository: CharacterRepository,
    onChatCreated: (String) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    redesignImages: ProfileImageRepository? = null
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    
    val personas by personaRepository.getAllPersonas().collectAsState(initial = emptyList())
    val characters by characterRepository.getAllCharacters().collectAsState(initial = emptyList())
    
    var step by rememberSaveable { mutableStateOf(1) }
    var selectedPersonaId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedCharacterId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedCharacter = characters.firstOrNull { it.characterId == selectedCharacterId }
    var chatName by rememberSaveable { mutableStateOf("") }
    // Legacy greeting records have no IDs. Derive one from the complete, deduplicated
    // text rather than title or order, and keep large entries out of saved instance state.
    var openingKey by rememberSaveable { mutableStateOf<String?>(null) }
    val openings = remember(selectedCharacter) {
        selectedCharacter?.let(characterRepository::getOpeningMessages).orEmpty().map { greeting ->
            val key = java.security.MessageDigest.getInstance("SHA-256")
                .digest(greeting.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            key to greeting
        }
    }
    val selectedOpening = if (openingKey == null) openings.firstOrNull()?.second
        else openings.firstOrNull { it.first == openingKey }?.second
    var previewKey by rememberSaveable { mutableStateOf<String?>(null) }
    if (step == 3 && previewKey != null) {
        val index = openings.indexOfFirst { it.first == previewKey }
        openings.getOrNull(index)?.let { (key, greeting) ->
            AlertDialog(
                onDismissRequest = { previewKey = null },
                title = { Text("Greeting ${index + 1}") },
                text = { Text(greeting, Modifier.verticalScroll(rememberScrollState())) },
                dismissButton = { TextButton(onClick = { previewKey = null }) { Text("Close") } },
                confirmButton = { TextButton(onClick = { openingKey = key; previewKey = null }) { Text("Use greeting") } }
            )
        }
    }
    
    var showPasteDialogPersona by remember { mutableStateOf(false) }
    var showPasteDialogCharacter by remember { mutableStateOf(false) }
    var pasteContent by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }
    var batchImportSummary by remember { mutableStateOf<BatchImportSummary?>(null) }
    var batchImportLabel by remember { mutableStateOf("profile") }

    val personaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            coroutineScope.launch {
                batchImportLabel = "persona"
                batchImportSummary = importJsonDocuments(context, uris) { personaRepository.importPersonaJson(it) }
            }
        }
    }
    
    val characterLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            coroutineScope.launch {
                batchImportLabel = "character"
                batchImportSummary = importJsonDocuments(context, uris) { characterRepository.importCharacterJson(it) }
            }
        }
    }

    if (showPasteDialogPersona) {
        AlertDialog(
            onDismissRequest = { showPasteDialogPersona = false },
            title = { Text("Paste Persona JSON") },
            text = {
                OutlinedTextField(
                    value = pasteContent,
                    onValueChange = { pasteContent = it },
                    modifier = Modifier.fillMaxWidth().height(200.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    coroutineScope.launch {
                        try {
                            personaRepository.importPersonaJson(pasteContent)
                            showPasteDialogPersona = false
                            pasteContent = ""
                        } catch (e: Exception) { importError = e.localizedMessage }
                    }
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { showPasteDialogPersona = false }) { Text("Cancel") } }
        )
    }

    if (showPasteDialogCharacter) {
        AlertDialog(
            onDismissRequest = { showPasteDialogCharacter = false },
            title = { Text("Paste Character JSON") },
            text = {
                OutlinedTextField(
                    value = pasteContent,
                    onValueChange = { pasteContent = it },
                    modifier = Modifier.fillMaxWidth().height(200.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    coroutineScope.launch {
                        try {
                            characterRepository.importCharacterJson(pasteContent)
                            showPasteDialogCharacter = false
                            pasteContent = ""
                        } catch (e: Exception) { importError = e.localizedMessage }
                    }
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { showPasteDialogCharacter = false }) { Text("Cancel") } }
        )
    }

    if (importError != null) {
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text("Import Error") },
            text = { Text(importError!!) },
            confirmButton = { TextButton(onClick = { importError = null }) { Text("OK") } }
        )
    }
    batchImportSummary?.let { summary ->
        BatchImportSummaryDialog(
            title = "${batchImportLabel.replaceFirstChar(Char::uppercase)} Import",
            summary = summary,
            itemLabel = batchImportLabel,
            onDismiss = { batchImportSummary = null }
        )
    }

    val pickerState = rememberSaveableStateHolder()
    BackHandler(enabled = step > 1) { step-- }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Solo Chat") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (step > 1) step-- else onNavigateBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = elsewhereTopAppBarColors()
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            when (step) {
                1 -> {
                    Text("Select a Persona", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    if (personas.isEmpty()) {
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("No Persona yet", style = MaterialTheme.typography.titleMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { personaLauncher.launch(arrayOf("application/json", "*/*")) }) { Text("Import Personas") }
                                    OutlinedButton(onClick = { showPasteDialogPersona = true }) { Text("Paste JSON") }
                                }
                            }
                        }
                    } else {
                        if (redesignImages != null) {
                            pickerState.SaveableStateProvider("personas") { StoryIdentityPicker(
                                entries = personas.map { it.storyIdentity() },
                                selectedIds = listOfNotNull(selectedPersonaId),
                                repository = redesignImages,
                                kind = ProfileImageKind.PERSONA,
                                onSelect = { id -> selectedPersonaId = id },
                                modifier = Modifier.weight(1f)
                            ) }
                            Row {
                                TextButton(onClick = { personaLauncher.launch(arrayOf("application/json", "*/*")) }) { Text("Import Files") }
                                TextButton(onClick = { showPasteDialogPersona = true }) { Text("Paste JSON") }
                            }
                        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                            items(personas) { persona ->
                                SelectionCard(
                                    text = persona.displayName,
                                    selected = selectedPersonaId == persona.personaId,
                                    onClick = { selectedPersonaId = persona.personaId }
                                )
                            }
                            item {
                                Spacer(modifier = Modifier.height(16.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { personaLauncher.launch(arrayOf("application/json", "*/*")) }) { Text("Import Files") }
                                    TextButton(onClick = { showPasteDialogPersona = true }) { Text("Paste JSON") }
                                }
                            }
                        }
                        Button(
                            onClick = { step = 2 },
                            enabled = personas.any { it.personaId == selectedPersonaId },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Next") }
                    }
                }
                2 -> {
                    Text("Select a Character", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    if (characters.isEmpty()) {
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("No Character yet", style = MaterialTheme.typography.titleMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { characterLauncher.launch(arrayOf("application/json", "*/*")) }) { Text("Import Characters") }
                                    OutlinedButton(onClick = { showPasteDialogCharacter = true }) { Text("Paste JSON") }
                                }
                            }
                        }
                    } else {
                        if (redesignImages != null) {
                            pickerState.SaveableStateProvider("characters") { StoryIdentityPicker(
                                entries = characters.map { it.storyIdentity() },
                                selectedIds = listOfNotNull(selectedCharacterId),
                                repository = redesignImages,
                                kind = ProfileImageKind.CHARACTER,
                                onSelect = { id ->
                                    val character = characters.firstOrNull { it.characterId == id }
                                    if (character != null && selectedCharacterId != id) {
                                        selectedCharacterId = character.characterId
                                        chatName = "Chat with ${character.displayName}"
                                        openingKey = null
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) }
                            Row {
                                TextButton(onClick = { characterLauncher.launch(arrayOf("application/json", "*/*")) }) { Text("Import Files") }
                                TextButton(onClick = { showPasteDialogCharacter = true }) { Text("Paste JSON") }
                            }
                        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                            items(characters) { character ->
                                SelectionCard(
                                    text = character.displayName,
                                    selected = selectedCharacterId == character.characterId,
                                    onClick = { 
                                        val changedCharacter = selectedCharacterId != character.characterId
                                        selectedCharacterId = character.characterId
                                        if (changedCharacter) {
                                            chatName = "Chat with ${character.displayName}"
                                            openingKey = null
                                        }
                                    }
                                )
                            }
                            item {
                                Spacer(modifier = Modifier.height(16.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { characterLauncher.launch(arrayOf("application/json", "*/*")) }) { Text("Import Files") }
                                    TextButton(onClick = { showPasteDialogCharacter = true }) { Text("Paste JSON") }
                                }
                            }
                        }
                        Button(
                            onClick = { step = 3 },
                            enabled = selectedCharacter != null,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Next") }
                    }
                }
                3 -> {
                    Text("Name this chat", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = chatName,
                        onValueChange = { chatName = it },
                        label = { Text("Chat Name") },
                        shape = MaterialTheme.shapes.medium,
                        colors = elsewhereTextFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (openings.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text("Opening message", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Choose a saved greeting or start with an empty chat. No model is contacted.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        LazyColumn(
                            Modifier.weight(1f).selectableGroup(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item {
                                OpeningMessageOption(
                                    label = "No greeting",
                                    selected = selectedOpening == null,
                                    onSelect = { openingKey = "" }
                                )
                            }
                            items(openings, key = { it.first }) { (key, opening) ->
                                OpeningMessageOption(
                                    label = opening,
                                    selected = selectedOpening == opening,
                                    onSelect = { openingKey = key },
                                    onPreview = if (redesignImages != null) { { previewKey = key } } else null
                                )
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    Button(
                        onClick = {
                            val persona = personas.firstOrNull { it.personaId == selectedPersonaId }
                            val character = selectedCharacter
                            if (persona != null && character != null) coroutineScope.launch {
                                val chatId = chatRepository.createSoloChat(
                                    displayName = chatName.takeIf { it.isNotBlank() } ?: "Untitled Chat",
                                    personaId = persona.personaId,
                                    characterId = character.characterId,
                                    characterDisplayName = character.displayName,
                                    initialCharacterMessage = selectedOpening
                                )
                                onChatCreated(chatId)
                            }
                        },
                        enabled = chatName.isNotBlank() && selectedCharacter != null && personas.any { it.personaId == selectedPersonaId },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Create Chat") }
                }
            }
        }
    }
}

@Composable
private fun OpeningMessageOption(label: String, selected: Boolean, onSelect: () -> Unit, onPreview: (() -> Unit)? = null) {
    Card(
        modifier = Modifier.fillMaxWidth().selectable(
            selected = selected,
            onClick = onSelect,
            role = Role.RadioButton
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = elsewhereCardBorder()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                RadioButton(selected = selected, onClick = null)
                Text(label, modifier = Modifier.padding(start = 8.dp), maxLines = 5,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            if (onPreview != null) TextButton(onClick = onPreview) { Text("Read full greeting") }
        }
    }
}

@Composable
fun SelectionCard(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
        ),
        border = elsewhereCardBorder()
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
