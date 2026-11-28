package com.example.phone

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.domain.provider.ModelProvider
import com.example.phonelab.PulseGeneratedPost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun PulseRefreshDialog(
    target: StoryPhoneActions.Target,
    pack: StoryPhoneActions.FeedPack,
    channel: StoryPhoneActions.FeedChannel,
    phone: StoryPhoneActions,
    settings: PulseModelSettings,
    provider: ModelProvider,
    onClose: () -> Unit,
    onPublished: (Int) -> Unit
) {
    val scope = rememberCoroutineScope()
    val model by produceState<String?>(null, settings) { value = settings.getModel().orEmpty() }
    val engine = remember(provider) { PulseReplyEngine(provider) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<StoryPhoneActions.PendingPulse?>(null) }
    var posts by remember { mutableStateOf<List<PulseGeneratedPost>>(emptyList()) }
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(if (pending == null) "Generate for ${channel.name}" else "Review generated posts") },
        text = {
            Column(Modifier.heightIn(max = 390.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val current = pending
                if (current == null) {
                    Text("One request to ${model?.takeIf(String::isNotBlank) ?: "the Pulse model"}. It may use OpenRouter credits.")
                    if (model == "") Text("Choose a specific Pulse model in Settings → Default engine first.")
                    Text("Select up to 8 public events. Private texts and your main Story are never sent to Pulse.")
                    pack.publicEvents.forEach { event ->
                        Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                            Checkbox(checked = event.id in selected, modifier = Modifier.testTag("pulse_event_${event.id}"), enabled = !busy &&
                                (event.id in selected || selected.size < 8),
                                onCheckedChange = { selected = if (it) selected + event.id else selected - event.id })
                            Text(event.summary, Modifier.weight(1f).padding(top = 12.dp))
                        }
                    }
                } else {
                    Text("Nothing has been added yet. These posts will appear only in this playthrough, not in World Canon.")
                    posts.forEach { post ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(current.request.accounts.first { it.id == post.accountId }.name,
                                    style = MaterialTheme.typography.titleSmall)
                                if (post.kind == "rumor") Text("Rumor · unverified", color = MaterialTheme.colorScheme.tertiary)
                                Text(post.text)
                            }
                        }
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            val generating = pending == null
            TextButton(
                enabled = !busy && if (generating) !model.isNullOrBlank() && selected.isNotEmpty() else posts.isNotEmpty(),
                modifier = Modifier.testTag(if (generating) "pulse_generate" else "pulse_publish"),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val existing = pending
                            if (existing == null) {
                                val candidate = phone.preparePulse(target, pack.id, channel.id, selected.toList())
                                posts = engine.generate(model, candidate.request)
                                pending = candidate
                            } else onPublished(phone.publishPulse(existing, posts))
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: "Couldn't finish this Pulse batch." }
                        finally { busy = false }
                    }
                }
            ) { Text(if (generating) "Generate preview" else "Add posts to feed") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text("Cancel") } }
    )
}
