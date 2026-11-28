package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.domain.provider.ModelProvider
import com.example.domain.provider.OpenRouterModel
import com.example.phone.PulseModelSettings
import com.example.ui.components.elsewhereCardBorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun PulseModelCard(settings: PulseModelSettings, provider: ModelProvider?) {
    val scope = rememberCoroutineScope()
    var model by rememberSaveable { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<List<OpenRouterModel>>(emptyList()) }
    var show by remember { mutableStateOf(false) }
    var fetching by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(settings) {
        model = settings.getModel().orEmpty()
        loaded = true
    }
    Card(Modifier.fillMaxWidth(), border = elsewhereCardBorder()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("World Pulse engine", style = MaterialTheme.typography.titleLarge)
            Text("Uses the same OpenRouter connection as Story but only the model you pick here. Nothing runs automatically.")
            OutlinedTextField(
                model, { model = it }, enabled = loaded, singleLine = true, label = { Text("Pulse model ID") },
                modifier = Modifier.fillMaxWidth().testTag("pulse_model_id")
            )
            if (provider != null) OutlinedButton(onClick = {
                show = !show
                if (show && !fetching && models.isEmpty()) scope.launch {
                    fetching = true
                    status = null
                    try {
                        models = provider.getModels().filterNot { it.id.equals("openrouter/auto", true) }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { status = "Couldn't load the catalog. You can still enter a model ID." }
                    finally { fetching = false }
                }
            }) { Text(if (show) "Hide Pulse model catalog" else "Choose Pulse model") }
            if (show) {
                if (fetching) LinearProgressIndicator(Modifier.fillMaxWidth())
                OutlinedTextField(query, { query = it }, label = { Text("Search Pulse models") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                models.asSequence().filter { it.name.contains(query, true) || it.id.contains(query, true) }
                    .take(12).forEach { m -> ModelItem(m, m.id == model) { model = m.id; show = false } }
            }
            Button(enabled = loaded, onClick = {
                scope.launch {
                    try {
                        settings.saveModel(model)
                        status = if (model.isBlank()) "Pulse generation disabled." else "Pulse model saved."
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { status = e.message ?: "Couldn't save the Pulse model." }
                }
            }, modifier = Modifier.testTag("save_pulse_model")) { Text("Save Pulse model") }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
