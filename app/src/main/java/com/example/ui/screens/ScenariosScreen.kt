package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.data.model.*
import com.example.di.AppContainer
import com.example.domain.repository.ProfileImageKind
import com.example.ui.components.*
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScenariosScreen(container: AppContainer, onChatCreated: (String) -> Unit, onPersonas: () -> Unit) {
    val repository = container.scenarioRepository ?: return
    val scenarios by repository.observeAll().collectAsState(emptyList())
    val personas by container.personaRepository.getAllPersonas().collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var draftJson by rememberSaveable { mutableStateOf(ScenarioCodec.encode(ScenarioDefinition())) }
    var startingId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedPersonaId by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var showDiscard by remember { mutableStateOf(false) }
    val definition = remember(draftJson) { ScenarioCodec.decode(draftJson) }
    if (editingId != null) {
        ScenarioEditor(
            definition = definition,
            onChange = { draftJson = ScenarioCodec.encode(it) },
            onSave = {
                if (!busy) scope.launch {
                    busy = true
                    try { repository.save(requireNotNull(editingId), definition); editingId = null; error = null }
                    catch (e: Exception) { error = e.message ?: "Unable to save scenario." }
                    finally { busy = false }
                }
            },
            onCancel = { showDiscard = true },
            artwork = { ProfileImageEditor(container.profileImageRepository, ProfileImageKind.SCENARIO, editingId, definition.title) },
            busy = busy, error = error
        )
        androidx.activity.compose.BackHandler { showDiscard = true }
        if (showDiscard) AlertDialog(onDismissRequest = { showDiscard = false }, title = { Text("Discard editor changes?") },
            text = { Text("Unsaved text changes will be lost. Artwork changes are saved immediately.") },
            confirmButton = { TextButton(onClick = { editingId = null; showDiscard = false; error = null }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { showDiscard = false }) { Text("Keep editing") } })
        return
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Scenarios") }, colors = elsewhereTopAppBarColors()) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Inhabit a story or world. Each playthrough has its own persona and history.") }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { editingId = UUID.randomUUID().toString(); draftJson = ScenarioCodec.encode(ScenarioDefinition()); error = null }, modifier = Modifier.weight(1f)) { Text("Quick Scene") }
                OutlinedButton(onClick = { editingId = UUID.randomUUID().toString(); draftJson = ScenarioCodec.encode(ScenarioDefinition(experience = ScenarioExperience.SANDBOX)); error = null }, modifier = Modifier.weight(1f)) { Text("Open World / Sandbox") }
            } }
            item { OutlinedTextField(query, { query = it }, label = { Text("Search titles, categories and tags") }, modifier = Modifier.fillMaxWidth(), colors = elsewhereTextFieldColors()) }
            if (scenarios.isEmpty()) item { Text("Create a Quick Scene with just a title and premise, or build a sandbox. Both can grow into ongoing stories.") }
            items(scenarios.filter {
                val d = ScenarioCodec.decode(it.definitionJson)
                listOf(d.title, d.displayTitle, d.categories, d.tags, d.publicDescription).any { text -> text.contains(query, ignoreCase = true) }
            }, key = { it.scenarioId }) { scenario ->
                val d = remember(scenario.definitionJson) { ScenarioCodec.decode(scenario.definitionJson) }
                Card(border = elsewhereCardBorder(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ProfileImageAvatar(container.profileImageRepository, ProfileImageKind.SCENARIO, scenario.scenarioId, d.title, size = 56.dp)
                            Column { Text(d.displayTitle.ifBlank { d.title }, style = MaterialTheme.typography.titleLarge)
                                Text("Scenario · " + if (d.experience == ScenarioExperience.QUICK_SCENE) "Quick Scene" else "Sandbox", style = MaterialTheme.typography.labelMedium) }
                        }
                        if (d.publicDescription.isNotBlank()) Text(d.publicDescription)
                        if (d.tags.isNotBlank()) Text(d.tags, style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { startingId = scenario.scenarioId; selectedPersonaId = null; error = null }) { Text("New playthrough") }
                            TextButton(onClick = { editingId = scenario.scenarioId; draftJson = scenario.definitionJson; error = null }) { Text("Edit") }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
    if (startingId != null) AlertDialog(onDismissRequest = { if (!busy) startingId = null },
        title = { Text("Select a player persona") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Starts an independent story using this scenario's current configuration.")
            LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(personas, key = { it.personaId }) { persona -> SelectionCard(persona.displayName, selectedPersonaId == persona.personaId) { selectedPersonaId = persona.personaId } }
            }
            if (personas.isEmpty()) TextButton(onClick = onPersonas) { Text("Create or import a persona") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = selectedPersonaId != null && !busy, onClick = {
            scope.launch {
                busy = true
                try { val id = repository.start(requireNotNull(startingId), requireNotNull(selectedPersonaId)); startingId = null; onChatCreated(id) }
                catch (e: Exception) { error = e.message ?: "Unable to start playthrough." }
                finally { busy = false }
            }
        }) { Text("Start") } }, dismissButton = { TextButton(enabled = !busy, onClick = { startingId = null }) { Text("Cancel") } })
}

