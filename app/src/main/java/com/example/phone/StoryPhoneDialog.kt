package com.example.phone

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.di.AppContainer
import com.example.phonelab.PulseFeedPack
import com.example.phonelab.systems.ContactRole
import com.example.phonelab.systems.Destination
import com.example.phonelab.systems.PhoneMessage
import com.example.phonelab.systems.Speaker
import com.example.ui.components.ResourceTextField
import com.example.ui.components.elsewhereCardBorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.util.UUID

private suspend fun <T> attemptPhone(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StoryPhoneDialog(chatId: String, app: AppContainer, phone: StoryPhoneActions, onClose: () -> Unit) {
    var section by rememberSaveable { mutableStateOf("home") }
    var contactId by rememberSaveable { mutableStateOf<String?>(null) }
    var personaId by rememberSaveable { mutableStateOf<String?>(null) }
    val candidates by produceState<List<Pair<String, String>>?>(null, chatId) {
        val active = app.chatRepository.getPersonaParticipantsForChat(chatId)
        active.collect { members ->
            val ids = members.filter { it.isActive }.map { it.personaId }.toSet()
            value = app.chatRepository.getChatSummaryById(chatId)?.personas.orEmpty()
                .filter { it.personaId in ids }.map { it.personaId to it.displayName }
        }
    }
    val persona = candidates.orEmpty().singleOrNull() ?: candidates.orEmpty().find { it.first == personaId }
    val target = persona?.let { StoryPhoneActions.Target(chatId, it.first) }
    val loaded by produceState<Result<StoryPhoneActions.Inbox>?>(null, target) {
        value = null
        if (target != null) value = attemptPhone { phone.inbox(target) }
    }
    val inbox = loaded?.getOrNull()
    val contact = inbox?.contacts?.find { it.id == contactId }
    val drafts = rememberSaveableStateHolder()
    val replyEngine = remember(app, phone) { PhoneReplyEngine(app.chatRepository, app.modelProvider, phone) }
    fun back() {
        if (contactId != null) contactId = null
        else if (section != "home") section = "home"
        else onClose()
    }
    Dialog(
        onDismissRequest = ::back,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(Modifier.fillMaxSize().testTag("persona_phone"), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                topBar = {
                    TopAppBar(
                        title = { Text(contact?.name ?: when (section) {
                            "texts" -> "Texting"
                            "pulse" -> "World Pulse"
                            else -> "Persona’s Phone"
                        }) },
                        navigationIcon = {
                            IconButton(onClick = ::back) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back on the phone")
                            }
                        },
                        actions = {
                            IconButton(onClick = onClose, modifier = Modifier.testTag("phone_return_story")) {
                                Icon(Icons.Default.Close, "Return to Story")
                            }
                        },
                        windowInsets = WindowInsets(0, 0, 0, 0)
                    )
                }
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    when {
                        candidates == null || (target != null && loaded == null) ->
                            CircularProgressIndicator(Modifier.align(Alignment.Center))
                        candidates.isNullOrEmpty() -> PhoneNotice("Add a Persona to this story to open their phone.")
                        persona == null -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Whose phone are you opening?", style = MaterialTheme.typography.titleMedium)
                            candidates.orEmpty().forEach { (id, name) ->
                                OutlinedButton(onClick = { personaId = id }) { Text(name) }
                            }
                        }
                        target == null -> Unit
                        inbox == null -> PhoneNotice(loaded?.exceptionOrNull()?.message ?: "This phone isn't available.")
                        contact != null -> drafts.SaveableStateProvider("${target.personaId}:${contact.id}") {
                            PhoneThread(target, contact, phone, replyEngine)
                        }
                        section == "texts" -> LazyColumn(contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (inbox.contacts.isEmpty()) item { PhoneNotice("No story characters are available to text yet.") }
                            items(inbox.contacts, key = { it.id }) { row ->
                                Card(onClick = { contactId = row.id }, border = elsewhereCardBorder(),
                                    modifier = Modifier.fillMaxWidth().testTag("phone_contact_${row.id}")) {
                                    Column(Modifier.padding(16.dp)) {
                                        Text(row.name, style = MaterialTheme.typography.titleMedium)
                                        val description = if (row.role == ContactRole.LEAD) "Story Character" else "NPC"
                                        Text(listOfNotNull(description, row.introducedIn).joinToString(" · "),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        section == "pulse" -> PhonePulse(target, phone)
                        else -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(persona.second, style = MaterialTheme.typography.headlineMedium)
                            Text("Inside this playthrough", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FilledTonalButton(
                                onClick = { section = "texts" },
                                modifier = Modifier.fillMaxWidth().testTag("phone_texting")
                            ) { Icon(Icons.Default.ChatBubbleOutline, null); Spacer(Modifier.width(8.dp)); Text("Texting") }
                            FilledTonalButton(
                                onClick = { section = "pulse" },
                                modifier = Modifier.fillMaxWidth().testTag("phone_world_pulse")
                            ) { Icon(Icons.Default.Public, null); Spacer(Modifier.width(8.dp)); Text("World Pulse") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhoneNotice(text: String) {
    Text(text, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PhoneThread(
    target: StoryPhoneActions.Target,
    contact: StoryPhoneActions.Contact,
    phone: StoryPhoneActions,
    engine: PhoneReplyEngine
) {
    val scope = rememberCoroutineScope()
    var messages by remember { mutableStateOf<List<PhoneMessage>>(emptyList()) }
    var ready by remember { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var requestRunning by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var reviewing by remember { mutableStateOf<PhoneMessage?>(null) }
    LaunchedEffect(target, contact.id) {
        attemptPhone { phone.messages(target, contact.id) }
            .onSuccess { messages = it; ready = true }
            .onFailure { notice = it.message ?: "This thread could not be loaded." }
    }
    fun send(retryId: String? = null) {
        if (!ready || requestRunning || (retryId == null && (draft.isBlank() || draft.length > 4000))) return
        val sent = draft
        requestRunning = true
        notice = null
        scope.launch {
            try {
                val pending = if (retryId == null) {
                    phone.prepareText(target, contact.id, UUID.randomUUID().toString(), sent, System.currentTimeMillis())
                        .also { if (draft == sent) draft = "" }
                } else phone.retryText(target, contact.id, retryId)
                messages = phone.messages(target, contact.id)
                engine.reply(pending)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = e.message ?: "The reply could not be generated."
            } finally { requestRunning = false }
            attemptPhone { phone.messages(target, contact.id) }.onSuccess { messages = it }
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("phone_messages"), reverseLayout = true,
            contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages.asReversed(), key = { it.id }) { message ->
                val persona = message.speaker == Speaker.PERSONA
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (persona) Arrangement.End else Arrangement.Start) {
                    Card(
                        onClick = { reviewing = message },
                        modifier = Modifier.fillMaxWidth(.84f),
                        colors = CardDefaults.cardColors(containerColor = if (persona) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) { Text(message.text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge) }
                }
            }
            if (messages.isEmpty()) item { PhoneNotice("No texts yet. This thread is separate from the Story.") }
        }
        if (requestRunning) LinearProgressIndicator(Modifier.fillMaxWidth())
        notice?.let { Text(it, Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error) }
        val latest = messages.lastOrNull()
        if (!requestRunning && latest?.speaker == Speaker.PERSONA) {
            TextButton(onClick = { send(latest.id) }) { Text("Retry reply") }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(draft, { draft = it }, modifier = Modifier.weight(1f).testTag("phone_text_composer"),
                label = { Text("Text ${contact.name}") }, maxLines = 4,
                isError = draft.length > 4000, enabled = ready && !requestRunning)
            Button(onClick = { send() }, enabled = ready && !requestRunning && draft.isNotBlank() && draft.length <= 4000) {
                Text("Send")
            }
        }
    }
    reviewing?.let { message ->
        PhoneReview(message, onClose = { reviewing = null }) { note, world ->
            val id = UUID.randomUUID().toString()
            phone.saveSelected(target, contact.id, id, listOf(message.id), note,
                if (world) Destination.WORLD_CANON_DRAFT else Destination.PLAYTHROUGH_LORE)
            val title = "Text with ${contact.name}".take(160)
            if (world) phone.queueWorldReview(target, contact.id, id, title)
            else phone.saveCampaignNote(target, contact.id, id, title)
            notice = if (world) "Saved as a draft in World Canon." else "Saved to the Campaign’s Discoveries."
        }
    }
}

@Composable
private fun PhoneReview(message: PhoneMessage, onClose: () -> Unit, onSave: suspend (String, Boolean) -> Unit) {
    var note by remember { mutableStateOf(message.text) }
    var world by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Review this text") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ResourceTextField(note, { note = it }, "Text to save", maxLength = 12000, enabled = !busy)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = world, enabled = !busy, onCheckedChange = { world = it })
                Text("Queue for World Canon review")
            }
            Text(if (world) "Saves a draft, not accepted canon. Review it under the World’s Canon tab."
                else "Saves a Campaign Discovery. It isn’t automatically shared with the Story engine.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(
            enabled = !busy && note.isNotBlank() && note.length <= 12000,
            onClick = {
                busy = true
                scope.launch {
                    attemptPhone { onSave(note, world) }.onSuccess { onClose() }
                        .onFailure { error = it.message ?: "Could not save this note." }
                    busy = false
                }
            }
        ) { Text("Save reviewed text") } },
        dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text("Cancel") } }
    )
}

@Composable
private fun PhonePulse(target: StoryPhoneActions.Target, phone: StoryPhoneActions) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var feed by remember { mutableStateOf<List<StoryPhoneActions.FeedPack>>(emptyList()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var showImport by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<StoryPhoneActions.FeedPreview?>(null) }
    var includePosts by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var packId by rememberSaveable { mutableStateOf<String?>(null) }
    var channelId by rememberSaveable { mutableStateOf<String?>(null) }
    val pack = feed.firstOrNull { it.id == packId } ?: feed.firstOrNull()
    val channel = pack?.channels?.firstOrNull { it.id == channelId } ?: pack?.channels?.firstOrNull()
    LaunchedEffect(target, revision) {
        attemptPhone { phone.feed(target) }.onSuccess { feed = it }
            .onFailure { notice = it.message ?: "The feed could not be opened." }
    }
    fun previewFile(bytes: ByteArray) = scope.launch {
        attemptPhone { phone.previewFeedPack(target, bytes) }
            .onSuccess { preview = it; includePosts = false; showImport = false; notice = null }
            .onFailure { notice = it.message ?: "The Feed Pack could not be read." }
    }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            attemptPhone {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readNBytes(PulseFeedPack.MAX_BYTES + 1)
                    } ?: error("Could not open the selected file.")
                }
            }.onSuccess { previewFile(it) }.onFailure { notice = it.message ?: "Could not read the selected file." }
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("phone_pulse_feeds"), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Button(onClick = { notice = null; showImport = true }) { Text("Import Feed Pack") }
            Text("Imported posts only. AI feed refresh is not available in this build.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        if (notice != null) item { PhoneNotice(notice.orEmpty()) }
        if (pack == null) item { PhoneNotice("No feeds here yet. Import a World Pulse Feed Pack.") }
        if (feed.size > 1) items(feed, key = { it.id }) { option ->
            FilterChip(selected = option.id == pack?.id, onClick = { packId = option.id; channelId = null },
                label = { Text(option.name) })
        }
        pack?.let { selected ->
            item { Text(selected.name, style = MaterialTheme.typography.headlineSmall) }
            if (selected.channels.size > 1) items(selected.channels, key = { "channel:${it.id}" }) { option ->
                FilterChip(selected = option.id == channel?.id, onClick = { channelId = option.id },
                    label = { Text(option.name) })
            }
        }
        channel?.let { selected ->
            item { Text(selected.name, style = MaterialTheme.typography.titleLarge) }
            if (selected.posts.isEmpty()) item { PhoneNotice("No starter posts in this playthrough for this feed.") }
            items(selected.posts, key = { "post:${pack?.id}:${it.id}" }) { post ->
                Card(Modifier.fillMaxWidth(), border = elsewhereCardBorder()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(post.byline, style = MaterialTheme.typography.titleSmall)
                        if (post.kind == "rumor") Text("Rumor · unverified", color = MaterialTheme.colorScheme.tertiary)
                        Text(post.text)
                        post.replies.forEach { reply ->
                            HorizontalDivider()
                            Text(reply.byline, style = MaterialTheme.typography.labelLarge)
                            if (reply.kind == "rumor") Text("Rumor · unverified", color = MaterialTheme.colorScheme.tertiary)
                            Text(reply.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
    if (showImport) AlertDialog(
        onDismissRequest = { showImport = false },
        title = { Text("Open a World Pulse Feed Pack") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { documents.launch(arrayOf("application/json", "text/json", "text/plain")) }) {
                    Text("Choose JSON file")
                }
                ResourceTextField(pasted, { pasted = it }, "Feed Pack JSON", maxLength = PulseFeedPack.MAX_BYTES)
                notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = pasted.isNotBlank() && pasted.length <= PulseFeedPack.MAX_BYTES,
                onClick = { previewFile(pasted.toByteArray(Charsets.UTF_8)) }) { Text("Review pasted JSON") }
        },
        dismissButton = { TextButton(onClick = { showImport = false }) { Text("Cancel") } }
    )
    preview?.let { reviewed ->
        val counts = reviewed.content
        AlertDialog(
            onDismissRequest = { if (!busy) preview = null },
            title = { Text("Review ${counts.name}") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${counts.platforms} platforms · ${counts.accounts} accounts")
                Text("Platform definitions go to this World. Starter posts go only to this playthrough.")
                if (counts.starterForReview() != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includePosts, enabled = !busy, onCheckedChange = { includePosts = it })
                    Text("Also import the starter feed (${counts.posts} posts, ${counts.replies} replies)")
                }
                notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    attemptPhone { phone.importReviewedPack(reviewed, includePosts) }.onSuccess { receipt ->
                        preview = null
                        pasted = ""
                        packId = receipt.starterId ?: receipt.templateId
                        channelId = null
                        revision++
                        notice = if (receipt.addedTemplate || receipt.addedStarter) "Feed Pack imported." else "This Feed Pack is already here."
                    }.onFailure { notice = it.message ?: "The Feed Pack could not be imported." }
                    busy = false
                }
            }) { Text("Import here") } },
            dismissButton = { TextButton(onClick = { preview = null }, enabled = !busy) { Text("Cancel") } }
        )
    }
}
