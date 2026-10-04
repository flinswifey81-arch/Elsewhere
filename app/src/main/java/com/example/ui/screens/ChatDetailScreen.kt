package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.model.MessageEntity
import com.example.data.model.SpeakerType
import com.example.data.model.ChatType
import com.example.di.AppContainer
import com.example.ui.components.MarkdownText
import com.example.ui.components.elsewhereDestructiveButtonColors
import com.example.ui.components.elsewhereTextFieldColors
import com.example.ui.components.elsewhereTopAppBarColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailScreen(
    chatId: String,
    appContainer: AppContainer,
    onNavigateBack: () -> Unit,
    onChatDeleted: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToInspector: (String?) -> Unit = {},
    onNavigateToLorebooks: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val viewModel: ChatDetailViewModel = viewModel(
        factory = ChatDetailViewModel.Factory(
            chatId = chatId,
            chatRepository = appContainer.chatRepository,
            messageRepository = appContainer.messageRepository,
            characterRepository = appContainer.characterRepository,
            personaRepository = appContainer.personaRepository,
            modelProvider = appContainer.modelProvider,
            memoryRepository = appContainer.memoryRepository,
            lorebookRepository = appContainer.lorebookRepository
        )
    )

    val uiState by viewModel.uiState.collectAsState()
    val draft by viewModel.draft.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var showDeleteChatConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(uiState) {
        if (uiState is ChatUiState.Deleted) {
            onChatDeleted()
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHostState) { snackbarData ->
                Snackbar(
                    snackbarData = snackbarData,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    val title = (uiState as? ChatUiState.Success)?.chatSummary?.chat?.displayName ?: "Chat"
                    Text(text = title, maxLines = 1)
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        onNavigateToInspector((uiState as? ChatUiState.Success)?.selectedGroupCharacterId)
                    }) {
                        Icon(Icons.Filled.Info, contentDescription = "Inspector")
                    }
                    IconButton(onClick = onNavigateToLorebooks) {
                        Icon(Icons.Filled.MenuBook, contentDescription = "Chat Lorebooks")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = { showDeleteChatConfirm = true }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete Chat",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                },
                colors = elsewhereTopAppBarColors()
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val state = uiState) {
                is ChatUiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is ChatUiState.Error -> {
                    Text("Error loading chat.", modifier = Modifier.align(Alignment.Center))
                }
                is ChatUiState.Deleted -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                is ChatUiState.Success -> {
                    val isHistoryOnly = state.isHistoryOnlyChat()
                    Column(modifier = Modifier.fillMaxSize()) {
                        val hasStreamingItem = state.isGenerating || state.partialStopped
                        AutoScrollingChatColumn(
                            conversationKey = chatId,
                            historyLoaded = state.historyLoaded,
                            itemCount = state.groupedMessages.size + if (hasStreamingItem) 1 else 0,
                            isStreaming = hasStreamingItem,
                            streamContentKey = state.streamingContent,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            items(state.groupedMessages, key = { it.primaryMessage.messageId }) { group ->
                                MessageBubble(
                                    group = group,
                                    onSwitchVariant = { msgId -> viewModel.switchVariant(group.primaryMessage.variantGroupId ?: group.primaryMessage.messageId, msgId) },
                                    onRegenerate = { viewModel.regenerateMessage(it) },
                                    onContinue = { viewModel.continueMessage(it) },
                                    onCopy = { content ->
                                        clipboardManager.setText(AnnotatedString(content))
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar("Message copied")
                                        }
                                    },
                                    onEdit = { id, content -> viewModel.editMessage(id, content) },
                                    onDelete = { viewModel.deleteMessage(it) },
                                    onShowMetadata = { viewModel.showGenerationDetails(it) }
                                )
                            }
                            
                            if (hasStreamingItem) {
                                item(key = "streaming-response") {
                                    val generationCharacterName = state.generationCharacterId
                                        ?.let { id ->
                                            state.groupCharacters.firstOrNull {
                                                it.character.characterId == id
                                            }?.character?.displayName
                                                ?: state.chatSummary.characters.firstOrNull {
                                                    it.characterId == id
                                                }?.displayName
                                        }
                                    StreamingBubble(
                                        content = state.streamingContent ?: "",
                                        characterName = generationCharacterName
                                            ?: state.chatSummary.characters.firstOrNull()?.displayName
                                            ?: "Character"
                                    )
                                }
                            }
                        }

                        if (
                            state.chatSummary.chat.chatType == ChatType.GROUP &&
                            state.chatSummary.personas.isNotEmpty() &&
                            !state.partialStopped
                        ) {
                            GroupCharacterControls(
                                characters = state.groupCharacters,
                                availableCharacters = state.availableGroupCharacters,
                                selectedCharacterId = state.selectedGroupCharacterId,
                                isGenerating = state.isGenerating,
                                canGenerate = state.messages.any { it.isPrimaryVariant },
                                onSelectCharacter = viewModel::selectGroupCharacter,
                                onGenerate = viewModel::generateGroupResponse,
                                onSetActive = viewModel::setGroupCharacterActive,
                                onMove = viewModel::moveGroupCharacter,
                                onRemove = viewModel::removeGroupCharacter,
                                onAdd = viewModel::addGroupCharacter
                            )
                        }

                        if (isHistoryOnly) {
                            HistoryOnlyChatNotice()
                        } else if (state.partialStopped) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp)
                                    .imePadding(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                Button(onClick = { viewModel.keepPartial() }) {
                                    Text("Keep Partial")
                                }
                                OutlinedButton(onClick = { viewModel.discardPartial() }) {
                                    Text("Discard")
                                }
                            }
                        } else {
                            state.generationError?.let { error ->
                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(error, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                        TextButton(onClick = viewModel::retryGeneration) { Text("Retry") }
                                    }
                                }
                            }
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                tonalElevation = 3.dp,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = draft,
                                        onValueChange = viewModel::onDraftChanged,
                                        modifier = Modifier.weight(1f),
                                        placeholder = { Text("Type a message...") },
                                        maxLines = 5,
                                        shape = MaterialTheme.shapes.medium,
                                        colors = elsewhereTextFieldColors()
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    if (state.isGenerating) {
                                        IconButton(onClick = { viewModel.stopGeneration() }) {
                                            Icon(Icons.Filled.Stop, contentDescription = "Stop Generation", tint = MaterialTheme.colorScheme.error)
                                        }
                                    } else {
                                        val isGroup = state.chatSummary.chat.chatType == ChatType.GROUP
                                        val canSend = draft.isNotBlank() ||
                                            (!isGroup && state.messages.lastOrNull()?.speakerType == SpeakerType.PERSONA)
                                        IconButton(
                                            onClick = {
                                                if (draft.isNotBlank()) viewModel.sendMessage()
                                                else if (!isGroup) viewModel.retryGeneration()
                                            },
                                            enabled = canSend
                                        ) {
                                            Icon(
                                                Icons.Filled.Send,
                                                contentDescription = "Send",
                                                tint = if (canSend) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    if (state.generationDetails.isVisible) {
                        AlertDialog(
                            onDismissRequest = { viewModel.hideGenerationDetails() },
                            title = { Text("Generation Details") },
                            text = {
                                val meta = state.generationDetails.metadata
                                if (meta == null) {
                                    Text("No metadata found for this response.")
                                } else {
                                    Column {
                                        Text("Model: ${meta.resolvedModelId}")
                                        Text("Provider: ${meta.provider}")
                                        Text("Tokens: ${meta.completionTokens} / ${meta.totalTokens}")
                                        Text("Cost: $${meta.reportedCost}")
                                        Text("Duration: ${meta.generationTimeMs} ms")
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { viewModel.hideGenerationDetails() }) {
                                    Text("Close")
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showDeleteChatConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteChatConfirm = false },
            title = { Text("Delete Chat") },
            text = { Text("Delete this entire chat and all of its messages and chat-specific memory? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteChatConfirm = false
                        viewModel.deleteChat()
                    },
                    colors = elsewhereDestructiveButtonColors()
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteChatConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

internal fun ChatUiState.Success.isHistoryOnlyChat(): Boolean =
    chatSummary.personas.isEmpty() || when (chatSummary.chat.chatType) {
        ChatType.SOLO -> chatSummary.characters.isEmpty()
        ChatType.GROUP -> groupParticipantsLoaded && groupCharacters.isEmpty()
    }

@Composable
private fun HistoryOnlyChatNotice() {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "History only — a required Character or Persona was permanently deleted. " +
                "Saved messages and speaker names are preserved. Add a Character where available; " +
                "otherwise new messages and AI replies remain disabled.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
    }
}

@Composable
internal fun GroupCharacterControls(
    characters: List<GroupCharacterState>,
    availableCharacters: List<com.example.data.model.CharacterEntity>,
    selectedCharacterId: String?,
    isGenerating: Boolean,
    canGenerate: Boolean,
    onSelectCharacter: (String) -> Unit,
    onGenerate: (String?) -> Unit,
    onSetActive: (String, Boolean) -> Unit,
    onMove: (String, Int) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: (String) -> Unit
) {
    val activeCharacters = characters.filter { it.isActive }.sortedBy { it.sortPosition }
    val orderedCharacters = characters.sortedBy { it.sortPosition }
    val participantIds = characters.mapTo(mutableSetOf()) { it.character.characterId }
    val addableCharacters = availableCharacters.filter { it.characterId !in participantIds }
    val selectedCharacter = activeCharacters.firstOrNull {
        it.character.characterId == selectedCharacterId
    }
    var showManageCharacters by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Next Character",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                activeCharacters.forEach { groupCharacter ->
                    FilterChip(
                        selected = groupCharacter.character.characterId == selectedCharacterId,
                        onClick = { onSelectCharacter(groupCharacter.character.characterId) },
                        enabled = !isGenerating,
                        label = { Text(groupCharacter.character.displayName) }
                    )
                }
            }
            Button(
                onClick = { onGenerate(selectedCharacterId) },
                enabled = selectedCharacter != null && canGenerate && !isGenerating,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (isGenerating) {
                        "Generating ${selectedCharacter?.character?.displayName ?: "reply"}…"
                    } else {
                        "Generate ${selectedCharacter?.character?.displayName ?: "Character"} Reply"
                    }
                )
            }
            TextButton(
                onClick = { showManageCharacters = true },
                enabled = !isGenerating,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Manage Characters")
            }
        }
    }

    if (showManageCharacters) {
        AlertDialog(
            onDismissRequest = { showManageCharacters = false },
            title = { Text("Manage Group Characters") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Reorder, mute, or remove Characters. Removing a Character keeps every historical message and speaker name.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    orderedCharacters.forEachIndexed { index, groupCharacter ->
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    "${index + 1}. ${groupCharacter.character.displayName}" +
                                        if (groupCharacter.isActive) "" else " (muted)",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    TextButton(
                                        onClick = { onMove(groupCharacter.character.characterId, -1) },
                                        enabled = index > 0
                                    ) { Text("Up") }
                                    TextButton(
                                        onClick = { onMove(groupCharacter.character.characterId, 1) },
                                        enabled = index < orderedCharacters.lastIndex
                                    ) { Text("Down") }
                                    TextButton(
                                        onClick = {
                                            onSetActive(
                                                groupCharacter.character.characterId,
                                                !groupCharacter.isActive
                                            )
                                        }
                                    ) { Text(if (groupCharacter.isActive) "Mute" else "Activate") }
                                    TextButton(
                                        onClick = { onRemove(groupCharacter.character.characterId) },
                                        colors = elsewhereDestructiveButtonColors()
                                    ) { Text("Remove") }
                                }
                            }
                        }
                    }
                    if (addableCharacters.isNotEmpty()) {
                        HorizontalDivider()
                        Text("Add existing Character", style = MaterialTheme.typography.titleSmall)
                        addableCharacters.forEach { character ->
                            OutlinedButton(
                                onClick = { onAdd(character.characterId) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Add ${character.displayName}")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showManageCharacters = false }) { Text("Done") }
            }
        )
    }
}

@Composable
internal fun AutoScrollingChatColumn(
    conversationKey: String,
    historyLoaded: Boolean,
    itemCount: Int,
    isStreaming: Boolean,
    streamContentKey: String?,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit
) {
    var initialScrollComplete by remember(conversationKey) { mutableStateOf(false) }
    var followStreaming by remember(conversationKey) { mutableStateOf(false) }
    val isUserDragging by listState.interactionSource.collectIsDraggedAsState()
    val nearBottomThresholdPx = with(LocalDensity.current) { 72.dp.roundToPx() }

    LaunchedEffect(conversationKey, historyLoaded, itemCount) {
        if (historyLoaded && !initialScrollComplete) {
            listState.scrollToBottom(itemCount)
            initialScrollComplete = true
            followStreaming = true
        }
    }

    LaunchedEffect(isUserDragging, initialScrollComplete) {
        if (!initialScrollComplete) return@LaunchedEffect
        followStreaming = if (isUserDragging) {
            false
        } else {
            listState.isNearBottom(nearBottomThresholdPx)
        }
    }

    LaunchedEffect(isStreaming, streamContentKey, itemCount) {
        if (
            initialScrollComplete &&
            isStreaming &&
            followStreaming &&
            !isUserDragging
        ) {
            listState.scrollToBottom(itemCount)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.alpha(
            if (!historyLoaded || initialScrollComplete) 1f else 0f
        ),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

private suspend fun LazyListState.scrollToBottom(itemCount: Int) {
    if (itemCount <= 0) return

    val lastIndex = itemCount - 1
    scrollToItem(lastIndex)
    val lastItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == lastIndex } ?: return
    val hiddenBottom = lastItem.offset + lastItem.size - layoutInfo.viewportEndOffset
    if (hiddenBottom > 0) {
        scrollBy(hiddenBottom.toFloat())
    }
}

private fun LazyListState.isNearBottom(thresholdPx: Int): Boolean {
    val info = layoutInfo
    if (info.totalItemsCount == 0) return true

    val lastVisibleItem = info.visibleItemsInfo.lastOrNull() ?: return false
    if (lastVisibleItem.index != info.totalItemsCount - 1) return false

    val hiddenBottom = lastVisibleItem.offset + lastVisibleItem.size - info.viewportEndOffset
    return hiddenBottom <= thresholdPx
}

@Composable
fun MessageBubble(
    group: MessageGroup,
    onSwitchVariant: (String) -> Unit,
    onRegenerate: (String) -> Unit,
    onContinue: (String) -> Unit,
    onCopy: (String) -> Unit,
    onEdit: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onShowMetadata: (String) -> Unit
) {
    val message = group.primaryMessage
    val isUser = message.speakerType == SpeakerType.PERSONA
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val shape = if (isUser) {
        RoundedCornerShape(16.dp, 16.dp, 0.dp, 16.dp)
    } else {
        RoundedCornerShape(16.dp, 16.dp, 16.dp, 0.dp)
    }

    var showMenu by remember { mutableStateOf(false) }
    var isEditing by remember { mutableStateOf(false) }
    var editContent by remember { mutableStateOf(message.content) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = message.speakerDisplayNameSnapshot,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
            if (message.editedAt != null) {
                Text(
                    text = "(edited)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }
        
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
        ) {
            if (isUser) {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                    }
                    MessageMenu(
                        expanded = showMenu,
                        onDismiss = { showMenu = false },
                        onCopy = { onCopy(message.content) },
                        onEdit = { isEditing = true },
                        onDelete = { showDeleteConfirm = true },
                        onRegenerate = null,
                        onContinue = null,
                        onShowMetadata = null
                    )
                }
            }

            Surface(
                shape = shape,
                color = color,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(0.88f)
            ) {
                if (isEditing) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        OutlinedTextField(
                            value = editContent,
                            onValueChange = { editContent = it },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            colors = elsewhereTextFieldColors()
                        )
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = { isEditing = false; editContent = message.content }) {
                                Text("Cancel")
                            }
                            TextButton(onClick = {
                                onEdit(message.messageId, editContent)
                                isEditing = false
                            }) {
                                Text("Save")
                            }
                        }
                    }
                } else {
                    MarkdownText(
                        markdown = message.content,
                        color = textColor,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            if (!isUser) {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                    }
                    MessageMenu(
                        expanded = showMenu,
                        onDismiss = { showMenu = false },
                        onCopy = { onCopy(message.content) },
                        onEdit = { isEditing = true },
                        onDelete = { showDeleteConfirm = true },
                        onRegenerate = { onRegenerate(message.messageId) },
                        onContinue = { onContinue(message.messageId) },
                        onShowMetadata = { onShowMetadata(message.messageId) }
                    )
                }
            }
        }

        if (group.variants.size > 1) {
            val currentIndex = group.variants.indexOfFirst { it.messageId == message.messageId }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp, start = 8.dp, end = 8.dp)
            ) {
                Text(
                    text = "<",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        if (currentIndex > 0) {
                            onSwitchVariant(group.variants[currentIndex - 1].messageId)
                        }
                    }.padding(4.dp)
                )
                Text(
                    text = "${currentIndex + 1} / ${group.variants.size}",
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    text = ">",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        if (currentIndex < group.variants.size - 1) {
                            onSwitchVariant(group.variants[currentIndex + 1].messageId)
                        }
                    }.padding(4.dp)
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Message") },
            text = { Text("Are you sure you want to delete this message?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(message.messageId)
                        showDeleteConfirm = false
                    },
                    colors = elsewhereDestructiveButtonColors()
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun MessageMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRegenerate: (() -> Unit)?,
    onContinue: (() -> Unit)?,
    onShowMetadata: (() -> Unit)?
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        DropdownMenuItem(
            text = { Text("Copy") },
            onClick = { onDismiss(); onCopy() }
        )
        DropdownMenuItem(
            text = { Text("Edit") },
            onClick = { onDismiss(); onEdit() }
        )
        if (onRegenerate != null) {
            DropdownMenuItem(
                text = { Text("Regenerate") },
                onClick = { onDismiss(); onRegenerate() }
            )
        }
        if (onContinue != null) {
            DropdownMenuItem(
                text = { Text("Continue") },
                onClick = { onDismiss(); onContinue() }
            )
        }
        if (onShowMetadata != null) {
            DropdownMenuItem(
                text = { Text("Generation Details") },
                onClick = { onDismiss(); onShowMetadata() }
            )
        }
        DropdownMenuItem(
            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
            onClick = { onDismiss(); onDelete() }
        )
    }
}

@Composable
fun StreamingBubble(content: String, characterName: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "$characterName (typing...)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
        Surface(
            shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 0.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth(0.88f)
        ) {
            MarkdownText(
                markdown = content,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}
