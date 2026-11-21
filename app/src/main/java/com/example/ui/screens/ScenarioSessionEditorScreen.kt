package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.data.model.ScenarioCodec
import com.example.di.AppContainer
import kotlinx.coroutines.launch

/**
 * Edits only the selected ongoing Scenario session. The reusable template, its
 * other playthroughs and every saved story message remain owned by their origin.
 */
@Composable
fun ScenarioSessionEditorScreen(
    chatId: String,
    container: AppContainer,
    onNavigateBack: () -> Unit
) {
    val repository = container.scenarioRepository
    val scope = rememberCoroutineScope()
    var draftJson by rememberSaveable(chatId) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var loading by remember(chatId) { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(chatId, repository) {
        if (draftJson == null) {
            try {
                val session = requireNotNull(repository?.session(chatId)) {
                    "This Scenario playthrough is unavailable."
                }
                draftJson = session.definitionJson
                error = null
            } catch (failure: Exception) {
                error = failure.message ?: "The Scenario playthrough could not be opened."
            }
        }
        loading = false
    }

    // Even while saving, intercept Android Back so the screen cannot disappear
    // halfway through a successful transaction and silently lose navigation feedback.
    BackHandler {
        if (!busy) {
            if (draftJson == null) onNavigateBack() else showDiscard = true
        }
    }
    val json = draftJson
    if (loading || (json == null && error == null)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (json == null) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(error ?: "Unable to load this playthrough.")
            Spacer(Modifier.height(12.dp))
            Button(onClick = onNavigateBack) { Text("Back to chat") }
        }
        return
    }

    val draft = remember(json) { ScenarioCodec.decode(json) }
    ScenarioEditor(
        definition = draft,
        onChange = { draftJson = ScenarioCodec.encode(it) },
        onSave = {
            if (!busy) scope.launch {
                busy = true
                try {
                    requireNotNull(repository) {
                        "Scenario editing is unavailable in this build."
                    }.updateActiveSession(chatId, draft)
                    onNavigateBack()
                } catch (failure: Exception) {
                    error = failure.message ?: "Could not save this playthrough."
                } finally {
                    busy = false
                }
            }
        },
        onCancel = { if (!busy) showDiscard = true },
        busy = busy,
        error = error,
        editingActiveSession = true
    )

    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text("Leave this playthrough editor?") },
            text = {
                Text(
                    "Unsaved changes will be discarded. The existing story, template " +
                        "and other playthroughs will not change."
                )
            },
            confirmButton = {
                TextButton(onClick = { showDiscard = false; onNavigateBack() }) {
                    Text("Discard changes")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscard = false }) { Text("Keep editing") }
            }
        )
    }
}
