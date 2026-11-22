package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.example.domain.world.WorldDefinitionCodec
import com.example.domain.world.WorldArchiveTransferCoordinator
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.model.WorldCastEntity
import com.example.data.model.WorldContinuityEventEntity
import com.example.data.model.WorldEntity
import com.example.data.model.CampaignEntity
import com.example.data.model.CampaignChapterEntity
import com.example.data.model.CampaignJournalEntryEntity
import com.example.data.model.CampaignContinuityItemEntity
import com.example.data.model.ChatEntity
import com.example.domain.repository.CampaignRepository
import com.example.domain.repository.WorldRepository
import com.example.domain.repository.ChatRepository
import com.example.ui.components.elsewhereCardBorder
import com.example.ui.components.elsewhereTextFieldColors
import com.example.ui.components.elsewhereTopAppBarColors
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

/**
 * Manual Worlds editor with limited World-definition JSON import/export.
 * Complete archives with linked resources and episode history remain a separate milestone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldsScreen(
    repository: WorldRepository,
    archiveTransfer: WorldArchiveTransferCoordinator? = null,
    chatRepository: ChatRepository? = null,
    campaignRepository: CampaignRepository? = null,
    onOpenChat: ((String) -> Unit)? = null,
    onCreateSoloEpisode: ((String) -> Unit)? = null,
    onCreateScenarioEpisode: ((String) -> Unit)? = null,
    onCreateSoloCampaignChapter: ((String, String) -> Unit)? = null
) {
    val worlds by repository.observeWorlds().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = worlds.firstOrNull { it.worldId == selectedId }
    var editingWorld by remember { mutableStateOf<WorldEntity?>(null) }
    var isCreatingWorld by remember { mutableStateOf(false) }
    var editingCast by remember { mutableStateOf<WorldCastEntity?>(null) }
    var editingEvent by remember { mutableStateOf<WorldContinuityEventEntity?>(null) }
    var deletingWorld by remember { mutableStateOf<WorldEntity?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshVersion by remember { mutableIntStateOf(0) }
    var cast by remember { mutableStateOf<List<WorldCastEntity>>(emptyList()) }
    var events by remember { mutableStateOf<List<WorldContinuityEventEntity>>(emptyList()) }
    var campaigns by remember { mutableStateOf<List<CampaignEntity>>(emptyList()) }
    var chapters by remember { mutableStateOf<List<CampaignChapterEntity>>(emptyList()) }
    var availableChats by remember { mutableStateOf<List<ChatEntity>>(emptyList()) }
    var chosenCampaignId by remember { mutableStateOf<String?>(null) }
    val chosenCampaign = campaigns.firstOrNull {
        it.campaignId == chosenCampaignId && it.worldId == selectedId
    }
    var newCampaignOpen by remember { mutableStateOf(false) }
    var newCampaignTitle by remember { mutableStateOf("") }
    var deletingCampaign by remember { mutableStateOf<CampaignEntity?>(null) }
    var newChapterOpen by remember { mutableStateOf(false) }
    var newChapterTitle by remember { mutableStateOf("") }
    var newChapterChatId by remember { mutableStateOf<String?>(null) }
    var chatPickerOpen by remember { mutableStateOf(false) }
    var editingChapter by remember { mutableStateOf<CampaignChapterEntity?>(null) }
    var chapterEditTitle by remember { mutableStateOf("") }
    var chapterEditRecap by remember { mutableStateOf("") }
    var chapterEditComplete by remember { mutableStateOf(false) }
    var journalEntries by remember { mutableStateOf<List<CampaignJournalEntryEntity>>(emptyList()) }
    var checkpointStatuses by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var journalDialogOpen by remember { mutableStateOf(false) }
    var journalDraftType by remember { mutableStateOf("JOURNAL") }
    var journalDraftChapterId by remember { mutableStateOf<String?>(null) }
    var journalEditTarget by remember { mutableStateOf<CampaignJournalEntryEntity?>(null) }
    var journalDraftTitle by remember { mutableStateOf("") }
    var journalDraftBody by remember { mutableStateOf("") }
    var deleteJournalTarget by remember { mutableStateOf<CampaignJournalEntryEntity?>(null) }
    var worldDraftJournalTarget by remember { mutableStateOf<CampaignJournalEntryEntity?>(null) }
    var worldDraftReviewedTitle by remember { mutableStateOf("") }
    var worldDraftReviewedFacts by remember { mutableStateOf("") }
    var continuityItems by remember { mutableStateOf<List<CampaignContinuityItemEntity>>(emptyList()) }
    var continuityDialogOpen by remember { mutableStateOf(false) }
    var continuityEditTarget by remember { mutableStateOf<CampaignContinuityItemEntity?>(null) }
    var continuityDraftType by remember { mutableStateOf("THREAD") }
    var continuityDraftTitle by remember { mutableStateOf("") }
    var continuityDraftDetails by remember { mutableStateOf("") }
    var continuityDraftResolution by remember { mutableStateOf("") }
    var continuityDraftComplete by remember { mutableStateOf(false) }
    var continuityDraftOpenChapter by remember { mutableStateOf<String?>(null) }
    var continuityDraftCloseChapter by remember { mutableStateOf<String?>(null) }
    var continuityTypePickerOpen by remember { mutableStateOf(false) }
    var continuityOpenChapterPickerOpen by remember { mutableStateOf(false) }
    var continuityCloseChapterPickerOpen by remember { mutableStateOf(false) }
    var deleteContinuityTarget by remember { mutableStateOf<CampaignContinuityItemEntity?>(null) }

    val context = LocalContext.current
    var showImportJson by remember { mutableStateOf(false) }
    var importJsonText by remember { mutableStateOf("") }
    var pendingExportJson by remember { mutableStateOf<String?>(null) }
    var confirmPlaintextWorldExport by remember { mutableStateOf<WorldEntity?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        val json = pendingExportJson
        pendingExportJson = null
        if (uri != null && json != null) {
            scope.launch {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json) }
                        ?: throw IllegalStateException("Unable to open the selected export location.")
                }.onSuccess {
                    notice = "World Definition JSON saved. This is an unencrypted file; " +
                        "chat history, attachments, drafts, and unaccepted continuity are excluded."
                }
                    .onFailure { error = it.message ?: "Unable to save World JSON." }
            }
        }
    }
    val importFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) scope.launch {
            runCatching {
                // Bounded read to avoid loading giant or malicious content into the editor.
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Unable to open the selected file.")
                input.bufferedReader().use { reader ->
                    val content = StringBuilder()
                    val block = CharArray(8192)
                    while (true) {
                        val length = reader.read(block)
                        if (length < 0) break
                        require(content.length + length <= WorldDefinitionCodec.MAX_JSON_CHARS) {
                            "World JSON exceeds size limit."
                        }
                        content.append(block, 0, length)
                    }
                    content.toString()
                }
            }.onSuccess {
                importJsonText = it
                showImportJson = true
                error = null
            }.onFailure { error = it.message ?: "Unable to read World JSON." }
        }
    }

    // Switching Worlds must never leave dialogs/actions pointing to a
    // previously opened Campaign, even for one async Room refresh frame.
    LaunchedEffect(selectedId) {
        chosenCampaignId = null
        journalDialogOpen = false
        journalEditTarget = null
        worldDraftJournalTarget = null
        continuityDialogOpen = false
        continuityEditTarget = null
        deleteContinuityTarget = null
    }

    LaunchedEffect(selectedId, chosenCampaignId, refreshVersion) {
        val id = selectedId
        if (id == null) {
            cast = emptyList()
            events = emptyList()
            campaigns = emptyList()
            chapters = emptyList()
            journalEntries = emptyList()
            checkpointStatuses = emptyMap()
            continuityItems = emptyList()
            availableChats = emptyList()
        } else {
            try {
                cast = repository.getCast(id)
                events = repository.getEvents(id)
                campaigns = campaignRepository?.forWorld(id).orEmpty()
                chapters = chosenCampaignId?.takeIf { chosen ->
                    campaigns.any { it.campaignId == chosen }
                }?.let { campaignRepository?.chapters(it) }.orEmpty()
                val activeCampaign = chosenCampaignId?.takeIf { chosen ->
                    campaigns.any { it.campaignId == chosen }
                }
                journalEntries = activeCampaign?.let { campaignRepository?.journal(it) }.orEmpty()
                continuityItems = activeCampaign?.let {
                    campaignRepository?.continuityItems(it)
                }.orEmpty()
                checkpointStatuses = journalEntries
                    .filter { it.entryType == "CHECKPOINT" }
                    .associate { entry ->
                        entry.journalEntryId to runCatching {
                            requireNotNull(campaignRepository)
                                .checkpointStatus(entry.campaignId, entry.journalEntryId)
                                .name.replace('_', ' ')
                        }.getOrDefault("CHECKPOINT UNAVAILABLE")
                    }
                availableChats = chatRepository?.getAllChats()?.first().orEmpty()
            } catch (failure: Exception) {
                error = failure.message ?: "Unable to load World details."
            }
        }
    }

    fun submit(action: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            try {
                action()
                refreshVersion++
            } catch (failure: Exception) {
                error = failure.message ?: "World change could not be saved."
            } finally {
                busy = false
            }
        }
    }

    if (newCampaignOpen && selected != null && campaignRepository != null) AlertDialog(
        onDismissRequest = { if (!busy) newCampaignOpen = false },
        title = { Text("Create Campaign") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Campaigns are separate stories inside this World. Their private events and recaps never become shared World Canon automatically.")
                OutlinedTextField(
                    newCampaignTitle, { newCampaignTitle = it.take(200) },
                    label = { Text("Campaign name") },
                    modifier = Modifier.testTag("campaign_title_field")
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && newCampaignTitle.isNotBlank(), onClick = {
                submit {
                    val created = campaignRepository.createCampaign(selected.worldId, newCampaignTitle)
                    chosenCampaignId = created.campaignId
                    newCampaignTitle = ""
                    newCampaignOpen = false
                }
            }, modifier = Modifier.testTag("save_campaign")) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = { newCampaignOpen = false }) { Text("Cancel") } }
    )

    deletingCampaign?.let { target ->
        if (campaignRepository != null) AlertDialog(
            onDismissRequest = { if (!busy) deletingCampaign = null },
            title = { Text("Delete Campaign organization?") },
            text = {
                Text("This permanently deletes this Campaign's organization, chapter recaps, " +
                    "Journal entries, checkpoints, arc summaries, promises and other tracked " +
                    "threads. It does NOT delete any underlying chat, message, variant or " +
                    "chat-local Story Memory. This cannot be undone.")
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    submit {
                        campaignRepository.deleteCampaign(target.campaignId)
                        if (chosenCampaignId == target.campaignId) chosenCampaignId = null
                        deletingCampaign = null
                    }
                }) { Text("Delete Campaign") }
            },
            dismissButton = { TextButton(onClick = { deletingCampaign = null }) { Text("Cancel") } }
        )
    }

    if (newChapterOpen && chosenCampaign != null && campaignRepository != null) AlertDialog(
        onDismissRequest = { if (!busy) newChapterOpen = false },
        title = { Text("Link chat as chapter") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Link an existing conversation without copying its messages. A chat can belong to only one Campaign.")
                Box {
                    OutlinedButton(onClick = { chatPickerOpen = true }) {
                        Text(availableChats.firstOrNull { it.chatId == newChapterChatId }?.displayName ?: "Choose chat")
                    }
                    DropdownMenu(expanded = chatPickerOpen, onDismissRequest = { chatPickerOpen = false }) {
                        availableChats.forEach { chat ->
                            DropdownMenuItem(text = { Text(chat.displayName) }, onClick = {
                                newChapterChatId = chat.chatId
                                if (newChapterTitle.isBlank()) newChapterTitle = chat.displayName.take(200)
                                chatPickerOpen = false
                            })
                        }
                    }
                }
                OutlinedTextField(newChapterTitle, { newChapterTitle = it.take(200) },
                    label = { Text("Chapter title") }, modifier = Modifier.testTag("campaign_chapter_title"))
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && newChapterChatId != null && newChapterTitle.isNotBlank(),
                onClick = {
                    submit {
                        campaignRepository.addChapter(chosenCampaign.campaignId,
                            requireNotNull(newChapterChatId), newChapterTitle)
                        newChapterOpen = false
                        newChapterTitle = ""
                        newChapterChatId = null
                    }
                }, modifier = Modifier.testTag("save_campaign_chapter")
            ) { Text("Link Chapter") }
        },
        dismissButton = { TextButton(onClick = { newChapterOpen = false }) { Text("Cancel") } }
    )

    editingChapter?.let { chapter ->
        if (campaignRepository != null) AlertDialog(
            onDismissRequest = { if (!busy) editingChapter = null },
            title = { Text("Chapter recap") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(chapterEditTitle, { chapterEditTitle = it.take(200) },
                        label = { Text("Chapter title") })
                    OutlinedTextField(chapterEditRecap, { chapterEditRecap = it.take(12_000) },
                        label = { Text("Private recap") }, minLines = 3, maxLines = 8,
                        modifier = Modifier.testTag("campaign_chapter_recap"))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = chapterEditComplete, onCheckedChange = { chapterEditComplete = it })
                        Text("Chapter complete")
                    }
                    Text("These are player-authored notes. They are not automatically sent to the model or added to shared World Canon.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && chapterEditTitle.isNotBlank(), onClick = {
                    submit {
                        campaignRepository.updateChapter(chapter.campaignId, chapter.chapterId,
                            chapterEditTitle, chapterEditRecap, chapterEditComplete)
                        editingChapter = null
                    }
                }, modifier = Modifier.testTag("save_campaign_recap")) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingChapter = null }) { Text("Cancel") } }
        )
    }

    if (journalDialogOpen && chosenCampaign != null && campaignRepository != null) AlertDialog(
        onDismissRequest = { if (!busy) journalDialogOpen = false },
        title = {
            Text(
                when {
                    journalEditTarget != null -> "Edit Campaign entry"
                    journalDraftType == "CHECKPOINT" -> "Save chapter checkpoint"
                    else -> "New Campaign Journal entry"
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (journalDraftType == "CHECKPOINT") {
                        "A checkpoint bookmarks the latest selected story turn. It does not rewind or overwrite chat history. Changed variants and Wack boundaries remain respected."
                    } else {
                        "This note stays private to this Campaign and is not automatically added to World Canon or model context."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = journalDraftTitle,
                    onValueChange = { journalDraftTitle = it.take(200) },
                    label = { Text("Title") },
                    modifier = Modifier.testTag("campaign_journal_title")
                )
                OutlinedTextField(
                    value = journalDraftBody,
                    onValueChange = { journalDraftBody = it.take(12_000) },
                    label = { Text(if (journalDraftType == "CHECKPOINT") "Checkpoint notes" else "Journal entry") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.testTag("campaign_journal_body")
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && journalDraftTitle.isNotBlank(),
                modifier = Modifier.testTag("save_campaign_journal_entry"),
                onClick = {
                    submit {
                        val target = journalEditTarget
                        if (target != null) {
                            campaignRepository.updateJournalEntry(
                                chosenCampaign.campaignId, target.journalEntryId,
                                journalDraftTitle, journalDraftBody
                            )
                        } else if (journalDraftType == "CHECKPOINT") {
                            campaignRepository.saveCheckpoint(
                                chosenCampaign.campaignId,
                                requireNotNull(journalDraftChapterId),
                                journalDraftTitle, journalDraftBody
                            )
                        } else {
                            campaignRepository.addJournalNote(
                                chosenCampaign.campaignId, journalDraftTitle,
                                journalDraftBody, journalDraftChapterId
                            )
                        }
                        journalDialogOpen = false
                        journalEditTarget = null
                    }
                }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = { journalDialogOpen = false; journalEditTarget = null }) {
                Text("Cancel")
            }
        }
    )

    deleteJournalTarget?.let { target ->
        if (campaignRepository != null) AlertDialog(
            onDismissRequest = { if (!busy) deleteJournalTarget = null },
            title = { Text("Delete journal entry?") },
            text = {
                Text("Only this Campaign entry is deleted. The underlying chat, selected variants and Story Memories remain unchanged.")
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    submit {
                        campaignRepository.deleteJournalEntry(
                            target.campaignId, target.journalEntryId
                        )
                        deleteJournalTarget = null
                    }
                }) { Text("Delete entry") }
            },
            dismissButton = {
                TextButton(onClick = { deleteJournalTarget = null }) { Text("Cancel") }
            }
        )
    }

    if (continuityDialogOpen && chosenCampaign != null && campaignRepository != null) AlertDialog(
        onDismissRequest = { if (!busy) continuityDialogOpen = false },
        title = {
            Text(
                when {
                    continuityEditTarget != null -> "Edit Campaign continuity"
                    continuityDraftType == "ARC" -> "New arc summary"
                    else -> "New ongoing story thread"
                }
            )
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "This is private, player-authored Campaign continuity. No automatic quests, " +
                        "OpenRouter calls, World Canon changes or cross-Campaign memory sharing.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (continuityEditTarget == null && continuityDraftType != "ARC") {
                    Box {
                        OutlinedButton(onClick = { continuityTypePickerOpen = true }) {
                            Text("Type: " + continuityDraftType)
                        }
                        DropdownMenu(expanded = continuityTypePickerOpen,
                            onDismissRequest = { continuityTypePickerOpen = false }) {
                            listOf("THREAD", "PROMISE", "QUEST", "DISCOVERY", "RELATIONSHIP")
                                .forEach { kind ->
                                    DropdownMenuItem(text = { Text(kind) }, onClick = {
                                        continuityDraftType = kind
                                        continuityTypePickerOpen = false
                                    })
                                }
                        }
                    }
                } else {
                    Text("Type: " + continuityDraftType, style = MaterialTheme.typography.labelMedium)
                }
                if (continuityEditTarget != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = continuityDraftComplete,
                            onCheckedChange = { continuityDraftComplete = it },
                            modifier = Modifier.testTag("campaign_continuity_complete")
                        )
                        Text(if (continuityDraftType == "ARC") "Arc complete" else "Resolved")
                    }
                }
                OutlinedTextField(
                    value = continuityDraftTitle,
                    onValueChange = { continuityDraftTitle = it.take(200) },
                    label = { Text("Arc or thread title") },
                    modifier = Modifier.testTag("campaign_continuity_title")
                )
                OutlinedTextField(
                    value = continuityDraftDetails,
                    onValueChange = { continuityDraftDetails = it.take(12_000) },
                    label = {
                        Text(if (continuityDraftType == "ARC") "Arc summary" else "What is ongoing?")
                    },
                    minLines = 3, maxLines = 8,
                    modifier = Modifier.testTag("campaign_continuity_details")
                )
                if (continuityEditTarget == null) {
                    Box {
                        OutlinedButton(onClick = { continuityOpenChapterPickerOpen = true }) {
                            Text("Opened in: " +
                                (chapters.firstOrNull { it.chapterId == continuityDraftOpenChapter }
                                    ?.title ?: "No linked chapter"))
                        }
                        DropdownMenu(expanded = continuityOpenChapterPickerOpen,
                            onDismissRequest = { continuityOpenChapterPickerOpen = false }) {
                            DropdownMenuItem(text = { Text("No linked chapter") }, onClick = {
                                continuityDraftOpenChapter = null
                                continuityOpenChapterPickerOpen = false
                            })
                            chapters.forEach { chapter ->
                                DropdownMenuItem(
                                    text = { Text("Chapter " + chapter.ordinal + ": " + chapter.title) },
                                    onClick = {
                                        continuityDraftOpenChapter = chapter.chapterId
                                        continuityOpenChapterPickerOpen = false
                                    }
                                )
                            }
                        }
                    }
                }
                if (continuityEditTarget != null) {
                    if (continuityDraftComplete) {
                        OutlinedTextField(
                            value = continuityDraftResolution,
                            onValueChange = { continuityDraftResolution = it.take(12_000) },
                            label = {
                                Text(if (continuityDraftType == "ARC") "Arc ending" else "How was this resolved?")
                            },
                            minLines = 2, maxLines = 6,
                            modifier = Modifier.testTag("campaign_continuity_resolution")
                        )
                        Box {
                            OutlinedButton(onClick = { continuityCloseChapterPickerOpen = true }) {
                                Text("Closed in: " +
                                    (chapters.firstOrNull { it.chapterId == continuityDraftCloseChapter }
                                        ?.title ?: "No linked chapter"))
                            }
                            DropdownMenu(expanded = continuityCloseChapterPickerOpen,
                                onDismissRequest = { continuityCloseChapterPickerOpen = false }) {
                                DropdownMenuItem(text = { Text("No linked chapter") }, onClick = {
                                    continuityDraftCloseChapter = null
                                    continuityCloseChapterPickerOpen = false
                                })
                                chapters.forEach { chapter ->
                                    DropdownMenuItem(
                                        text = { Text("Chapter " + chapter.ordinal + ": " + chapter.title) },
                                        onClick = {
                                            continuityDraftCloseChapter = chapter.chapterId
                                            continuityCloseChapterPickerOpen = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("save_campaign_continuity"),
                enabled = !busy && continuityDraftTitle.isNotBlank() &&
                    (continuityDraftType != "ARC" || continuityDraftDetails.isNotBlank()),
                onClick = {
                    submit {
                        val prior = continuityEditTarget
                        if (prior == null) {
                            campaignRepository.addContinuityItem(
                                chosenCampaign.campaignId, continuityDraftType,
                                continuityDraftTitle, continuityDraftDetails,
                                continuityDraftOpenChapter
                            )
                        } else {
                            campaignRepository.updateContinuityItem(
                                chosenCampaign.campaignId, prior.itemId,
                                continuityDraftTitle, continuityDraftDetails,
                                if (continuityDraftComplete) {
                                    if (prior.itemType == "ARC") "COMPLETE" else "RESOLVED"
                                } else {
                                    if (prior.itemType == "ARC") "ACTIVE" else "OPEN"
                                },
                                continuityDraftResolution, continuityDraftCloseChapter
                            )
                        }
                        continuityEditTarget = null
                        continuityDialogOpen = false
                    }
                }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = {
                continuityDialogOpen = false
                continuityEditTarget = null
            }) { Text("Cancel") }
        }
    )

    deleteContinuityTarget?.let { target ->
        if (campaignRepository != null) AlertDialog(
            onDismissRequest = { if (!busy) deleteContinuityTarget = null },
            title = { Text("Delete Campaign continuity item?") },
            text = {
                Text("This deletes only the player-authored Campaign note. " +
                    "Existing chat messages, variants, chapter recaps and World Canon survive.")
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    submit {
                        campaignRepository.deleteContinuityItem(target.campaignId, target.itemId)
                        deleteContinuityTarget = null
                    }
                }) { Text("Delete note") }
            },
            dismissButton = {
                TextButton(onClick = { deleteContinuityTarget = null }) { Text("Cancel") }
            }
        )
    }

    worldDraftJournalTarget?.let { target ->
        val campaign = campaigns.firstOrNull { it.campaignId == target.campaignId }
        if (campaign != null && campaignRepository != null && selected?.worldId == campaign.worldId) {
            AlertDialog(
                onDismissRequest = { if (!busy) worldDraftJournalTarget = null },
                title = { Text("Propose for shared World Canon?") },
                text = {
                    Column(
                        modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Only the reviewed text below becomes a World DRAFT. " +
                                "It is not accepted canon until you separately choose Accept as Canon. " +
                                "Check carefully for spoilers and private Character information.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedTextField(
                            value = worldDraftReviewedTitle,
                            onValueChange = { worldDraftReviewedTitle = it.take(200) },
                            label = { Text("Reviewed World event title") },
                            modifier = Modifier.testTag("world_draft_from_campaign_title")
                        )
                        OutlinedTextField(
                            value = worldDraftReviewedFacts,
                            onValueChange = { worldDraftReviewedFacts = it.take(12_000) },
                            label = { Text("Facts to propose for World review") },
                            minLines = 3,
                            maxLines = 8,
                            modifier = Modifier.testTag("world_draft_from_campaign_facts")
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = !busy && worldDraftReviewedTitle.isNotBlank() &&
                            worldDraftReviewedFacts.isNotBlank(),
                        modifier = Modifier.testTag("propose_world_draft_from_campaign"),
                        onClick = {
                            submit {
                                campaignRepository.proposeJournalEntryForWorldReview(
                                    target.campaignId,
                                    target.journalEntryId,
                                    worldDraftReviewedTitle,
                                    worldDraftReviewedFacts
                                )
                                worldDraftJournalTarget = null
                                notice = "World continuity draft created. Review it in Continuity " +
                                    "and select Accept as Canon only if you want to share it."
                            }
                        }
                    ) { Text("Create draft") }
                },
                dismissButton = {
                    TextButton(onClick = { worldDraftJournalTarget = null }) { Text("Cancel") }
                }
            )
        }
    }

    confirmPlaintextWorldExport?.let { world ->
        AlertDialog(
            onDismissRequest = { if (!busy) confirmPlaintextWorldExport = null },
            title = { Text("Export unencrypted World Definition?") },
            text = {
                Text(
                    "This JSON includes the World and its linked Character cards, Persona cards, " +
                        "Scenarios, Lorebooks and accepted continuity. Embedded private notes and " +
                        "author notes may be included. It is NOT encrypted. Save it somewhere " +
                        "you trust, or use the encrypted World archive for private transfers. " +
                        "Chat histories and attachments are not included."
                )
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    confirmPlaintextWorldExport = null
                    submit {
                        val json = repository.exportDefinition(world.worldId)
                        pendingExportJson = json
                        exportLauncher.launch(
                            world.title.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".world.json"
                        )
                    }
                }) { Text("Export World JSON") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = {
                    confirmPlaintextWorldExport = null
                }) { Text("Cancel") }
            }
        )
    }

    if (showImportJson) AlertDialog(
        onDismissRequest = { if (!busy) showImportJson = false },
        title = { Text("Import World Definition JSON") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Creates a separate World with linked Character and Persona cards, Scenarios, Lorebooks, cast and accepted continuity. All embedded resources get new IDs; existing cards remain unchanged. Chat history is NOT included.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = importJsonText,
                    onValueChange = { importJsonText = it },
                    label = { Text("Paste Elsewhere World JSON") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 190.dp, max = 330.dp),
                    maxLines = 12,
                    colors = elsewhereTextFieldColors(),
                    enabled = !busy
                )
                Text("Check the source of the JSON before importing. Private fields in embedded cards are stored locally in the imported World.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && importJsonText.isNotBlank(), onClick = {
                submit {
                    val created = repository.importDefinition(importJsonText)
                    selectedId = created.worldId
                    importJsonText = ""
                    showImportJson = false
                    notice = "Imported World: " + created.title
                }
            }, modifier = Modifier.testTag("import_world_definition_confirm")) { Text(if (busy) "Importing…" else "Import") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = { showImportJson = false }) { Text("Cancel") }
        }
    )

    editingWorld?.let { draft ->
        WorldDetailsDialog(
            value = draft,
            isNew = isCreatingWorld,
            busy = busy,
            onChange = { editingWorld = it },
            onDismiss = { if (!busy) editingWorld = null },
            onSave = {
                submit {
                    if (isCreatingWorld) {
                        val created = repository.createWorld(draft.title, draft.settingSummary)
                        repository.updateWorld(created.copy(description = draft.description, worldRules = draft.worldRules))
                        selectedId = created.worldId
                    } else {
                        repository.updateWorld(draft)
                    }
                    editingWorld = null
                }
            }
        )
    }
    editingCast?.let { draft ->
        WorldCastDialog(
            value = draft,
            isNew = cast.none { it.worldCharacterId == draft.worldCharacterId },
            busy = busy,
            onChange = { editingCast = it },
            onDismiss = { if (!busy) editingCast = null },
            onSave = {
                submit {
                    if (cast.any { it.worldCharacterId == draft.worldCharacterId }) {
                        repository.updateCastMember(draft)
                    } else {
                        repository.createCastMember(draft)
                    }
                    editingCast = null
                }
            }
        )
    }
    editingEvent?.let { draft ->
        WorldEventDialog(
            value = draft,
            isNew = events.none { it.eventId == draft.eventId },
            busy = busy,
            onChange = { editingEvent = it },
            onDismiss = { if (!busy) editingEvent = null },
            onSave = {
                submit {
                    if (events.any { it.eventId == draft.eventId }) {
                        repository.updateDraftEvent(draft)
                    } else {
                        repository.draftEvent(draft.worldId, draft.title, draft.facts)
                    }
                    editingEvent = null
                }
            }
        )
    }
    deletingWorld?.let { world ->
        AlertDialog(
            onDismissRequest = { if (!busy) deletingWorld = null },
            title = { Text("Delete World?") },
            text = { Text("Delete " + world.title + " and its World Cast and continuity? Linked chats, Characters, Personas, Scenarios and Lorebooks survive. A World with Campaigns cannot be deleted until its Campaigns are explicitly removed. This cannot be undone.") },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    submit {
                        repository.deleteWorld(world.worldId)
                        selectedId = null
                        deletingWorld = null
                    }
                }) { Text("Delete World", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { deletingWorld = null }) { Text("Cancel") } }
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Worlds") }, colors = elsewhereTopAppBarColors()) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("worlds_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (selected == null) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("A World organizes its setting, recurring cast and continuity independently of any one chat or episode.")
                        Text("World Definition JSON contains setting data without episode history. An encrypted World archive can include linked chats, message history, variants and images.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = {
                            isCreatingWorld = true
                            editingWorld = WorldEntity(title = "")
                        }, modifier = Modifier.testTag("create_world")) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("New World")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { importJsonText = ""; showImportJson = true },
                                modifier = Modifier.testTag("paste_world_json")) { Text("Paste World JSON") }
                            TextButton(onClick = {
                                importFileLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                            }) { Text("Import file") }
                        }
                    }
                }
                if (archiveTransfer != null) item(key = "archive_import") {
                    WorldArchiveControls(archiveTransfer, world = null, onImported = { selectedId = it })
                }
                if (worlds.isEmpty()) item { Text("No Worlds yet. Create your first setting by hand.") }
                items(worlds, key = { it.worldId }) { world ->
                    Card(
                        modifier = Modifier.fillMaxWidth().testTag("world_card_" + world.worldId),
                        onClick = { selectedId = world.worldId },
                        border = elsewhereCardBorder()
                    ) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Public, contentDescription = null)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(world.title, style = MaterialTheme.typography.titleMedium)
                                if (world.settingSummary.isNotBlank()) Text(world.settingSummary, maxLines = 3, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            } else {
                item(key = "world_header") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(onClick = { selectedId = null }) { Text("← All Worlds") }
                        Text(selected.title, style = MaterialTheme.typography.headlineSmall)
                        if (selected.description.isNotBlank()) Text(selected.description)
                        if (selected.settingSummary.isNotBlank()) {
                            Text("Setting", style = MaterialTheme.typography.titleSmall)
                            Text(selected.settingSummary)
                        }
                        if (selected.worldRules.isNotBlank()) {
                            Text("World rules", style = MaterialTheme.typography.titleSmall)
                            Text(selected.worldRules)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { isCreatingWorld = false; editingWorld = selected.copy() }) { Text("Edit World") }
                            TextButton(onClick = { deletingWorld = selected }) { Text("Delete") }
                        }
                        OutlinedButton(enabled = !busy, modifier = Modifier.testTag("export_world_definition"),
                            onClick = { confirmPlaintextWorldExport = selected }
                        ) { Text("Export World Definition JSON") }
                        if (chatRepository != null) {
                            if (onCreateSoloEpisode != null) {
                                Button(
                                    onClick = { onCreateSoloEpisode(selected.worldId) },
                                    modifier = Modifier.testTag("world_new_solo_episode")
                                ) { Text("New solo episode") }
                            }
                            if (onCreateScenarioEpisode != null) {
                                OutlinedButton(
                                    onClick = { onCreateScenarioEpisode(selected.worldId) },
                                    modifier = Modifier.testTag("world_new_scenario_episode")
                                ) { Text("New Scenario episode") }
                            }
                            WorldArchiveChatLinks(
                                repository, chatRepository, selected,
                                onOpenChat = { chatId -> onOpenChat?.invoke(chatId) }
                            )
                        }
                        if (archiveTransfer != null) {
                            WorldArchiveControls(archiveTransfer, selected, onImported = { selectedId = it })
                        }
                        Text("World Definition JSON excludes episode history. Encrypted World archives can include linked chat history and files.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (campaignRepository != null) {
                    item(key = "campaign_header") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            HorizontalDivider()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Campaigns (" + campaigns.size + ")", style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    newCampaignTitle = ""
                                    newCampaignOpen = true
                                }, modifier = Modifier.testTag("create_campaign")) { Text("New") }
                            }
                            Text("Campaigns isolate independent playthroughs. Chapter recaps are private notes; sharing them with another Campaign or World Canon requires explicit future review.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    items(campaigns, key = { "campaign_" + it.campaignId }) { campaign ->
                        Card(modifier = Modifier.fillMaxWidth(), border = elsewhereCardBorder()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(campaign.title, style = MaterialTheme.typography.titleMedium)
                                TextButton(onClick = {
                                    chosenCampaignId = if (chosenCampaignId == campaign.campaignId) null else campaign.campaignId
                                }, modifier = Modifier.testTag("open_campaign_" + campaign.campaignId)) {
                                    Text(if (chosenCampaignId == campaign.campaignId) "Hide chapters" else "View chapters")
                                }
                                if (chosenCampaignId == campaign.campaignId) {
                                    Text("Chapters (" + chapters.size + ")", style = MaterialTheme.typography.titleSmall)
                                    if (onCreateSoloCampaignChapter != null) {
                                        Button(onClick = {
                                            onCreateSoloCampaignChapter(selected.worldId, campaign.campaignId)
                                        }, modifier = Modifier.testTag("start_campaign_solo_chapter")) {
                                            Text("New solo chapter")
                                        }
                                    }
                                    TextButton(onClick = {
                                        newChapterChatId = null
                                        newChapterTitle = ""
                                        newChapterOpen = true
                                    }, modifier = Modifier.testTag("add_campaign_chapter")) { Text("Link an existing chat") }
                                    chapters.forEach { chapter ->
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text("Chapter " + chapter.ordinal + ": " + chapter.title)
                                            if (chapter.recap.isNotBlank()) Text(chapter.recap,
                                                style = MaterialTheme.typography.bodySmall, maxLines = 3)
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                TextButton(onClick = { onOpenChat?.invoke(chapter.chatId) }) { Text("Open chat") }
                                                TextButton(onClick = {
                                                    editingChapter = chapter
                                                    chapterEditTitle = chapter.title
                                                    chapterEditRecap = chapter.recap
                                                    chapterEditComplete = chapter.state == "COMPLETE"
                                                }) { Text("Edit recap") }
                                                TextButton(
                                                    modifier = Modifier.testTag("checkpoint_chapter_" + chapter.chapterId),
                                                    onClick = {
                                                        journalEditTarget = null
                                                        journalDraftType = "CHECKPOINT"
                                                        journalDraftChapterId = chapter.chapterId
                                                        journalDraftTitle = "Chapter " + chapter.ordinal + " checkpoint"
                                                        journalDraftBody = ""
                                                        journalDialogOpen = true
                                                    }
                                                ) { Text("Save checkpoint") }
                                            }
                                        }
                                    }
                                    HorizontalDivider()
                                    Text(
                                        "Arcs & ongoing threads (" + continuityItems.size + ")",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        "Organize long-running arcs, promises, quests, discoveries and relationships " +
                                            "without mandatory game mechanics. Each entry belongs only to this Campaign.",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(
                                            modifier = Modifier.testTag("campaign_new_arc"),
                                            onClick = {
                                                continuityEditTarget = null
                                                continuityDraftType = "ARC"
                                                continuityDraftTitle = ""
                                                continuityDraftDetails = ""
                                                continuityDraftResolution = ""
                                                continuityDraftComplete = false
                                                continuityDraftOpenChapter = null
                                                continuityDraftCloseChapter = null
                                                continuityDialogOpen = true
                                            }
                                        ) { Text("New arc") }
                                        TextButton(
                                            modifier = Modifier.testTag("campaign_new_thread"),
                                            onClick = {
                                                continuityEditTarget = null
                                                continuityDraftType = "THREAD"
                                                continuityDraftTitle = ""
                                                continuityDraftDetails = ""
                                                continuityDraftResolution = ""
                                                continuityDraftComplete = false
                                                continuityDraftOpenChapter = null
                                                continuityDraftCloseChapter = null
                                                continuityDialogOpen = true
                                            }
                                        ) { Text("Track thread") }
                                    }
                                    continuityItems.forEach { item ->
                                        Column(
                                            modifier = Modifier.fillMaxWidth()
                                                .testTag("campaign_continuity_" + item.itemId),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                item.itemType + " • " + item.status + " — " + item.title,
                                                style = MaterialTheme.typography.titleSmall
                                            )
                                            if (item.details.isNotBlank()) Text(
                                                item.details, style = MaterialTheme.typography.bodySmall,
                                                maxLines = 4
                                            )
                                            if (item.resolution.isNotBlank()) Text(
                                                "Ending: " + item.resolution,
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 4
                                            )
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                TextButton(
                                                    modifier = Modifier.testTag("edit_campaign_continuity_" + item.itemId),
                                                    onClick = {
                                                        continuityEditTarget = item
                                                        continuityDraftType = item.itemType
                                                        continuityDraftTitle = item.title
                                                        continuityDraftDetails = item.details
                                                        continuityDraftResolution = item.resolution
                                                        continuityDraftComplete = item.status == "COMPLETE" ||
                                                            item.status == "RESOLVED"
                                                        continuityDraftOpenChapter = item.openedChapterId
                                                        continuityDraftCloseChapter = item.closedChapterId
                                                        continuityDialogOpen = true
                                                    }
                                                ) { Text("Edit / resolve") }
                                                TextButton(onClick = {
                                                    deleteContinuityTarget = item
                                                }) { Text("Delete") }
                                            }
                                        }
                                    }
                                    HorizontalDivider()
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Campaign Journal (" + journalEntries.size + ")",
                                            style = MaterialTheme.typography.titleSmall,
                                            modifier = Modifier.weight(1f))
                                        TextButton(
                                            modifier = Modifier.testTag("add_campaign_journal"),
                                            onClick = {
                                                journalEditTarget = null
                                                journalDraftType = "JOURNAL"
                                                journalDraftChapterId = null
                                                journalDraftTitle = ""
                                                journalDraftBody = ""
                                                journalDialogOpen = true
                                            }
                                        ) { Text("New entry") }
                                    }
                                    Text("Timeline is chronological, with source-linked checkpoints. Notes never become shared World Canon automatically.",
                                        style = MaterialTheme.typography.bodySmall)
                                    journalEntries.forEach { entry ->
                                        Column(
                                            modifier = Modifier.fillMaxWidth()
                                                .testTag("campaign_journal_" + entry.journalEntryId),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                (if (entry.entryType == "CHECKPOINT") "Checkpoint: " else "Journal: ") + entry.title,
                                                style = MaterialTheme.typography.titleSmall
                                            )
                                            if (entry.body.isNotBlank()) {
                                                Text(entry.body, style = MaterialTheme.typography.bodySmall, maxLines = 4)
                                            }
                                            if (entry.entryType == "CHECKPOINT") {
                                                Text(
                                                    "Turn " + (entry.sourceOrderIndex?.toString() ?: "not started") +
                                                        " • " + checkpointStatuses[entry.journalEntryId].orEmpty(),
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                TextButton(onClick = {
                                                    journalEditTarget = entry
                                                    journalDraftType = entry.entryType
                                                    journalDraftChapterId = entry.chapterId
                                                    journalDraftTitle = entry.title
                                                    journalDraftBody = entry.body
                                                    journalDialogOpen = true
                                                }) { Text("Edit") }
                                                TextButton(onClick = { deleteJournalTarget = entry }) {
                                                    Text("Delete")
                                                }
                                                if (entry.entryType == "JOURNAL") {
                                                    TextButton(
                                                        modifier = Modifier.testTag(
                                                            "draft_world_from_journal_" + entry.journalEntryId
                                                        ),
                                                        onClick = {
                                                            worldDraftJournalTarget = entry
                                                            worldDraftReviewedTitle = entry.title
                                                            worldDraftReviewedFacts = entry.body
                                                        }
                                                    ) { Text("Propose to World") }
                                                }
                                            }
                                        }
                                    }
                                    TextButton(onClick = { deletingCampaign = campaign }) { Text("Delete Campaign") }
                                }
                            }
                        }
                    }
                    item(key = "campaign_portability_note") {
                        Text("Until Campaign-aware World archives are implemented, use whole-app encrypted backup to preserve Campaigns. World archives refuse a lossy Campaign export.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item(key = "cast_header") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("World Cast (" + cast.size + ")", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = { editingCast = WorldCastEntity(worldId = selected.worldId, name = "") }) { Text("Add") }
                    }
                }
                if (cast.isEmpty()) item {
                    Text("World Cast members are reusable identities, not yet AI bots or Personas.", style = MaterialTheme.typography.bodySmall)
                }
                items(cast, key = { it.worldCharacterId }) { member ->
                    Card(border = elsewhereCardBorder(), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(member.name, style = MaterialTheme.typography.titleMedium)
                            if (member.role.isNotBlank()) Text(member.role, style = MaterialTheme.typography.bodySmall)
                            if (member.identity.isNotBlank()) Text(member.identity, maxLines = 4)
                            TextButton(onClick = { editingCast = member.copy() }) { Text("Edit profile") }
                        }
                    }
                }
                item(key = "event_header") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Continuity (" + events.size + ")", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            editingEvent = WorldContinuityEventEntity(worldId = selected.worldId, title = "", facts = "")
                        }) { Text("Draft event") }
                    }
                }
                item { Text("Continuity is reviewed manually. Drafts do not become shared Canon until accepted.", style = MaterialTheme.typography.bodySmall) }
                items(events, key = { it.eventId }) { event ->
                    Card(modifier = Modifier.fillMaxWidth(), border = elsewhereCardBorder()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(event.title, style = MaterialTheme.typography.titleMedium)
                            Text(event.facts)
                            Text(event.status, style = MaterialTheme.typography.labelSmall)
                            if (event.status == "DRAFT") {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { editingEvent = event.copy() }) { Text("Edit") }
                                    TextButton(enabled = !busy, onClick = {
                                        submit { repository.acceptEvent(selected.worldId, event.eventId) }
                                    }) { Text("Accept as Canon") }
                                }
                            }
                        }
                    }
                }
                item {
                    HorizontalDivider()
                    Text("World links, Campaign chapters and approved continuity have separate ownership. Campaign recaps remain private notes and do not automatically enter the model's prompt or shared World Canon.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            notice?.let { message ->
                item { Text(message, color = MaterialTheme.colorScheme.primary) }
            }
            error?.let { message ->
                item { Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("worlds_error")) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
internal fun WorldDetailsDialog(
    value: WorldEntity, isNew: Boolean, busy: Boolean, onChange: (WorldEntity) -> Unit,
    onDismiss: () -> Unit, onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Create World" else "Edit World") },
        text = {
            Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                WorldField("World title", value.title, true) { onChange(value.copy(title = it)) }
                WorldField("Description", value.description) { onChange(value.copy(description = it)) }
                WorldField("Setting and overview", value.settingSummary) { onChange(value.copy(settingSummary = it)) }
                WorldField("World rules", value.worldRules) { onChange(value.copy(worldRules = it)) }
                Text("Worlds are local records. These edits do not change existing chats.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onSave, enabled = !busy && value.title.isNotBlank() && value.title.length <= 300,
            modifier = Modifier.testTag("save_world")) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } }
    )
}

@Composable
internal fun WorldCastDialog(
    value: WorldCastEntity, isNew: Boolean, busy: Boolean, onChange: (WorldCastEntity) -> Unit,
    onDismiss: () -> Unit, onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New World Character" else "Edit World Character") },
        text = {
            Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                WorldField("Name", value.name, true) { onChange(value.copy(name = it)) }
                WorldField("Role", value.role) { onChange(value.copy(role = it)) }
                WorldField("Identity", value.identity) { onChange(value.copy(identity = it)) }
                WorldField("Appearance", value.appearance) { onChange(value.copy(appearance = it)) }
                WorldField("Personality", value.personality) { onChange(value.copy(personality = it)) }
                WorldField("Voice", value.voice) { onChange(value.copy(voice = it)) }
                WorldField("Background", value.backstory) { onChange(value.copy(backstory = it)) }
                WorldField("Motivations", value.motivations) { onChange(value.copy(motivations = it)) }
                WorldField("Knowledge limits", value.knowledgeLimits) { onChange(value.copy(knowledgeLimits = it)) }
                Text("A World character is not automatically a Character bot or player Persona.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onSave, enabled = !busy && value.name.isNotBlank() && value.name.length <= 300,
            modifier = Modifier.testTag("save_world_cast")) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } }
    )
}

@Composable
internal fun WorldEventDialog(
    value: WorldContinuityEventEntity, isNew: Boolean, busy: Boolean, onChange: (WorldContinuityEventEntity) -> Unit,
    onDismiss: () -> Unit, onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Draft Continuity Event" else "Edit Draft Event") },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                WorldField("Event title", value.title, true) { onChange(value.copy(title = it)) }
                WorldField("What happened", value.facts) { onChange(value.copy(facts = it)) }
                Text("Save as draft. Accept as Canon separately after reviewing.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onSave, enabled = !busy && value.title.isNotBlank() && value.facts.isNotBlank(),
            modifier = Modifier.testTag("save_world_event")) { Text("Save draft") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } }
    )
}

@Composable
private fun WorldField(label: String, value: String, single: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
        singleLine = single, maxLines = if (single) 1 else 8, colors = elsewhereTextFieldColors())
}
