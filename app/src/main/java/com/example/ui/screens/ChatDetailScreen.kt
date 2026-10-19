package com.example.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.model.MessageEntity
import com.example.data.model.SpeakerType
import com.example.data.model.ChatType
import com.example.data.model.MessageAttachmentEntity
import com.example.di.AppContainer
import com.example.ui.components.MarkdownText
import com.example.ui.components.ProfileImageAvatar
import com.example.ui.components.elsewhereDestructiveButtonColors
import com.example.ui.components.elsewhereTextFieldColors
import com.example.ui.components.elsewhereTopAppBarColors
import com.example.ui.theme.LocalElsewhereSemanticColors
import com.example.domain.repository.ProfileImageKind
import com.example.domain.repository.ProfileImageRepository
import com.example.domain.story.StoryResponse
import com.example.domain.story.StoryResponseCodec
import com.example.domain.story.StorySegmentType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
    onNavigateToNpcBank: () -> Unit = {},
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
            lorebookRepository = appContainer.lorebookRepository,
            // Co-op is intentionally dormant in the product UI. Persisted room data and
            // protocol wiring remain available for a future revisit, but ordinary chats must
            // stay local and usable rather than inheriting stale room restrictions.
            coopSessionManager = null,
            messageAttachmentRepository = appContainer.messageAttachmentRepository,
            scenarioRepository = appContainer.scenarioRepository,
            npcRepository = appContainer.npcRepository,
            npcOutputParser = appContainer.npcOutputParser
        )
    )

    val uiState by viewModel.uiState.collectAsState()
    val draft by viewModel.draft.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var showDeleteChatConfirm by remember { mutableStateOf(false) }
    var showChatToolsMenu by remember { mutableStateOf(false) }
    var showChatMenu by remember { mutableStateOf(false) }
    var showWackConfirm by remember { mutableStateOf(false) }
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let(viewModel::addDraftImage)
    }

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
                    Box {
                        val successState = uiState as? ChatUiState.Success
                        val toolActionsEnabled = successState != null &&
                            successState.historyLoaded &&
                            !successState.isGenerating &&
                            !successState.partialStopped &&
                            successState.inChatToolStatus?.kind != InChatToolStatusKind.WORKING
                        TextButton(onClick = { showChatToolsMenu = true }) { Text("Tools", maxLines = 1) }
                        DropdownMenu(
                            expanded = showChatToolsMenu,
                            onDismissRequest = { showChatToolsMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text("Update Memory Now")
                                },
                                enabled = toolActionsEnabled,
                                onClick = {
                                    showChatToolsMenu = false
                                    viewModel.updateMemoryNow()
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Wack — reset future context",
                                        color = MaterialTheme.colorScheme.error
                                    )
                                },
                                enabled = toolActionsEnabled,
                                onClick = {
                                    showChatToolsMenu = false
                                    showWackConfirm = true
                                }
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { showChatMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Chat navigation and actions")
                        }
                        DropdownMenu(expanded = showChatMenu, onDismissRequest = { showChatMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Context Inspector") },
                                leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                                onClick = {
                                    showChatMenu = false
                                    onNavigateToInspector((uiState as? ChatUiState.Success)?.selectedGroupCharacterId)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Chat Lorebooks") },
                                leadingIcon = { Icon(Icons.Filled.MenuBook, contentDescription = null) },
                                onClick = { showChatMenu = false; onNavigateToLorebooks() }
                            )
                            if ((uiState as? ChatUiState.Success)?.chatSummary?.chat?.chatType != ChatType.SCENARIO) DropdownMenuItem(
                                text = { Text("NPC Bank") },
                                leadingIcon = { Icon(Icons.Filled.Groups, contentDescription = null) },
                                onClick = { showChatMenu = false; onNavigateToNpcBank() }
                            )
                            DropdownMenuItem(
                                text = { Text("Chat Settings") },
                                leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                                onClick = { showChatMenu = false; onNavigateToSettings() }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Delete entire chat", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { showChatMenu = false; showDeleteChatConfirm = true }
                            )
                        }
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
                        state.inChatToolStatus?.let { status ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = if (status.kind == InChatToolStatusKind.ERROR) {
                                    MaterialTheme.colorScheme.errorContainer
                                } else {
                                    MaterialTheme.colorScheme.secondaryContainer
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (status.kind == InChatToolStatusKind.WORKING) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                    }
                                    Text(status.message, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
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
                                    profileImageRepository = appContainer.profileImageRepository,
                                    attachments = state.attachments.filter { it.messageId == group.primaryMessage.messageId },
                                    attachmentFile = { attachment -> appContainer.messageAttachmentRepository?.resolveFile(attachment) },
                                    mutationsEnabled = !state.isGenerating &&
                                        !state.partialStopped && !state.partialResolutionInProgress,
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
                                        characterId = state.generationCharacterId
                                            ?: state.chatSummary.characters.firstOrNull()?.characterId,
                                        profileImageRepository = appContainer.profileImageRepository,
                                        characterName = generationCharacterName
                                            ?: (if (state.chatSummary.chat.chatType == ChatType.SCENARIO) "Narrator" else null)
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
                                Button(
                                    onClick = { viewModel.keepPartial() },
                                    enabled = !state.partialResolutionInProgress
                                ) {
                                    Text("Keep Partial")
                                }
                                OutlinedButton(
                                    onClick = { viewModel.discardPartial() },
                                    enabled = !state.partialResolutionInProgress
                                ) {
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
                                Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                                    if (state.draftAttachments.isNotEmpty()) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            state.draftAttachments.forEach { attachment ->
                                                DraftImagePreview(
                                                    attachment = attachment,
                                                    file = appContainer.messageAttachmentRepository?.resolveFile(attachment),
                                                    onRemove = { viewModel.removeDraftImage(attachment.attachmentId) }
                                                )
                                            }
                                        }
                                        Text(
                                            "Attached images are normalized to remove embedded metadata, stored only on this device, and sent to the selected model provider when this turn is generated.",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                        Spacer(Modifier.height(8.dp))
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                    IconButton(
                                        onClick = {
                                            imagePicker.launch(
                                                PickVisualMediaRequest(
                                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                                )
                                            )
                                        },
                                        enabled = !state.isGenerating && state.draftAttachments.size < 4
                                    ) {
                                        Icon(Icons.Filled.AddPhotoAlternate, contentDescription = "Attach image")
                                    }
                                        OutlinedTextField(
                                        value = draft,
                                        onValueChange = viewModel::onDraftChanged,
                                        modifier = Modifier.weight(1f),
                                        placeholder = { Text("Type a message...") },
                                        maxLines = 5,
                                        shape = MaterialTheme.shapes.medium,
                                            colors = elsewhereTextFieldColors(),
                                            enabled = true
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    if (state.isGenerating) {
                                        IconButton(onClick = { viewModel.stopGeneration() }) {
                                            Icon(Icons.Filled.Stop, contentDescription = "Stop Generation", tint = MaterialTheme.colorScheme.error)
                                        }
                                    } else {
                                        val isGroup = state.chatSummary.chat.chatType == ChatType.GROUP
                                        val canSend = draft.isNotBlank() || state.draftAttachments.isNotEmpty() ||
                                            (!isGroup && state.messages.lastOrNull()?.speakerType == SpeakerType.PERSONA)
                                        IconButton(
                                            onClick = {
                                                if (draft.isNotBlank() || state.draftAttachments.isNotEmpty()) viewModel.sendMessage()
                                                else if (!isGroup) viewModel.retryGeneration()
                                            },
                                            enabled = canSend && !state.partialResolutionInProgress
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

    if (showWackConfirm) {
        AlertDialog(
            onDismissRequest = { showWackConfirm = false },
            title = { Text("Wack old context?") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "This creates a persistent reset boundary for future model context. " +
                            "Your visible transcript and variants, durable memories, Lorebooks, NPC Bank records, images, and chat/model/Narrator settings stay saved. " +
                            "If this chat contains facts you want saved as durable memory, cancel and run Update Memory Now first. " +
                            "The rolling summary is cleared, and messages before this point will no longer be used for generation, Lorebook keyword scans, automatic summaries, or Context Inspector. " +
                            "Retry, Regenerate, and Continue will not run on pre-reset messages. This does not remove information from requests already sent to a model provider."
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showWackConfirm = false
                        viewModel.wackContext()
                    },
                    colors = elsewhereDestructiveButtonColors()
                ) {
                    Text("Wack")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWackConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
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
        ChatType.SCENARIO -> false
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
    profileImageRepository: ProfileImageRepository? = null,
    attachments: List<MessageAttachmentEntity> = emptyList(),
    attachmentFile: (MessageAttachmentEntity) -> File? = { null },
    responsibleParticipantName: String? = null,
    mutationsEnabled: Boolean = true,
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
    val ensembleStory = remember(message.content) { StoryResponseCodec.decode(message.content) }
    if (ensembleStory != null && message.speakerType == SpeakerType.CHARACTER) {
        EnsembleMessageBubble(
            group = group,
            story = ensembleStory,
            profileImageRepository = profileImageRepository,
            responsibleParticipantName = responsibleParticipantName,
            mutationsEnabled = mutationsEnabled,
            onSwitchVariant = onSwitchVariant,
            onRegenerate = onRegenerate,
            onContinue = onContinue,
            onCopy = onCopy,
            onEdit = onEdit,
            onDelete = onDelete,
            onShowMetadata = onShowMetadata
        )
        return
    }
    val profileImageKind = when (message.speakerType) {
        SpeakerType.PERSONA -> ProfileImageKind.PERSONA
        SpeakerType.CHARACTER -> ProfileImageKind.CHARACTER
        else -> null
    }
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    val semanticColors = LocalElsewhereSemanticColors.current
    val color = if (isUser) semanticColors.personaBubble else semanticColors.characterBubble
    val textColor = if (isUser) semanticColors.onPersonaBubble else semanticColors.onCharacterBubble
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
        responsibleParticipantName?.let { participant ->
            Text(
                text = "Sent by $participant",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
        
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
        ) {
            if (!isUser) {
                ProfileImageAvatar(
                    repository = profileImageRepository,
                    kind = profileImageKind,
                    profileId = message.speakerId,
                    displayName = message.speakerDisplayNameSnapshot,
                    size = 36.dp,
                    contentDescription = "Profile picture for ${message.speakerDisplayNameSnapshot}",
                    modifier = Modifier.testTag("message_avatar_${message.speakerType.name}_${message.speakerId}")
                )
                Spacer(Modifier.width(6.dp))
            }
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
                        onShowMetadata = null,
                        mutationsEnabled = mutationsEnabled
                    )
                }
            }

            Surface(
                shape = shape,
                color = color,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(0.70f)
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
                    Column(modifier = Modifier.padding(12.dp)) {
                        if (attachments.isNotEmpty()) {
                            attachments.sortedBy { it.sortPosition }.forEach { attachment ->
                                LocalAttachmentImage(
                                    file = attachmentFile(attachment),
                                    contentDescription = "Image sent by ${message.speakerDisplayNameSnapshot}",
                                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                        if (message.content.isNotBlank()) {
                            MarkdownText(
                                markdown = message.content,
                                color = textColor
                            )
                        }
                    }
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
                        onShowMetadata = { onShowMetadata(message.messageId) },
                        mutationsEnabled = mutationsEnabled
                    )
                }
            }
            if (isUser) {
                Spacer(Modifier.width(6.dp))
                ProfileImageAvatar(
                    repository = profileImageRepository,
                    kind = profileImageKind,
                    profileId = message.speakerId,
                    displayName = message.speakerDisplayNameSnapshot,
                    size = 36.dp,
                    contentDescription = "Profile picture for ${message.speakerDisplayNameSnapshot}",
                    modifier = Modifier.testTag("message_avatar_${message.speakerType.name}_${message.speakerId}")
                )
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
                    modifier = Modifier.clickable(enabled = mutationsEnabled) {
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
                    modifier = Modifier.clickable(enabled = mutationsEnabled) {
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
private fun EnsembleMessageBubble(
    group: MessageGroup,
    story: StoryResponse,
    profileImageRepository: ProfileImageRepository?,
    responsibleParticipantName: String?,
    mutationsEnabled: Boolean,
    onSwitchVariant: (String) -> Unit,
    onRegenerate: (String) -> Unit,
    onContinue: (String) -> Unit,
    onCopy: (String) -> Unit,
    onEdit: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onShowMetadata: (String) -> Unit
) {
    val message = group.primaryMessage
    var showMenu by remember { mutableStateOf(false) }
    var isEditing by remember { mutableStateOf(false) }
    var editContent by remember(message.messageId, message.content) {
        mutableStateOf(StoryResponseCodec.readableTranscript(story))
    }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        responsibleParticipantName?.let { participant ->
            Text(
                text = "Sent by $participant",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
        if (message.editedAt != null) {
            Text(
                text = "(edited)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }

        if (isEditing) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                Column(Modifier.padding(8.dp)) {
                    OutlinedTextField(
                        value = editContent,
                        onValueChange = { editContent = it },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        colors = elsewhereTextFieldColors()
                    )
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = {
                            isEditing = false
                            editContent = StoryResponseCodec.readableTranscript(story)
                        }) { Text("Cancel") }
                        TextButton(onClick = {
                            onEdit(message.messageId, editContent)
                            isEditing = false
                        }) { Text("Save") }
                    }
                }
            }
        } else {
            StorySegments(
                story = story,
                primaryCharacterId = message.speakerId,
                primaryCharacterName = message.speakerDisplayNameSnapshot,
                profileImageRepository = profileImageRepository
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 42.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (group.variants.size > 1) {
                EnsembleVariantSelector(
                    group = group,
                    mutationsEnabled = mutationsEnabled,
                    onSwitchVariant = onSwitchVariant
                )
            }
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                }
                MessageMenu(
                    expanded = showMenu,
                    onDismiss = { showMenu = false },
                    onCopy = { onCopy(StoryResponseCodec.readableTranscript(story)) },
                    onEdit = { isEditing = true },
                    onDelete = { showDeleteConfirm = true },
                    onRegenerate = { onRegenerate(message.messageId) },
                    onContinue = { onContinue(message.messageId) },
                    onShowMetadata = { onShowMetadata(message.messageId) },
                    mutationsEnabled = mutationsEnabled
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Message") },
            text = { Text("Delete this complete narrated response?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(message.messageId)
                        showDeleteConfirm = false
                    },
                    colors = elsewhereDestructiveButtonColors()
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun StorySegments(
    story: StoryResponse,
    primaryCharacterId: String?,
    primaryCharacterName: String,
    profileImageRepository: ProfileImageRepository?
) {
    val semanticColors = LocalElsewhereSemanticColors.current
    story.segments.forEachIndexed { index, segment ->
        if (index > 0) Spacer(Modifier.height(8.dp))
        when (segment.type) {
            StorySegmentType.NARRATION -> Text(
                text = segment.content,
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 6.dp)
                    .testTag("story_narration_$index")
            )
            StorySegmentType.CHARACTER, StorySegmentType.NPC -> {
                val isPrimary = segment.type == StorySegmentType.CHARACTER
                val speakerId = if (isPrimary) primaryCharacterId else segment.speakerId
                val speakerName = if (isPrimary) primaryCharacterName else segment.speakerName ?: "Supporting character"
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                    Text(
                        text = speakerName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 50.dp, bottom = 2.dp)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProfileImageAvatar(
                            repository = profileImageRepository,
                            kind = if (isPrimary) ProfileImageKind.CHARACTER else null,
                            profileId = speakerId,
                            displayName = speakerName,
                            size = 36.dp,
                            contentDescription = "Profile picture for $speakerName",
                            modifier = Modifier.testTag("story_speaker_${segment.type.name}_$index")
                        )
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 0.dp),
                            color = semanticColors.characterBubble,
                            tonalElevation = 1.dp,
                            modifier = Modifier.fillMaxWidth(0.76f)
                        ) {
                            MarkdownText(
                                markdown = segment.content,
                                color = semanticColors.onCharacterBubble,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EnsembleVariantSelector(
    group: MessageGroup,
    mutationsEnabled: Boolean,
    onSwitchVariant: (String) -> Unit
) {
    val currentIndex = group.variants.indexOfFirst { it.messageId == group.primaryMessage.messageId }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "<",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(enabled = mutationsEnabled && currentIndex > 0) {
                onSwitchVariant(group.variants[currentIndex - 1].messageId)
            }.padding(4.dp)
        )
        Text(text = "${currentIndex + 1} / ${group.variants.size}", style = MaterialTheme.typography.labelSmall)
        Text(
            text = ">",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(
                enabled = mutationsEnabled && currentIndex in 0 until group.variants.lastIndex
            ) {
                onSwitchVariant(group.variants[currentIndex + 1].messageId)
            }.padding(4.dp)
        )
    }
}

@Composable
private fun DraftImagePreview(
    attachment: MessageAttachmentEntity,
    file: File?,
    onRemove: () -> Unit
) {
    Box(Modifier.size(92.dp)) {
        LocalAttachmentImage(
            file = file,
            contentDescription = "Selected image",
            modifier = Modifier.fillMaxSize()
        )
        FilledIconButton(
            onClick = onRemove,
            modifier = Modifier.align(Alignment.TopEnd).size(28.dp)
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Remove image", modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun LocalAttachmentImage(
    file: File?,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, file?.absolutePath) {
        value = withContext(Dispatchers.IO) {
            file?.takeIf(File::isFile)?.let { imageFile ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(imageFile.absolutePath, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
                BitmapFactory.decodeFile(
                    imageFile.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )?.asImageBitmap()
            }
        }
    }
    val image = bitmap
    if (image == null) {
        Surface(modifier = modifier, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Box(contentAlignment = Alignment.Center) { Text("Image unavailable", style = MaterialTheme.typography.bodySmall) }
        }
    } else {
        Image(
            bitmap = image,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Crop
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
    onShowMetadata: (() -> Unit)?,
    mutationsEnabled: Boolean = true
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
            onClick = { onDismiss(); onEdit() },
            enabled = mutationsEnabled
        )
        if (onRegenerate != null) {
            DropdownMenuItem(
                text = { Text("Regenerate") },
                onClick = { onDismiss(); onRegenerate() },
                enabled = mutationsEnabled
            )
        }
        if (onContinue != null) {
            DropdownMenuItem(
                text = { Text("Continue") },
                onClick = { onDismiss(); onContinue() },
                enabled = mutationsEnabled
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
            onClick = { onDismiss(); onDelete() },
            enabled = mutationsEnabled
        )
    }
}

@Composable
fun StreamingBubble(
    content: String,
    characterName: String,
    characterId: String? = null,
    profileImageRepository: ProfileImageRepository? = null
) {
    val story = remember(content) { StoryResponseCodec.decode(content) }
    if (story != null) {
        Column(modifier = Modifier.fillMaxWidth()) {
            StorySegments(
                story = story,
                primaryCharacterId = characterId,
                primaryCharacterName = characterName,
                profileImageRepository = profileImageRepository
            )
            Text(
                text = "Generating…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 50.dp, top = 4.dp)
            )
        }
        return
    }
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProfileImageAvatar(
                repository = profileImageRepository,
                kind = ProfileImageKind.CHARACTER,
                profileId = characterId,
                displayName = characterName,
                size = 36.dp,
                contentDescription = "Profile picture for $characterName",
                modifier = Modifier.testTag("streaming_avatar_${characterId.orEmpty()}")
            )
            Spacer(Modifier.width(6.dp))
            Surface(
                shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 0.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(0.70f)
            ) {
                MarkdownText(
                    markdown = content,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}
