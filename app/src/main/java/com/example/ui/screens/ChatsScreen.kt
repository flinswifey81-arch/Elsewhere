package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.repository.ChatRepository
import com.example.ui.components.ElsewhereEmptyState
import com.example.ui.components.elsewhereCardBorder
import com.example.ui.components.elsewhereTopAppBarColors
import com.example.data.model.ChatSummary
import com.example.data.model.ChatType
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(
    chatRepository: ChatRepository,
    onNavigateToChat: (String) -> Unit,
    onNewSoloChat: () -> Unit,
    onNewGroupChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    val chats by chatRepository.getAllChatSummaries().collectAsState(initial = emptyList())
    var showCreateMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Chats", style = MaterialTheme.typography.titleLarge) },
                colors = elsewhereTopAppBarColors()
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (showCreateMenu) {
                    ExtendedFloatingActionButton(
                        onClick = {
                            showCreateMenu = false
                            onNewSoloChat()
                        },
                        icon = { Icon(Icons.Filled.Person, "Solo Chat") },
                        text = { Text("New Solo Chat") },
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    ExtendedFloatingActionButton(
                        onClick = {
                            showCreateMenu = false
                            onNewGroupChat()
                        },
                        icon = { Icon(Icons.Filled.Group, "Group Chat") },
                        text = { Text("New Group Chat") },
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                FloatingActionButton(
                    onClick = { showCreateMenu = !showCreateMenu },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "New Chat")
                }
            }
        },
        modifier = modifier.testTag("chats_screen")
    ) { padding ->
        if (chats.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                    ElsewhereEmptyState(
                        icon = Icons.AutoMirrored.Filled.Chat,
                        title = "No chats yet.",
                        message = "Tap the + button to start a conversation."
                    )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(chats, key = { it.chat.chatId }) { chatSummary ->
                    ChatCard(
                        chatSummary = chatSummary,
                        onClick = { onNavigateToChat(chatSummary.chat.chatId) }
                    )
                }
            }
        }
    }
}

@Composable
fun ChatCard(
    chatSummary: ChatSummary,
    onClick: () -> Unit
) {
    val formatter = remember { SimpleDateFormat("MMM dd", Locale.getDefault()) }
    val timeString = formatter.format(Date(chatSummary.chat.updatedAt))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("chat_card_${chatSummary.chat.chatId}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        , border = elsewhereCardBorder()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = chatSummary.chat.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = timeString,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            
            val participantsText = if (chatSummary.chat.chatType == ChatType.SOLO) {
                val characterName = chatSummary.characters.firstOrNull()?.displayName ?: "Unknown Character"
                val personaName = chatSummary.personas.firstOrNull()?.displayName ?: "Unknown Persona"
                "$personaName & $characterName"
            } else {
                val characterNames = chatSummary.characters.map { it.displayName }
                val personaNames = chatSummary.personas.map { it.displayName }
                val allNames = personaNames + characterNames
                allNames.joinToString(", ")
            }
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = if (chatSummary.chat.chatType == ChatType.SOLO) "Solo" else "Group",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = participantsText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
