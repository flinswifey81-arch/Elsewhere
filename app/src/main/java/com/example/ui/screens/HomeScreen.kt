package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.repository.ChatRepository
import com.example.ui.components.elsewhereCardBorder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    chatRepository: ChatRepository,
    onNavigateToChat: (String) -> Unit,
    onNavigateToLibrary: () -> Unit,
    onNavigateToLorebooks: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToChats: () -> Unit,
    onNewSoloChat: () -> Unit,
    onNewGroupChat: () -> Unit,
    modifier: Modifier = Modifier,
    onScenarios: (() -> Unit)? = null
) {
    var recentChats by remember(chatRepository) {
        mutableStateOf<List<com.example.data.model.ChatSummary>?>(null)
    }
    LaunchedEffect(chatRepository) {
        chatRepository.getRecentChatSummaries(3).collect { recentChats = it }
    }

    Scaffold(
        modifier = modifier.testTag("home_screen"),
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            // Subtle abstract window/portal background gradient at top
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                Color.Transparent
                            )
                        )
                    )
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).testTag("home_list"),
                contentPadding = PaddingValues(top = 28.dp, start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Header
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                        border = elsewhereCardBorder()
                    ) {
                        Image(
                            painter = painterResource(R.drawable.elsewhere_logo),
                            contentDescription = "Elsewhere — Private stories, your way.",
                            modifier = Modifier.fillMaxWidth().heightIn(max = 112.dp),
                            contentScale = ContentScale.FillWidth
                        )
                    }
                }

                // Continue Section
                item {
                    Column {
                        Text(
                            text = "Continue",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 12.dp, start = 8.dp)
                        )
                        
                        val loadedChats = recentChats
                        if (loadedChats == null) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                ),
                                border = elsewhereCardBorder()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    Text("Opening your local archive…")
                                }
                            }
                        } else if (loadedChats.isEmpty()) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                ),
                                border = elsewhereCardBorder()
                            ) {
                                Box(
                                    modifier = Modifier.padding(24.dp).fillMaxWidth(),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Your next doorway is ready.", style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            text = "Start a Solo or Group chat below. Characters, Personas, and Lorebooks stay in your local library.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                val latest = loadedChats.first()
                                Text("Resume your latest story", style = MaterialTheme.typography.bodySmall)
                                ChatCard(
                                    chatSummary = latest,
                                    onClick = { onNavigateToChat(latest.chat.chatId) }
                                )
                                if (loadedChats.size > 1) {
                                    Text("Other recent chats", style = MaterialTheme.typography.bodySmall)
                                    loadedChats.drop(1).forEach { chatSummary ->
                                        ChatCard(
                                            chatSummary = chatSummary,
                                            onClick = { onNavigateToChat(chatSummary.chat.chatId) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Start Something New Section
                item {
                    Column {
                        Text(
                            text = "Start Something New",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 12.dp, start = 8.dp)
                        )
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val stackActions = maxWidth < 360.dp || LocalDensity.current.fontScale > 1.25f
                            if (stackActions) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ActionCard("New Solo Chat", Icons.Filled.Add, onNewSoloChat, Modifier.fillMaxWidth(), horizontal = true)
                                    ActionCard("New Group Chat", Icons.Filled.AutoAwesome, onNewGroupChat, Modifier.fillMaxWidth(), horizontal = true)
                                }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    ActionCard("New Solo Chat", Icons.Filled.Add, onNewSoloChat, Modifier.weight(1f))
                                    ActionCard("New Group Chat", Icons.Filled.AutoAwesome, onNewGroupChat, Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
                
                onScenarios?.let { openScenarios ->
                    item { ActionCard("Scenarios · Quick Scene or Sandbox", Icons.Filled.AutoStories, openScenarios, Modifier.fillMaxWidth(), horizontal = true) }
                }
                // Library Section
                item {
                    Column {
                        Text(
                            text = "Library & Setup",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 12.dp, start = 8.dp)
                        )
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                            border = elsewhereCardBorder()
                        ) {
                            ManagementLink("Characters & Personas", "Build and organize your cast", Icons.Filled.Bookmarks, onNavigateToLibrary)
                            HorizontalDivider()
                            ManagementLink("Lorebooks", "Manage reusable world context", Icons.Filled.MenuBook, onNavigateToLorebooks)
                            HorizontalDivider()
                            ManagementLink("Settings", "Appearance and local model credentials", Icons.Filled.Settings, onNavigateToSettings)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManagementLink(
    title: String,
    supporting: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ActionCard(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    horizontal: Boolean = false
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = elsewhereCardBorder()
    ) {
        if (horizontal) {
            Row(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        } else {
            Column(
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
