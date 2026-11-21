package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.model.StoryMemoryEntity
import com.example.data.model.StoryMemoryKnowledgeChangeEntity
import com.example.data.model.StoryMemoryPerspectiveEntity
import com.example.data.model.CharacterEntity
import com.example.domain.memory.StoryActorKind
import com.example.domain.memory.StoryActorRef
import com.example.domain.repository.StoryRelationshipEntry
import com.example.domain.repository.StoryPromiseEntry
import com.example.domain.repository.StoryPromiseOutcome
import com.example.domain.repository.StoryCandidateSourcePreview
import com.example.domain.memory.StoryCharacterPerspective
import com.example.domain.memory.StoryMemoryClaim
import com.example.domain.memory.StoryMemoryEligibilityPolicy
import com.example.domain.memory.StoryMemoryLifecycle
import com.example.domain.memory.StoryMemoryReview
import com.example.domain.memory.StoryMemoryExclusion
import com.example.domain.repository.ChatRepository
import com.example.domain.repository.StoryMemoryRepository
import com.example.ui.components.elsewhereTopAppBarColors
import kotlinx.coroutines.launch

/**
 * Chat-local, user-controlled story archive. Retrieval is recipient-scoped,
 * source-linked and bounded, never an additional paid model request.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryMemoriesScreen(
    chatId: String,
    repository: StoryMemoryRepository,
    chatRepository: ChatRepository,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var memories by remember(chatId) { mutableStateOf<List<StoryMemoryEntity>>(emptyList()) }
    var loading by remember(chatId) { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    var boundary by remember(chatId) { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var createDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<StoryMemoryEntity?>(null) }
    var removing by remember { mutableStateOf<StoryMemoryEntity?>(null) }
    var reviewingCandidate by remember { mutableStateOf<StoryMemoryEntity?>(null) }
    var candidatePreview by remember { mutableStateOf<StoryCandidateSourcePreview?>(null) }
    var scanFeedback by remember(chatId) { mutableStateOf<String?>(null) }
    var restoring by remember { mutableStateOf<StoryMemoryEntity?>(null) }
    var grantingKnowledge by remember { mutableStateOf<StoryMemoryEntity?>(null) }
    var selectedKnowers by remember { mutableStateOf<Set<String>>(emptySet()) }
    var perspectiveMemory by remember { mutableStateOf<StoryMemoryEntity?>(null) }
    var perspectiveCharacterId by remember { mutableStateOf<String?>(null) }
    var perspectiveStance by remember { mutableStateOf(StoryCharacterPerspective.KNOWS) }
    var perspectiveNote by remember { mutableStateOf("") }
    var perspectiveCurrent by remember { mutableStateOf<List<StoryMemoryPerspectiveEntity>>(emptyList()) }
    var perspectiveHistory by remember { mutableStateOf<List<StoryMemoryPerspectiveEntity>>(emptyList()) }
    var knowledgeHistory by remember(chatId) {
        mutableStateOf<List<StoryMemoryKnowledgeChangeEntity>>(emptyList())
    }
    var characters by remember(chatId) { mutableStateOf<List<CharacterEntity>>(emptyList()) }
    var knowers by remember(chatId) { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
    var relationshipEntries by remember(chatId) {
        mutableStateOf<List<StoryRelationshipEntry>>(emptyList())
    }
    var relationshipDialogOpen by remember { mutableStateOf(false) }
    var promiseDialogOpen by remember { mutableStateOf(false) }
    var continuingRelationship by remember { mutableStateOf<StoryRelationshipEntry?>(null) }
    var closingPromise by remember { mutableStateOf<StoryPromiseEntry?>(null) }
    var promiseEntries by remember(chatId) { mutableStateOf<List<StoryPromiseEntry>>(emptyList()) }
    var participantChoices by remember(chatId) {
        mutableStateOf<List<Pair<StoryActorRef, String>>>(emptyList())
    }

    LaunchedEffect(chatId) {
        val summary = chatRepository.getChatSummaryById(chatId)
        boundary = summary?.chat?.contextResetOrderIndex
        characters = summary?.characters.orEmpty()
        participantChoices =
            summary?.personas.orEmpty().map {
                StoryActorRef(StoryActorKind.PERSONA, it.personaId) to
                    "${it.displayName} (Persona)"
            } + summary?.characters.orEmpty().map {
                StoryActorRef(StoryActorKind.CHARACTER, it.characterId) to
                    "${it.displayName} (Character)"
            }
    }
    LaunchedEffect(chatId, repository) {
        try {
            // Load a one-shot Room snapshot before the reactive collector.
            // On some Android/Robolectric schedulers the initial invalidation
            // emission may arrive late even though the query is ready. Keeping
            // the UI behind that emission leaves the player on a spinner.
            val initial = repository.listForChat(chatId)
            memories = initial
            loading = false
            repository.observe(chatId).collect { current ->
                memories = current
                // The list must remain usable even if supplemental history
                // and knowledge queries take longer than the primary read.
                loading = false
                relationshipEntries = repository.getRelationshipHistory(chatId)
                promiseEntries = repository.getPromiseHistory(chatId)
                knowers = current.associate {
                    it.memoryId to repository.getCharacterKnowers(chatId, it.memoryId)
                }
            }
        } catch (failure: Exception) {
            error = failure.message ?: "Unable to load saved story memories."
            loading = false
        }
    }
    fun applyChange(action: suspend () -> Unit) {
        if (working) return
        scope.launch {
            working = true
            try { action() }
            catch (failure: Exception) { error = failure.message ?: "Unable to update story memory." }
            finally { working = false }
        }
    }

    var draft by remember(createDialog, editing?.memoryId) {
        mutableStateOf(editing?.content ?: "")
    }
    var claim by remember(createDialog) { mutableStateOf(StoryMemoryClaim.FACT) }
    var selectingClaim by remember { mutableStateOf(false) }
    val linkedRelationships = relationshipEntries.associateBy { it.memory.memoryId }
    val linkedPromises = promiseEntries.associateBy { it.memory.memoryId }
    fun actorLabel(actor: StoryActorRef): String =
        participantChoices.firstOrNull { it.first == actor }?.second
            ?: "${actor.kind.name.lowercase()}: ${actor.stableId}"

    if (createDialog || editing != null) AlertDialog(
        onDismissRequest = { if (!working) { createDialog = false; editing = null } },
        title = { Text(if (editing == null) "Save Story Memory" else "Edit Story Memory") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Only this chat stores this record. Knowledge must be assigned explicitly before a Character may recall it. No extra model call is made here.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    draft, { draft = it.take(12_000) },
                    label = { Text("What should Elsewhere remember?") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp),
                    minLines = 3,
                    supportingText = { Text("${draft.length} / 12,000") },
                    enabled = !working
                )
                if (editing == null) Box {
                    OutlinedButton(onClick = { selectingClaim = true }, enabled = !working) {
                        Text("Type: ${claim.name.lowercase().replace('_', ' ')}")
                    }
                    DropdownMenu(selectingClaim, onDismissRequest = { selectingClaim = false }) {
                        StoryMemoryClaim.entries
                            .filter { it != StoryMemoryClaim.RELATIONSHIP }
                            .forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category.name.lowercase().replace('_', ' ')) },
                                onClick = { claim = category; selectingClaim = false }
                            )
                        }
                    }
                }
                Text("For evolving relationships, use Record relationship change to link both people and retain earlier states.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Knowledge: Storyteller only by default. Use Who knows this? after saving to grant it.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                enabled = draft.isNotBlank() && !working,
                onClick = {
                    val target = editing
                    applyChange {
                        if (target == null) repository.saveManual(chatId, draft, claim)
                        else repository.update(chatId, target.memoryId, text = draft)
                        createDialog = false
                        editing = null
                    }
                }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = { createDialog = false; editing = null }, enabled = !working) {
                Text("Cancel")
            }
        }
    )

    if (relationshipDialogOpen || promiseDialogOpen) {
        val creatingPromise = promiseDialogOpen
        var relationshipText by remember(relationshipDialogOpen, promiseDialogOpen, continuingRelationship?.memory?.memoryId) {
            mutableStateOf("")
        }
        var fromActor by remember(relationshipDialogOpen, promiseDialogOpen, continuingRelationship?.memory?.memoryId) {
            mutableStateOf(continuingRelationship?.from ?: participantChoices.firstOrNull()?.first)
        }
        var toActor by remember(relationshipDialogOpen, promiseDialogOpen, continuingRelationship?.memory?.memoryId) {
            mutableStateOf(continuingRelationship?.to ?:
                participantChoices.firstOrNull { it.first != fromActor }?.first)
        }
        var fromExpanded by remember { mutableStateOf(false) }
        var toExpanded by remember { mutableStateOf(false) }
        val prior = continuingRelationship
        AlertDialog(
            modifier = Modifier.testTag("relationship_state_dialog"),
            onDismissRequest = {
                if (!working) {
                    relationshipDialogOpen = false
                    promiseDialogOpen = false
                    continuingRelationship = null
                }
            },
            title = { Text(when {
                creatingPromise -> "Record a promise"
                prior == null -> "Record relationship change"
                else -> "Record a new relationship state"
            }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (creatingPromise)
                            "Record who made this promise and to whom. An outcome can be added later; only the storyteller knows it unless you explicitly grant knowledge."
                        else
                            "Record a player-approved change for these two people. Earlier states remain in history. The new state is storyteller-only until you explicitly assign knowledge.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Box {
                        OutlinedButton(
                            onClick = { fromExpanded = true },
                            enabled = !working && prior == null
                        ) { Text("From: " + (fromActor?.let(::actorLabel) ?: "Select person")) }
                        DropdownMenu(
                            expanded = fromExpanded,
                            onDismissRequest = { fromExpanded = false }
                        ) {
                            participantChoices.forEach { (actor, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        fromActor = actor
                                        if (toActor == actor) toActor =
                                            participantChoices.firstOrNull { it.first != actor }?.first
                                        fromExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Box {
                        OutlinedButton(
                            onClick = { toExpanded = true },
                            enabled = !working && prior == null
                        ) { Text("To: " + (toActor?.let(::actorLabel) ?: "Select person")) }
                        DropdownMenu(
                            expanded = toExpanded,
                            onDismissRequest = { toExpanded = false }
                        ) {
                            participantChoices.forEach { (actor, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = { toActor = actor; toExpanded = false }
                                )
                            }
                        }
                    }
                    if (prior != null) {
                        Text("Earlier state (kept in history): ${prior.memory.content}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedTextField(
                        modifier = Modifier.testTag("relationship_state_input"),
                        value = relationshipText,
                        onValueChange = { relationshipText = it.take(12_000) },
                        label = { Text(if (creatingPromise) "What was promised?" else "How has this relationship changed?") },
                        minLines = 3,
                        supportingText = { Text("${relationshipText.length} / 12,000") },
                        enabled = !working
                    )
                    if (participantChoices.size < 2) Text(
                        "Add two Character/Persona participants to this chat first.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !working && relationshipText.isNotBlank() &&
                        fromActor != null && toActor != null && fromActor != toActor,
                    onClick = {
                        val from = fromActor ?: return@TextButton
                        val to = toActor ?: return@TextButton
                        val description = relationshipText
                        val previousId = prior?.memory?.memoryId
                        applyChange {
                            if (creatingPromise) repository.savePromise(chatId, from, to, description)
                            else repository.saveRelationshipChange(
                                chatId, from, to, description, previousId
                            )
                            relationshipDialogOpen = false
                            promiseDialogOpen = false
                            continuingRelationship = null
                        }
                    }
                ) { Text(if (creatingPromise) "Save promise" else "Save relationship state") }
            },
            dismissButton = {
                TextButton(
                    enabled = !working,
                    onClick = {
                        relationshipDialogOpen = false
                        promiseDialogOpen = false
                        continuingRelationship = null
                    }
                ) { Text("Cancel") }
            }
        )
    }

    closingPromise?.let { entry ->
        var outcome by remember(entry.memory.memoryId) { mutableStateOf(StoryPromiseOutcome.KEPT) }
        var outcomeExpanded by remember(entry.memory.memoryId) { mutableStateOf(false) }
        var outcomeDetails by remember(entry.memory.memoryId) { mutableStateOf("") }
        AlertDialog(
            modifier = Modifier.testTag("record_promise_outcome_dialog"),
            onDismissRequest = { if (!working) closingPromise = null },
            title = { Text("Record promise outcome") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Promise by ${actorLabel(entry.from)} to ${actorLabel(entry.to)}:",
                        style = MaterialTheme.typography.bodySmall)
                    Text(entry.memory.content, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "This preserves the original in history and records your new outcome separately. Include enough detail for future recall; knowledge isn't granted automatically.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Box {
                        OutlinedButton(onClick = { outcomeExpanded = true }, enabled = !working) {
                            Text("Outcome: ${outcome.name.lowercase()}")
                        }
                        DropdownMenu(
                            expanded = outcomeExpanded,
                            onDismissRequest = { outcomeExpanded = false }
                        ) {
                            StoryPromiseOutcome.entries.forEach { choice ->
                                DropdownMenuItem(
                                    text = { Text(choice.name.lowercase()) },
                                    onClick = { outcome = choice; outcomeExpanded = false }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = outcomeDetails,
                        onValueChange = { outcomeDetails = it.take(4_000) },
                        label = { Text("What happened?") },
                        minLines = 3,
                        supportingText = { Text("${outcomeDetails.length} / 4,000") },
                        enabled = !working
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !working && outcomeDetails.isNotBlank(),
                    onClick = {
                        val current = closingPromise ?: return@TextButton
                        val description = outcomeDetails
                        val decision = outcome
                        applyChange {
                            repository.closePromise(chatId, current.memory.memoryId, decision, description)
                            closingPromise = null
                        }
                    }
                ) { Text("Save outcome") }
            },
            dismissButton = {
                TextButton(enabled = !working, onClick = { closingPromise = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    var reviewDraft by remember(reviewingCandidate?.memoryId) {
        mutableStateOf(reviewingCandidate?.content.orEmpty())
    }
    var reviewClaim by remember(reviewingCandidate?.memoryId) {
        mutableStateOf(runCatching {
            StoryMemoryClaim.valueOf(reviewingCandidate?.claim.orEmpty())
        }.getOrDefault(StoryMemoryClaim.EVENT))
    }
    var reviewingClaimOptions by remember { mutableStateOf(false) }
    LaunchedEffect(chatId, reviewingCandidate?.memoryId) {
        val memoryId = reviewingCandidate?.memoryId
        candidatePreview = null
        if (memoryId != null) {
            try {
                candidatePreview = repository.previewCandidateSource(chatId, memoryId)
            } catch (failure: Exception) {
                error = failure.message ?: "Could not inspect the candidate source."
            }
        }
    }
    reviewingCandidate?.let { candidate ->
        val canApprove = candidatePreview?.status == StoryMemoryExclusion.ELIGIBLE
        val allowedClaims = listOf(
            StoryMemoryClaim.FACT, StoryMemoryClaim.EVENT,
            StoryMemoryClaim.PROMISE, StoryMemoryClaim.DISCOVERY,
            StoryMemoryClaim.RUMOR, StoryMemoryClaim.SECRET,
            StoryMemoryClaim.UNRESOLVED_THREAD
        )
        AlertDialog(
            modifier = Modifier.testTag("story_candidate_review_dialog"),
            onDismissRequest = { if (!working) reviewingCandidate = null },
            title = { Text("Review proposed memory") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "This is a suggestion from a selected chat message, NOT an established " +
                            "fact. Nothing enters model memory without your approval. " +
                            "Review the original and correct the wording or category first.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Source: " + (candidatePreview?.speakerName ?: "Checking source…") +
                            " • turn ${candidate.sourceOrderIndex}",
                        style = MaterialTheme.typography.labelMedium
                    )
                    if (candidatePreview == null) {
                        CircularProgressIndicator()
                    } else {
                        if (!canApprove) Text(
                            "Source no longer eligible: ${candidatePreview?.status}. " +
                                "The message may have changed, been deleted, switched variants, " +
                                "or crossed a Wack boundary. You can reject it, not approve it.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            candidatePreview?.visibleSource ?: "Original source is unavailable.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    OutlinedTextField(
                        value = reviewDraft,
                        onValueChange = { reviewDraft = it.take(12_000) },
                        label = { Text("Approved wording") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        enabled = !working && canApprove
                    )
                    Box {
                        OutlinedButton(
                            onClick = { reviewingClaimOptions = true },
                            enabled = !working && canApprove
                        ) { Text("Category: ${reviewClaim.name.lowercase().replace('_', ' ')}") }
                        DropdownMenu(
                            expanded = reviewingClaimOptions,
                            onDismissRequest = { reviewingClaimOptions = false }
                        ) {
                            allowedClaims.forEach { claim ->
                                DropdownMenuItem(
                                    text = { Text(claim.name.lowercase().replace('_', ' ')) },
                                    onClick = {
                                        reviewClaim = claim
                                        reviewingClaimOptions = false
                                    }
                                )
                            }
                        }
                    }
                    Text(
                        "Approval is storyteller-only. No Character automatically learns this, " +
                            "and your existing World or Character cards aren't edited.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !working && canApprove && reviewDraft.isNotBlank(),
                    modifier = Modifier.testTag("approve_story_candidate"),
                    onClick = {
                        val content = reviewDraft
                        val claim = reviewClaim
                        applyChange {
                            repository.reviewCandidate(
                                chatId, candidate.memoryId, approve = true,
                                editedText = content, category = claim
                            )
                            reviewingCandidate = null
                        }
                    }
                ) { Text("Approve memory") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        modifier = Modifier.testTag("reject_story_candidate"),
                        enabled = !working,
                        onClick = {
                            applyChange {
                                repository.reviewCandidate(
                                    chatId, candidate.memoryId, approve = false
                                )
                                reviewingCandidate = null
                            }
                        }
                    ) { Text("Reject") }
                    TextButton(enabled = !working, onClick = {
                        reviewingCandidate = null
                    }) { Text("Later") }
                }
            }
        )
    }

    LaunchedEffect(chatId, perspectiveMemory?.memoryId) {
        val memoryId = perspectiveMemory?.memoryId
        perspectiveCurrent = if (memoryId == null) emptyList() else try {
            repository.getCurrentCharacterPerspectives(chatId, memoryId)
        } catch (failure: Exception) {
            error = failure.message ?: "Unable to load character perspectives."
            emptyList()
        }
        val current = perspectiveCurrent.firstOrNull {
            it.characterId == perspectiveCharacterId
        }
        perspectiveStance = current?.stance?.let {
            runCatching { StoryCharacterPerspective.valueOf(it) }.getOrNull()
        } ?: StoryCharacterPerspective.KNOWS
        perspectiveNote = current?.note.orEmpty()
    }

    LaunchedEffect(chatId, perspectiveMemory?.memoryId, perspectiveCharacterId) {
        val id = perspectiveMemory?.memoryId
        val characterId = perspectiveCharacterId
        perspectiveHistory = if (id == null || characterId == null) emptyList() else try {
            repository.getCharacterPerspectiveHistory(chatId, id, characterId)
        } catch (failure: Exception) {
            error = failure.message ?: "Unable to load perspective history."
            emptyList()
        }
    }

    perspectiveMemory?.let { memory ->
        val memoryFingerprint = remember(memory.memoryId, memory.content) {
            StoryMemoryEligibilityPolicy.sha256(memory.content)
        }
        var choosingCharacter by remember(memory.memoryId) { mutableStateOf(false) }
        var choosingStance by remember(memory.memoryId) { mutableStateOf(false) }
        AlertDialog(
            modifier = Modifier.testTag("story_memory_perspective_dialog"),
            onDismissRequest = { if (!working) perspectiveMemory = null },
            title = { Text("Character perspectives") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 390.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(memory.content, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "What does this Character currently think about this memory? " +
                            "A belief or suspicion is not established truth. Recording a perspective " +
                            "does not grant access: use Who knows this? separately. " +
                            "Unaware blocks recall even if an old grant remains. " +
                            "No model call is made here.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Box {
                        OutlinedButton(
                            onClick = { choosingCharacter = true },
                            enabled = !working && characters.isNotEmpty()
                        ) {
                            val chosen = characters.firstOrNull {
                                it.characterId == perspectiveCharacterId
                            }
                            Text("Character: ${chosen?.displayName ?: "Select"}")
                        }
                        DropdownMenu(
                            expanded = choosingCharacter,
                            onDismissRequest = { choosingCharacter = false }
                        ) {
                            characters.forEach { character ->
                                DropdownMenuItem(
                                    text = { Text(character.displayName) },
                                    onClick = {
                                        perspectiveCharacterId = character.characterId
                                        val current = perspectiveCurrent.firstOrNull {
                                            it.characterId == character.characterId
                                        }
                                        perspectiveStance = current?.stance?.let {
                                            runCatching { StoryCharacterPerspective.valueOf(it) }.getOrNull()
                                        } ?: StoryCharacterPerspective.KNOWS
                                        perspectiveNote = current?.note.orEmpty()
                                        choosingCharacter = false
                                    }
                                )
                            }
                        }
                    }
                    Box {
                        OutlinedButton(
                            onClick = { choosingStance = true },
                            enabled = !working && perspectiveCharacterId != null
                        ) { Text("State: ${perspectiveStance.name.lowercase()}") }
                        DropdownMenu(
                            expanded = choosingStance,
                            onDismissRequest = { choosingStance = false }
                        ) {
                            StoryCharacterPerspective.entries.forEach { choice ->
                                DropdownMenuItem(
                                    text = { Text(choice.name.lowercase()) },
                                    onClick = {
                                        perspectiveStance = choice
                                        choosingStance = false
                                    }
                                )
                            }
                        }
                    }
                    val currentPerspective = perspectiveCurrent.firstOrNull {
                        it.characterId == perspectiveCharacterId
                    }
                    if (currentPerspective != null &&
                        currentPerspective.contentSha256 != memoryFingerprint
                    ) {
                        Text(
                            "This memory has been edited since the last perspective was saved. " +
                                "The old perspective is excluded from recall until you confirm it again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    OutlinedTextField(
                        value = perspectiveNote,
                        onValueChange = { perspectiveNote = it.take(2_000) },
                        label = { Text("Player note (optional, never sent to model)") },
                        enabled = !working && perspectiveCharacterId != null,
                        minLines = 2,
                        supportingText = { Text("${perspectiveNote.length} / 2,000") }
                    )
                    if (perspectiveHistory.isNotEmpty()) {
                        HorizontalDivider()
                        Text("Recorded history for this Character:", style = MaterialTheme.typography.labelMedium)
                        perspectiveHistory.takeLast(6).forEach { event ->
                            Text(
                                "Revision ${event.revision}: ${event.stance.lowercase()}" +
                                    (event.note?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !working && perspectiveCharacterId != null,
                    onClick = {
                        val characterId = perspectiveCharacterId ?: return@TextButton
                        val note = perspectiveNote
                        val stance = perspectiveStance
                        applyChange {
                            repository.recordCharacterPerspective(
                                chatId, memory.memoryId, characterId, stance, note
                            )
                            perspectiveMemory = null
                        }
                    }
                ) { Text("Save perspective") }
            },
            dismissButton = {
                TextButton(enabled = !working, onClick = { perspectiveMemory = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    LaunchedEffect(chatId, grantingKnowledge?.memoryId) {
        val id = grantingKnowledge?.memoryId
        knowledgeHistory = if (id == null) emptyList() else try {
            repository.getCharacterKnowledgeHistory(chatId, id)
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }
    grantingKnowledge?.let { memory ->
        AlertDialog(
            onDismissRequest = { if (!working) grantingKnowledge = null },
            title = { Text("Who knows this?") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Only explicitly selected Characters may recall this as their own knowledge. Leave everyone unchecked for storyteller-only continuity.",
                        style = MaterialTheme.typography.bodySmall)
                    if (characters.isEmpty()) Text("There are no Character participants in this chat.")
                    characters.forEach { character ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                modifier = Modifier.testTag("memory_knowledge_${character.characterId}"),
                                checked = character.characterId in selectedKnowers,
                                onCheckedChange = { checked ->
                                    selectedKnowers = if (checked) selectedKnowers + character.characterId
                                        else selectedKnowers - character.characterId
                                },
                                enabled = !working && (memory.claim != "BELIEF" || selectedKnowers.isEmpty() ||
                                    character.characterId in selectedKnowers) &&
                                    memory.claim != "CONTRADICTION"
                            )
                            Text(character.displayName)
                        }
                    }
                    if (memory.claim == "CONTRADICTION") Text(
                        "Contradictions must remain storyteller-only.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (knowledgeHistory.isNotEmpty()) {
                        HorizontalDivider()
                        Text(
                            "Previous player assignments (not proof that a character learned or forgot something in the story):",
                            style = MaterialTheme.typography.bodySmall
                        )
                        knowledgeHistory.takeLast(6).forEach { entry ->
                            val character = characters.firstOrNull {
                                it.characterId == entry.characterId
                            }?.displayName ?: entry.characterId
                            Text(
                                "$character: " +
                                    (if (entry.change == "GRANTED") "access granted" else "access revoked") +
                                    " • revision ${entry.revision}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !working &&
                        (memory.claim != "BELIEF" || selectedKnowers.size <= 1) &&
                        (memory.claim != "CONTRADICTION" || selectedKnowers.isEmpty()),
                    onClick = {
                        val chosen = selectedKnowers
                        applyChange {
                            repository.setCharacterKnowledge(chatId, memory.memoryId, chosen)
                            grantingKnowledge = null
                        }
                    }
                ) { Text("Save knowledge") }
            },
            dismissButton = {
                TextButton(onClick = { grantingKnowledge = null }, enabled = !working) {
                    Text("Cancel")
                }
            }
        )
    }
    removing?.let { memory ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Delete this Story Memory?") },
            text = { Text("This permanently deletes this saved record and any explicit Wack restoration grants. The original chat messages remain untouched.") },
            confirmButton = {
                TextButton(enabled = !working, onClick = {
                    applyChange {
                        repository.delete(chatId, memory.memoryId)
                        removing = null
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } }
        )
    }
    restoring?.let { memory ->
        AlertDialog(
            modifier = Modifier.testTag("story_memory_restore_dialog"),
            onDismissRequest = { restoring = null },
            title = { Text("Restore this memory after Wack?") },
            text = {
                Text("This explicitly permits this one memory to be considered for future recall after the current Wack boundary. It does NOT restore the old transcript, other memories, or discarded variants. No model request is made.")
            },
            confirmButton = {
                TextButton(enabled = !working, onClick = {
                    applyChange {
                        repository.restoreAcrossWack(chatId, memory.memoryId)
                        restoring = null
                    }
                }) { Text("Restore this one memory") }
            },
            dismissButton = { TextButton(onClick = { restoring = null }) { Text("Cancel") } }
        )
    }
    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("Story Memory") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } }
        )
    }

    Scaffold(
        modifier = modifier.testTag("story_memories_screen"),
        topBar = {
            TopAppBar(
                title = { Text("Story Memories") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = elsewhereTopAppBarColors()
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { createDialog = true }, modifier = Modifier.testTag("story_memory_add")) {
                Icon(Icons.Filled.Bookmarks, contentDescription = "Save a new Story Memory")
            }
        }
    ) { padding ->
        when {
            loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).testTag("story_memories_list"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Your chat-local story archive", style = MaterialTheme.typography.titleMedium)
                        Text("Create, correct, pin and assign knowledge here. Eligible memories can be recalled during generation, without any separate OpenRouter call. This does not change legacy 'Update Memory Now'.",
                            style = MaterialTheme.typography.bodySmall)
                        if (boundary != null) Text(
                            "This chat has a Wack boundary. Old memories require individual Restore approval.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
                        )
                        if (memories.isEmpty()) Text("No Story Memories saved in this chat yet.")
                        OutlinedButton(
                            modifier = Modifier.testTag("scan_story_candidates"),
                            enabled = !working,
                            onClick = {
                                applyChange {
                                    val scan = repository.scanRecentCandidateMessages(chatId)
                                    scanFeedback = "Checked ${scan.scannedMessages} recent selected " +
                                        "messages; added ${scan.newCandidates} review candidates. " +
                                        "No provider call."
                                }
                            }
                        ) { Text("Suggest memories from recent messages") }
                        scanFeedback?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                        val pendingCount = memories.count {
                            it.review == StoryMemoryReview.PENDING_REVIEW.name
                        }
                        if (pendingCount > 0) Text(
                            "$pendingCount proposed memories need your review.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedButton(onClick = { createDialog = true }, enabled = !working) {
                            Text("Save new Story Memory")
                        }
                        OutlinedButton(
                            onClick = {
                                continuingRelationship = null
                                relationshipDialogOpen = true
                            },
                            enabled = !working && participantChoices.size >= 2,
                            modifier = Modifier.testTag("record_relationship_change")
                        ) { Text("Record relationship change") }
                        OutlinedButton(
                            onClick = { promiseDialogOpen = true },
                            enabled = !working && participantChoices.size >= 2,
                            modifier = Modifier.testTag("record_story_promise")
                        ) { Text("Record promise") }
                    }
                }
                items(memories, key = { it.memoryId }) { memory ->
                    Card(Modifier.fillMaxWidth().testTag("story_memory_${memory.memoryId}")) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(memory.content, style = MaterialTheme.typography.bodyLarge)
                            if (memory.review == StoryMemoryReview.PENDING_REVIEW.name) {
                                Text(
                                    "Proposed from message #${memory.sourceOrderIndex} " +
                                        "— NOT approved or used in generation",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                OutlinedButton(
                                    modifier = Modifier.testTag("review_story_candidate_${memory.memoryId}"),
                                    enabled = !working,
                                    onClick = { reviewingCandidate = memory }
                                ) { Text("Review source and approve/reject") }
                            } else if (memory.review == StoryMemoryReview.REJECTED.name) {
                                Text(
                                    "Rejected suggestion — never used for recall",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            linkedPromises[memory.memoryId]?.let { entry ->
                                Text(
                                    "Promise: ${actorLabel(entry.from)} → ${actorLabel(entry.to)}",
                                    style = MaterialTheme.typography.labelMedium
                                )
                                if (memory.lifecycle == StoryMemoryLifecycle.ACTIVE.name) {
                                    OutlinedButton(
                                        enabled = !working,
                                        onClick = { closingPromise = entry },
                                        modifier = Modifier.testTag("close_promise_${memory.memoryId}")
                                    ) { Text("Record outcome") }
                                } else if (memory.promiseOutcome != null) {
                                    Text(
                                        "Outcome: ${memory.promiseOutcome.lowercase()}",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                            linkedRelationships[memory.memoryId]?.let { entry ->
                                Text(
                                    "Relationship: ${actorLabel(entry.from)} → ${actorLabel(entry.to)}",
                                    style = MaterialTheme.typography.labelMedium
                                )
                                if (memory.lifecycle == StoryMemoryLifecycle.ACTIVE.name) {
                                    OutlinedButton(
                                        enabled = !working,
                                        onClick = {
                                            continuingRelationship = entry
                                            relationshipDialogOpen = true
                                        },
                                        modifier = Modifier.testTag("relationship_change_${memory.memoryId}")
                                    ) { Text("Record a change") }
                                }
                            }
                            Text(
                                "${memory.claim.lowercase().replace('_', ' ')} • " +
                                    "${memory.lifecycle.lowercase()} • " +
                                    (if (memory.visibility == "CHARACTER_ONLY") {
                                        "known to " + characters.filter { it.characterId in knowers[memory.memoryId].orEmpty() }
                                            .joinToString(", ") { it.displayName }.ifBlank { "no Characters" }
                                    } else "storyteller only") + " • " +
                                    (if (memory.originKind == "MESSAGE") "from selected message" else "manually saved"),
                                style = MaterialTheme.typography.labelSmall
                            )
                            if (characters.isNotEmpty() &&
                                memory.review == StoryMemoryReview.APPROVED.name) {
                                OutlinedButton(
                                    modifier = Modifier.testTag("story_memory_perspectives_${memory.memoryId}"),
                                    enabled = !working,
                                    onClick = {
                                        perspectiveCharacterId = characters.firstOrNull()?.characterId
                                        perspectiveCurrent = emptyList()
                                        perspectiveStance = StoryCharacterPerspective.KNOWS
                                        perspectiveNote = ""
                                        perspectiveMemory = memory
                                    }
                                ) { Text("Character perspectives") }
                            }
                            if (memory.hidden) Text("Hidden from future retrieval", style = MaterialTheme.typography.labelSmall)
                            if (memory.pinned) Text("Pinned for future relevance", style = MaterialTheme.typography.labelSmall)
                            // Never present unreviewed suggestions as editable canon
                            // or allow a premature grant, pin or resolution.
                            if (memory.review == StoryMemoryReview.APPROVED.name) {
                            // Keep all four controls inside the phone viewport.
                            // A single unwrapped Row placed Hide beyond the usable width,
                            // making its semantics node appear clickable without a hit.
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(enabled = !working, onClick = { editing = memory }) {
                                        Text("Edit")
                                    }
                                    TextButton(enabled = !working, onClick = {
                                        selectedKnowers = knowers[memory.memoryId].orEmpty()
                                        grantingKnowledge = memory
                                    }) { Text("Who knows this?") }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(
                                        modifier = Modifier.testTag("story_memory_pin_${memory.memoryId}"),
                                        enabled = !working,
                                        onClick = {
                                            applyChange { repository.update(chatId, memory.memoryId, pinned = !memory.pinned) }
                                        }
                                    ) { Text(if (memory.pinned) "Unpin" else "Pin") }
                                    TextButton(
                                        modifier = Modifier.testTag("story_memory_visibility_${memory.memoryId}"),
                                        enabled = !working,
                                        onClick = {
                                            applyChange { repository.update(chatId, memory.memoryId, hidden = !memory.hidden) }
                                        }
                                    ) { Text(if (memory.hidden) "Show" else "Hide") }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (memory.lifecycle == StoryMemoryLifecycle.ACTIVE.name &&
                                    memory.memoryId !in linkedPromises) TextButton(enabled = !working, onClick = {
                                    applyChange { repository.update(chatId, memory.memoryId, lifecycle = StoryMemoryLifecycle.RESOLVED) }
                                }) { Text("Resolve") }
                                if (boundary != null && memory.sourceOrderIndex <= boundary!!) {
                                    OutlinedButton(
                                        enabled = !working,
                                        modifier = Modifier.testTag("restore_memory_${memory.memoryId}"),
                                        onClick = { restoring = memory }
                                    ) { Text("Restore after Wack") }
                                }
                                TextButton(enabled = !working, onClick = { removing = memory }) { Text("Delete") }
                            }
                            } else {
                                TextButton(enabled = !working, onClick = { removing = memory }) {
                                    Text("Delete suggestion")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
