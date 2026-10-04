package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.ChatLorebookState
import com.example.data.model.LorebookEntryEntity
import com.example.domain.repository.LorebookRepository
import com.example.ui.components.ElsewhereEmptyState
import com.example.ui.components.elsewhereCardBorder
import com.example.ui.components.elsewhereTextFieldColors
import com.example.ui.components.elsewhereTopAppBarColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatLorebooksScreen(
    chatId: String,
    repository: LorebookRepository,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var states by remember { mutableStateOf<List<ChatLorebookState>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshVersion by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var editingOverlay by remember { mutableStateOf<Pair<ChatLorebookState, LorebookEntryEntity>?>(null) }

    fun refresh() { refreshVersion++ }

    LaunchedEffect(chatId, refreshVersion) {
        loading = true
        try {
            states = repository.getChatLorebookStates(chatId)
        } catch (error: Exception) {
            errorMessage = error.message ?: "Unable to load chat Lorebooks."
        } finally {
            loading = false
        }
    }

    editingOverlay?.let { (state, entry) ->
        val overlay = state.overlays[entry.entryId]
        var value by remember(state.lorebook.lorebookId, entry.entryId) {
            mutableStateOf(if (overlay?.hasCurrentOverride == true) overlay.currentContent else entry.currentContent)
        }
        AlertDialog(
            onDismissRequest = { editingOverlay = null },
            title = { Text("Chat Current: ${entry.title}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This override belongs only to this chat. It does not change the reusable Lorebook or Canon baseline.")
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text("Current content for this chat") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
                        colors = elsewhereTextFieldColors()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { repository.setChatCurrentOverride(chatId, entry.entryId, value) }
                            .onFailure { errorMessage = it.message ?: "Unable to save chat Current." }
                        editingOverlay = null
                        refresh()
                    }
                }) { Text("Save Chat Override") }
            },
            dismissButton = { TextButton(onClick = { editingOverlay = null }) { Text("Cancel") } }
        )
    }

    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text("Lorebook Error") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { errorMessage = null }) { Text("OK") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chat Lorebooks") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = elsewhereTopAppBarColors()
            )
        },
        modifier = modifier.testTag("chat_lorebooks_screen"),
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        when {
            loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            states.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                ElsewhereEmptyState(
                    icon = Icons.Default.MenuBook,
                    title = "No Lorebooks in your library.",
                    message = "Create or import one from the Lorebooks tab, then return here to attach it."
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).testTag("chat_lorebooks_list"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        "Attach only the books this story needs. An unbound story_id is not global; nothing is used until you attach it here. Detaching preserves this chat's Current overrides and manual activation for later reattachment.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                items(states, key = { it.lorebook.lorebookId }) { state ->
                    val attached = state.attachment != null
                    Card(Modifier.fillMaxWidth(), border = elsewhereCardBorder()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(state.lorebook.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    if (state.lorebook.description.isNotBlank()) Text(state.lorebook.description, style = MaterialTheme.typography.bodySmall)
                                    if (!state.lorebook.enabled) Text("Disabled in the library", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(if (attached) "Attached" else "Not attached", style = MaterialTheme.typography.labelSmall)
                                    Switch(
                                        checked = attached,
                                        onCheckedChange = { shouldAttach ->
                                            scope.launch {
                                                runCatching {
                                                    if (shouldAttach) repository.attachLorebook(chatId, state.lorebook.lorebookId)
                                                    else repository.detachLorebook(chatId, state.lorebook.lorebookId)
                                                }.onFailure { errorMessage = it.message ?: "Unable to change attachment." }
                                                refresh()
                                            }
                                        },
                                        modifier = Modifier.testTag("attach_lorebook_${state.lorebook.lorebookId}")
                                    )
                                }
                            }
                            if (attached) {
                                HorizontalDivider()
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Use in this chat", style = MaterialTheme.typography.titleSmall)
                                        Text("Turn off temporarily without removing overlays.", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Switch(
                                        checked = state.attachment?.isEnabled == true,
                                        onCheckedChange = { enabled ->
                                            scope.launch {
                                                runCatching { repository.setAttachmentEnabled(chatId, state.lorebook.lorebookId, enabled) }
                                                    .onFailure { errorMessage = it.message ?: "Unable to update this attachment." }
                                                refresh()
                                            }
                                        }
                                    )
                                }
                                state.entries.forEach { entry ->
                                    val overlay = state.overlays[entry.entryId]
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                                        shape = MaterialTheme.shapes.medium
                                    ) {
                                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(entry.title, style = MaterialTheme.typography.titleSmall)
                                                    Text(
                                                        "${entry.activationMode} · priority ${entry.priority}${if (!entry.enabled) " · entry disabled" else ""}",
                                                        style = MaterialTheme.typography.bodySmall
                                                    )
                                                }
                                                if (entry.activationMode == "manual") {
                                                    Column(horizontalAlignment = Alignment.End) {
                                                        Text("Manual active", style = MaterialTheme.typography.labelSmall)
                                                        Switch(
                                                            checked = overlay?.manualActive == true,
                                                            onCheckedChange = { active ->
                                                                scope.launch {
                                                                    runCatching { repository.setManualActive(chatId, entry.entryId, active) }
                                                                        .onFailure { errorMessage = it.message ?: "Unable to update manual activation." }
                                                                    refresh()
                                                                }
                                                            },
                                                            modifier = Modifier.testTag("manual_lore_${entry.entryId}")
                                                        )
                                                    }
                                                }
                                            }
                                            Text(
                                                if (overlay?.hasCurrentOverride == true) "Using chat-specific Current" else "Using Lorebook Current",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                OutlinedButton(onClick = { editingOverlay = state to entry }) { Text("Edit Chat Current") }
                                                if (overlay?.hasCurrentOverride == true) {
                                                    TextButton(onClick = {
                                                        scope.launch {
                                                            runCatching { repository.clearChatCurrentOverride(chatId, entry.entryId) }
                                                                .onFailure { errorMessage = it.message ?: "Unable to clear chat override." }
                                                            refresh()
                                                        }
                                                    }) { Text("Use Book Current") }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
