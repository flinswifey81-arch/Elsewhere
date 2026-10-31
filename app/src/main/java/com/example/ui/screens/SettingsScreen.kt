package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.domain.repository.AppearanceSettings
import com.example.domain.repository.AppearanceRepository
import com.example.domain.repository.ApiKeyRepository
import com.example.domain.repository.ColorPalette
import kotlinx.coroutines.launch
import com.example.domain.repository.ThemeMode
import com.example.domain.repository.ChatRepository
import com.example.data.model.EngineDefaultsEntity
import com.example.data.model.ProviderRoutingMode
import com.example.data.model.ResponseLengthProfile
import com.example.domain.provider.ModelProvider
import com.example.domain.provider.OpenRouterModel
import com.example.domain.provider.ProviderEndpoint
import com.example.domain.backup.BackupInspection
import com.example.domain.backup.BackupRepository
import com.example.ui.components.elsewhereCardBorder
import com.example.ui.components.elsewhereDestructiveButtonColors
import com.example.ui.components.elsewhereTextFieldColors
import com.example.ui.components.elsewhereTopAppBarColors
import com.example.ui.theme.getLightColorScheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsRepository: ApiKeyRepository,
    appearanceRepository: AppearanceRepository,
    chatRepository: ChatRepository? = null,
    modelProvider: ModelProvider? = null,
    backupRepository: BackupRepository? = null,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf("") }
    var savedKey by remember { mutableStateOf<String?>(null) }
    var isLoaded by remember { mutableStateOf(false) }

    val appearanceSettings by appearanceRepository.appearanceSettings.collectAsState(initial = AppearanceSettings())

    LaunchedEffect(Unit) {
        savedKey = settingsRepository.getApiKey()
        isLoaded = true
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                colors = elsewhereTopAppBarColors()
            )
        },
        modifier = modifier.testTag("settings_screen"),
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("settings_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Appearance Section
            item {
                Text(
                    text = "Appearance",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp, start = 8.dp)
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    border = elsewhereCardBorder()
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("Appearance mode", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                ThemeMode.SYSTEM to "System",
                                ThemeMode.LIGHT to "Light",
                                ThemeMode.DARK to "Dark"
                            ).forEach { (mode, label) ->
                                FilterChip(
                                    selected = appearanceSettings.themeMode == mode,
                                    onClick = {
                                        coroutineScope.launch { appearanceRepository.updateThemeMode(mode) }
                                    },
                                    label = { Text(label) },
                                    modifier = Modifier.fillMaxWidth().testTag("appearance_mode_${mode.name.lowercase()}")
                                )
                            }
                        }

                        Text(
                            "Color palette",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Stored locally and included in encrypted backups.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ColorPalette.selectable.forEach { palette ->
                                PaletteChoice(
                                    palette = palette,
                                    selected = appearanceSettings.colorPalette == palette &&
                                        appearanceSettings.legacyAccentOverride == null,
                                    onClick = {
                                        coroutineScope.launch { appearanceRepository.updateColorPalette(palette) }
                                    }
                                )
                            }
                        }
                        appearanceSettings.legacyAccentOverride?.let { legacy ->
                            Text(
                                "Existing $legacy customization is preserved until you choose a palette above.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }

                        Text("Font Size: ${appearanceSettings.fontSize.toInt()}sp", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Slider(
                            value = appearanceSettings.fontSize,
                            onValueChange = {
                                coroutineScope.launch { appearanceRepository.updateFontSize(it) }
                            },
                            valueRange = 12f..24f,
                            steps = 11,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }

            // Model & API Section
            item {
                Text(
                    text = "Model & API",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp, start = 8.dp)
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    border = elsewhereCardBorder()
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("OpenRouter Integration", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        if (isLoaded) {
                            if (savedKey != null) {
                                Text("API Key configured.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            settingsRepository.deleteApiKey()
                                            savedKey = null
                                        }
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = elsewhereDestructiveButtonColors()
                                ) {
                                    Text("Remove Key")
                                }
                            } else {
                                OutlinedTextField(
                                    value = apiKey,
                                    onValueChange = { apiKey = it },
                                    label = { Text("API Key") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = elsewhereTextFieldColors(),
                                    modifier = Modifier.fillMaxWidth().testTag("api_key_input")
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            if (apiKey.isNotBlank()) {
                                                settingsRepository.saveApiKey(apiKey)
                                                savedKey = apiKey
                                                apiKey = ""
                                            }
                                        }
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.testTag("save_key_button")
                                ) {
                                    Text("Save Key")
                                }
                            }
                        } else {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                        }
                    }
                }
            }

            if (chatRepository != null) {
                item { EngineDefaultsCard(chatRepository, modelProvider) }
            }

            // About Section
            if (backupRepository != null) {
                item {
                    BackupRestoreSettingsCard(backupRepository)
                }
            }

            // About Section
            item {
                Text(
                    text = "About",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp, start = 8.dp)
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    border = elsewhereCardBorder()
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("Elsewhere", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Private stories, your way.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Version 1.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun EngineDefaultsCard(repository: ChatRepository, modelProvider: ModelProvider?) {
    val scope = rememberCoroutineScope()
    var defaults by remember { mutableStateOf(EngineDefaultsEntity()) }
    var loaded by remember { mutableStateOf(false) }
    var modelId by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<List<OpenRouterModel>>(emptyList()) }
    var modelSearch by remember { mutableStateOf("") }
    var showModels by remember { mutableStateOf(false) }
    var endpoints by remember { mutableStateOf<List<ProviderEndpoint>>(emptyList()) }
    var isFetchingCatalog by remember { mutableStateOf(false) }
    LaunchedEffect(repository) {
        defaults = repository.getEngineDefaults()
        modelId = defaults.selectedModelId.orEmpty()
        endpoint = defaults.providerEndpoint.orEmpty()
        loaded = true
    }
    Text("Default Engine", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp, start = 8.dp))
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), border = elsewhereCardBorder()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("New chats inherit these values. Existing chats keep their current engine settings unless you switch them to global defaults.", style = MaterialTheme.typography.bodySmall)
            if (!loaded) CircularProgressIndicator() else {
                OutlinedTextField(modelId, { modelId = it }, label = { Text("Model ID") }, modifier = Modifier.fillMaxWidth(), colors = elsewhereTextFieldColors())
                if (modelProvider != null) {
                    OutlinedButton(onClick = {
                        showModels = !showModels
                        if (showModels && models.isEmpty()) scope.launch {
                            isFetchingCatalog = true
                            runCatching { modelProvider.getModels() }
                                .onSuccess { models = it }
                                .onFailure { status = "Model catalog could not be loaded: ${it.message}" }
                            isFetchingCatalog = false
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text(if (showModels) "Hide model catalog" else "Choose from model catalog") }
                    if (showModels) {
                        OutlinedTextField(modelSearch, { modelSearch = it }, label = { Text("Search models") }, modifier = Modifier.fillMaxWidth(), colors = elsewhereTextFieldColors())
                        if (isFetchingCatalog) CircularProgressIndicator()
                        models.filter { it.name.contains(modelSearch, true) || it.id.contains(modelSearch, true) }.take(12).forEach { model ->
                            ModelItem(model, model.id == modelId) {
                                modelId = model.id
                                scope.launch {
                                    endpoints = runCatching { modelProvider.getEndpoints(model.id) }.getOrDefault(emptyList())
                                }
                            }
                        }
                    }
                }
                Text("Provider routing", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ProviderRoutingMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(selected = defaults.providerRoutingMode == mode,
                            onClick = { defaults = defaults.copy(providerRoutingMode = mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, ProviderRoutingMode.entries.size)) { Text(mode.name.lowercase().replaceFirstChar(Char::uppercase)) }
                    }
                }
                if (defaults.providerRoutingMode != ProviderRoutingMode.AUTO) {
                    OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Provider endpoint") }, modifier = Modifier.fillMaxWidth(), colors = elsewhereTextFieldColors())
                    endpoints.forEach { candidate ->
                        TextButton(onClick = { endpoint = candidate.identifier }) { Text(candidate.name) }
                    }
                }
                Text("Temperature: ${defaults.temperature?.toString() ?: "Provider default"}")
                Slider(defaults.temperature ?: 1f, { defaults = defaults.copy(temperature = it) }, valueRange = 0f..2f, steps = 19)
                TextButton(onClick = { defaults = defaults.copy(temperature = null) }) { Text("Use provider temperature") }
                Text("Top P: ${defaults.topP?.toString() ?: "Provider default"}")
                Slider(defaults.topP ?: 1f, { defaults = defaults.copy(topP = it) }, valueRange = 0f..1f, steps = 9)
                TextButton(onClick = { defaults = defaults.copy(topP = null) }) { Text("Use provider Top P") }
                Text("Response length", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ResponseLengthProfile.entries.forEachIndexed { index, profile ->
                        SegmentedButton(selected = defaults.responseLengthProfile == profile,
                            onClick = { defaults = defaults.copy(responseLengthProfile = profile) },
                            shape = SegmentedButtonDefaults.itemShape(index, ResponseLengthProfile.entries.size)) { Text(profile.name.lowercase().replaceFirstChar(Char::uppercase)) }
                    }
                }
                if (defaults.responseLengthProfile == ResponseLengthProfile.CUSTOM) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Min" to defaults.customMin, "Target" to defaults.customTargetMax, "Max" to defaults.customHardMax).forEach { (label, value) ->
                            OutlinedTextField(value?.toString().orEmpty(), { raw ->
                                val parsed = raw.toIntOrNull()
                                defaults = when (label) { "Min" -> defaults.copy(customMin = parsed); "Target" -> defaults.copy(customTargetMax = parsed); else -> defaults.copy(customHardMax = parsed) }
                            }, label = { Text(label) }, modifier = Modifier.weight(1f), colors = elsewhereTextFieldColors())
                        }
                    }
                }
                Button(onClick = {
                    scope.launch {
                        val candidate = defaults.copy(selectedModelId = modelId.trim().takeIf(String::isNotEmpty), providerEndpoint = endpoint.trim().takeIf(String::isNotEmpty))
                        val customValid = candidate.responseLengthProfile != ResponseLengthProfile.CUSTOM ||
                            listOf(candidate.customMin, candidate.customTargetMax, candidate.customHardMax).all { it != null } &&
                            candidate.customMin!! <= candidate.customTargetMax!! && candidate.customTargetMax!! <= candidate.customHardMax!!
                        if (!customValid) status = "Custom lengths must be filled in from smallest to largest."
                        else { repository.saveEngineDefaults(candidate); defaults = candidate; status = "Default engine saved." }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Save Default Engine") }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun BackupRestoreSettingsCard(repository: BackupRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isWorking by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var showExportPassphrase by remember { mutableStateOf(false) }
    var exportPassphrase by remember { mutableStateOf("") }
    var exportConfirmation by remember { mutableStateOf("") }
    var pendingExportPassphrase by remember { mutableStateOf<String?>(null) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var restorePassphrase by remember { mutableStateOf("") }
    var restoreValidationError by remember { mutableStateOf<String?>(null) }
    var showRestorePassphrase by remember { mutableStateOf(false) }
    var restoreInspection by remember { mutableStateOf<BackupInspection?>(null) }
    var restoreConfirmation by remember { mutableStateOf("") }
    var showRecoveryConfirmation by remember { mutableStateOf(false) }
    var safetyAvailable by remember { mutableStateOf(repository.hasSafetyBackup()) }
    var interruptedRestore by remember { mutableStateOf(repository.hasInterruptedRestore()) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        val passphrase = pendingExportPassphrase
        pendingExportPassphrase = null
        exportPassphrase = ""
        exportConfirmation = ""
        if (uri == null || passphrase == null) return@rememberLauncherForActivityResult
        scope.launch {
            isWorking = true
            statusMessage = null
            runCatching {
                context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                    repository.exportEncrypted(output, passphrase.toCharArray())
                } ?: error("The selected backup destination could not be opened.")
            }.onSuccess { preview ->
                statusMessage = "Encrypted backup saved: ${preview.chats} chats and ${preview.messages} messages."
            }.onFailure { error ->
                statusMessage = error.message ?: "Backup could not be created."
            }
            isWorking = false
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            pendingRestoreUri = uri
            restorePassphrase = ""
            restoreValidationError = null
            showRestorePassphrase = true
        }
    }

    Text(
        text = "Local backup & restore",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp, start = 8.dp)
    )
    Card(
        modifier = Modifier.fillMaxWidth().testTag("backup_restore_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = elsewhereCardBorder()
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Encrypted device backup", style = MaterialTheme.typography.titleMedium)
            Text(
                "Includes profiles, chats, variants, settings, drafts, memories, Lorebooks, NPC Bank data, images, avatars, and offline co-op history. API keys and device/transport credentials are never included. Android system backup is disabled for app data; this encrypted file is the supported transfer path.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = { showExportPassphrase = true },
                enabled = !isWorking,
                modifier = Modifier.fillMaxWidth().testTag("create_local_backup")
            ) { Text("Create encrypted backup") }
            OutlinedButton(
                onClick = { restoreLauncher.launch(arrayOf("application/octet-stream", "application/zip", "*/*")) },
                enabled = !isWorking,
                modifier = Modifier.fillMaxWidth().testTag("restore_local_backup")
            ) { Text("Restore from backup") }
            if (safetyAvailable || interruptedRestore) {
                OutlinedButton(
                    onClick = { showRecoveryConfirmation = true },
                    enabled = !isWorking,
                    modifier = Modifier.fillMaxWidth().testTag("recover_pre_restore_backup")
                ) {
                    Text(if (interruptedRestore) "Recover interrupted restore" else "Undo last restore")
                }
            }
            if (isWorking) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            statusMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (showExportPassphrase) {
        AlertDialog(
            onDismissRequest = {
                showExportPassphrase = false
                exportPassphrase = ""
                exportConfirmation = ""
            },
            title = { Text("Protect this backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Use at least 10 characters. This passphrase cannot be recovered by Elsewhere.")
                    OutlinedTextField(
                        value = exportPassphrase,
                        onValueChange = { exportPassphrase = it },
                        label = { Text("Passphrase") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = exportConfirmation,
                        onValueChange = { exportConfirmation = it },
                        label = { Text("Confirm passphrase") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = exportPassphrase.length >= 10 && exportPassphrase == exportConfirmation,
                    onClick = {
                        pendingExportPassphrase = exportPassphrase
                        showExportPassphrase = false
                        exportLauncher.launch("elsewhere-${System.currentTimeMillis()}.elsewhere-backup")
                    }
                ) { Text("Choose location") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showExportPassphrase = false
                    exportPassphrase = ""
                    exportConfirmation = ""
                }) { Text("Cancel") }
            }
        )
    }

    if (showRestorePassphrase) {
        AlertDialog(
            onDismissRequest = {
                if (!isWorking) {
                    showRestorePassphrase = false
                    restorePassphrase = ""
                    restoreValidationError = null
                    pendingRestoreUri = null
                }
            },
            title = { Text("Open encrypted backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = restorePassphrase,
                        onValueChange = {
                            restorePassphrase = it
                            restoreValidationError = null
                        },
                        label = { Text("Backup passphrase") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        enabled = !isWorking,
                        isError = restoreValidationError != null,
                        modifier = Modifier.testTag("restore_passphrase_input")
                    )
                    restoreValidationError?.let { message ->
                        Text(
                            message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("restore_validation_error")
                        )
                    }
                    if (isWorking) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(
                    enabled = restorePassphrase.length >= 10 && !isWorking,
                    onClick = {
                        val uri = pendingRestoreUri ?: return@TextButton
                        val attempt = restorePassphrase.toCharArray()
                        scope.launch {
                            isWorking = true
                            restoreValidationError = null
                            statusMessage = null
                            try {
                                val inspection = context.contentResolver.openInputStream(uri)?.use { input ->
                                    repository.inspectEncryptedForRestore(input, attempt)
                                } ?: error("The selected backup could not be opened.")
                                restoreInspection = inspection
                                showRestorePassphrase = false
                            } catch (error: Exception) {
                                restoreValidationError = error.message ?: "Backup could not be validated."
                                restorePassphrase = ""
                            } finally {
                                attempt.fill('\u0000')
                                isWorking = false
                            }
                        }
                    }
                ) { Text("Validate") }
            },
            dismissButton = {
                TextButton(
                    enabled = !isWorking,
                    onClick = {
                        showRestorePassphrase = false
                        restorePassphrase = ""
                        restoreValidationError = null
                        pendingRestoreUri = null
                    }
                ) { Text("Cancel") }
            }
        )
    }

    restoreInspection?.let { inspection ->
        val preview = inspection.preview
        AlertDialog(
            onDismissRequest = {
                restoreInspection = null
                restoreConfirmation = ""
                restorePassphrase = ""
                pendingRestoreUri = null
            },
            title = { Text("Replace local Elsewhere data?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${preview.characters} Characters, ${preview.personas} Personas, ${preview.scenarios} Scenarios, ${preview.chats} chats, " +
                            "${preview.messages} messages, ${preview.memories} memories, ${preview.lorebooks} Lorebooks, " +
                            "${preview.npcDossiers} NPC dossiers, ${preview.messageAttachments} message images, and " +
                            "${preview.profileImages} avatars passed validation."
                    )
                    if (preview.includesCoopHistory) {
                        Text("Co-op history will be retained offline, but rooms cannot resume without new pairing.")
                    }
                    Text("Elsewhere will first create a recoverable private safety copy. Type RESTORE to continue.")
                    OutlinedTextField(
                        value = restoreConfirmation,
                        onValueChange = { restoreConfirmation = it },
                        label = { Text("Type RESTORE") },
                        singleLine = true,
                        modifier = Modifier.testTag("restore_confirmation")
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = restoreConfirmation == "RESTORE" && !isWorking,
                    onClick = {
                        val uri = pendingRestoreUri ?: return@TextButton
                        restoreInspection = null
                        scope.launch {
                            isWorking = true
                            statusMessage = null
                            runCatching {
                                context.contentResolver.openInputStream(uri)?.use { input ->
                                    repository.restoreEncrypted(
                                        input,
                                        restorePassphrase.toCharArray(),
                                        expectedEncryptedSha256 = inspection.encryptedSha256
                                    )
                                } ?: error("The selected backup could not be reopened.")
                            }.onSuccess { result ->
                                statusMessage = "Restore complete: ${result.restoredChats} chats and ${result.restoredMessages} messages."
                            }.onFailure { statusMessage = it.message ?: "Restore failed." }
                            restorePassphrase = ""
                            restoreConfirmation = ""
                            pendingRestoreUri = null
                            safetyAvailable = repository.hasSafetyBackup()
                            interruptedRestore = repository.hasInterruptedRestore()
                            isWorking = false
                        }
                    }
                ) { Text("Replace local data", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = {
                    restoreInspection = null
                    restoreConfirmation = ""
                    restorePassphrase = ""
                    pendingRestoreUri = null
                }) { Text("Cancel") }
            }
        )
    }

    if (showRecoveryConfirmation) {
        AlertDialog(
            onDismissRequest = { showRecoveryConfirmation = false },
            title = { Text("Recover the pre-restore copy?") },
            text = { Text("This replaces current local data with the private safety copy made immediately before the last restore.") },
            confirmButton = {
                TextButton(onClick = {
                    showRecoveryConfirmation = false
                    scope.launch {
                        isWorking = true
                        runCatching { repository.recoverLastRestore() }
                            .onSuccess { statusMessage = "Previous local data recovered." }
                            .onFailure { statusMessage = it.message ?: "Recovery failed." }
                        safetyAvailable = repository.hasSafetyBackup()
                        interruptedRestore = repository.hasInterruptedRestore()
                        isWorking = false
                    }
                }) { Text("Recover", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showRecoveryConfirmation = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun PaletteChoice(
    palette: ColorPalette,
    selected: Boolean,
    onClick: () -> Unit
) {
    val preview = getLightColorScheme(palette)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .testTag("palette_${palette.storageId}"),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainer
        ),
        border = elsewhereCardBorder()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                RadioButton(selected = selected, onClick = null)
                Text(palette.displayName, modifier = Modifier.weight(1f))
            }
            Row(
                modifier = Modifier.padding(start = 50.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(preview.primary, preview.primaryContainer, preview.secondary, preview.surface).forEach { color ->
                    Surface(
                        modifier = Modifier.size(22.dp),
                        shape = RoundedCornerShape(11.dp),
                        color = color,
                        border = androidx.compose.foundation.BorderStroke(1.dp, preview.outline)
                    ) {}
                }
            }
        }
    }
}
