package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.components.elsewhereCardBorder
import com.example.ui.components.elsewhereTopAppBarColors

@Composable
private fun HubLink(title: String, subtitle: String, icon: ImageVector, tag: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).testTag(tag),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = elsewhereCardBorder(),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryHubScreen(
    onCharacters: () -> Unit,
    onPersonas: () -> Unit,
    onLorebooks: () -> Unit,
    onNpcChats: () -> Unit,
    onSettings: () -> Unit
) {
    Scaffold(topBar = {
        CenterAlignedTopAppBar(title = { Text("Library", style = MaterialTheme.typography.titleLarge) }, colors = elsewhereTopAppBarColors())
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("library_hub"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Text("Everything that brings your stories to life.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { HubLink("Characters", "Create and manage your AI cast", Icons.Default.People, "hub_characters", onCharacters) }
            item { HubLink("Personas", "Your identities across stories", Icons.Default.Face, "hub_personas", onPersonas) }
            item { HubLink("Lorebooks", "Reusable setting and story context", Icons.Default.MenuBook, "hub_lorebooks", onLorebooks) }
            item { HubLink("NPC Bank", "Manage supporting cast from inside each chat", Icons.AutoMirrored.Filled.Chat, "hub_npc_bank", onNpcChats) }
            item { HorizontalDivider() }
            item { HubLink("Settings", "Themes, model connections and backups", Icons.Default.Settings, "hub_settings", onSettings) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateHubScreen(
    onSolo: () -> Unit,
    onGroup: () -> Unit,
    onScenario: () -> Unit,
    onWorlds: () -> Unit
) {
    Scaffold(topBar = {
        CenterAlignedTopAppBar(title = { Text("Create", style = MaterialTheme.typography.titleLarge) }, colors = elsewhereTopAppBarColors())
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("create_hub"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Text("Pick the kind of story you want to begin.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { HubLink("Solo chat", "One main Character, with optional NPC Co-Leads", Icons.Default.Person, "create_solo_hub", onSolo) }
            item { HubLink("Group chat", "Multiple AI Characters, independent voices", Icons.Default.Groups, "create_group_hub", onGroup) }
            item { HubLink("Scenario", "Quick Scene or a full narrative sandbox", Icons.Default.AutoStories, "create_scenario_hub", onScenario) }
            item { HubLink("World", "Build a setting, cast and linked episodes", Icons.Default.Public, "create_world_hub", onWorlds) }
        }
    }
}
