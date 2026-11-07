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
import androidx.compose.material.icons.filled.ChevronRight
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
import com.example.domain.repository.ColorPalette
import com.example.ui.components.ElsewhereThemeBanner
import androidx.compose.ui.text.style.TextOverflow
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
    onScenarios: (() -> Unit)? = null,
    palette: ColorPalette = ColorPalette.PURPLE
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
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxWidth().height(270.dp).background(
                    Brush.verticalGradient(
                        listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), Color.Transparent)
                    )
                )
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).testTag("home_list"),
                contentPadding = PaddingValues(top = 12.dp, start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                item(key = "branding") {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = onNavigateToSettings,
                                modifier = Modifier.testTag("home_open_settings")
                            ) {
                                Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Settings")
                            }
                        }
                        ElsewhereThemeBanner(palette = palette)
                    }
                }

                item(key = "continue") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Continue",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                        val loadedChats = recentChats
                        when {
                            loadedChats == null -> Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                                border = elsewhereCardBorder()
                            ) {
                                Row(
                                    Modifier.padding(20.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    CircularProgressIndicator(Modifier.size(22.dp))
                                    Text("Opening your local archive…")
                                }
                            }
                            loadedChats.isEmpty() -> Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                                border = elsewhereCardBorder()
                            ) {
                                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text("Your next doorway is ready.", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "Begin a Solo, Group or Scenario story whenever you're ready.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                            else -> {
                                Text("Resume your latest story", style = MaterialTheme.typography.bodySmall)
                                val latest = loadedChats.first()
                                ChatCard(
                                    chatSummary = latest,
                                    onClick = { onNavigateToChat(latest.chat.chatId) }
                                )
                            }
                        }
                    }
                }

                if (recentChats?.size ?: 0 > 1) {
                    item(key = "recent") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Recently played", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                TextButton(onClick = onNavigateToChats, modifier = Modifier.testTag("home_see_all_chats")) {
                                    Text("See all")
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                recentChats.orEmpty().drop(1).forEach { summary ->
                                    Card(
                                        onClick = { onNavigateToChat(summary.chat.chatId) },
                                        modifier = Modifier.weight(1f).heightIn(min = 76.dp)
                                            .testTag("home_recent_" + summary.chat.chatId),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                                        border = elsewhereCardBorder()
                                    ) {
                                        Text(
                                            summary.chat.displayName,
                                            modifier = Modifier.padding(14.dp),
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item(key = "create") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Start something new",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val vertical = maxWidth < 340.dp || LocalDensity.current.fontScale > 1.3f
                            if (vertical) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ActionCard("Solo", Icons.Filled.Add, onNewSoloChat, Modifier.fillMaxWidth(), horizontal = true)
                                    ActionCard("Group", Icons.Filled.AutoAwesome, onNewGroupChat, Modifier.fillMaxWidth(), horizontal = true)
                                    onScenarios?.let { ActionCard("Scenario", Icons.Filled.AutoStories, it, Modifier.fillMaxWidth(), horizontal = true) }
                                }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ActionCard("Solo", Icons.Filled.Add, onNewSoloChat, Modifier.weight(1f))
                                    ActionCard("Group", Icons.Filled.AutoAwesome, onNewGroupChat, Modifier.weight(1f))
                                    onScenarios?.let { ActionCard("Scenario", Icons.Filled.AutoStories, it, Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }

                item(key = "library") {
                    Card(
                        onClick = onNavigateToLibrary,
                        modifier = Modifier.fillMaxWidth().testTag("home_library_link"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        border = elsewhereCardBorder()
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Icon(Icons.Filled.Bookmarks, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) {
                                Text("Your Library", style = MaterialTheme.typography.titleMedium)
                                Text("Characters, Personas, Lorebooks and NPCs", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Filled.ChevronRight, contentDescription = null)
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
