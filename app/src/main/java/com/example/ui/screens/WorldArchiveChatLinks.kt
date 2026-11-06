package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.model.ChatEntity
import com.example.data.model.WorldEntity
import com.example.data.model.WorldResourceType
import com.example.domain.repository.WorldRepository
import com.example.domain.repository.ChatRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * World memberships already exist in Room, but the original editor had no way to associate
 * a chat through the UI. Expose links before archiving so a World never silently exports a
 * definition with zero history when the user expected its episodes.
 *
 * Linking/unlinking does NOT move, alter or delete the underlying chat.
 */
@Composable
fun WorldArchiveChatLinks(
    repository: WorldRepository,
    chatRepository: ChatRepository,
    world: WorldEntity,
    onOpenChat: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var chats by remember(world.worldId) { mutableStateOf<List<ChatEntity>>(emptyList()) }
    var linkedIds by remember(world.worldId) { mutableStateOf<Set<String>>(emptySet()) }
    var adding by remember { mutableStateOf(false) }
    var pendingUnlink by remember { mutableStateOf<ChatEntity?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        chats = chatRepository.getAllChats().first()
            .sortedWith(compareByDescending<ChatEntity> { it.updatedAt }.thenBy { it.chatId })
        linkedIds = repository.getMemberships(world.worldId)
            .filter { it.resourceType == WorldResourceType.CHAT.name }
            .map { it.resourceId }.toSet()
    }

    LaunchedEffect(world.worldId) {
        try {
            refresh()
        } catch (failure: Exception) {
            error = failure.message ?: "Linked chats couldn't be loaded."
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().testTag("world_archive_chat_links")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Linked chats (${linkedIds.size})",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium)
            OutlinedButton(
                enabled = !busy,
                modifier = Modifier.testTag("world_archive_link_chat"),
                onClick = {
                    error = null
                    scope.launch {
                        try {
                            refresh()
                            adding = true
                        } catch (failure: Exception) {
                            error = failure.message ?: "Unable to list chats."
                        }
                    }
                }
            ) { Text("Link chat") }
        }
        Text(
            "Only explicitly linked chats are included in this World's archive. " +
                "Linking a chat does not move or delete it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        chats.filter { it.chatId in linkedIds }.forEach { chat ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(chat.displayName, style = MaterialTheme.typography.bodyMedium)
                    Text(chat.chatType.name.lowercase().replaceFirstChar { it.uppercase() } +
                        " · " + chat.chatId.takeLast(8),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(
                    enabled = !busy,
                    modifier = Modifier.testTag("world_archive_open_chat_" + chat.chatId),
                    onClick = { onOpenChat(chat.chatId) }
                ) { Text("Open") }
                TextButton(enabled = !busy, onClick = { pendingUnlink = chat }) {
                    Text("Unlink")
                }
            }
        }
        if (linkedIds.isEmpty()) {
            Text(
                "No chats linked yet. An archive of this World would contain no episode history.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
    }

    if (adding) {
        val available = chats.filter { it.chatId !in linkedIds }
        AlertDialog(
            onDismissRequest = { if (!busy) adding = false },
            title = { Text("Link existing chat to World") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Select a conversation to include in this World's archive. " +
                            "You can unlink it later without deleting the chat.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (available.isEmpty()) {
                        Text("Every local chat is already linked to this World.")
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                            items(available, key = { it.chatId }) { chat ->
                                TextButton(
                                    modifier = Modifier.fillMaxWidth().testTag(
                                        "world_link_chat_" + chat.chatId
                                    ),
                                    enabled = !busy,
                                    onClick = {
                                        scope.launch {
                                            busy = true
                                            try {
                                                repository.attach(
                                                    world.worldId, WorldResourceType.CHAT, chat.chatId
                                                )
                                                refresh()
                                                adding = false
                                                error = null
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (failure: Exception) {
                                                error = failure.message ?: "Chat couldn't be linked."
                                            } finally {
                                                busy = false
                                            }
                                        }
                                    }
                                ) {
                                    Text(chat.displayName + " · " + chat.chatType.name.lowercase() +
                                        " · " + chat.chatId.takeLast(8))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = { adding = false }) { Text("Close") }
            }
        )
    }

    pendingUnlink?.let { chat ->
        AlertDialog(
            onDismissRequest = { if (!busy) pendingUnlink = null },
            title = { Text("Unlink chat from World?") },
            text = {
                Text(
                    "Remove ${chat.displayName} from this World's membership? " +
                        "Its messages, variants and images will remain in Elsewhere. " +
                        "They won't be included the next time you export this World."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    modifier = Modifier.testTag("world_archive_unlink_confirm"),
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                repository.detach(world.worldId, WorldResourceType.CHAT, chat.chatId)
                                refresh()
                                pendingUnlink = null
                                error = null
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                error = failure.message ?: "Chat couldn't be unlinked."
                            } finally {
                                busy = false
                            }
                        }
                    }
                ) { Text("Unlink only") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { pendingUnlink = null }) { Text("Cancel") }
            }
        )
    }
}
