package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.domain.repository.CharacterRepository
import com.example.domain.repository.ChatRepository
import com.example.domain.repository.PersonaRepository
import com.example.ui.components.elsewhereTextFieldColors
import com.example.ui.components.elsewhereTopAppBarColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupChatScreen(
    chatRepository: ChatRepository,
    personaRepository: PersonaRepository,
    characterRepository: CharacterRepository,
    onChatCreated: (String) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val personas by personaRepository.getAllPersonas().collectAsState(initial = emptyList())
    val characters by characterRepository.getAllCharacters().collectAsState(initial = emptyList())

    var step by remember { mutableStateOf(1) }
    val selectedPersonas = remember { mutableStateListOf<String>() }
    val selectedCharacters = remember { mutableStateListOf<String>() }
    var chatName by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Group Chat") },
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
                    Text("Select Personas (1 or more)", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    if (personas.isEmpty()) {
                        Text("No personas found.", color = MaterialTheme.colorScheme.error)
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                            items(personas) { persona ->
                                val isSelected = selectedPersonas.contains(persona.personaId)
                                SelectionCard(
                                    text = persona.displayName,
                                    selected = isSelected,
                                    onClick = { 
                                        if (isSelected) selectedPersonas.remove(persona.personaId)
                                        else selectedPersonas.add(persona.personaId)
                                    }
                                )
                            }
                        }
                        Button(
                            onClick = { step = 2 },
                            enabled = selectedPersonas.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                        ) { Text("Next") }
                    }
                }
                2 -> {
                    Text("Select Characters (2 or more)", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    if (characters.size < 2) {
                        Text("Not enough characters found. Need at least 2.", color = MaterialTheme.colorScheme.error)
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                            items(characters) { character ->
                                val isSelected = selectedCharacters.contains(character.characterId)
                                SelectionCard(
                                    text = character.displayName,
                                    selected = isSelected,
                                    onClick = { 
                                        if (isSelected) selectedCharacters.remove(character.characterId)
                                        else selectedCharacters.add(character.characterId)
                                    }
                                )
                            }
                        }
                        Button(
                            onClick = { 
                                chatName = "Group Chat (${selectedCharacters.size} chars)"
                                step = 3 
                            },
                            enabled = selectedCharacters.size >= 2,
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                        ) { Text("Next") }
                    }
                }
                3 -> {
                    Text("Name this group chat", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = chatName,
                        onValueChange = { chatName = it },
                        label = { Text("Chat Name") },
                        shape = MaterialTheme.shapes.medium,
                        colors = elsewhereTextFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                val chatId = chatRepository.createGroupChat(
                                    displayName = chatName.takeIf { it.isNotBlank() } ?: "Untitled Group",
                                    personaIds = selectedPersonas.toList(),
                                    characterIds = selectedCharacters.toList()
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
