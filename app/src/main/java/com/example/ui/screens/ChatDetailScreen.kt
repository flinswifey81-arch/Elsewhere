package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.model.MessageEntity
import com.example.data.model.SpeakerType
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
    onNavigateToInspector: () -> Unit = {},
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
            memoryRepository = appContainer.memoryRepository
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
                    IconButton(onClick = onNavigateToInspector) {
                        Icon(Icons.Filled.Info, contentDescription = "Inspector")
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
                    Column(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                            
                            if (state.isGenerating || state.partialStopped) {
                                item {
                                    StreamingBubble(
                                        content = state.streamingContent ?: "",
                                        characterName = state.chatSummary.characters.firstOrNull()?.displayName ?: "Character"
                                    )
                                }
                            }
                        }

                        if (state.partialStopped) {
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
                                        IconButton(
                                            onClick = {
                                                if (draft.isNotBlank()) viewModel.sendMessage()
                                                else viewModel.retryGeneration()
                                            }
                                        ) {
                                            Icon(Icons.Filled.Send, contentDescription = "Send", tint = if (draft.isNotBlank() || state.messages.lastOrNull()?.speakerType == SpeakerType.PERSONA) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
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
