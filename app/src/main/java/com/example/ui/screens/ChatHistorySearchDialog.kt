package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.MessageEntity
import com.example.domain.repository.MessageRepository
import kotlinx.coroutines.CancellationException

/**
 * Local archive reader, not memory retrieval. Opening/searching a result never
 * adds its content to a prompt, restores a Wack memory, or changes a variant.
 */
@Composable
fun ChatHistorySearchDialog(
    chatId: String,
    messageRepository: MessageRepository,
    onDismiss: () -> Unit
) {
    var query by remember(chatId) { mutableStateOf("") }
    var submitted by remember(chatId) { mutableStateOf("") }
    var revision by remember(chatId) { mutableIntStateOf(0) }
    var matches by remember(chatId) { mutableStateOf<List<MessageEntity>>(emptyList()) }
    var selected by remember(chatId) { mutableStateOf<MessageEntity?>(null) }
    var isSearching by remember(chatId) { mutableStateOf(false) }
    var error by remember(chatId) { mutableStateOf<String?>(null) }

    LaunchedEffect(chatId, revision) {
        if (revision == 0) return@LaunchedEffect
        isSearching = true
        error = null
        matches = emptyList()
        selected = null
        try {
            matches = messageRepository.searchSelectedHistoryForReader(
                chatId = chatId, phrase = submitted, maxResults = 30
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "Could not search this chat."
        } finally {
            isSearching = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (selected == null) "Search chat history" else "Earlier message") },
        text = {
            if (selected != null) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        selected!!.speakerDisplayNameSnapshot,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Column(
                        modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())
                    ) {
                        Text(selected!!.content, style = MaterialTheme.typography.bodyMedium)
                    }
                    TextButton(onClick = { selected = null }) {
                        Text("Back to matches")
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Finds whole words and consecutive phrases in selected messages. " +
                            "Older, pre-Wack messages remain readable here, but never enter AI context.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it.take(120) },
                        label = { Text("Word or phrase") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("history_search_input")
                    )
                    Button(
                        onClick = {
                            submitted = query.trim()
                            revision++
                        },
                        enabled = query.trim().length in 2..120 && !isSearching,
                        modifier = Modifier.testTag("history_search_submit")
                    ) { Text("Search") }

                    if (isSearching) {
                        CircularProgressIndicator()
                    } else if (error != null) {
                        Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    } else if (revision > 0) {
                        Text(
                            if (matches.isEmpty()) "No matching selected messages."
                            else "Showing " + matches.size + " newest matches for \"" + submitted + "\".",
                            style = MaterialTheme.typography.bodySmall
                        )
                        LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                            items(matches, key = { it.messageId }) { item ->
                                Column(
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable { selected = item }
                                        .testTag("history_search_hit")
                                        .padding(vertical = 10.dp)
                                ) {
                                    Text(
                                        item.speakerDisplayNameSnapshot,
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        item.content.replace('\n', ' ').replace('\r', ' ').take(240),
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
