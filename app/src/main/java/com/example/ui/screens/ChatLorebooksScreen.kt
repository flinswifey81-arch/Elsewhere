package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    var states by remember(chatId) { mutableStateOf<List<ChatLorebookState>>(emptyList()) }
    var loading by remember(chatId) { mutableStateOf(true) }
    var updating by remember(chatId) { mutableStateOf(false) }
    var refreshVersion by remember(chatId) { mutableIntStateOf(0) }
    var errorMessage by remember(chatId) { mutableStateOf<String?>(null) }
    var editingOverlay by remember(chatId) { mutableStateOf<Pair<ChatLorebookState, LorebookEntryEntity>?>(null) }
    val expandedBookIds = remember(chatId) { mutableStateMapOf<String, Boolean>() }
    val listState = rememberLazyListState()

    fun refresh() { refreshVersion++ }

    // Never replace the list with a full-screen spinner when an attachment changes.
    // Keeping the same LazyListState and stable item keys prevents jump-to-top.
    LaunchedEffect(chatId, refreshVersion) {
        try {
            states = repository.getChatLorebookStates(chatId)
        } catch (error: Exception) {
            errorMessage = error.message ?: "Unable to load chat Lorebooks."
        } finally {
            loading = false
        }
    }

    fun applyChange(failureMessage: String, change: suspend () -> Unit) {
        if (updating) return
        scope.launch {
            updating = true
            try {
                change()
                states = repository.getChatLorebookStates(chatId)
            } catch (error: Exception) {
                errorMessage = error.message ?: failureMessage
            } finally {
                updating = false
            }
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
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding).testTag("chat_lorebooks_list"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "chat_lorebooks_header") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Lorebooks must be attached to affect this chat. Detaching preserves Current overrides and manual activation for later reattachment.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "${states.count { it.attachment != null }} of ${states.size} attached",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    applyChange("Unable to attach all Lorebooks.") {
                                        repository.attachAllLorebooks(chatId)
                                    }
                                },
                                enabled = !updating && states.any { it.attachment == null },
                                modifier = Modifier.testTag("attach_all_lorebooks")
                            ) { Text("Attach All") }
                            OutlinedButton(
                                onClick = {
                                    applyChange("Unable to enable attached Lorebooks.") {
                                        repository.enableAllAttachedLorebooks(chatId)
                                    }
                                },
                                enabled = !updating && states.any { it.attachment?.isEnabled == false },
                                modifier = Modifier.testTag("enable_all_lorebooks")
                            ) { Text("Enable All") }
                        }
                        if (updating) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            "Attach All adds missing books. Enable All turns on attached books for this chat only; neither changes library-wide settings.",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                items(states, key = { it.lorebook.lorebookId }) { state ->
                    val attached = state.attachment != null
                    Card(
                        Modifier.fillMaxWidth().testTag("lorebook_card_${state.lorebook.lorebookId}"),
                        border = elsewhereCardBorder()
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(state.lorebook.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    if (state.lorebook.description.isNotBlank()) {
                                        Text(
                                            state.lorebook.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (!state.lorebook.enabled) Text("Disabled in the library", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(if (attached) "Attached" else "Not attached", style = MaterialTheme.typography.labelSmall)
                                    Switch(
                                        checked = attached,
                                        onCheckedChange = { shouldAttach ->
                                            applyChange("Unable to change attachment.") {
                                                if (shouldAttach) repository.attachLorebook(chatId, state.lorebook.lorebookId)
                                                else repository.detachLorebook(chatId, state.lorebook.lorebookId)
                                            }
                                        },
                                        enabled = !updating,
                                        modifier = Modifier.testTag("attach_lorebook_${state.lorebook.lorebookId}")
                                    )
                                }
                            }
                            if (attached) {
                                HorizontalDivider()
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Use in this chat", style = MaterialTheme.typography.bodyMedium)
                                    }
                                    Switch(
                                        checked = state.attachment?.isEnabled == true,
                                        onCheckedChange = { enabled ->
                                            applyChange("Unable to update this attachment.") {
                                                repository.setAttachmentEnabled(chatId, state.lorebook.lorebookId, enabled)
                                            }
                                        },
                                        enabled = !updating,
                                        modifier = Modifier.testTag("enable_lorebook_${state.lorebook.lorebookId}")
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        expandedBookIds[state.lorebook.lorebookId] =
                                            expandedBookIds[state.lorebook.lorebookId] != true
                                    },
                                    modifier = Modifier.testTag("expand_lorebook_${state.lorebook.lorebookId}")
                                ) {
                                    Text(
                                        if (expandedBookIds[state.lorebook.lorebookId] == true)
                                            "Hide entries" else "Show entries (${state.entries.size})"
                                    )
                                }
                                if (expandedBookIds[state.lorebook.lorebookId] == true) state.entries.forEach { entry ->
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
                                                                applyChange("Unable to update manual activation.") {
                                                                    repository.setManualActive(chatId, entry.entryId, active)
                                                                }
                                                            },
                                                            enabled = !updating,
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
                                                        applyChange("Unable to clear chat override.") {
                                                            repository.clearChatCurrentOverride(chatId, entry.entryId)
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
