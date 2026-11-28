package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.data.model.CollectionIdentityKind
import com.example.data.model.IdentityCollection
import com.example.data.model.CharacterEntity
import com.example.data.model.PersonaEntity
import com.example.domain.repository.ProfileImageKind
import com.example.domain.repository.ProfileImageRepository
import com.example.ui.components.ProfileImageAvatar
import com.example.ui.components.ProfileImageEditor
import com.example.ui.components.elsewhereCardBorder
import com.example.ui.components.elsewhereTextFieldColors

internal data class StoryIdentity(val id: String, val name: String, val label: String, val aliases: List<String>)

internal fun PersonaEntity.storyIdentity() = StoryIdentity(personaId, personaName, displayName, aliases)
internal fun CharacterEntity.storyIdentity() = StoryIdentity(characterId, characterName, displayName, aliases)

@Composable
internal fun StoryIdentityPicker(
    entries: List<StoryIdentity>,
    selectedIds: List<String>,
    repository: ProfileImageRepository,
    kind: ProfileImageKind,
    multiple: Boolean = false,
    collections: List<IdentityCollection> = emptyList(),
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var grid by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var collectionId by rememberSaveable { mutableStateOf<String?>(null) }
    val collectionKind = if (kind == ProfileImageKind.CHARACTER) CollectionIdentityKind.CHARACTER else CollectionIdentityKind.PERSONA
    val active = collections.firstOrNull { it.collection.collectionId == collectionId }
    val visible = remember(entries, query, active, collections) {
        entries.filter { entry ->
            (active == null || active.contains(collectionKind, entry.id)) &&
                query.matchesLibraryQuery(entry.name, entry.label, *entry.aliases.toTypedArray(),
                    *collections.labelsFor(collectionKind, entry.id).toTypedArray())
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(query, { query = it },
            modifier = Modifier.fillMaxWidth().testTag("picker_search"), singleLine = true, label = { Text("Search names or labels") },
            colors = elsewhereTextFieldColors())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = !grid, onClick = { grid = false }, label = { Text("List") })
            FilterChip(selected = grid, onClick = { grid = true }, label = { Text("Grid") })
            if (collections.isNotEmpty()) CollectionFilter(collections, collectionId, { collectionId = it }, Modifier.weight(1f))
            else Text("${visible.size} shown", style = MaterialTheme.typography.labelMedium)
        }
        val list: @Composable (StoryIdentity) -> Unit = { entry ->
            StoryIdentityCard(entry, selectedIds.indexOf(entry.id), multiple, repository, kind, grid,
                onSelect = { onSelect(entry.id) }, onEdit = { editingId = entry.id },
                collectionLabels = collections.labelsFor(collectionKind, entry.id))
        }
        if (visible.isEmpty()) {
            Text(if (active == null) "No profiles match your search." else "No matching profiles in this collection.", modifier = Modifier.weight(1f))
        } else if (grid) {
            LazyVerticalGrid(columns = GridCells.Adaptive(144.dp), modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visible, key = { it.id }) { list(it) }
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visible, key = { it.id }) { list(it) }
            }
        }
        val selected = if (!multiple) entries.firstOrNull { it.id == selectedIds.firstOrNull() } else null
        if (selected != null) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                ProfileImageAvatar(repository, kind, selected.id, selected.name, 36.dp)
                Text("Selected: ${selected.label}", style = MaterialTheme.typography.labelLarge)
            }
        } else if (multiple && selectedIds.isNotEmpty()) {
            Text("${selectedIds.size} selected", style = MaterialTheme.typography.labelLarge)
        }
    }
    entries.firstOrNull { it.id == editingId }?.let { entry ->
        StoryPortraitDialog(entry, kind, repository) { editingId = null }
    }
}

@Composable
internal fun StoryPortraitDialog(entry: StoryIdentity, kind: ProfileImageKind, repository: ProfileImageRepository, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Portrait · ${entry.label}") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            ProfileImageEditor(repository, kind, entry.id, entry.label, Modifier.fillMaxWidth(), compact = true)
        } },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
    )
}

@Composable
internal fun StoryIdentityCard(
    entry: StoryIdentity,
    index: Int,
    multiple: Boolean,
    repository: ProfileImageRepository,
    kind: ProfileImageKind,
    grid: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    collectionLabels: List<String> = emptyList()
) {
    val isSelected = index >= 0
    val portrait: @Composable () -> Unit = {
        ProfileImageAvatar(repository, kind, entry.id, entry.name, if (grid) 88.dp else 62.dp,
            contentDescription = "Portrait for ${entry.label}", modifier = Modifier.testTag("picker_portrait_${entry.id}"))
    }
    val labels: @Composable () -> Unit = {
        Text(entry.name, style = MaterialTheme.typography.titleMedium)
        if (entry.label != entry.name) Text(entry.label, style = MaterialTheme.typography.bodyMedium)
        if (collectionLabels.isNotEmpty()) CollectionLabels(collectionLabels)
        if (isSelected) Text(if (multiple) "Selected · ${index + 1}" else "Selected", style = MaterialTheme.typography.labelMedium)
    }
    val edit: @Composable () -> Unit = {
        IconButton(onClick = onEdit, modifier = Modifier.testTag("picker_edit_${entry.id}")) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Edit portrait for ${entry.label}")
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth().testTag("picker_identity_${entry.id}").selectable(
            selected = isSelected, role = if (multiple) Role.Checkbox else Role.RadioButton, onClick = onSelect
        ),
        border = elsewhereCardBorder(),
        colors = CardDefaults.cardColors(containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        if (grid) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { edit() }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { portrait() }
                labels()
            }
        } else {
            Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                portrait()
                Column(Modifier.weight(1f)) { labels() }
                edit()
            }
        }
    }
}
