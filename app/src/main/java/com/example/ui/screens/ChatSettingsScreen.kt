package com.example.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.model.ProviderRoutingMode
import com.example.data.model.ResponseLengthProfile
import com.example.domain.provider.ModelProvider
import com.example.domain.repository.ChatRepository
import com.example.domain.provider.OpenRouterModel
import com.example.domain.provider.ProviderEndpoint
import com.example.ui.components.elsewhereCardBorder
import com.example.ui.components.elsewhereTextFieldColors
import com.example.ui.components.elsewhereTopAppBarColors
import java.math.BigDecimal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSettingsScreen(
    chatId: String,
    chatRepository: ChatRepository,
    modelProvider: ModelProvider,
    onNavigateBack: () -> Unit
) {
    val viewModel: ChatSettingsViewModel = viewModel(
        factory = ChatSettingsViewModel.Factory(
            chatId = chatId,
            chatRepository = chatRepository,
            modelProvider = modelProvider
        )
    )

    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chat Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = elsewhereTopAppBarColors()
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .testTag("chat_settings_content")
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                uiState.error?.let { error ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(error, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }

                // Advanced Generation Params
                Text("Generation Parameters", style = MaterialTheme.typography.titleMedium)
                
                Text("Temperature: ${uiState.settings?.temperature?.toString() ?: "Default"}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = uiState.settings?.temperature ?: 1.0f,
                    onValueChange = { viewModel.onTemperatureChanged(it) },
                    valueRange = 0f..2f,
                    steps = 19
                )
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { viewModel.onTemperatureChanged(null) }) { Text("Reset Temp") }
                }

                Text("Top P: ${uiState.settings?.topP?.toString() ?: "Default"}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = uiState.settings?.topP ?: 1.0f,
                    onValueChange = { viewModel.onTopPChanged(it) },
                    valueRange = 0f..1f,
                    steps = 9
                )
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { viewModel.onTopPChanged(null) }) { Text("Reset Top P") }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Response Length Profile
                Text("Response Length", style = MaterialTheme.typography.titleMedium)
                uiState.settings?.responseLengthProfile?.let { currentProfile ->
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SegmentedButton(
                            selected = currentProfile == ResponseLengthProfile.SHORT,
                            onClick = { viewModel.onResponseProfileSelected(ResponseLengthProfile.SHORT) },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 4)
                        ) { Text("Short") }
                        SegmentedButton(
                            selected = currentProfile == ResponseLengthProfile.NORMAL,
                            onClick = { viewModel.onResponseProfileSelected(ResponseLengthProfile.NORMAL) },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 4)
                        ) { Text("Normal") }
                        SegmentedButton(
                            selected = currentProfile == ResponseLengthProfile.LONG,
                            onClick = { viewModel.onResponseProfileSelected(ResponseLengthProfile.LONG) },
                            shape = SegmentedButtonDefaults.itemShape(index = 2, count = 4)
                        ) { Text("Long") }
                        SegmentedButton(
                            selected = currentProfile == ResponseLengthProfile.CUSTOM,
                            onClick = { viewModel.onResponseProfileSelected(ResponseLengthProfile.CUSTOM) },
                            shape = SegmentedButtonDefaults.itemShape(index = 3, count = 4)
                        ) { Text("Custom") }
                    }
                    
                    if (currentProfile == ResponseLengthProfile.CUSTOM) {
                        var min by remember(uiState.settings) { mutableStateOf(uiState.settings?.customMin?.toString() ?: "900") }
                        var target by remember(uiState.settings) { mutableStateOf(uiState.settings?.customTargetMax?.toString() ?: "1600") }
                        var max by remember(uiState.settings) { mutableStateOf(uiState.settings?.customHardMax?.toString() ?: "1900") }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = min,
                                onValueChange = { min = it },
                                label = { Text("Min") },
                                shape = MaterialTheme.shapes.medium,
                                colors = elsewhereTextFieldColors(),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = target,
                                onValueChange = { target = it },
                                label = { Text("Target") },
                                shape = MaterialTheme.shapes.medium,
                                colors = elsewhereTextFieldColors(),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = max,
                                onValueChange = { max = it },
                                label = { Text("Max") },
                                shape = MaterialTheme.shapes.medium,
                                colors = elsewhereTextFieldColors(),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Button(onClick = {
                            val minVal = min.toIntOrNull() ?: 900
                            val targetVal = target.toIntOrNull() ?: 1600
                            val maxVal = max.toIntOrNull() ?: 1900
                            viewModel.onCustomLengthsChanged(minVal, targetVal, maxVal)
                        }) {
                            Text("Apply Custom Lengths")
                        }
                        uiState.customLengthError?.let { error ->
                            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        val desc = when (currentProfile) {
                            ResponseLengthProfile.SHORT -> "400-800 characters (max 1000)"
                            ResponseLengthProfile.NORMAL -> "900-1600 characters (max 1900)"
                            ResponseLengthProfile.LONG -> "1600-2600 characters (max 3000)"
                            else -> ""
                        }
                        Text(desc, style = MaterialTheme.typography.bodySmall)
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Routing Mode
                Text("Provider Routing", style = MaterialTheme.typography.titleMedium)
                uiState.settings?.providerRoutingMode?.let { currentMode ->
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = currentMode == ProviderRoutingMode.AUTO,
                            onClick = { viewModel.onRoutingModeSelected(ProviderRoutingMode.AUTO) },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
                        ) { Text("Auto") }
                        SegmentedButton(
                            selected = currentMode == ProviderRoutingMode.PREFER,
                            onClick = { viewModel.onRoutingModeSelected(ProviderRoutingMode.PREFER) },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
                        ) { Text("Prefer") }
                        SegmentedButton(
                            selected = currentMode == ProviderRoutingMode.LOCK,
                            onClick = { viewModel.onRoutingModeSelected(ProviderRoutingMode.LOCK) },
                            shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
                        ) { Text("Lock") }
                    }
                    val routingDesc = when (currentMode) {
                        ProviderRoutingMode.AUTO -> "Automatically route to the best available provider."
                        ProviderRoutingMode.PREFER -> "Attempt preferred endpoint first, but fallback if unavailable."
                        ProviderRoutingMode.LOCK -> "Strictly use only the selected endpoint. Will fail if unavailable."
                    }
                    Text(routingDesc, style = MaterialTheme.typography.bodySmall)
                }
                
                if (uiState.settings?.providerRoutingMode != ProviderRoutingMode.AUTO) {
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = !expanded }
                    ) {
                        OutlinedTextField(
                            value = selectedEndpointDisplayName(
                                selectedIdentifier = uiState.settings?.providerEndpoint,
                                endpoints = uiState.endpoints
                            ),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Endpoint") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            if (uiState.isFetchingEndpoints) {
                                DropdownMenuItem(text = { Text("Loading...") }, onClick = {})
                            } else if (uiState.endpoints.isEmpty()) {
                                DropdownMenuItem(text = { Text("No specific endpoints available") }, onClick = {})
                            } else {
                                uiState.endpoints.forEach { endpoint ->
                                    DropdownMenuItem(
                                        text = { Text(endpoint.name) },
                                        onClick = {
                                            viewModel.onEndpointSelected(endpoint.identifier)
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Model Selection
                Text("Model Selection", style = MaterialTheme.typography.titleMedium)
                
                uiState.settings?.selectedModelId?.let { modelId ->
                    val selectedModel = uiState.models.find { it.id == modelId }
                    if (selectedModel != null) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                            modifier = Modifier.fillMaxWidth(),
                            border = elsewhereCardBorder()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Active Model", style = MaterialTheme.typography.labelSmall)
                                Spacer(Modifier.height(4.dp))
                                Text(selectedModel.name, fontWeight = FontWeight.Bold)
                                Text(selectedModel.id, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    } else {
                        Text("Selected Model: $modelId (Not in catalog)")
                    }
                } ?: run {
                    Text("No model selected")
                }

                Spacer(Modifier.height(8.dp))
                
                var showModelSearch by remember { mutableStateOf(false) }
                Button(onClick = { showModelSearch = !showModelSearch }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (showModelSearch) "Hide Models" else "Search Models")
                }

                if (showModelSearch) {
                    OutlinedTextField(
                        value = uiState.searchQuery,
                        onValueChange = viewModel::onSearchQueryChanged,
                        label = { Text("Search Models") },
                        shape = MaterialTheme.shapes.medium,
                        colors = elsewhereTextFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    if (uiState.isFetchingModels) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                    } else {
                        // Limit to 20 to avoid lag in a scrollview
                        uiState.filteredModels.take(20).forEach { model ->
                            ModelItem(
                                model = model,
                                isSelected = model.id == uiState.settings?.selectedModelId,
                                onClick = { viewModel.onModelSelected(model.id) }
                            )
                        }
                        if (uiState.filteredModels.size > 20) {
                            Text("... and ${uiState.filteredModels.size - 20} more. Refine search.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ModelItem(model: OpenRouterModel, isSelected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ),
        border = elsewhereCardBorder()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(model.name, fontWeight = FontWeight.Bold, color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(model.id, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Ctx: ${model.contextLength / 1000}k", style = MaterialTheme.typography.labelSmall)
                val inPrice = formatPrice(model.pricingPrompt)
                val outPrice = formatPrice(model.pricingCompletion)
                Text("\$$inPrice / \$$outPrice per 1M", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

fun formatPrice(priceStr: String): String {
    val price = priceStr.toBigDecimalOrNull() ?: return "0"
    return (price * BigDecimal(1_000_000)).stripTrailingZeros().toPlainString()
}

internal fun selectedEndpointDisplayName(
    selectedIdentifier: String?,
    endpoints: List<ProviderEndpoint>
): String = selectedIdentifier
    ?.let { identifier -> endpoints.firstOrNull { it.identifier == identifier }?.name ?: identifier }
    ?: "Select Endpoint"
