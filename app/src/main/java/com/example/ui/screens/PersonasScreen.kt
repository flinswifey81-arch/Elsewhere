package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.ManualPersonaFields
import com.example.data.model.PersonaEntity
import com.example.domain.repository.PersonaRepository
import com.example.ui.components.ElsewhereEmptyState
import com.example.ui.components.elsewhereCardBorder
import com.example.ui.components.elsewhereTextFieldColors
import com.example.ui.components.elsewhereTopAppBarColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonasScreen(
    repository: PersonaRepository,
    modifier: Modifier = Modifier,
    onPersonaSelected: (String) -> Unit
) {
    val personas by repository.getAllPersonas().collectAsState(initial = emptyList())
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    
    var showImportOptions by remember { mutableStateOf(false) }
    var showPasteDialog by remember { mutableStateOf(false) }
    var pasteContent by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var editingPersona by remember { mutableStateOf<PersonaEntity?>(null) }
    
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            coroutineScope.launch {
                try {
                    val inputStream = context.contentResolver.openInputStream(it)
                    val json = inputStream?.bufferedReader()?.use { reader -> reader.readText() }
                    if (json != null) {
                        repository.importPersonaJson(json, replaceExisting = false, importAsNew = false)
                        importError = null
                    } else {
                        importError = "Failed to read file."
                    }
                } catch (e: Exception) {
                    importError = e.localizedMessage ?: "Unknown error"
                }
            }
        }
    }

    if (showImportOptions) {
        AlertDialog(
            onDismissRequest = { showImportOptions = false },
            title = { Text("Import Persona") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { 
                            showImportOptions = false
                            launcher.launch(arrayOf("application/json", "*/*"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Import JSON File")
                    }
                    Button(
                        onClick = { 
                            showImportOptions = false
                            showPasteDialog = true 
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Paste JSON")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showImportOptions = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showPasteDialog) {
        AlertDialog(
            onDismissRequest = { showPasteDialog = false },
            title = { Text("Paste JSON") },
            text = {
                OutlinedTextField(
                    value = pasteContent,
                    onValueChange = { pasteContent = it },
                    label = { Text("JSON Content") },
                    modifier = Modifier.fillMaxWidth().height(200.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    coroutineScope.launch {
                        try {
                            repository.importPersonaJson(pasteContent, replaceExisting = false, importAsNew = false)
                            showPasteDialog = false
                            pasteContent = ""
                            importError = null
                        } catch (e: Exception) {
                            importError = e.localizedMessage ?: "Unknown error"
                        }
                    }
                }) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPasteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    if (importError != null) {
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text("Import Error") },
            text = { Text(importError!!) },
            confirmButton = {
                TextButton(onClick = { importError = null }) {
                    Text("OK")
                }
            }
        )
    }

    if (showEditor) {
        ManualPersonaEditor(
            initialFields = editingPersona?.let(repository::getManualFields) ?: ManualPersonaFields(),
            isEditing = editingPersona != null,
            onCancel = {
                showEditor = false
                editingPersona = null
            },
            onSave = { fields ->
                coroutineScope.launch {
                    try {
                        val existing = editingPersona
                        if (existing == null) {
                            repository.createManualPersona(fields)
                        } else {
                            repository.updateManualPersona(existing.personaId, fields)
                        }
                        showEditor = false
                        editingPersona = null
                        importError = null
                    } catch (e: Exception) {
                        importError = e.localizedMessage ?: "Unable to save Persona."
                    }
                }
            },
            modifier = modifier
        )
        return
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Personas", style = MaterialTheme.typography.titleLarge) },
                colors = elsewhereTopAppBarColors()
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ExtendedFloatingActionButton(
                    onClick = {
                        editingPersona = null
                        showEditor = true
                    },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("New Persona") },
                    modifier = Modifier.testTag("new_persona_fab")
                )
                ExtendedFloatingActionButton(
                    onClick = { showImportOptions = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Import JSON") },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.testTag("import_persona_fab")
                )
            }
        },
        modifier = modifier.testTag("personas_screen"),
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (personas.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    ElsewhereEmptyState(
                        icon = Icons.Default.Face,
                        title = "Your library is empty.",
                        message = "Create a Persona or import a persona (JSON) to start."
                    )
                    Button(
                        onClick = {
                            editingPersona = null
                            showEditor = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text("New Persona", color = MaterialTheme.colorScheme.onSecondary)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { launcher.launch(arrayOf("application/json", "*/*")) }) {
                            Text("Import JSON File")
                        }
                        OutlinedButton(onClick = { showPasteDialog = true }) {
                            Text("Paste JSON")
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(personas) { persona ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("persona_item_${persona.personaId}"),
                        onClick = { onPersonaSelected(persona.personaId) },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        border = elsewhereCardBorder()
                    ) {
                        Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Face,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = persona.displayName,
                                        style = MaterialTheme.typography.titleLarge,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (persona.isArchived) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(
                                                text = "Archived",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = persona.personaName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (persona.aliases.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Aliases: ${persona.aliases.joinToString(", ")}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    editingPersona = persona
                                    showEditor = true
                                },
                                modifier = Modifier.testTag("edit_persona_${persona.personaId}")
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit ${persona.displayName}")
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualPersonaEditor(
    initialFields: ManualPersonaFields,
    isEditing: Boolean,
    onCancel: () -> Unit,
    onSave: (ManualPersonaFields) -> Unit,
    modifier: Modifier = Modifier
) {
    var fields by remember(initialFields) { mutableStateOf(initialFields) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEditing) "Edit Persona" else "New Persona") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { onSave(fields) },
                        enabled = fields.name.isNotBlank(),
                        modifier = Modifier.testTag("save_persona")
                    ) {
                        Text("Save")
                    }
                },
                colors = elsewhereTopAppBarColors()
            )
        },
        modifier = modifier.testTag("manual_persona_editor"),
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Card(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = elsewhereCardBorder()
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = fields.name,
                    onValueChange = { fields = fields.copy(name = it) },
                    label = { Text("Name") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    colors = elsewhereTextFieldColors(),
                    modifier = Modifier.fillMaxWidth().testTag("persona_field_Name")
                )
                OutlinedTextField(
                    value = fields.backstoryInstructions,
                    onValueChange = { fields = fields.copy(backstoryInstructions = it) },
                    label = { Text("Backstory / Persona Instructions") },
                    minLines = 6,
                    shape = MaterialTheme.shapes.medium,
                    colors = elsewhereTextFieldColors(),
                    modifier = Modifier.fillMaxWidth().weight(1f).testTag("persona_field_Backstory / Persona Instructions")
                )
            }
        }
    }
}
