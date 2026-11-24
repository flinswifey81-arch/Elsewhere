package com.example.domain.repository

import androidx.room.withTransaction
import com.example.data.AppDatabase
import com.example.data.model.MessageEntity
import com.example.data.model.GenerationMetadataEntity
import com.example.data.model.CharacterEntity
import com.example.data.model.ChatType
import com.example.data.model.ManualCharacterFieldsCodec
import com.example.data.model.NpcBankSettingsEntity
import com.example.data.model.NpcDossier
import com.example.data.model.NpcDossierEntity
import com.example.data.model.NpcSceneMode
import com.example.domain.npc.NpcSceneCastResolver
import com.example.domain.npc.NpcScenePresence
import com.example.data.model.NpcDossierUpdateEntity
import com.example.data.model.NpcLibraryBankEntity
import com.example.data.model.NpcLibraryEntryEntity
import com.example.domain.npc.NpcStructuredUpdate
import com.example.domain.npc.NpcStructuredOutputParser
import com.example.domain.npc.NpcProseDiscovery
import com.example.data.model.SpeakerType
import com.example.domain.story.StoryResponseCodec
import com.example.domain.story.StorySegmentType
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

enum class NpcImportCollisionMode { IMPORT_AS_NEW, REPLACE_EXISTING }

data class NpcPromotionResult(val characterId: String, val created: Boolean, val attachedToChat: Boolean)

data class NpcUpdateApplyReport(val skippedReasons: List<String> = emptyList()) {
    fun warning(kind: String): String? = skippedReasons.takeIf { it.isNotEmpty() }?.let { reasons ->
        "$kind saved; ${reasons.size} NPC update(s) skipped: " +
            reasons.distinct().joinToString(", ") + "."
    }
}

@JsonClass(generateAdapter = true)
data class NpcBankJson(
    @Json(name = "schema_version") val schemaVersion: Int = 1,
    val npcs: List<NpcCardJson>
)

@JsonClass(generateAdapter = true)
data class NpcCardJson(
    @Json(name = "npc_id") val npcId: String,
    @Json(name = "display_name") val displayName: String,
    val aliases: List<String> = emptyList(),
    val role: String = "",
    val appearance: String = "",
    val personality: String = "",
    @Json(name = "relationship_to_persona") val relationshipToPersona: String = "",
    @Json(name = "persona_relationships") val personaRelationships: Map<String, String> = emptyMap(),
    @Json(name = "character_knowledge") val characterKnowledge: Map<String, String> = emptyMap(),
    val goals: String = "",
    val secrets: String = "",
    @Json(name = "author_notes") val authorNotes: String = "",
    @Json(name = "keep_in_scene") val keepInScene: Boolean = false,
    @Json(name = "extension_fields") val extensionFields: Map<String, Any?> = emptyMap()
)

data class NpcContextSelection(
    val dossier: NpcDossier,
    val score: Int
)

data class NpcLibraryBank(val bank: NpcLibraryBankEntity, val entries: List<NpcLibraryEntryEntity>)

