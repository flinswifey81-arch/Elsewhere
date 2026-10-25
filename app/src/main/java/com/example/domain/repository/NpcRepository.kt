package com.example.domain.repository

import androidx.room.withTransaction
import com.example.data.AppDatabase
import com.example.data.model.MessageEntity
import com.example.data.model.GenerationMetadataEntity
import com.example.data.model.NpcBankSettingsEntity
import com.example.data.model.NpcDossier
import com.example.data.model.NpcDossierEntity
import com.example.data.model.NpcDossierUpdateEntity
import com.example.data.model.NpcLibraryBankEntity
import com.example.data.model.NpcLibraryEntryEntity
import com.example.domain.npc.NpcStructuredUpdate
import com.example.domain.npc.NpcStructuredOutputParser
import com.example.domain.story.StoryResponseCodec
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

enum class NpcImportCollisionMode { IMPORT_AS_NEW, REPLACE_EXISTING }

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
    private val dataCoordinator: AppDataMaintenanceCoordinator = AppDataMaintenanceCoordinator.Global
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
                    npc.toLibrary(bank.bankId, index).copy(extensionFieldsJson = baseExtensions[npc.npcId])
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

    suspend fun getProjectedDossiers(chatId: String): List<NpcDossier> = withContext(Dispatchers.IO) {
        project(dao.getDossiers(chatId), dao.getUpdates(chatId))
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
        activePersonaId: String?
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
                applyStructuredUpdatesInTransaction(
                    chatId = message.chatId,
                    sourceMessageId = message.messageId,
                    variantGroupId = message.variantGroupId,
                    sourceRevision = 0,
                    isApplied = true,
                    updates = updates,
                    activePersonaId = activePersonaId
                )
            }
        }
    }

    suspend fun persistContinuationAtomically(
        message: MessageEntity,
        metadata: GenerationMetadataEntity?,
        updates: List<NpcStructuredUpdate>,
        activePersonaId: String?
    ) = dataCoordinator.withMutation {
        withContext(Dispatchers.IO) {
            database.withTransaction {
                messageDao.updateMessage(message)
                if (metadata != null) messageDao.insertGenerationMetadata(metadata)
                applyStructuredUpdatesInTransaction(
                    chatId = message.chatId,
                    sourceMessageId = message.messageId,
                    variantGroupId = message.variantGroupId,
                    sourceRevision = dao.nextSourceRevision(message.messageId),
                    isApplied = message.isPrimaryVariant,
                    updates = updates,
                    activePersonaId = activePersonaId
                )
            }
        }
    }

    private suspend fun applyStructuredUpdatesInTransaction(
        chatId: String,
        sourceMessageId: String,
        variantGroupId: String,
        sourceRevision: Int,
        isApplied: Boolean,
        updates: List<NpcStructuredUpdate>,
        activePersonaId: String?
    ) {
        if (updates.isEmpty()) return
        require(updates.size <= 24) { "Too many NPC updates." }
        val sourceOrderIndex = requireNotNull(messageDao.getMessageById(sourceMessageId)) {
            "NPC updates require a persisted source response."
        }.orderIndex
        var bases = dao.getDossiers(chatId)
        var projected = project(bases, dao.getUpdates(chatId))
        val events = mutableListOf<NpcDossierUpdateEntity>()
        updates.forEachIndexed { updateIndex, change ->
                var npcId = change.npcId
                val existingBase = npcId?.let { id -> bases.firstOrNull { it.npcId == id } }
                val matchedProjection = change.displayName?.let { name -> findByName(projected, name) }
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
                    dao.insertDossier(base)
                    bases = bases + base
                }
                if (!isCreation) {
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
                change.displayName?.let { fields[FIELD_DISPLAY_NAME] = it.trim() }
                change.aliases?.let { fields[FIELD_ALIASES] = aliasesAdapter.toJson(it) }
                change.role?.let { fields[FIELD_ROLE] = it }
                change.appearance?.let { fields[FIELD_APPEARANCE] = it }
                change.personality?.let { fields[FIELD_PERSONALITY] = it }
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
        }
        dao.insertUpdates(events)
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

    suspend fun relevantContext(
        chatId: String,
        recentMessages: List<MessageEntity>,
        observerCharacterId: String? = null,
        activePersonaId: String? = null,
        isGroup: Boolean = false,
        asOfOrderIndex: Long? = null,
        limit: Int = 3
    ): String? =
        withContext(Dispatchers.IO) {
            val bases = dao.getDossiers(chatId)
            val updates = dao.getUpdates(chatId).let { all ->
                if (asOfOrderIndex == null) all else all.filter { it.sourceOrderIndex <= asOfOrderIndex }
            }
            val dossiers = project(bases, updates)
            if (dossiers.isEmpty()) return@withContext null
            val recentText = recentMessages.filter { it.isPrimaryVariant }.takeLast(4)
                .joinToString("\n") {
                    StoryResponseCodec.promptTranscript(
                        NpcStructuredOutputParser.finalVisibleStoryText(it.content)
                    )
                }.lowercase(Locale.ROOT)
            val selected = dossiers.map { dossier ->
                val names = listOf(dossier.displayName) + dossier.aliases
                val mentionScore = names.sumOf { alias ->
                    if (alias.isBlank()) 0 else Regex("(?i)(?<![\\p{L}\\p{N}_])${Regex.escape(alias)}(?![\\p{L}\\p{N}_])")
                        .findAll(recentText).count() * 20
                }
                NpcContextSelection(dossier, mentionScore + if (dossier.keepInScene) 1000 else 0)
            }.sortedWith(compareByDescending<NpcContextSelection> { it.score }.thenByDescending { it.dossier.updatedAt })
                .take(limit.coerceIn(1, 8))
            selected.joinToString("\n\n") { selection ->
                with(selection.dossier) {
                    buildString {
                        append("[NPC id=").append(npcId).append("]\n")
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
            }.takeIf(String::isNotBlank)
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
                        ?.let { runCatching { mapAdapter.fromJson(it) }.getOrNull() }.orEmpty()
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
                    isManualBase = true,
                    extensionFieldsJson = card.extensionFields.takeIf(Map<*, *>::isNotEmpty)?.let(mapAdapter::toJson),
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
        extensionFieldsJson = extensionFields.takeIf(Map<*, *>::isNotEmpty)?.let(mapAdapter::toJson), sortPosition = position
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
        authorNotes = authorNotes, keepInScene = keepInScene, isManualBase = true, extensionFieldsJson = extensionFieldsJson
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
            isManualBase = base.isManualBase,
            createdAt = base.createdAt,
            updatedAt = maxOf(base.updatedAt, relevant.maxOfOrNull { it.createdAt } ?: base.updatedAt)
        )
    }

    private fun parseAliases(json: String): List<String> =
        runCatching { aliasesAdapter.fromJson(json).orEmpty() }.getOrDefault(emptyList())

    private fun parseStringMap(json: String): Map<String, String> =
        runCatching { stringMapAdapter.fromJson(json).orEmpty() }.getOrDefault(emptyMap())

    private fun findByName(dossiers: List<NpcDossier>, name: String): NpcDossier? {
        val normalized = name.trim().lowercase(Locale.ROOT)
        return dossiers.firstOrNull { dossier ->
            dossier.displayName.lowercase(Locale.ROOT) == normalized ||
                dossier.aliases.any { it.lowercase(Locale.ROOT) == normalized }
        }
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
