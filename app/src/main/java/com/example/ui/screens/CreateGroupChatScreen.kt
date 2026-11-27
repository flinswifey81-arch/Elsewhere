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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.domain.repository.CharacterRepository
import com.example.domain.repository.ChatRepository
import com.example.domain.repository.ProfileImageKind
import com.example.domain.repository.ProfileImageRepository
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
    modifier: Modifier = Modifier,
    redesignImages: ProfileImageRepository? = null
) {
    val coroutineScope = rememberCoroutineScope()
    val personas by personaRepository.getAllPersonas().collectAsState(initial = emptyList())
    val characters by characterRepository.getAllCharacters().collectAsState(initial = emptyList())

    var step by rememberSaveable { mutableStateOf(1) }
    var selectedPersonaId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedCharacters by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    fun toggleCharacter(id: String) {
        selectedCharacters = ArrayList(if (id in selectedCharacters) selectedCharacters - id else selectedCharacters + id)
    }
    var chatName by rememberSaveable { mutableStateOf("") }

    val pickerState = rememberSaveableStateHolder()
    BackHandler(enabled = step > 1) { step-- }
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
                    Text("Select Persona", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    if (personas.isEmpty()) {
                        Text("No personas found.", color = MaterialTheme.colorScheme.error)
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
                        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                            items(personas) { persona ->
                                val isSelected = selectedPersonaId == persona.personaId
                                SelectionCard(
                                    text = persona.displayName,
                                    selected = isSelected,
                                    onClick = { selectedPersonaId = persona.personaId }
                                )
                            }
                        }
                        Button(
                            onClick = { step = 2 },
                            enabled = personas.any { it.personaId == selectedPersonaId },
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
                        if (redesignImages != null) {
                            pickerState.SaveableStateProvider("characters") { StoryIdentityPicker(
                                entries = characters.map { it.storyIdentity() },
                                selectedIds = selectedCharacters.toList(),
                                repository = redesignImages,
                                kind = ProfileImageKind.CHARACTER,
                                multiple = true,
                                onSelect = { id ->
                                    toggleCharacter(id)
                                },
                                modifier = Modifier.weight(1f)
                            ) }
                        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                            items(characters) { character ->
                                val isSelected = selectedCharacters.contains(character.characterId)
                                val selectedOrder = selectedCharacters.indexOf(character.characterId)
                                SelectionCard(
                                    text = if (selectedOrder >= 0) {
                                        "${selectedOrder + 1}. ${character.displayName}"
                                    } else {
                                        character.displayName
                                    },
                                    selected = isSelected,
                                    onClick = { 
                                        toggleCharacter(character.characterId)
                                    }
                                )
                            }
                        }
                        Button(
                            onClick = { 
                                chatName = "Group Chat (${selectedCharacters.size} chars)"
                                step = 3 
                            },
                            enabled = selectedCharacters.count { id -> characters.any { it.characterId == id } } >= 2,
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
                                    personaIds = listOf(requireNotNull(selectedPersonaId)),
                                    characterIds = selectedCharacters.filter { id -> characters.any { it.characterId == id } }
                                )
                                onChatCreated(chatId)
                            }
                        },
                        enabled = chatName.isNotBlank() && personas.any { it.personaId == selectedPersonaId } &&
                            selectedCharacters.count { id -> characters.any { it.characterId == id } } >= 2,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Create Chat") }
                }
            }
        }
    }
}