@Composable
internal fun ScenarioEditor(definition: ScenarioDefinition, onChange: (ScenarioDefinition) -> Unit,
    onSave: () -> Unit, onCancel: () -> Unit, artwork: @Composable () -> Unit = {}, busy: Boolean = false, error: String? = null) {
    var advanced by rememberSaveable { mutableStateOf(definition.experience == ScenarioExperience.SANDBOX) }
    var cast by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(if (definition.experience == ScenarioExperience.QUICK_SCENE) "Quick Scene" else "Open World / Sandbox", style = MaterialTheme.typography.headlineMedium) }
        item { Text("Only title and premise are required. You can expand this scenario later. Saved edits apply to new playthroughs; existing stories retain their starting configuration.") }
        item { ScenarioField("Scenario Title", definition.title) { onChange(definition.copy(title = it)) } }
        item { ScenarioField("Premise / Scene Setup", definition.premise) { onChange(definition.copy(premise = it)) } }
        item { ScenarioField("Opening Message (optional)", definition.openingMessage) { onChange(definition.copy(openingMessage = it)) } }
        item { TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide worldbuilding and settings" else "Worldbuilding and settings") } }
        if (advanced) {
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = definition.experience == ScenarioExperience.QUICK_SCENE, onClick = { onChange(definition.copy(experience = ScenarioExperience.QUICK_SCENE)) }, label = { Text("Quick Scene") })
                FilterChip(selected = definition.experience == ScenarioExperience.SANDBOX, onClick = { onChange(definition.copy(experience = ScenarioExperience.SANDBOX)) }, label = { Text("Sandbox") })
            } }
            item { ScenarioField("Display / Card Title", definition.displayTitle) { onChange(definition.copy(displayTitle = it)) } }
            item { ScenarioField("Public Description (display only)", definition.publicDescription) { onChange(definition.copy(publicDescription = it)) } }
            item { artwork() }
            item { ScenarioField("World and Setting Details", definition.setting) { onChange(definition.copy(setting = it)) } }
            item { ScenarioField("Narration Style", definition.narrationStyle) { onChange(definition.copy(narrationStyle = it)) } }
            item { ScenarioField("Opening Situation", definition.openingSituation) { onChange(definition.copy(openingSituation = it)) } }
            item { ScenarioField("Locations", definition.locations) { onChange(definition.copy(locations = it)) } }
            item { ScenarioField("Factions or Organizations", definition.factions) { onChange(definition.copy(factions = it)) } }
            item { ScenarioField("World Rules and Established Lore", definition.worldRules) { onChange(definition.copy(worldRules = it)) } }
            item { ScenarioField("Additional Instructions", definition.instructions) { onChange(definition.copy(instructions = it)) } }
            item { ScenarioField("Categories", definition.categories) { onChange(definition.copy(categories = it)) } }
            item { ScenarioField("Tags", definition.tags) { onChange(definition.copy(tags = it)) } }
        }
        item { TextButton(onClick = { cast = !cast }) { Text("Recurring NPCs (${definition.npcs.size}) · ${if (cast) "Hide" else "Edit"}") } }
        if (cast) {
            items(definition.npcs, key = { it.npcId }) { npc ->
                fun update(value: ScenarioNpc) = onChange(definition.copy(npcs = definition.npcs.map { if (it.npcId == value.npcId) value else it }))
                Card(border = elsewhereCardBorder()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScenarioField("NPC Name", npc.name) { update(npc.copy(name = it)) }
                    ScenarioField("Appearance", npc.appearance) { update(npc.copy(appearance = it)) }
                    ScenarioField("Personality", npc.personality) { update(npc.copy(personality = it)) }
                    ScenarioField("Background", npc.background) { update(npc.copy(background = it)) }
                    ScenarioField("Motivations", npc.motivations) { update(npc.copy(motivations = it)) }
                    ScenarioField("Relationships", npc.relationships) { update(npc.copy(relationships = it)) }
                    ScenarioField("Narrative Role", npc.narrativeRole) { update(npc.copy(narrativeRole = it)) }
                    ScenarioField("Additional Character Instructions", npc.instructions) { update(npc.copy(instructions = it)) }
                    TextButton(onClick = { onChange(definition.copy(npcs = definition.npcs.filterNot { it.npcId == npc.npcId })) }) { Text("Remove NPC") }
                } }
            }
            item { OutlinedButton(onClick = { onChange(definition.copy(npcs = definition.npcs + ScenarioNpc())) }) { Text("Add NPC") } }
        }
        item { error?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSave, enabled = !busy && definition.title.isNotBlank() && definition.premise.isNotBlank()) { Text(if (busy) "Saving…" else "Save scenario") }
            TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel") }
        } }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ScenarioField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium, colors = elsewhereTextFieldColors(), maxLines = 12)
}
