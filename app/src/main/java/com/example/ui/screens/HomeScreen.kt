package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
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
    onNewSoloChat: () -> Unit,
    onNewGroupChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    val recentChats by chatRepository.getRecentChatSummaries(3).collectAsState(initial = emptyList())

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
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(top = 28.dp, start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                // Header
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = elsewhereCardBorder()
                    ) {
                        Image(
                            painter = painterResource(R.drawable.elsewhere_logo),
                            contentDescription = "Elsewhere — Private stories, your way.",
                            modifier = Modifier.fillMaxWidth().heightIn(max = 168.dp),
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
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(bottom = 12.dp, start = 8.dp)
                        )
                        
                        if (recentChats.isEmpty()) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                ),
                                border = elsewhereCardBorder()
                            ) {
                                Box(
                                    modifier = Modifier.padding(32.dp).fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "It's quiet here.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                recentChats.forEach { chatSummary ->
                                    ChatCard(
                                        chatSummary = chatSummary,
                                        onClick = { onNavigateToChat(chatSummary.chat.chatId) }
                                    )
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
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(bottom = 12.dp, start = 8.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            ActionCard(
                                title = "Solo Chat",
                                icon = Icons.Filled.Add,
                                onClick = onNewSoloChat,
                                modifier = Modifier.weight(1f)
                            )
                            ActionCard(
                                title = "Group Chat",
                                icon = Icons.Filled.AutoAwesome,
                                onClick = onNewGroupChat,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                
                // Library Section
                item {
                    Column {
                        Text(
                            text = "Library",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(bottom = 12.dp, start = 8.dp)
                        )
                        ActionCard(
                            title = "Characters & Personas",
                            icon = Icons.Filled.Bookmarks,
                            onClick = onNavigateToLibrary,
                            modifier = Modifier.fillMaxWidth(),
                            horizontal = true
                        )
                    }
                }
            }
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
