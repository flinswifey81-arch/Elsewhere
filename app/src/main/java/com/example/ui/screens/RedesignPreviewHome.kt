package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ChatSummary
import com.example.data.model.ChatType
import com.example.domain.repository.ChatRepository
import com.example.domain.repository.ColorPalette
import com.example.domain.repository.ProfileImageKind
import com.example.domain.repository.ProfileImageRepository
import com.example.ui.components.ElsewhereThemeBanner
import com.example.ui.components.ProfileImageAvatar
import com.example.ui.components.elsewhereCardBorder

/** The approved Home hierarchy connected to the existing navigation and local recent stories. */
@Composable
internal fun RedesignPreviewHome(
    repository: ChatRepository, palette: ColorPalette, images: ProfileImageRepository?,
    onChat: (String) -> Unit, onChats: () -> Unit, onLibrary: () -> Unit, onSettings: () -> Unit,
    onSolo: () -> Unit, onGroup: () -> Unit, onScenario: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val chats by remember(repository) { repository.getRecentChatSummaries(3) }
        .collectAsState(initial = null)
    val colors = MaterialTheme.colorScheme
    Box(modifier.fillMaxSize().background(colors.background).testTag("home_screen"), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth().testTag("home_list"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item("brand") {
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        IconButton(onClick=onSettings,modifier=Modifier.testTag("home_open_settings")) {
                            Icon(Icons.Filled.Settings,contentDescription="Settings")
                        }
                    }
                    ElsewhereThemeBanner(palette)
                }
            }
            item("continue") {
                Text("YOUR NEXT CHAPTER",style=MaterialTheme.typography.labelSmall,
                    color=colors.onSurfaceVariant,letterSpacing=1.2.sp)
                Spacer(Modifier.height(8.dp))
                when (val latest = chats?.firstOrNull()) {
                    null -> Card(Modifier.fillMaxWidth(), border=elsewhereCardBorder()) {
                        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            if (chats == null) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text("Opening your stories…")
                            } else {
                                Text("Start your first story",style=MaterialTheme.typography.titleLarge)
                                Text("Pick Solo, Group, or Scenario below.",color=colors.onSurfaceVariant)
                            }
                        }
                    }
                    else -> Card(onClick={onChat(latest.chat.chatId)}, modifier=Modifier.fillMaxWidth().testTag("home_continue_story"),
                        shape=RoundedCornerShape(20.dp),border=elsewhereCardBorder()) {
                        Column(
                            Modifier.fillMaxWidth().heightIn(min=188.dp)
                                .background(Brush.verticalGradient(listOf(colors.surfaceContainerHigh,colors.surface)))
                                .padding(20.dp),
                            verticalArrangement=Arrangement.spacedBy(8.dp)
                        ) {
                            Text(latest.modeLabel(),color=colors.onSurfaceVariant,style=MaterialTheme.typography.labelMedium)
                            Text(latest.chat.displayName,style=MaterialTheme.typography.headlineMedium.copy(fontFamily=FontFamily.Serif),
                                maxLines=3,overflow=TextOverflow.Ellipsis)
                            latest.castLabel().takeIf { it.isNotBlank() }?.let { Text(it,color=colors.onSurfaceVariant) }
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                Text("Continue story",color=colors.primary)
                                Icon(Icons.AutoMirrored.Filled.ArrowForward,contentDescription=null,tint=colors.primary,
                                    modifier=Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            val additional=chats.orEmpty().drop(1)
            if (additional.isNotEmpty()) item("recent") {
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text("Recently played",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                        TextButton(onClick=onChats,modifier=Modifier.testTag("home_see_all_chats")) { Text("See all") }
                    }
                    BoxWithConstraints {
                        val stacked=maxWidth<330.dp || LocalDensity.current.fontScale>1.3f
                        if (stacked) Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            additional.forEach { RecentStory(it,images,{onChat(it.chat.chatId)},Modifier.fillMaxWidth()) }
                        } else Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            additional.forEach { RecentStory(it,images,{onChat(it.chat.chatId)},Modifier.weight(1f)) }
                        }
                    }
                }
            }
            item("quick starts") {
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Start something new",style=MaterialTheme.typography.titleLarge)
                    BoxWithConstraints {
                        val actions=listOf(Triple("Solo",Icons.Filled.Person,onSolo),
                            Triple("Group",Icons.Filled.Groups,onGroup)) +
                            listOfNotNull(onScenario?.let{Triple("Scenario",Icons.Filled.AutoStories,it)})
                        if(maxWidth<330.dp || LocalDensity.current.fontScale>1.3f) {
                            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                actions.forEach { (label,icon,click) ->
                                    OutlinedButton(click,Modifier.fillMaxWidth()) {
                                        Icon(icon,contentDescription=null,modifier=Modifier.size(20.dp))
                                        Spacer(Modifier.width(8.dp)); Text(label)
                                    }
                                }
                            }
                        } else Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            actions.forEach { (label,icon,click) ->
                                Card(click,Modifier.weight(1f),border=elsewhereCardBorder()) {
                                    Column(Modifier.fillMaxWidth().padding(vertical=12.dp),
                                        horizontalAlignment=Alignment.CenterHorizontally,
                                        verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                        Icon(icon,contentDescription=null,tint=colors.primary)
                                        Text(label,style=MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item("library") {
                Card(onClick=onLibrary,modifier=Modifier.fillMaxWidth().testTag("home_library_link"),
                    border=elsewhereCardBorder()) {
                    Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Filled.Bookmarks,contentDescription=null,tint=colors.primary)
                        Column(Modifier.weight(1f)) {
                            Text("Your library",style=MaterialTheme.typography.titleMedium)
                            Text("Characters, Personas, Lorebooks and NPCs",style=MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.AutoMirrored.Filled.ArrowForward,contentDescription=null)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentStory(chat: ChatSummary, images: ProfileImageRepository?, click: () -> Unit, modifier: Modifier) {
    val lead=chat.characters.firstOrNull()
    Card(onClick=click,modifier=modifier.testTag("home_recent_" + chat.chat.chatId),border=elsewhereCardBorder()) {
        Row(Modifier.fillMaxWidth().padding(10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            ProfileImageAvatar(images,ProfileImageKind.CHARACTER,lead?.characterId,
                lead?.displayName ?: chat.chat.displayName,44.dp)
            Column(Modifier.weight(1f)) {
                Text(chat.chat.displayName,style=MaterialTheme.typography.titleSmall,maxLines=3,overflow=TextOverflow.Ellipsis)
                Text(chat.modeLabel(),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun ChatSummary.modeLabel() = when (chat.chatType) {
    ChatType.SOLO -> "Solo story"; ChatType.GROUP -> "Group story"; ChatType.SCENARIO -> "Scenario"
}
private fun ChatSummary.castLabel(): String = (personas.take(1).map { it.displayName } +
    characters.take(2).map { it.displayName }).joinToString(" · ")
