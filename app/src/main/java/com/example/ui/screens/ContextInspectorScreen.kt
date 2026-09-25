package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.di.AppContainer
import com.example.domain.compiler.ContextCompilerV1
import com.example.domain.provider.RoleplayMessage
import com.example.ui.components.elsewhereCardBorder
import com.example.ui.components.elsewhereDestructiveButtonColors
import com.example.ui.components.elsewhereTopAppBarColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextInspectorScreen(
    chatId: String,
    appContainer: AppContainer,
    onNavigateBack: () -> Unit
) {
    var compiledMessages by remember { mutableStateOf<List<RoleplayMessage>>(emptyList()) }
    var totalMessages by remember { mutableStateOf(0) }
    var includedMessages by remember { mutableStateOf(0) }
    var modelId by remember { mutableStateOf("") }
    var routingMode by remember { mutableStateOf("") }
    var durableMemoryCount by remember { mutableStateOf(0) }
    var durableMemories by remember { mutableStateOf<List<com.example.data.model.RoleplayMemoryEntity>>(emptyList()) }
    var hasRollingSummary by remember { mutableStateOf(false) }

    var isLoading by remember { mutableStateOf(true) }
    var refreshVersion by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(chatId, refreshVersion) {
        try {
            val summary = appContainer.chatRepository.getChatSummaryById(chatId)
            val settings = appContainer.chatRepository.getChatSettings(chatId)
                ?: com.example.data.model.ChatSettingsEntity(chatId = chatId)
            val character = summary?.characters?.firstOrNull()?.let { appContainer.characterRepository.getCharacterById(it.characterId) }
            val persona = summary?.personas?.firstOrNull()?.let { appContainer.personaRepository.getPersonaById(it.personaId) }
            val history = appContainer.messageRepository.getMessagesForChat(chatId).first()
                .filter { it.isPrimaryVariant }
            val memories = character?.let {
                appContainer.memoryRepository.getMemoriesOnce(chatId, it.characterId)
            }.orEmpty()
            val rollingSummary = appContainer.memoryRepository.getSummaryOnce(chatId)?.summary
            modelId = settings.selectedModelId ?: "Default"
            routingMode = settings.providerRoutingMode.name
            durableMemoryCount = memories.size
            durableMemories = memories
            hasRollingSummary = !rollingSummary.isNullOrBlank()

            if (character != null && persona != null && history.isNotEmpty()) {
                val currentMessage = history.last()
                val priorHistory = history.dropLast(1)
                val trimmed = priorHistory.takeLast(com.example.domain.memory.AutomaticMemoryManager.DEFAULT_RECENT_MESSAGE_LIMIT)
                totalMessages = history.size
                includedMessages = trimmed.size + 1
                compiledMessages = ContextCompilerV1().compileSoloContext(
                    character = character,
                    persona = persona,
                    chatHistory = priorHistory,
                    currentMessage = currentMessage,
                    responseProfile = settings.responseLengthProfile,
                    customMin = settings.customMin,
                    customTargetMax = settings.customTargetMax,
                    customHardMax = settings.customHardMax,
                    durableMemories = memories,
                    rollingSummary = rollingSummary
                )
            }
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Context Inspector") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = elsewhereTopAppBarColors()
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), border = elsewhereCardBorder()) {
                        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                            Text("Settings Overview", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Text("Model: $modelId", style = MaterialTheme.typography.bodySmall)
                            Text("Routing Mode: $routingMode", style = MaterialTheme.typography.bodySmall)
                            Text("Total Messages: $totalMessages", style = MaterialTheme.typography.bodySmall)
                            Text("Included in Context: $includedMessages", style = MaterialTheme.typography.bodySmall)
                            Text("Omitted from Context: ${totalMessages - includedMessages}", style = MaterialTheme.typography.bodySmall)
                            Text("Durable Memories: $durableMemoryCount", style = MaterialTheme.typography.bodySmall)
                            Text("Rolling Summary: ${if (hasRollingSummary) "Included" else "Not yet needed"}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (durableMemories.isNotEmpty()) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth(), border = elsewhereCardBorder()) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Durable Memories", style = MaterialTheme.typography.titleMedium)
                                durableMemories.forEach { memory ->
                                    Row(modifier = Modifier.fillMaxWidth()) {
                                        Text(
                                            "[${memory.category.name}] ${memory.content}",
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        TextButton(
                                            onClick = {
                                                scope.launch {
                                                    appContainer.memoryRepository.deleteMemory(memory)
                                                    refreshVersion++
                                                }
                                            },
                                            colors = elsewhereDestructiveButtonColors()
                                        ) { Text("Delete") }
                                    }
                                }
                            }
                        }
                    }
                }
                items(compiledMessages) { msg ->
                    Card(modifier = Modifier.fillMaxWidth(), border = elsewhereCardBorder()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Role: ${msg.role.name}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            msg.name?.let {
                                Text("Name: $it", style = MaterialTheme.typography.labelMedium)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(msg.content, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}