class NpcRepository(
    private val database: AppDatabase,
    private val moshi: Moshi,
    private val dataCoordinator: AppDataMaintenanceCoordinator = AppDataMaintenanceCoordinator.Global,
    private val profileImageRepository: ProfileImageRepository? = null
) {
    private val dao get() = database.npcDao()
    private val messageDao get() = database.messageDao()
    private val aliasesType = Types.newParameterizedType(List::class.java, String::class.java)
    private val aliasesAdapter = moshi.adapter<List<String>>(aliasesType)
    private val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(mapType)
    private val stringMapType = Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
    private val stringMapAdapter = moshi.adapter<Map<String, String>>(stringMapType)
    private val bankAdapter = moshi.adapter(NpcBankJson::class.java)

    fun observeProjectedDossiers(chatId: String): Flow<List<NpcDossier>> =
        combine(dao.observeDossiers(chatId), dao.observeUpdates(chatId)) { bases, updates ->
            project(bases, updates)
        }

    fun observeSettings(chatId: String): Flow<NpcBankSettingsEntity?> = dao.observeSettings(chatId)

    fun observeLibraryBanks(): Flow<List<NpcLibraryBankEntity>> = dao.observeLibraryBanks()

    fun observeReusableCharacters(): Flow<List<CharacterEntity>> =
        database.characterDao().getAllCharacters()

    /** Internal per-chat NPC role link; not exported as independent NPC canon. */
    private fun linkedCharacterId(base: NpcDossierEntity): String? =
        base.extensionFieldsJson?.let { json ->
            runCatching { mapAdapter.fromJson(json) }.getOrNull()
        }?.get("_elsewhere_linked_character_id") as? String

    /**
     * One existing Character can appear in many independent RP chats, each
     * with a DIFFERENT chat-local NPC dossier. They share authored identity,
     * not chat memories, variant state, relationships, or discovered secrets.
     * Idempotent per (chat, Character ID). No model/API request.
     */
    suspend fun addExistingCharacterAsNpc(chatId: String, characterId: String): String =
        dataCoordinator.withMutation {
            withContext(Dispatchers.IO) {
                val result = database.withTransaction {
                    val chat = requireNotNull(database.chatDao().getChatById(chatId)) {
                        "The chat no longer exists."
                    }
                    val character = requireNotNull(
                        database.characterDao().getCharacterById(characterId)
                    ) { "The linked Character no longer exists." }
                    require(!character.isArchived) {
                        "Archived Characters cannot be linked into an active NPC Bank."
                    }
                    val existing = dao.getDossiers(chatId).firstOrNull { dossier ->
                        linkedCharacterId(dossier) == characterId ||
                            (dossier.npcId == characterId &&
                                character.extensionFieldsJson?.let { ext ->
                                    runCatching { mapAdapter.fromJson(ext) }.getOrNull()
                                }?.get("_elsewhere_promoted_npc_id") == characterId)
                    }
                    if (existing != null) {
                        // Repeated linking never duplicates either role.
                        if (chat.chatType != ChatType.SCENARIO &&
                            database.chatDao().getChatParticipant(chatId, characterId) == null
                        ) {
                            database.chatDao().addChatParticipantAtEnd(chatId, characterId)
                        }
                        return@withTransaction existing.npcId to false
                    }
                    val authored = ManualCharacterFieldsCodec.toEditableFields(character)
                    val npcId = UUID.randomUUID().toString()
                    dao.insertDossier(
                        NpcDossierEntity(
                            npcId = npcId,
                            chatId = chatId,
                            displayName = character.displayName,
                            aliasesJson = aliasesAdapter.toJson(character.aliases),
                            appearance = authored.appearance.take(4000),
                            personality = authored.personality.take(4000),
                            goals = authored.conversationalGoals.take(4000),
                            role = "",
                            sceneMode = "AUTO",
                            isManualBase = true,
                            extensionFieldsJson = mapAdapter.toJson(
                                mapOf("_elsewhere_linked_character_id" to characterId)
                            )
                        )
                    )
                    if (chat.chatType != ChatType.SCENARIO &&
                        database.chatDao().getChatParticipant(chatId, characterId) == null
                    ) {
                        // Make a selected Character available for Solo spotlight
                        // or Group roster without ever making it the initial lead.
                        database.chatDao().addChatParticipantAtEnd(chatId, characterId)
                    }
                    npcId to true
                }
                if (result.second) {
                    // Copy once; subsequent imports/links never overwrite a
                    // picture that the player edited for THIS chat's NPC.
                    runCatching {
                        profileImageRepository?.copyImageToKind(
                            ProfileImageKind.CHARACTER, characterId,
                            ProfileImageKind.NPC, result.first
                        )
                    }
                }
                result.first
            }
        }

    /** Promoted NPC and reusable Character share their exact stable ID. */
    fun observePromotedCharacterIds(): Flow<Set<String>> =
        database.characterDao().getAllCharacters().map { cards ->
            cards.filter { card ->
                card.extensionFieldsJson?.let { json ->
                    runCatching { mapAdapter.fromJson(json)?.get("_elsewhere_promoted_npc_id") }
                        .getOrNull() == card.characterId
                } == true
            }.mapTo(mutableSetOf()) { it.characterId }
        }

    /**
     * Promote a chat NPC into a reusable Character WITHOUT deleting the NPC,
     * its variant-linked update history, or any prior messages. The same ID is
     * used in both tables. Only portrayal fields cross this boundary; secrets,
     * private author notes and per-Persona relationships never become public
     * global Character knowledge. No model call.
     */
    suspend fun promoteNpcToCharacter(chatId: String, npcId: String): NpcPromotionResult =
        dataCoordinator.withMutation {
            withContext(Dispatchers.IO) {
                val chat = requireNotNull(database.chatDao().getChatById(chatId)) {
                    "This chat does not exist."
                }
                val source = requireNotNull(dao.getDossier(npcId)) {
                    "This NPC no longer exists."
                }
                require(source.chatId == chatId) {
                    "This NPC belongs to a different chat."
                }
                require(linkedCharacterId(source) == null) {
                    "This NPC is already linked to an existing Character. " +
                        "Use that Character instead of creating a duplicate."
                }
                // Snapshot selected-variant updates after Wack. Never use a
                // non-selected historical branch as Character base material.
                val npc = getProjectedDossiers(
                    chatId, afterOrderIndex = chat.contextResetOrderIndex
                ).firstOrNull { it.npcId == npcId }
                    ?: throw IllegalStateException("The NPC could not be projected.")
                val result = database.withTransaction {
                    require(dao.getDossier(npcId)?.chatId == chatId) {
                        "The original NPC was changed or removed. Nothing was promoted."
                    }
                    val existing = database.characterDao().getCharacterById(npcId)
                    if (existing != null) {
                        val origin = existing.extensionFieldsJson?.let {
                            runCatching { mapAdapter.fromJson(it) }.getOrNull()
                        }?.get("_elsewhere_promoted_npc_id")
                        require(origin == npcId) {
                            "An unrelated Character owns this ID. Nothing was changed."
                        }
                        require(!existing.isArchived) {
                            "The linked Character is archived. Unarchive it before using it again."
                        }
                    } else {
                        database.characterDao().insertNewCharacter(
                            CharacterEntity(
                                characterId = npcId,
                                displayName = npc.displayName,
                                characterName = npc.displayName,
                                aliases = npc.aliases,
                                importSchemaVersion = 1,
                                identityJson = mapAdapter.toJson(
                                    mapOf("name" to npc.displayName, "role" to npc.role)
                                ),
                                appearanceJson = npc.appearance.takeIf(String::isNotBlank)?.let {
                                    mapAdapter.toJson(mapOf("description" to it))
                                },
                                personalityJson = mapAdapter.toJson(
                                    mapOf("summary" to npc.personality, "goals" to npc.goals)
                                ),
                                voiceJson = null,
                                behaviorJson = null,
                                backstoryJson = null,
                                knowledgeJson = null,
                                worldContextJson = null,
                                writingRulesJson = null,
                                examplesJson = null,
                                authorNotesJson = null,
                                extensionFieldsJson = mapAdapter.toJson(
                                    mapOf("_elsewhere_promoted_npc_id" to npcId)
                                )
                            )
                        )
                    }
                    // Scenario episodes have a narrator, not a direct primary
                    // Character roster. They can link this card explicitly.
                    val attach = chat.chatType != ChatType.SCENARIO &&
                        database.chatDao().getChatParticipant(chatId, npcId) == null
                    if (attach) database.chatDao().addChatParticipantAtEnd(chatId, npcId)
                    NpcPromotionResult(npcId, existing == null, attach)
                }
                if (result.created) {
                    // Device-local portraits stay untouched in the NPC Bank.
                    // A copy into Character storage is best effort, not destructive.
                    runCatching {
                        profileImageRepository?.copyImageToKind(
                            ProfileImageKind.NPC, npcId,
                            ProfileImageKind.CHARACTER, npcId
                        )
                    }
                }
                result
            }
        }

    suspend fun getLibraryBank(bankId: String): NpcLibraryBank? = withContext(Dispatchers.IO) {
        dao.getLibraryBanks().firstOrNull { it.bankId == bankId }?.let { NpcLibraryBank(it, dao.getLibraryEntries(bankId)) }
    }

    suspend fun importLibraryBank(name: String, json: String): String = dataCoordinator.withMutation {
        withContext(Dispatchers.IO) {
            require(name.isNotBlank()) { "Library bank name is required." }
            require(json.length <= MAX_IMPORT_CHARS) { "NPC JSON is too large." }
            val document = parseImportDocument(json)
            require(document.schemaVersion == 1) { "Unsupported NPC schema version ${document.schemaVersion}." }
            require(document.npcs.isNotEmpty()) { "The NPC bank is empty." }
            require(document.npcs.size <= MAX_IMPORT_NPCS) { "An NPC import can contain at most $MAX_IMPORT_NPCS records." }
            document.npcs.forEach(::validateCard)
            val bank = NpcLibraryBankEntity(name = name.trim())
            database.withTransaction {
                dao.insertLibraryBank(bank)
                dao.insertLibraryEntries(document.npcs.mapIndexed { index, card -> card.toLibrary(bank.bankId, index) })
            }
            bank.bankId
        }
    }

    suspend fun saveChatToLibrary(chatId: String, name: String): String = dataCoordinator.withMutation {
        withContext(Dispatchers.IO) {
            require(name.isNotBlank()) { "Library bank name is required." }
            val projected = getProjectedDossiers(chatId)
            require(projected.isNotEmpty()) { "This chat has no NPCs to save." }
            val baseExtensions = dao.getDossiers(chatId).associate { it.npcId to it.extensionFieldsJson }
            val bank = NpcLibraryBankEntity(name = name.trim())
            database.withTransaction {
                dao.insertLibraryBank(bank)
                dao.insertLibraryEntries(projected.mapIndexed { index, npc ->
                    npc.toLibrary(bank.bankId, index).copy(
                        extensionFieldsJson = baseExtensions[npc.npcId]
                            ?.let { raw -> runCatching { mapAdapter.fromJson(raw) }.getOrNull() }
                            ?.filterKeys { it != "_elsewhere_linked_character_id" }
                            ?.takeIf { it.isNotEmpty() }
                            ?.let(mapAdapter::toJson)
                    )
                })
            }
            bank.bankId
        }
    }

    suspend fun addLibraryNpcsToChat(chatId: String, bankId: String, selectedIds: Set<String>? = null): Int =
        dataCoordinator.withMutation {
            withContext(Dispatchers.IO) {
                val entries = dao.getLibraryEntries(bankId).filter { selectedIds == null || it.libraryNpcId in selectedIds }
                require(entries.isNotEmpty()) { "Select at least one NPC." }
                database.withTransaction {
                    entries.forEach { entry -> dao.insertDossier(entry.toDossier(chatId)) }
                }
                entries.size
            }
        }

    suspend fun deleteLibraryBank(bankId: String) = dataCoordinator.withMutation {
        withContext(Dispatchers.IO) {
            database.withTransaction {
                dao.deleteLibraryEntries(bankId)
                dao.deleteLibraryBank(bankId)
            }
        }
    }

    suspend fun getSettings(chatId: String): NpcBankSettingsEntity =
        dataCoordinator.withMutation {
            withContext(Dispatchers.IO) {
            dao.getSettings(chatId) ?: NpcBankSettingsEntity(chatId, isEnabled = false).also { dao.upsertSettings(it) }
            }
        }

    suspend fun setEnabled(chatId: String, enabled: Boolean) = dataCoordinator.withMutation {
        withContext(Dispatchers.IO) {
            dao.upsertSettings(NpcBankSettingsEntity(chatId, enabled))
        }
    }

    /** Keep the editable Bank intact; only generation-side projections honor Wack. */
    suspend fun getProjectedDossiers(
        chatId: String, afterOrderIndex: Long? = null
    ): List<NpcDossier> = withContext(Dispatchers.IO) {
        val updates = dao.getUpdates(chatId).filter {
            afterOrderIndex == null || it.sourceOrderIndex > afterOrderIndex
        }
        project(dao.getDossiers(chatId), updates)
    }

    suspend fun saveManual(dossier: NpcDossier) = dataCoordinator.withMutation {
        withContext(Dispatchers.IO) {
        validateCard(dossier)
        database.withTransaction {
            val existing = dao.getDossier(dossier.npcId)
            require(existing == null || existing.chatId == dossier.chatId) { "NPC identity belongs to another chat." }
            val entity = NpcDossierEntity(
                npcId = dossier.npcId,
                chatId = dossier.chatId,
                displayName = dossier.displayName.trim(),
                aliasesJson = aliasesAdapter.toJson(dossier.aliases.map(String::trim).filter(String::isNotEmpty).distinct()),
                role = dossier.role.trim(),
                appearance = dossier.appearance.trim(),
                personality = dossier.personality.trim(),
                relationshipToPersona = dossier.relationshipToPersona.trim(),
                personaRelationshipsJson = stringMapAdapter.toJson(dossier.personaRelationships.cleanMap()),
                characterKnowledgeJson = stringMapAdapter.toJson(dossier.characterKnowledge.cleanMap()),
                goals = dossier.goals.trim(),
                secrets = dossier.secrets.trim(),
                authorNotes = dossier.authorNotes.trim(),
                keepInScene = dossier.keepInScene,
                sceneMode = existing?.sceneMode ?: dossier.sceneMode,
                isManualBase = true,
                extensionFieldsJson = existing?.extensionFieldsJson,
                createdAt = existing?.createdAt ?: dossier.createdAt,
                updatedAt = System.currentTimeMillis()
            )
            dao.deleteUpdatesForDossier(dossier.npcId)
            if (existing == null) dao.insertDossier(entity) else dao.updateDossier(entity)
        }
        }
    }

    suspend fun setKeepInScene(npcId: String, keep: Boolean) = dataCoordinator.withMutation {
        withContext(Dispatchers.IO) {
            val existing = requireNotNull(dao.getDossier(npcId)) { "NPC not found." }
            dao.updateDossier(existing.copy(keepInScene = keep, updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun setSceneMode(npcId: String, mode: NpcSceneMode) = dataCoordinator.withMutation {
        withContext(Dispatchers.IO) {
            val current = requireNotNull(dao.getDossier(npcId)) { "NPC not found." }
            dao.updateDossier(current.copy(sceneMode = mode.name, updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun deleteDossier(npcId: String) = dataCoordinator.withMutation {
        withContext(Dispatchers.IO) {
            database.withTransaction {
                dao.deleteUpdatesForDossier(npcId)
                dao.deleteDossier(npcId)
            }
        }
    }

    suspend fun nextSourceRevision(messageId: String): Int = withContext(Dispatchers.IO) {
        dao.nextSourceRevision(messageId)
    }

    suspend fun applyStructuredUpdates(
        chatId: String,
        sourceMessageId: String,
        variantGroupId: String,
        sourceRevision: Int,
        isApplied: Boolean,
        updates: List<NpcStructuredUpdate>,
        activePersonaId: String? = null
    ) = dataCoordinator.withMutation {
        withContext(Dispatchers.IO) {
            if (updates.isEmpty()) return@withContext
            require(updates.size <= 24) { "Too many NPC updates." }
            database.withTransaction {
                applyStructuredUpdatesInTransaction(
                    chatId,
                    sourceMessageId,
                    variantGroupId,
                    sourceRevision,
                    isApplied,
                    updates,
                    activePersonaId
                )
            }
        }
    }

    suspend fun persistGeneratedMessageAtomically(
        message: MessageEntity,
        metadata: GenerationMetadataEntity?,
        isNewVariant: Boolean,
        updates: List<NpcStructuredUpdate>,
        activePersonaId: String?,
        skipInvalidNpcMetadata: Boolean = false
    ) = dataCoordinator.withMutation {
        withContext(Dispatchers.IO) {
            database.withTransaction {
                if (isNewVariant) {
                    messageDao.insertVariantAndSelect(message)
                    dao.setVariantUpdatesApplied(message.variantGroupId, false)
                } else {
                    messageDao.insertMessage(message)
                }
                if (metadata != null) messageDao.insertGenerationMetadata(metadata)
                // Metadata first, then speaker discovery to recover from bad model IDs.
                val metadataReport = applyStructuredUpdatesInTransaction(
                    chatId = message.chatId,
                    sourceMessageId = message.messageId,
                    variantGroupId = message.variantGroupId,
                    sourceRevision = 0,
                    isApplied = true,
                    updates = updates,
                    activePersonaId = activePersonaId,
                    skipInvalidUpdates = skipInvalidNpcMetadata
                )
                if (dao.getSettings(message.chatId)?.isEnabled == true) {
                    val missingSpeakers = missingSpeakerDiscoveryUpdates(message, emptyList())
                    val proseSpeakers = missingProseDiscoveryUpdates(
                        message, missingSpeakers, activePersonaId
                    )
                    applyStructuredUpdatesInTransaction(
                        chatId = message.chatId,
                        sourceMessageId = message.messageId,
                        variantGroupId = message.variantGroupId,
                        sourceRevision = 0,
                        isApplied = true,
                        updates = missingSpeakers + proseSpeakers,
                        activePersonaId = activePersonaId
                    )
                }
                reconcileNpcSpeakerIds(message)
                metadataReport
            }
        }
    }

    suspend fun persistContinuationAtomically(
        message: MessageEntity,
        metadata: GenerationMetadataEntity?,
        updates: List<NpcStructuredUpdate>,
        activePersonaId: String?,
        skipInvalidNpcMetadata: Boolean = false
    ) = dataCoordinator.withMutation {
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val previous = messageDao.getMessageById(message.messageId)
                val previouslyNamedSpeakers = StoryResponseCodec.decode(previous?.content.orEmpty())
                    ?.segments.orEmpty()
                    .filter { it.type == StorySegmentType.NPC }
                    .mapNotNull { it.speakerName?.trim()?.lowercase(Locale.ROOT) }
                    .toSet()
                messageDao.updateMessage(message)
                if (metadata != null) messageDao.insertGenerationMetadata(metadata)
                // Continue rewrites one stored response with its appended segments.
                // Discover ONLY new structured NPC speakers, never speakers that
                // were already present in the older portion of that response.
                val newSpeakers = if (
                    message.isPrimaryVariant && dao.getSettings(message.chatId)?.isEnabled == true
                ) {
                    missingSpeakerDiscoveryUpdates(message, updates, previouslyNamedSpeakers)
                } else emptyList()
                val previousProseSpeakers = previous?.let {
                    proseSpeakerNames(it, activePersonaId).map(String::lowercase).toSet()
                }.orEmpty()
                val newProseSpeakers = if (
                    message.isPrimaryVariant && dao.getSettings(message.chatId)?.isEnabled == true
                ) {
                    missingProseDiscoveryUpdates(
                        message, updates + newSpeakers, activePersonaId,
                        ignoredNames = previousProseSpeakers
                    ).take((24 - updates.size - newSpeakers.size).coerceAtLeast(0))
                } else emptyList()
                val metadataReport = applyStructuredUpdatesInTransaction(
                    chatId = message.chatId,
                    sourceMessageId = message.messageId,
                    variantGroupId = message.variantGroupId,
                    sourceRevision = dao.nextSourceRevision(message.messageId),
                    isApplied = message.isPrimaryVariant,
                    updates = updates + newSpeakers + newProseSpeakers,
                    activePersonaId = activePersonaId,
                    skipInvalidUpdates = skipInvalidNpcMetadata
                )
                // Continuations already include earlier segments. Do not re-discover those
                // historical speakers or turn a switched-away variant back into a live NPC.
                reconcileNpcSpeakerIds(message)
                metadataReport
            }
        }
    }

    /**
     * Only a real structured NPC speaker segment qualifies for automatic creation.
     * A plain mention, label inside ordinary prose, or unrelated narrator text does not.
     * Do not create a second NPC if metadata is already creating the same speaker.
     */
    private suspend fun missingSpeakerDiscoveryUpdates(
        message: MessageEntity,
        metadataUpdates: List<NpcStructuredUpdate>,
        ignoredSpeakerNames: Set<String> = emptySet()
    ): List<NpcStructuredUpdate> {
        val story = StoryResponseCodec.decode(message.content) ?: return emptyList()
        val projected = project(dao.getDossiers(message.chatId), dao.getUpdates(message.chatId))
        return story.segments.asSequence()
            .filter { it.type == StorySegmentType.NPC && !it.content.isBlank() }
            .mapNotNull {
                it.speakerName?.trim()?.takeIf { name ->
                    name.isNotEmpty() && name.length <= 120 &&
                        !name.equals("Supporting character", ignoreCase = true)
                }?.let { name -> name to it.speakerId }
            }
            .distinctBy { (name, _) -> name.lowercase(Locale.ROOT) }
            .filterNot { (name, _) -> name.lowercase(Locale.ROOT) in ignoredSpeakerNames }
            .filterNot { (name, speakerId) ->
                projected.any { it.npcId == speakerId && matchesNameOrAlias(it, name) } ||
                    projected.any { matchesNameOrAlias(it, name) } ||
                    metadataUpdates.any { update ->
                        update.displayName?.equals(name, ignoreCase = true) == true ||
                            update.aliases?.any { it.equals(name, ignoreCase = true) } == true
                    }
            }
            .map { (name, _) ->
                NpcStructuredUpdate(null, name, null, null, null, null, null, null, null)
            }
            .toList()
    }

    /** A minimal, provenance-linked dossier for an unambiguously quoted new speaker. */
    private suspend fun proseSpeakerNames(
        message: MessageEntity,
        activePersonaId: String?
    ): List<String> {
        if (message.speakerType != SpeakerType.CHARACTER ||
            database.chatDao().getChatById(message.chatId)?.chatType != ChatType.SOLO
        ) return emptyList()
        // No guessing if the active player identity is missing.
        val persona = activePersonaId?.let(database.personaDao()::getPersonaById)
            ?: return emptyList()
        val protected = listOf(
            message.speakerDisplayNameSnapshot,
            persona.displayName,
            persona.personaName
        ) + persona.aliases
        val story = StoryResponseCodec.decode(message.content)
        val visible = story?.segments?.joinToString("\n") { it.content } ?: message.content
        return NpcProseDiscovery.namedSpeakers(
            NpcStructuredOutputParser.finalVisibleStoryText(visible), protected
        )
    }

    private suspend fun missingProseDiscoveryUpdates(
        message: MessageEntity,
        otherUpdates: List<NpcStructuredUpdate>,
        activePersonaId: String?,
        ignoredNames: Set<String> = emptySet()
    ): List<NpcStructuredUpdate> {
        val projected = project(dao.getDossiers(message.chatId), dao.getUpdates(message.chatId))
        return proseSpeakerNames(message, activePersonaId)
            .filterNot { it.lowercase(Locale.ROOT) in ignoredNames }
            .filterNot { name ->
                projected.any { matchesNameOrAlias(it, name) } ||
                    otherUpdates.any { update ->
                        update.displayName?.equals(name, ignoreCase = true) == true ||
                            update.aliases?.any { it.equals(name, ignoreCase = true) } == true
                    }
            }
            .map { NpcStructuredUpdate(null, it, null, null, null, null, null, null, null) }
            .take(12)
    }

    /** Persist stable Bank IDs instead of the model's temporary name-derived speaker IDs. */
    private suspend fun reconcileNpcSpeakerIds(message: MessageEntity) {
        val story = StoryResponseCodec.decode(message.content) ?: return
        if (story.segments.none { it.type == StorySegmentType.NPC }) return
        val projected = project(dao.getDossiers(message.chatId), dao.getUpdates(message.chatId))
        val segments = story.segments.map { segment ->
            if (segment.type != StorySegmentType.NPC) return@map segment
            val nameMatch = segment.speakerName?.let { findByName(projected, it) }
            val idMatch = projected.firstOrNull { it.npcId == segment.speakerId }
            // Conflicting evidence is ambiguous: do not silently give someone's
            // dialogue (and later their portrait) to another NPC.
            // Names/aliases take precedence over model-supplied IDs. A fabricated
            // or stale ID must never pull a different NPC's portrait into this message.
            // Speaker names must resolve unambiguously; a model-supplied ID cannot
            // resolve a shared alias or override a conflicting visible name.
            val resolved = nameMatch
            when {
                resolved != null -> segment.copy(
                    speakerId = resolved.npcId,
                    speakerName = resolved.displayName
                )
                idMatch != null && !segment.speakerName.isNullOrBlank() ->
                    segment.copy(speakerId = null)
                // Older structured messages can have a known ID without a display name.
                // Preserve that genuine stored reference, rather than erasing it.
                else -> segment
            }
        }
        if (segments != story.segments) {
            messageDao.updateMessage(message.copy(content = StoryResponseCodec.encode(story.copy(segments = segments))))
        }
    }

    private suspend fun applyStructuredUpdatesInTransaction(
        chatId: String,
        sourceMessageId: String,
        variantGroupId: String,
        sourceRevision: Int,
        isApplied: Boolean,
        updates: List<NpcStructuredUpdate>,
        activePersonaId: String?,
        skipInvalidUpdates: Boolean = false
    ): NpcUpdateApplyReport {
        if (updates.isEmpty()) return NpcUpdateApplyReport()
        require(updates.size <= 24) { "Too many NPC updates." }
        val sourceOrderIndex = requireNotNull(messageDao.getMessageById(sourceMessageId)) {
            "NPC updates require a persisted source response."
        }.orderIndex
        var bases = dao.getDossiers(chatId)
        val initialBaseIds = bases.map { it.npcId }.toSet()
        var projected = project(bases, dao.getUpdates(chatId))
        val events = mutableListOf<NpcDossierUpdateEntity>()
        val skipped = mutableListOf<String>()
        updates.forEachIndexed { updateIndex, change ->
            val checkpointBases = bases
            val checkpointProjected = projected
            val checkpointEventCount = events.size
            try {
                var npcId = change.npcId
                val existingBase = npcId?.let { id -> bases.firstOrNull { it.npcId == id } }
                val matchedProjection = change.displayName?.let { name -> findByName(projected, name) }
                // If aliases collide, refuse to guess which NPC the model meant.
                // The calling layer can retain the story without applying unsafe metadata.
                if (change.npcId == null && matchedProjection == null &&
                    change.displayName?.let { name -> projected.any { matchesNameOrAlias(it, name) } } == true
                ) {
                    throw IllegalArgumentException("Ambiguous NPC name; edit the Bank to disambiguate.")
                }
                if (npcId != null && existingBase == null && matchedProjection == null) {
                    val foreign = dao.getDossier(npcId)
                    require(foreign == null) { "NPC identity belongs to another chat." }
                }
                npcId = existingBase?.npcId ?: matchedProjection?.npcId ?: npcId
                if (npcId == null) npcId = UUID.randomUUID().toString()
                var base = bases.firstOrNull { it.npcId == npcId }
                val isCreation = base == null
                if (base == null) {
                    val name = requireNotNull(change.displayName?.trim()?.takeIf(String::isNotEmpty)) {
                        "A discovered NPC requires a display name."
                    }
                    require(name.length <= 120)
                    base = NpcDossierEntity(
                        npcId = npcId,
                        chatId = chatId,
                        displayName = name,
                        isManualBase = false,
                        createdAt = System.currentTimeMillis() + updateIndex,
                        updatedAt = System.currentTimeMillis()
                    )
                    // Defer writes until all fields validate; failed entries remain atomic.
                    bases = bases + base
                }
                // A creation-by-name can refer to an existing NPC by name or alias.
                // Preserve manually established identity/core fields instead of making
                // the entire update fail and potentially creating a duplicate.
                val knownByName = !isCreation && change.npcId == null && matchedProjection?.npcId == npcId
                if (!isCreation && !knownByName) {
                    require(
                        change.displayName == null &&
                            change.aliases == null &&
                            change.appearance == null &&
                            change.personality == null
                    ) {
                        "Automatic updates cannot change an existing NPC's identity or core profile. Edit the dossier manually instead."
                    }
                }
                val current = projected.firstOrNull { it.npcId == npcId }
                val fields = linkedMapOf<String, String>()
                if (isCreation) {
                    change.displayName?.let { fields[FIELD_DISPLAY_NAME] = it.trim() }
                    change.aliases?.let { fields[FIELD_ALIASES] = aliasesAdapter.toJson(it) }
                    change.appearance?.let { fields[FIELD_APPEARANCE] = it }
                    change.personality?.let { fields[FIELD_PERSONALITY] = it }
                }
                change.role?.let { fields[FIELD_ROLE] = it }
                change.relationshipToPersona?.let {
                    val field = activePersonaId?.let { personaId -> "$FIELD_PERSONA_RELATIONSHIP_PREFIX$personaId" }
                        ?: FIELD_RELATIONSHIP
                    fields[field] = it
                }
                change.goals?.let { fields[FIELD_GOALS] = it }
                change.secrets?.let { fields[FIELD_SECRETS] = it }
                fields.forEach { (field, newValue) ->
                    require(newValue.length <= 4000) { "NPC field is too long." }
                    require(events.none { it.npcId == npcId && it.fieldName == field }) {
                        "Duplicate NPC field update."
                    }
                    events += NpcDossierUpdateEntity(
                        chatId = chatId,
                        npcId = npcId,
                        sourceMessageId = sourceMessageId,
                        variantGroupId = variantGroupId,
                        sourceOrderIndex = sourceOrderIndex,
                        sourceRevision = sourceRevision,
                        fieldName = field,
                        oldValue = current?.field(field),
                        newValue = newValue,
                        isApplied = isApplied,
                        createdAt = System.currentTimeMillis() + events.size
                    )
                }
                projected = project(bases, dao.getUpdates(chatId) + events)
            } catch (invalid: IllegalArgumentException) {
                if (!skipInvalidUpdates) throw invalid
                bases = checkpointBases
                projected = checkpointProjected
                while (events.size > checkpointEventCount) events.removeAt(events.lastIndex)
                skipped += when (invalid.message) {
                    "A discovered NPC requires a display name." -> "unknown NPC ID"
                    "NPC identity belongs to another chat." -> "wrong-chat NPC ID"
                    "Duplicate NPC field update." -> "duplicate conflicting fields"
                    "NPC field is too long." -> "oversized NPC field"
                    "Ambiguous NPC name; edit the Bank to disambiguate." -> "ambiguous name"
                    "Automatic updates cannot change an existing NPC's identity or core profile. Edit the dossier manually instead." ->
                        "protected identity fields"
                    else -> "invalid NPC details"
                }
            }
        }
        bases.filter { it.npcId !in initialBaseIds }.forEach { dao.insertDossier(it) }
        dao.insertUpdates(events)
        return NpcUpdateApplyReport(skipped)
    }

    suspend fun selectVariant(variantGroupId: String, messageId: String) = dataCoordinator.withMutation {
        withContext(Dispatchers.IO) {
            database.withTransaction {
                messageDao.clearPrimaryVariants(variantGroupId)
                messageDao.setPrimaryVariant(messageId)
                dao.setVariantUpdatesApplied(variantGroupId, false)
                dao.setMessageUpdatesApplied(messageId, true)
            }
        }
    }

    suspend fun deleteMessageAndRepair(message: MessageEntity) = dataCoordinator.withMutation {
        withContext(Dispatchers.IO) {
            database.withTransaction {
                dao.deleteUpdatesForMessage(message.messageId)
                messageDao.deleteMessageAndRepair(message)
                if (message.isPrimaryVariant) {
                    val survivor = messageDao.getVariantsOnce(message.variantGroupId).firstOrNull { it.isPrimaryVariant }
                    survivor?.let { dao.setMessageUpdatesApplied(it.messageId, true) }
                }
            }
        }
    }

    /**
     * Active promoted Character gets only its OWN current-chat relationship
     * with the active Persona, never the NPC Bank's narrator-only secrets,
     * unrelated Character knowledge or another chat's relationship history.
     * A Wack boundary requires explicit reintroduction, so no old base leaks.
     */
    suspend fun promotedSpeakerRelationshipContext(
        chatId: String,
        characterId: String,
        activePersonaId: String,
        asOfOrderIndex: Long? = null,
        afterOrderIndex: Long? = null
    ): String? = withContext(Dispatchers.IO) {
        if (afterOrderIndex != null) return@withContext null
        val base = dao.getDossier(characterId)?.takeIf { it.chatId == chatId }
            ?: dao.getDossiers(chatId).firstOrNull {
                linkedCharacterId(it) == characterId
            }
            ?: return@withContext null
        val card = database.characterDao().getCharacterById(characterId)
            ?: return@withContext null
        val origin = card.extensionFieldsJson?.let { json ->
            runCatching { mapAdapter.fromJson(json) }.getOrNull()
        }?.get("_elsewhere_promoted_npc_id")
        if (origin != characterId && linkedCharacterId(base) != characterId) {
            return@withContext null
        }
        val changes = dao.getUpdates(chatId).filter {
            asOfOrderIndex == null || it.sourceOrderIndex <= asOfOrderIndex
        }
        val state = project(listOf(base), changes).firstOrNull()
            ?: return@withContext null
        val relationship = state.personaRelationships[activePersonaId]
            ?: state.relationshipToPersona.takeIf {
                state.personaRelationships.isEmpty()
            }
        relationship?.takeIf(String::isNotBlank)?.take(4000)?.let {
            "[CHAT-LOCAL RELATIONSHIP FOR ACTIVE CHARACTER ONLY]\n" +
                "Character ID: $characterId\n" +
                "Active Persona ID: $activePersonaId\n" +
                "Established relationship: $it\n" +
                "This does NOT transfer to any other chat, Persona or World canon."
        }
    }

    suspend fun relevantContext(
        chatId: String,
        recentMessages: List<MessageEntity>,
        observerCharacterId: String? = null,
        activePersonaId: String? = null,
        isGroup: Boolean = false,
        asOfOrderIndex: Long? = null,
        limit: Int = DEFAULT_NPC_CONTEXT_LIMIT,
        afterOrderIndex: Long? = null
    ): String? =
        withContext(Dispatchers.IO) {
            val bases = dao.getDossiers(chatId)
            val updates = dao.getUpdates(chatId).let { all ->
                all.filter { event ->
                    (asOfOrderIndex == null || event.sourceOrderIndex <= asOfOrderIndex) &&
                        (afterOrderIndex == null || event.sourceOrderIndex > afterOrderIndex)
                }
            }
            // A promoted NPC and the active Character share an ID; do not
            // introduce that identity again as a separate supporting actor.
            val dossiers = project(bases, updates)
                .filterNot { dossier ->
                    // Null means there is no responding Character to exclude.
                    // An ordinary NPC's unlinked character ID is also null:
                    // comparing those nulls would silently remove the entire Bank
                    // for narrator and other observer-less generation paths.
                    observerCharacterId != null &&
                        (dossier.npcId == observerCharacterId ||
                            dossier.linkedCharacterId == observerCharacterId)
                }
            if (dossiers.isEmpty()) return@withContext null
            val recentText = recentMessages.filter { it.isPrimaryVariant }.takeLast(4)
                .joinToString("\n") {
                    StoryResponseCodec.promptTranscript(
                        NpcStructuredOutputParser.finalVisibleStoryText(it.content)
                    )
                }.lowercase(Locale.ROOT)
            // Physical scene presence gates NPC context; a Co-Lead pin only affects priority.
            val present = NpcSceneCastResolver.resolve(dossiers, recentMessages)
                .filter { it.presence == NpcScenePresence.PRESENT }
                .map { it.dossier }
            val selected = present.map { dossier ->
                val names = listOf(dossier.displayName) + dossier.aliases
                val mentionScore = names.sumOf { alias ->
                    if (alias.isBlank()) 0 else Regex("(?i)(?<![\\p{L}\\p{N}_])${Regex.escape(alias)}(?![\\p{L}\\p{N}_])")
                        .findAll(recentText).count() * 20
                }
                NpcContextSelection(dossier, mentionScore + if (dossier.keepInScene) 1000 else 0)
            }.sortedWith(compareByDescending<NpcContextSelection> { it.score }.thenByDescending { it.dossier.updatedAt })
                .take(limit.coerceIn(1, 8))
            // Resolve suspending Room reads BEFORE entering joinToString's
            // non-suspending rendering lambda.
            val linkedCards = mutableMapOf<String, CharacterEntity>()
            selected.mapNotNull { it.dossier.linkedCharacterId }.distinct().forEach { id ->
                database.characterDao().getCharacterById(id)
                    ?.takeUnless { it.isArchived }
                    ?.let { linkedCards[id] = it }
            }
            // The Bank is both active-scene context and a bounded directory of
            // established recurring cast. An UNKNOWN NPC has not been shown to be
            // present: supply a tiny, non-secret casting hint so the storyteller
            // may stage an organic entrance without the Persona naming them.
            // Do not restore forgotten pre-Wack cast or broadcast extra cast to
            // per-speaker Group requests. Explicit AWAY is never eligible.
            val potentialCast = if (!isGroup && afterOrderIndex == null) {
                val sceneWords = Regex("[\\p{L}\\p{N}]{4,}")
                    .findAll(recentText).map { it.value }.toSet()
                NpcSceneCastResolver.resolve(dossiers, recentMessages)
                    .filter { it.presence == NpcScenePresence.UNKNOWN }
                    .sortedWith(
                        compareByDescending<com.example.domain.npc.NpcSceneMember> { member ->
                            val roleWords = Regex("[\\p{L}\\p{N}]{4,}")
                                .findAll(member.dossier.role.lowercase(Locale.ROOT))
                                .map { it.value }.toSet()
                            roleWords.count { it in sceneWords }
                        }.thenByDescending { it.dossier.keepInScene }
                            .thenByDescending { it.dossier.updatedAt }
                            .thenBy { it.dossier.npcId }
                    )
                    .take(minOf(4, limit.coerceIn(1, 8)))
                    .map { it.dossier }
            } else emptyList()
            val presentContext = selected.joinToString("\n\n") { selection ->
                val linkedCard = selection.dossier.linkedCharacterId?.let(linkedCards::get)
                with(selection.dossier) {
                    buildString {
                        append("[NPC id=").append(npcId).append("]\n")
                        linkedCard?.let { card ->
                            // Existing Character's authored identity is reusable.
                            // NEVER read its other chat memories, personal notes,
                            // knowledgeJson or world-specific private continuity.
                            append("LINKED CHARACTER ID: ").append(card.characterId).append('\n')
                            append("Use this person's authored portrayal as baseline; ")
                                .append("the NPC Bank fields below describe THIS chat's role.\n")
                            fun authored(label: String, raw: String?) {
                                raw?.takeIf(String::isNotBlank)?.take(1800)?.let { value ->
                                    append(label).append(": ").append(value).append('\n')
                                }
                            }
                            authored("Authored identity", card.identityJson)
                            authored("Authored appearance", card.appearanceJson)
                            authored("Authored personality", card.personalityJson)
                            authored("Authored voice", card.voiceJson)
                            authored("Authored behavior", card.behaviorJson)
                            authored("Authored backstory", card.backstoryJson)
                            authored("Authored writing rules", card.writingRulesJson)
                            ManualCharacterFieldsCodec.readStored(card)?.let { fields ->
                                authored("Manual tone", fields.tone)
                                authored("Manual personality", fields.personality)
                                authored("Manual appearance", fields.appearance)
                            }
                        }
                        append("[STORYTELLER CONTINUITY — NOT AUTOMATIC SPEAKER KNOWLEDGE]\n")
                        append("Name: ").append(displayName).append('\n')
                        if (aliases.isNotEmpty()) append("Aliases: ").append(aliases.joinToString(", ")).append('\n')
                        if (role.isNotBlank()) append("Role: ").append(role).append('\n')
                        if (appearance.isNotBlank()) append("Appearance: ").append(appearance).append('\n')
                        if (personality.isNotBlank()) append("Personality: ").append(personality).append('\n')
                        val scopedRelationship = activePersonaId?.let(personaRelationships::get)
                            ?: relationshipToPersona.takeIf { !isGroup && personaRelationships.isEmpty() }
                        if (!scopedRelationship.isNullOrBlank()) {
                            append("Active Persona relationship continuity: ").append(scopedRelationship).append('\n')
                        }
                if (goals.isNotBlank()) append("Goals: ").append(goals).append('\n')
                if (secrets.isNotBlank()) {
                    append("Storyteller-only secrets (not speaker knowledge): ")
                        .append(secrets)
                        .append('\n')
                }
                        observerCharacterId?.let(characterKnowledge::get)?.takeIf(String::isNotBlank)?.let { known ->
                            append("[SPEAKER-AUTHORIZED KNOWLEDGE FOR RESPONDING CHARACTER]\n")
                            append(known).append('\n')
                        }
                    }.trimEnd()
                }
            }
            val availableCastContext = if (potentialCast.isEmpty()) null else buildString {
                append("[POTENTIAL RECURRING CAST — NOT CONFIRMED PRESENT]\n")
                append("These are established chat-local supporting characters who are not ")
                append("known to be in the room. They may return through a plausible, ")
                append("scene-motivated entrance when the setting or ongoing plot calls ")
                append("for them. Do not teleport them, make them speak before entering, ")
                append("invent a Persona action to bring them in, or override an Away state.\n")
                potentialCast.forEach { npc ->
                    append("- ").append(npc.displayName.take(120))
                    npc.role.takeIf(String::isNotBlank)?.let { role ->
                        append(" — role: ").append(role.take(160))
                    }
                    append('\n')
                }
            }.trimEnd()
            listOfNotNull(presentContext.takeIf(String::isNotBlank), availableCastContext)
                .joinToString("\n\n").takeIf(String::isNotBlank)
        }

    suspend fun exportJson(chatId: String, includePrivateFields: Boolean = false): String = withContext(Dispatchers.IO) {
        val bases = dao.getDossiers(chatId).associateBy { it.npcId }
        val cards = getProjectedDossiers(chatId).map { dossier ->
            NpcCardJson(
                npcId = dossier.npcId,
                displayName = dossier.displayName,
                aliases = dossier.aliases,
                role = dossier.role,
                appearance = dossier.appearance,
                personality = dossier.personality,
                relationshipToPersona = dossier.relationshipToPersona,
                personaRelationships = dossier.personaRelationships,
                characterKnowledge = dossier.characterKnowledge,
                goals = dossier.goals,
                secrets = dossier.secrets.takeIf { includePrivateFields }.orEmpty(),
                authorNotes = dossier.authorNotes.takeIf { includePrivateFields }.orEmpty(),
                keepInScene = dossier.keepInScene,
                extensionFields = if (includePrivateFields) {
                    bases[dossier.npcId]?.extensionFieldsJson
                        ?.let { runCatching { mapAdapter.fromJson(it) }.getOrNull() }
                        ?.filterKeys { it != "_elsewhere_linked_character_id" }.orEmpty()
                } else emptyMap()
            )
        }
        bankAdapter.indent("  ").toJson(NpcBankJson(npcs = cards))
    }

    suspend fun importJson(
        chatId: String,
        json: String,
        collisionMode: NpcImportCollisionMode = NpcImportCollisionMode.IMPORT_AS_NEW
    ): Int = dataCoordinator.withMutation {
        withContext(Dispatchers.IO) {
        require(json.length <= MAX_IMPORT_CHARS) { "NPC JSON is too large." }
        val document = parseImportDocument(json)
        require(document.schemaVersion == 1) { "Unsupported NPC schema version ${document.schemaVersion}." }
        require(document.npcs.size <= MAX_IMPORT_NPCS) { "An NPC import can contain at most $MAX_IMPORT_NPCS records." }
        var count = 0
        database.withTransaction {
            document.npcs.forEach { card ->
                validateCard(card)
                val existing = dao.getDossier(card.npcId)
                val resolvedId = when {
                    existing == null -> card.npcId
                    collisionMode == NpcImportCollisionMode.IMPORT_AS_NEW -> UUID.randomUUID().toString()
                    existing.chatId == chatId -> card.npcId
                    else -> throw IllegalArgumentException("NPC id ${card.npcId} belongs to another chat; choose Import as New.")
                }
                val replacing = existing?.takeIf { it.npcId == resolvedId }
                val entity = NpcDossierEntity(
                    npcId = resolvedId,
                    chatId = chatId,
                    displayName = card.displayName.trim(),
                    aliasesJson = aliasesAdapter.toJson(card.aliases.map(String::trim).distinct()),
                    role = card.role.trim(),
                    appearance = card.appearance.trim(),
                    personality = card.personality.trim(),
                    relationshipToPersona = card.relationshipToPersona.trim(),
                    personaRelationshipsJson = stringMapAdapter.toJson(card.personaRelationships.cleanMap()),
                    characterKnowledgeJson = stringMapAdapter.toJson(card.characterKnowledge.cleanMap()),
                    goals = card.goals.trim(),
                    secrets = card.secrets.trim(),
                    authorNotes = card.authorNotes.trim(),
                    keepInScene = card.keepInScene,
                    sceneMode = replacing?.sceneMode ?: "AUTO",
                    isManualBase = true,
                    extensionFieldsJson = card.extensionFields
                        .filterKeys { it != "_elsewhere_linked_character_id" }
                        .takeIf { it.isNotEmpty() }?.let(mapAdapter::toJson),
                    createdAt = replacing?.createdAt ?: System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                if (replacing != null && collisionMode == NpcImportCollisionMode.REPLACE_EXISTING) {
                    dao.updateDossier(entity)
                } else {
                    dao.insertDossier(entity)
                }
                count++
            }
        }
        count
        }
    }

    private fun parseImportDocument(json: String): NpcBankJson {
        val root = requireNotNull(mapAdapter.fromJson(json)) { "NPC JSON is empty." }
        val rawNpcs = root["npcs"] as? List<*>
            ?: throw IllegalArgumentException("NPC JSON requires an npcs array.")
        val normalizedNpcs = rawNpcs.map { rawCard ->
            val card = (rawCard as? Map<*, *>)?.entries?.associate { (key, value) ->
                require(key is String) { "NPC fields must have string names." }
                key to value
            } ?: throw IllegalArgumentException("Each NPC must be an object.")
            val explicitExtensions = (card["extension_fields"] as? Map<*, *>)?.entries?.associate { (key, value) ->
                require(key is String) { "NPC extension fields must have string names." }
                key to value
            }.orEmpty()
            val unknownFields = card.filterKeys { it !in KNOWN_NPC_JSON_FIELDS }
            card.toMutableMap().apply {
                this["extension_fields"] = explicitExtensions + unknownFields
            }
        }
        val normalizedRoot = root.toMutableMap().apply { this["npcs"] = normalizedNpcs }
        return requireNotNull(bankAdapter.fromJson(mapAdapter.toJson(normalizedRoot))) { "NPC JSON is empty." }
    }

    private fun NpcCardJson.toLibrary(bankId: String, position: Int) = NpcLibraryEntryEntity(
        bankId = bankId, displayName = displayName.trim(), aliasesJson = aliasesAdapter.toJson(aliases.map(String::trim).filter(String::isNotEmpty).distinct()),
        role = role.trim(), appearance = appearance.trim(), personality = personality.trim(), relationshipToPersona = relationshipToPersona.trim(),
        personaRelationshipsJson = stringMapAdapter.toJson(personaRelationships.cleanMap()), characterKnowledgeJson = stringMapAdapter.toJson(characterKnowledge.cleanMap()),
        goals = goals.trim(), secrets = secrets.trim(), authorNotes = authorNotes.trim(), keepInScene = keepInScene,
        extensionFieldsJson = extensionFields
            .filterKeys { it != "_elsewhere_linked_character_id" }
            .takeIf { it.isNotEmpty() }?.let(mapAdapter::toJson),
        sortPosition = position
    )

    private fun NpcDossier.toLibrary(bankId: String, position: Int) = NpcLibraryEntryEntity(
        bankId = bankId, displayName = displayName, aliasesJson = aliasesAdapter.toJson(aliases), role = role, appearance = appearance,
        personality = personality, relationshipToPersona = relationshipToPersona, personaRelationshipsJson = stringMapAdapter.toJson(personaRelationships),
        characterKnowledgeJson = stringMapAdapter.toJson(characterKnowledge), goals = goals, secrets = secrets, authorNotes = authorNotes,
        keepInScene = keepInScene, sortPosition = position
    )

    private fun NpcLibraryEntryEntity.toDossier(chatId: String) = NpcDossierEntity(
        npcId = UUID.randomUUID().toString(), chatId = chatId, displayName = displayName, aliasesJson = aliasesJson, role = role,
        appearance = appearance, personality = personality, relationshipToPersona = relationshipToPersona,
        personaRelationshipsJson = personaRelationshipsJson, characterKnowledgeJson = characterKnowledgeJson, goals = goals, secrets = secrets,
        authorNotes = authorNotes, keepInScene = keepInScene, isManualBase = true,
        extensionFieldsJson = extensionFieldsJson?.let { raw ->
            runCatching { mapAdapter.fromJson(raw) }.getOrNull()
                ?.filterKeys { it != "_elsewhere_linked_character_id" }
                ?.takeIf { it.isNotEmpty() }?.let(mapAdapter::toJson)
        }
    )

    private fun project(
        bases: List<NpcDossierEntity>,
        updates: List<NpcDossierUpdateEntity>
    ): List<NpcDossier> = bases.mapNotNull { base ->
        val relevant = updates.filter { it.npcId == base.npcId && it.isApplied }
            .sortedWith(
                compareBy<NpcDossierUpdateEntity> { it.sourceOrderIndex }
                    .thenBy { it.sourceRevision }
                    .thenBy { it.createdAt }
                    .thenBy { it.updateId }
            )
        if (!base.isManualBase && relevant.isEmpty()) return@mapNotNull null
        // The stable name anchors sibling variants. A model-discovered dossier is still hidden
        // when it has no applied events, but another variant may update it by its supplied id.
        var displayName = base.displayName
        var aliases = if (base.isManualBase) parseAliases(base.aliasesJson) else emptyList()
        var role = if (base.isManualBase) base.role else ""
        var appearance = if (base.isManualBase) base.appearance else ""
        var personality = if (base.isManualBase) base.personality else ""
        var relationship = if (base.isManualBase) base.relationshipToPersona else ""
        val personaRelationships = if (base.isManualBase) parseStringMap(base.personaRelationshipsJson).toMutableMap() else mutableMapOf()
        val characterKnowledge = if (base.isManualBase) parseStringMap(base.characterKnowledgeJson).toMutableMap() else mutableMapOf()
        var goals = if (base.isManualBase) base.goals else ""
        var secrets = if (base.isManualBase) base.secrets else ""
        relevant.forEach { event ->
            when (event.fieldName) {
                FIELD_DISPLAY_NAME -> displayName = event.newValue
                FIELD_ALIASES -> aliases = parseAliases(event.newValue)
                FIELD_ROLE -> role = event.newValue
                FIELD_APPEARANCE -> appearance = event.newValue
                FIELD_PERSONALITY -> personality = event.newValue
                FIELD_RELATIONSHIP -> relationship = event.newValue
                FIELD_GOALS -> goals = event.newValue
                FIELD_SECRETS -> secrets = event.newValue
                else -> when {
                    event.fieldName.startsWith(FIELD_PERSONA_RELATIONSHIP_PREFIX) -> {
                        personaRelationships[event.fieldName.removePrefix(FIELD_PERSONA_RELATIONSHIP_PREFIX)] = event.newValue
                    }
                    event.fieldName.startsWith(FIELD_CHARACTER_KNOWLEDGE_PREFIX) -> {
                        characterKnowledge[event.fieldName.removePrefix(FIELD_CHARACTER_KNOWLEDGE_PREFIX)] = event.newValue
                    }
                }
            }
        }
        if (displayName.isBlank()) return@mapNotNull null
        NpcDossier(
            npcId = base.npcId,
            chatId = base.chatId,
            displayName = displayName,
            aliases = aliases,
            role = role,
            appearance = appearance,
            personality = personality,
            relationshipToPersona = relationship,
            personaRelationships = personaRelationships.toMap(),
            characterKnowledge = characterKnowledge.toMap(),
            goals = goals,
            secrets = secrets,
            authorNotes = base.authorNotes,
            keepInScene = base.keepInScene,
            sceneMode = base.sceneMode,
            linkedCharacterId = linkedCharacterId(base),
            isManualBase = base.isManualBase,
            createdAt = base.createdAt,
            updatedAt = maxOf(base.updatedAt, relevant.maxOfOrNull { it.createdAt } ?: base.updatedAt)
        )
    }

    private fun parseAliases(json: String): List<String> =
        runCatching { aliasesAdapter.fromJson(json).orEmpty() }.getOrDefault(emptyList())

    private fun parseStringMap(json: String): Map<String, String> =
        runCatching { stringMapAdapter.fromJson(json).orEmpty() }.getOrDefault(emptyMap())

    private fun matchesNameOrAlias(dossier: NpcDossier, name: String): Boolean {
        val normalized = name.trim().lowercase(Locale.ROOT)
        return normalized.isNotEmpty() &&
            (dossier.displayName.trim().lowercase(Locale.ROOT) == normalized ||
                dossier.aliases.any { it.trim().lowercase(Locale.ROOT) == normalized })
    }

    private fun findByName(dossiers: List<NpcDossier>, name: String): NpcDossier? {
        val normalized = name.trim().lowercase(Locale.ROOT)
        if (normalized.isBlank()) return null
        // Exact display names outrank aliases. Never guess between two equally
        // plausible matches; misattribution would attach dialogue to the wrong portrait.
        val exact = dossiers.filter { it.displayName.trim().lowercase(Locale.ROOT) == normalized }
        if (exact.size == 1) return exact.single()
        if (exact.isNotEmpty()) return null
        return dossiers.filter { dossier ->
            dossier.aliases.any { it.trim().lowercase(Locale.ROOT) == normalized }
        }.singleOrNull()
    }

    private fun NpcDossier.field(field: String): String? = when (field) {
        FIELD_DISPLAY_NAME -> displayName
        FIELD_ALIASES -> aliasesAdapter.toJson(aliases)
        FIELD_ROLE -> role
        FIELD_APPEARANCE -> appearance
        FIELD_PERSONALITY -> personality
        FIELD_RELATIONSHIP -> relationshipToPersona
        FIELD_GOALS -> goals
        FIELD_SECRETS -> secrets
        else -> when {
            field.startsWith(FIELD_PERSONA_RELATIONSHIP_PREFIX) -> personaRelationships[field.removePrefix(FIELD_PERSONA_RELATIONSHIP_PREFIX)]
            field.startsWith(FIELD_CHARACTER_KNOWLEDGE_PREFIX) -> characterKnowledge[field.removePrefix(FIELD_CHARACTER_KNOWLEDGE_PREFIX)]
            else -> null
        }
    }

    private fun validateCard(card: NpcDossier) {
        require(card.sceneMode in NpcSceneMode.entries.map { it.name }) { "Invalid NPC scene mode." }
        require(card.displayName.isNotBlank() && card.displayName.length <= 120) { "NPC display name is required." }
        require(card.aliases.size <= 20 && card.aliases.all { it.isNotBlank() && it.length <= 120 }) { "NPC aliases are invalid." }
        validateScopeMap(card.personaRelationships)
        validateScopeMap(card.characterKnowledge)
        (listOf(card.role, card.appearance, card.personality, card.relationshipToPersona, card.goals, card.secrets, card.authorNotes) +
            card.personaRelationships.values + card.characterKnowledge.values)
            .forEach { require(it.length <= 4000) { "NPC field is too long." } }
    }

    private fun validateCard(card: NpcCardJson) {
        require(card.npcId.isNotBlank() && card.npcId.length <= 80) { "NPC id is required." }
        require(card.displayName.isNotBlank() && card.displayName.length <= 120) { "NPC display name is required." }
        require(card.aliases.size <= 20 && card.aliases.all { it.isNotBlank() && it.length <= 120 }) { "NPC aliases are invalid." }
        validateScopeMap(card.personaRelationships)
        validateScopeMap(card.characterKnowledge)
        (listOf(card.role, card.appearance, card.personality, card.relationshipToPersona, card.goals, card.secrets, card.authorNotes) +
            card.personaRelationships.values + card.characterKnowledge.values)
            .forEach { require(it.length <= 4000) { "NPC field is too long." } }
    }

    companion object {
        // Solo Ensemble Narration can include more relevant NPC dossiers than ordinary chats.
        const val DEFAULT_NPC_CONTEXT_LIMIT = 3
        const val SOLO_ENSEMBLE_NPC_CONTEXT_LIMIT = 8

        const val FIELD_DISPLAY_NAME = "display_name"
        const val FIELD_ALIASES = "aliases"
        const val FIELD_ROLE = "role"
        const val FIELD_APPEARANCE = "appearance"
        const val FIELD_PERSONALITY = "personality"
        const val FIELD_RELATIONSHIP = "relationship_to_persona"
        const val FIELD_GOALS = "goals"
        const val FIELD_SECRETS = "secrets"
        const val FIELD_PERSONA_RELATIONSHIP_PREFIX = "persona_relationship:"
        const val FIELD_CHARACTER_KNOWLEDGE_PREFIX = "character_knowledge:"
        private val KNOWN_NPC_JSON_FIELDS = setOf(
            "npc_id", "display_name", "aliases", "role", "appearance", "personality",
            "relationship_to_persona", "persona_relationships", "character_knowledge",
            "goals", "secrets", "author_notes", "keep_in_scene", "extension_fields"
        )
        private const val MAX_IMPORT_CHARS = 1_000_000
        private const val MAX_IMPORT_NPCS = 200
    }

    private fun Map<String, String>.cleanMap(): Map<String, String> = entries
        .asSequence()
        .map { it.key.trim() to it.value.trim() }
        .toMap()

    private fun validateScopeMap(map: Map<String, String>) {
        require(map.size <= 100) { "NPC scoped relationship or knowledge map is too large." }
        require(map.keys.all { it.isNotBlank() && it.length <= 80 }) { "NPC scoped relationship or knowledge id is invalid." }
    }
}
