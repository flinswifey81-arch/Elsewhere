package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
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
import com.example.domain.repository.IdentityCollectionRepository
import com.example.domain.repository.ProfileImageKind
import com.example.domain.repository.ProfileImageRepository
import com.example.ui.components.ProfileImageAvatar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun observeCollections(repository: IdentityCollectionRepository?): List<IdentityCollection> {
    val flow = remember(repository) { repository?.observe() }
    return flow?.collectAsState(initial = emptyList())?.value.orEmpty()
}

internal fun List<IdentityCollection>.labelsFor(kind: CollectionIdentityKind, id: String): List<String> =
    filter { it.contains(kind, id) }.map { it.collection.label }

@Composable
internal fun CollectionFilter(
    collections: List<IdentityCollection>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    if (collections.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.testTag("collection_filter")) {
            Text(collections.firstOrNull { it.collection.collectionId == selectedId }?.collection?.label ?: "All collections",
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            DropdownMenuItem(text = { Text("All collections") }, onClick = { expanded = false; onSelect(null) })
            collections.forEach { collection ->
                DropdownMenuItem(text = { Text(collection.collection.label) },
                modifier = Modifier.testTag("collection_option_${collection.collection.label}"), onClick = {
                    expanded = false
                    onSelect(collection.collection.collectionId)
                })
            }
        }
    }
}

@Composable
internal fun LibraryCollectionActions(
    collections: List<IdentityCollection>,
    collectionId: String?,
    onSelect: (String?) -> Unit,
    onEdit: () -> Unit,
    gallery: Boolean,
    onToggleGallery: () -> Unit
) {
    val active = collections.firstOrNull { it.collection.collectionId == collectionId }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { CollectionFilter(collections, collectionId, onSelect, Modifier.fillMaxWidth()) }
        TextButton(onClick = onEdit) {
            Text(if (active == null) "New collection" else "Edit collection")
        }
        TextButton(onClick = onToggleGallery) { Text(if (gallery) "List view" else "Gallery view") }
    }
}

@Composable
internal fun CollectionEditor(
    repository: IdentityCollectionRepository,
    collection: IdentityCollection?,
    kind: CollectionIdentityKind,
    entries: List<StoryIdentity>,
    portraits: ProfileImageRepository?,
    onClose: () -> Unit,
    onDeleted: () -> Unit,
) {
    var label by rememberSaveable { mutableStateOf(collection?.collection?.label.orEmpty()) }
    val members = entries.asSequence().filter { collection?.contains(kind, it.id) == true }.map { it.id }.toSet()
    // Record only changed membership so a save doesn't undo unrelated selections or drop archived profiles.
    var changes by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var query by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val profileImageKind = if (kind == CollectionIdentityKind.CHARACTER) ProfileImageKind.CHARACTER else ProfileImageKind.PERSONA
    val noun = if (kind == CollectionIdentityKind.CHARACTER) "characters" else "personas"
    val visible = entries.filter { query.matchesLibraryQuery(it.name, it.label, *it.aliases.toTypedArray()) }
    fun write(action: suspend () -> Unit, after: () -> Unit) {
        if (busy) return
        busy = true
        failure = null
        scope.launch {
            try { action(); after() }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: IllegalArgumentException) { failure = error.message ?: "Couldn't save the collection." }
            catch (_: Exception) { failure = "Couldn't update the collection. Check that the profiles still exist and try again." }
            finally { busy = false }
        }
    }
    BackHandler { if (!busy) onClose() }
    Column(
        Modifier.fillMaxSize().imePadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(if (collection == null) "New collection" else "Edit collection",
            style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(label, { if (it.length <= 80) label = it }, singleLine = true,
            enabled = !busy, label = { Text("Collection name") },
            modifier = Modifier.fillMaxWidth().testTag("collection_name"))
        Text("Choose $noun. A profile can be in more than one collection.",
            style = MaterialTheme.typography.bodySmall)
        if (entries.isNotEmpty()) OutlinedTextField(query, { query = it }, singleLine = true,
            label = { Text("Filter $noun") }, modifier = Modifier.fillMaxWidth())
        val changedIds = changes.toSet()
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (visible.isEmpty()) item {
                Text(if (entries.isEmpty()) "No $noun yet." else "No matching $noun.")
            }
            items(visible, key = { it.id }) { entry ->
                val checked = (entry.id in members) xor (entry.id in changedIds)
                Row(Modifier.fillMaxWidth().testTag("collection_member_${entry.id}")
                    .toggleable(checked, enabled = !busy, role = Role.Checkbox) {
                        changes = ArrayList(if (entry.id in changedIds) changes - entry.id else changes + entry.id)
                    }.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    ProfileImageAvatar(portraits, profileImageKind, entry.id, entry.label, 36.dp)
                    Column(Modifier.weight(1f)) {
                        Text(entry.label, style = MaterialTheme.typography.bodyMedium)
                        if (entry.label != entry.name) Text(entry.name, style = MaterialTheme.typography.bodySmall)
                    }
                    Checkbox(checked, onCheckedChange = null)
                }
            }
        }
        failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically) {
            if (collection != null) {
                TextButton(enabled = !busy, onClick = { confirming = true }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.weight(1f))
            }
            TextButton(enabled = !busy, onClick = onClose) { Text("Cancel") }
            Button(enabled = !busy && label.isNotBlank(), onClick = {
                val added = changes.filterNot { it in members }.toSet()
                val removed = changes.filter { it in members }.toSet()
                write({ repository.save(collection?.collection?.collectionId, label, kind, added, removed) }, onClose)
            }) { Text("Save collection") }
        }
    }
    if (confirming && collection != null) AlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text("Delete ${collection.collection.label}?") },
        text = { Text("This removes the collection, not its profiles or stories.") },
        confirmButton = { TextButton(onClick = {
            confirming = false
            write({ repository.delete(collection.collection.collectionId) }, onDeleted)
        }) { Text("Delete collection") } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } }
    )
}

@Composable
internal fun CollectionLabels(labels: List<String>) {
    Text(labels.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
}
