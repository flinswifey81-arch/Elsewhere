package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.model.StoryMemoryEntity
import com.example.domain.memory.StoryMemoryClaim
import com.example.domain.memory.StoryMemoryLifecycle
import com.example.domain.repository.ChatRepository
import com.example.domain.repository.StoryMemoryRepository
import com.example.ui.components.elsewhereTopAppBarColors
import kotlinx.coroutines.launch

/**
 * Local manual story archive. Does not inject the saved records into model
 * requests; retrieval/ranking are a separate, independently gated milestone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryMemoriesScreen(
    chatId: String,
    repository: StoryMemoryRepository,
    chatRepository: ChatRepository,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var memories by remember(chatId) { mutableStateOf<List<StoryMemoryEntity>>(emptyList()) }
    var loading by remember(chatId) { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    var boundary by remember(chatId) { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var createDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<StoryMemoryEntity?>(null) }
    var removing by remember { mutableStateOf<StoryMemoryEntity?>(null) }
    var restoring by remember { mutableStateOf<StoryMemoryEntity?>(null) }

    LaunchedEffect(chatId) {
        boundary = chatRepository.getChatSummaryById(chatId)?.chat?.contextResetOrderIndex
    }
    LaunchedEffect(chatId, repository) {
        try {
            repository.observe(chatId).collect { memories = it; loading = false }
        } catch (failure: Exception) {
            error = failure.message ?: "Unable to load saved story memories."
            loading = false
        }
    }
    fun applyChange(action: suspend () -> Unit) {
        if (working) return
        scope.launch {
            working = true
            try { action() }
            catch (failure: Exception) { error = failure.message ?: "Unable to update story memory." }
            finally { working = false }
        }
    }

    var draft by remember(createDialog, editing?.memoryId) {
        mutableStateOf(editing?.content ?: "")
    }
    var claim by remember(createDialog) { mutableStateOf(StoryMemoryClaim.FACT) }
    var selectingClaim by remember { mutableStateOf(false) }

    if (createDialog || editing != null) AlertDialog(
        onDismissRequest = { if (!working) { createDialog = false; editing = null } },
        title = { Text(if (editing == null) "Save Story Memory" else "Edit Story Memory") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Only this chat stores this record. Saved story memories are not yet automatically sent to a model.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    draft, { draft = it.take(12_000) },
                    label = { Text("What should Elsewhere remember?") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp),
                    minLines = 3,
                    supportingText = { Text("${draft.length} / 12,000") },
                    enabled = !working
                )
                if (editing == null) Box {
                    OutlinedButton(onClick = { selectingClaim = true }, enabled = !working) {
                        Text("Type: ${claim.name.lowercase().replace('_', ' ')}")
                    }
                    DropdownMenu(selectingClaim, onDismissRequest = { selectingClaim = false }) {
                        StoryMemoryClaim.entries.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category.name.lowercase().replace('_', ' ')) },
                                onClick = { claim = category; selectingClaim = false }
                            )
                        }
                    }
                }
                Text("Knowledge: Storyteller only. No Character is automatically granted private information.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                enabled = draft.isNotBlank() && !working,
                onClick = {
                    val target = editing
                    applyChange {
                        if (target == null) repository.saveManual(chatId, draft, claim)
                        else repository.update(chatId, target.memoryId, text = draft)
                        createDialog = false
                        editing = null
                    }
                }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = { createDialog = false; editing = null }, enabled = !working) {
                Text("Cancel")
            }
        }
    )

    removing?.let { memory ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Delete this Story Memory?") },
            text = { Text("This permanently deletes this saved record and any explicit Wack restoration grants. The original chat messages remain untouched.") },
            confirmButton = {
                TextButton(enabled = !working, onClick = {
                    applyChange {
                        repository.delete(chatId, memory.memoryId)
                        removing = null
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } }
        )
    }
    restoring?.let { memory ->
        AlertDialog(
            onDismissRequest = { restoring = null },
            title = { Text("Restore this memory after Wack?") },
            text = {
                Text("This explicitly permits this one memory to be considered for future recall after the current Wack boundary. It does NOT restore the old transcript, other memories, or discarded variants. No model request is made.")
            },
            confirmButton = {
                TextButton(enabled = !working, onClick = {
                    applyChange {
                        repository.restoreAcrossWack(chatId, memory.memoryId)
                        restoring = null
                    }
                }) { Text("Restore this one memory") }
            },
            dismissButton = { TextButton(onClick = { restoring = null }) { Text("Cancel") } }
        )
    }
    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("Story Memory") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } }
        )
    }

    Scaffold(
        modifier = modifier.testTag("story_memories_screen"),
        topBar = {
            TopAppBar(
                title = { Text("Story Memories") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = elsewhereTopAppBarColors()
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { createDialog = true }, modifier = Modifier.testTag("story_memory_add")) {
                Icon(Icons.Filled.Bookmarks, contentDescription = "Save a new Story Memory")
            }
        }
    ) { padding ->
        when {
            loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).testTag("story_memories_list"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Your chat-local story archive", style = MaterialTheme.typography.titleMedium)
                        Text("Create, correct and pin memories here. Model retrieval is not active yet. This page does not change legacy 'Update Memory Now'.",
                            style = MaterialTheme.typography.bodySmall)
                        if (boundary != null) Text(
                            "This chat has a Wack boundary. Old memories require individual Restore approval.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
                        )
                        if (memories.isEmpty()) Text("No Story Memories saved in this chat yet.")
                        OutlinedButton(onClick = { createDialog = true }, enabled = !working) { Text("Save new Story Memory") }
                    }
                }
                items(memories, key = { it.memoryId }) { memory ->
                    Card(Modifier.fillMaxWidth().testTag("story_memory_${memory.memoryId}")) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(memory.content, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${memory.claim.lowercase().replace('_', ' ')} • " +
                                    "${memory.lifecycle.lowercase()} • storyteller only • " +
                                    (if (memory.originKind == "MESSAGE") "from selected message" else "manually saved"),
                                style = MaterialTheme.typography.labelSmall
                            )
                            if (memory.hidden) Text("Hidden from future retrieval", style = MaterialTheme.typography.labelSmall)
                            if (memory.pinned) Text("Pinned for future relevance", style = MaterialTheme.typography.labelSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(enabled = !working, onClick = { editing = memory }) { Text("Edit") }
                                TextButton(enabled = !working, onClick = {
                                    applyChange { repository.update(chatId, memory.memoryId, pinned = !memory.pinned) }
                                }) { Text(if (memory.pinned) "Unpin" else "Pin") }
                                TextButton(enabled = !working, onClick = {
                                    applyChange { repository.update(chatId, memory.memoryId, hidden = !memory.hidden) }
                                }) { Text(if (memory.hidden) "Show" else "Hide") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (memory.lifecycle != "RESOLVED") TextButton(enabled = !working, onClick = {
                                    applyChange { repository.update(chatId, memory.memoryId, lifecycle = StoryMemoryLifecycle.RESOLVED) }
                                }) { Text("Resolve") }
                                if (boundary != null && memory.sourceOrderIndex <= boundary!!) {
                                    OutlinedButton(enabled = !working, onClick = { restoring = memory }) { Text("Restore after Wack") }
                                }
                                TextButton(enabled = !working, onClick = { removing = memory }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        }
    }
}
