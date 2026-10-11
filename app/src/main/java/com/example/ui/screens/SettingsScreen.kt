package com.example.ui.screens

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.domain.repository.AppearanceSettings
import com.example.domain.repository.AppearanceRepository
import com.example.domain.repository.ApiKeyRepository
import com.example.domain.repository.ColorPalette
import kotlinx.coroutines.launch
import com.example.domain.repository.ThemeMode
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
                            "Saved on this device only.",
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
