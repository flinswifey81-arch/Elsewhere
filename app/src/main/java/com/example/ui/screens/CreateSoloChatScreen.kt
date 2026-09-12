package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.domain.repository.CharacterRepository
import com.example.domain.repository.ChatRepository
import com.example.domain.repository.PersonaRepository
import com.example.data.model.CharacterEntity
import com.example.data.model.PersonaEntity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateSoloChatScreen(
    chatRepository: ChatRepository,
    personaRepository: PersonaRepository,
    characterRepository: CharacterRepository,
    onChatCreated: (String) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    
    val personas by personaRepository.getAllPersonas().collectAsState(initial = emptyList())
    val characters by characterRepository.getAllCharacters().collectAsState(initial = emptyList())
    
    var step by remember { mutableStateOf(1) }
    var selectedPersona by remember { mutableStateOf<PersonaEntity?>(null) }
    var selectedCharacter by remember { mutableStateOf<CharacterEntity?>(null) }
    var chatName by remember { mutableStateOf("") }
    
    var showPasteDialogPersona by remember { mutableStateOf(false) }
    var showPasteDialogCharacter by remember { mutableStateOf(false) }
    var pasteContent by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }

    val personaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            coroutineScope.launch {
                try {
                    val stream = context.contentResolver.openInputStream(it)
                    val json = stream?.bufferedReader()?.use { r -> r.readText() }
                    if (json != null) personaRepository.importPersonaJson(json)
                } catch (e: Exception) { importError = e.localizedMessage }
            }
        }
    }
    
    val characterLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            coroutineScope.launch {
                try {
                    val stream = context.contentResolver.openInputStream(it)
                    val json = stream?.bufferedReader()?.use { r -> r.readText() }
                    if (json != null) characterRepository.importCharacterJson(json)
                } catch (e: Exception) { importError = e.localizedMessage }
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
                }
            )
        }
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
                                    Button(onClick = { personaLauncher.launch(arrayOf("application/json", "*/*")) }) { Text("Import Persona") }
                                    OutlinedButton(onClick = { showPasteDialogPersona = true }) { Text("Paste JSON") }
                                }
                            }
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                            items(personas) { persona ->
                                SelectionCard(
                                    text = persona.displayName,
                                    selected = selectedPersona == persona,
                                    onClick = { selectedPersona = persona }
                                )
                            }
                            item {
                                Spacer(modifier = Modifier.height(16.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { personaLauncher.launch(arrayOf("application/json", "*/*")) }) { Text("Import File") }
                                    TextButton(onClick = { showPasteDialogPersona = true }) { Text("Paste JSON") }
                                }
                            }
                        }
                        Button(
                            onClick = { step = 2 },
                            enabled = selectedPersona != null,
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
                                    Button(onClick = { characterLauncher.launch(arrayOf("application/json", "*/*")) }) { Text("Import Character") }
                                    OutlinedButton(onClick = { showPasteDialogCharacter = true }) { Text("Paste JSON") }
                                }
                            }
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                            items(characters) { character ->
                                SelectionCard(
                                    text = character.displayName,
                                    selected = selectedCharacter == character,
                                    onClick = { 
                                        selectedCharacter = character
                                        chatName = "Chat with ${character.displayName}"
                                    }
                                )
                            }
                            item {
                                Spacer(modifier = Modifier.height(16.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { characterLauncher.launch(arrayOf("application/json", "*/*")) }) { Text("Import File") }
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
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                val chatId = chatRepository.createSoloChat(
                                    displayName = chatName.takeIf { it.isNotBlank() } ?: "Untitled Chat",
                                    personaId = selectedPersona!!.personaId,
                                    characterId = selectedCharacter!!.characterId
                                )
                                onChatCreated(chatId)
                            }
                        },
                        enabled = chatName.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Create Chat") }
                }
            }
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
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
        )
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
