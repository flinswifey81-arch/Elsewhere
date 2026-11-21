package com.example.domain.world

import com.example.data.model.*
import com.example.domain.memory.StoryActorKind
import com.example.domain.memory.StoryMemoryEligibilityPolicy
import com.example.domain.story.StoryResponseCodec
import com.example.domain.story.StorySegmentType
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * An immutable, schema-aware import transform. It changes IDs in typed entities and known
 * embedded JSON fields while leaving prose, characterizations and dialogue word-for-word intact.
 *
 * This code DOES NOT persist the result. The eventual importer must revalidate the destination
 * state and stage ALL binaries before executing a single additive Room transaction.
 */
object WorldArchiveSemanticRemapper {
    data class RewrittenWorld(
        val state: WorldArchiveStateV1,
        /** Original archive member -> destination archive member. Bytes must still be verified. */
        val attachmentFileMoves: Map<String, String>
    )

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val linksAdapter = moshi.adapter(WorldLinksV1::class.java).failOnUnknown()
    private val rawStoryAdapter = moshi.adapter(Any::class.java)
    private val stringsAdapter = moshi.adapter<List<String>>(
        Types.newParameterizedType(List::class.java, String::class.java)
    )
    private val extensionsAdapter = moshi.adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )
    private val knowledgeAdapter = moshi.adapter<Map<String, String>>(
        Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
    )

    private fun <T> decode(json: String, label: String, parser: (String) -> T?): T =
        try {
            requireNotNull(parser(json)) { "Missing $label" }
        } catch (cause: Exception) {
            throw IllegalArgumentException("Invalid $label in World archive", cause)
        }

    fun rewrite(source: WorldArchiveStateV1, plan: WorldArchiveIdRemapPlan): RewrittenWorld {
        WorldArchiveStateCodec.validate(source)
        // Only app-known extension keys have a documented reference contract.
        // User-entered manual profile fields are plain authored text; the two
        // Living Cast link keys below get explicit typed ID remapping.
        fun extension(json: String?, label: String): Map<String, Any?> =
            if (json.isNullOrBlank() || json == "null") emptyMap()
            else decode(json, label, extensionsAdapter::fromJson)
        fun checked(json: String?, label: String, allowed: Set<String>): Map<String, Any?> {
            val fields = extension(json, label)
            require(fields.keys.all { it in allowed }) {
                "$label has unrecognized reference-bearing extension fields; " +
                    "a type-aware migration is required."
            }
            return fields
        }
        source.characters.forEach {
            checked(it.extensionFieldsJson, "Character extension",
                setOf("elsewhere_manual_fields", "_elsewhere_promoted_npc_id"))
        }
        source.personas.forEach {
            checked(it.extensionFieldsJson, "Persona extension",
                setOf("elsewhere_manual_persona_fields"))
        }
        source.npcDossiers.forEach {
            checked(it.extensionFieldsJson, "NPC extension",
                setOf("_elsewhere_linked_character_id"))
        }
        source.npcLibraryEntries.forEach {
            checked(it.extensionFieldsJson, "NPC Library extension", emptySet())
        }
        // A plan from another archive or namespace must never silently remap this one.
        require(WorldArchiveIdPlanner.plan(source, plan.namespace).mappings == plan.mappings) {
            "Import identity plan does not match the authenticated World snapshot"
        }
        fun id(kind: WorldArchiveIdKind, old: String): String = plan.get(kind, old)
        fun optional(kind: WorldArchiveIdKind, old: String?): String? = old?.let { id(kind, it) }
        fun ids(json: String, label: String, kind: WorldArchiveIdKind): String =
            stringsAdapter.toJson(decode(json, label, stringsAdapter::fromJson).map { id(kind, it) })
        fun keyedMap(json: String, label: String, kind: WorldArchiveIdKind): String {
            val original = decode(json, label, knowledgeAdapter::fromJson)
            return knowledgeAdapter.toJson(original.mapKeys { (old, _) -> id(kind, old) })
        }
        val sourceCharacterIds = source.characters.mapTo(mutableSetOf()) {
            it.characterId
        }
        val sourceNpcIds = source.npcDossiers.mapTo(mutableSetOf()) { it.npcId }
        fun characterExtension(row: CharacterEntity): String? {
            val fields = checked(row.extensionFieldsJson, "Character extension",
                setOf("elsewhere_manual_fields", "_elsewhere_promoted_npc_id")).toMutableMap()
            val origin = fields.remove("_elsewhere_promoted_npc_id") as? String
            if (origin == row.characterId && origin in sourceNpcIds) {
                require(id(WorldArchiveIdKind.CHARACTER, origin) ==
                    id(WorldArchiveIdKind.NPC_DOSSIER, origin)) {
                    "Promoted Character/NPC imported identities must stay aligned."
                }
                fields["_elsewhere_promoted_npc_id"] =
                    id(WorldArchiveIdKind.CHARACTER, origin)
            }
            return fields.takeIf { it.isNotEmpty() }?.let(extensionsAdapter::toJson)
        }
        fun npcExtension(row: NpcDossierEntity): String? {
            val fields = checked(row.extensionFieldsJson, "NPC extension",
                setOf("_elsewhere_linked_character_id")).toMutableMap()
            val linked = fields["_elsewhere_linked_character_id"] as? String
            if (linked != null) {
                require(linked in sourceCharacterIds) {
                    "Linked Character is missing from the selected World archive."
                }
                fields["_elsewhere_linked_character_id"] =
                    id(WorldArchiveIdKind.CHARACTER, linked)
            }
            return fields.takeIf { it.isNotEmpty() }?.let(extensionsAdapter::toJson)
        }
        fun scenarioJson(json: String, scenarioId: String): String {
            val original = ScenarioCodec.decode(json)
            val rebuilt = original.copy(npcs = original.npcs.map { npc ->
                npc.copy(
                    npcId = plan.scenarioNpcId(scenarioId, npc.npcId),
                    sourceCharacterId = optional(WorldArchiveIdKind.CHARACTER, npc.sourceCharacterId)
                )
            })
            rebuilt.validate()
            return ScenarioCodec.encode(rebuilt)
        }

        val originalLinks = decode(source.world.linksJson, "World cast links", linksAdapter::fromJson)
        val remappedLinks = originalLinks.copy(
            relationships = originalLinks.relationships.map {
                it.copy(
                    fromWorldCharacterId = id(WorldArchiveIdKind.WORLD_CAST, it.fromWorldCharacterId),
                    toWorldCharacterId = id(WorldArchiveIdKind.WORLD_CAST, it.toWorldCharacterId)
                )
            },
            castRoles = originalLinks.castRoles.map { role ->
                val resourceId = when (role.roleType) {
                    "CHARACTER" -> id(WorldArchiveIdKind.CHARACTER, role.resourceId)
                    "PERSONA" -> id(WorldArchiveIdKind.PERSONA, role.resourceId)
                    "NPC_LIBRARY" -> id(WorldArchiveIdKind.NPC_LIBRARY_ENTRY, role.resourceId)
                    "SCENARIO_NPC" -> plan.scenarioNpcId(
                        requireNotNull(role.scenarioId) { "Scenario role has no Scenario" },
                        role.resourceId
                    )
                    else -> throw IllegalArgumentException("Unknown World cast role")
                }
                role.copy(
                    worldCharacterId = id(WorldArchiveIdKind.WORLD_CAST, role.worldCharacterId),
                    resourceId = resourceId,
                    scenarioId = optional(WorldArchiveIdKind.SCENARIO, role.scenarioId)
                )
            }
        )

        fun provenance(kind: String, sourceId: String?): String? {
            if (sourceId == null) return null
            val resourceType = when (kind.lowercase()) {
                "character" -> WorldArchiveIdKind.CHARACTER
                "persona" -> WorldArchiveIdKind.PERSONA
                "npc", "npc_dossier", "chat_npc" -> WorldArchiveIdKind.NPC_DOSSIER
                "npc_library", "npc_library_entry" -> WorldArchiveIdKind.NPC_LIBRARY_ENTRY
                "scenario" -> WorldArchiveIdKind.SCENARIO
                else -> throw IllegalArgumentException(
                    "Cannot safely remap an unknown World cast provenance type"
                )
            }
            return id(resourceType, sourceId)
        }

        // Remap typed fictional actors, never global-replace text or infer identity by name.
        // Scenario NPC IDs are scenario-local and cannot be safely resolved from an actor
        // without its owning Scenario; refuse those rather than silently mislink them.
        fun storyActorId(kind: String, oldId: String, chatId: String): String =
            when (kind) {
                StoryActorKind.CHARACTER.name -> id(WorldArchiveIdKind.CHARACTER, oldId)
                StoryActorKind.PERSONA.name -> id(WorldArchiveIdKind.PERSONA, oldId)
                StoryActorKind.NPC.name -> id(WorldArchiveIdKind.NPC_DOSSIER, oldId)
                StoryActorKind.WORLD_CAST.name -> id(WorldArchiveIdKind.WORLD_CAST, oldId)
                StoryActorKind.NARRATOR.name -> {
                    require(oldId == "narrator:$chatId") {
                        "Cannot import an unknown narrator identity into another World"
                    }
                    "narrator:" + id(WorldArchiveIdKind.CHAT, chatId)
                }
                else -> throw IllegalArgumentException(
                    "Story Memory actor type requires a typed World import migration"
                )
            }

        fun sourceSpeakerId(memory: StoryMemoryEntity): String? =
            memory.sourceSpeakerId?.let { old ->
                when (memory.sourceSpeakerType) {
                    SpeakerType.CHARACTER.name -> id(WorldArchiveIdKind.CHARACTER, old)
                    SpeakerType.PERSONA.name -> id(WorldArchiveIdKind.PERSONA, old)
                    SpeakerType.NARRATOR.name -> if (old == "narrator:${memory.chatId}") {
                        "narrator:" + id(WorldArchiveIdKind.CHAT, memory.chatId)
                    } else old
                    else -> old
                }
            }

        val storyMemoryChat = source.storyMemories.associate { it.memoryId to it.chatId }
        val freshWorldId = id(WorldArchiveIdKind.WORLD, source.worldId)
        val npcOwnerById = source.npcDossiers.associate { it.npcId to it.chatId }
        val oldNpcIds = npcOwnerById.keys
        val selectedChatIds = source.chats.map { it.chatId }.toSet()
        val sourceMessageById = source.messages.associateBy { it.messageId }
        fun content(originalChat: String, raw: String): String {
            if (!StoryResponseCodec.isEncoded(raw)) return raw
            val response = requireNotNull(StoryResponseCodec.decode(raw)) {
                "Malformed structured story cannot be remapped safely"
            }
            // The standard decoder deliberately drops unknown or malformed segments for
            // display recovery. Import cannot do that: not one piece of user history may vanish.
            val root = decode(
                raw.substringAfter('\n', missingDelimiterValue = ""),
                "structured story JSON",
                rawStoryAdapter::fromJson
            ) as? Map<*, *> ?: throw IllegalArgumentException("Structured story must be a JSON object")
            require(root.keys == setOf("schemaVersion", "segments")) {
                "Structured story has unknown fields requiring an explicit importer"
            }
            val rawSegments = root["segments"] as? List<*>
                ?: throw IllegalArgumentException("Structured story has no segments")
            require(rawSegments.size == response.segments.size && rawSegments.all { item ->
                val segment = item as? Map<*, *> ?: return@all false
                segment.keys.all { it in setOf("type", "content", "speakerId", "speakerName") } &&
                    (segment["content"] as? String)?.isNotBlank() == true
            }) { "Structured story contains unsupported or lossy segments" }
            return StoryResponseCodec.encode(response.copy(segments = response.segments.map { segment ->
                val speaker = segment.speakerId
                if (speaker == null) segment else segment.copy(
                    speakerId = when (segment.type) {
                        StorySegmentType.CHARACTER -> id(WorldArchiveIdKind.CHARACTER, speaker)
                        StorySegmentType.NPC -> if (speaker in oldNpcIds) {
                            require(npcOwnerById[speaker] == originalChat) {
                                "Structured NPC speaker refers to a dossier from another chat"
                            }
                            id(WorldArchiveIdKind.NPC_DOSSIER, speaker)
                        } else {
                            // An unbanked scene speaker is scoped to its original chat, not
                            // an unrelated namesake in another World or another episode.
                            "story-npc:" + UUID.nameUUIDFromBytes(
                                "${plan.namespace}|story-speaker|$originalChat|$speaker"
                                    .toByteArray(StandardCharsets.UTF_8)
                            )
                        }
                        StorySegmentType.NARRATION -> speaker
                    }
                )
            }))
        }

        val rewrittenAttachments = source.messageAttachments.map { item ->
            val newId = id(WorldArchiveIdKind.MESSAGE_ATTACHMENT, item.attachmentId)
            item.copy(
                attachmentId = newId,
                chatId = id(WorldArchiveIdKind.CHAT, item.chatId),
                messageId = optional(WorldArchiveIdKind.MESSAGE, item.messageId),
                assetPath = "assets/message-attachments/$newId"
            )
        }
        val attachmentMoves = source.messageAttachments.zip(rewrittenAttachments)
            .associate { (old, new) -> old.assetPath to new.assetPath }

        val mapped = source.copy(
            worldId = freshWorldId,
            world = source.world.copy(
                worldId = freshWorldId,
                linksJson = linksAdapter.toJson(remappedLinks),
                defaultLorebookIdsJson = ids(
                    source.world.defaultLorebookIdsJson,
                    "World default Lorebooks", WorldArchiveIdKind.LOREBOOK
                )
            ),
            memberships = source.memberships.map { member ->
                val type = try {
                    WorldResourceType.valueOf(member.resourceType)
                } catch (cause: Exception) {
                    throw IllegalArgumentException("World membership resource type cannot be remapped", cause)
                }
                val kind = when (type) {
                    WorldResourceType.CHAT -> WorldArchiveIdKind.CHAT
                    WorldResourceType.CHARACTER -> WorldArchiveIdKind.CHARACTER
                    WorldResourceType.PERSONA -> WorldArchiveIdKind.PERSONA
                    WorldResourceType.SCENARIO -> WorldArchiveIdKind.SCENARIO
                    WorldResourceType.LOREBOOK -> WorldArchiveIdKind.LOREBOOK
                    WorldResourceType.NPC_LIBRARY_BANK -> WorldArchiveIdKind.NPC_LIBRARY_BANK
                }
                member.copy(worldId = freshWorldId, resourceId = id(kind, member.resourceId))
            },
            cast = source.cast.map { cast ->
                cast.copy(
                    worldCharacterId = id(WorldArchiveIdKind.WORLD_CAST, cast.worldCharacterId),
                    worldId = freshWorldId,
                    provenanceId = provenance(cast.provenanceKind, cast.provenanceId)
                )
            },
            continuity = source.continuity.map { event ->
                event.copy(
                    eventId = id(WorldArchiveIdKind.CONTINUITY_EVENT, event.eventId),
                    worldId = freshWorldId,
                    sourceChatId = optional(WorldArchiveIdKind.CHAT, event.sourceChatId),
                    relatedCastIdsJson = ids(event.relatedCastIdsJson,
                        "World continuity cast", WorldArchiveIdKind.WORLD_CAST)
                )
            },
            characters = source.characters.map {
                it.copy(
                    characterId = id(WorldArchiveIdKind.CHARACTER, it.characterId),
                    extensionFieldsJson = characterExtension(it)
                )
            },
            personas = source.personas.map {
                it.copy(personaId = id(WorldArchiveIdKind.PERSONA, it.personaId))
            },
            scenarios = source.scenarios.map {
                it.copy(
                    scenarioId = id(WorldArchiveIdKind.SCENARIO, it.scenarioId),
                    definitionJson = scenarioJson(it.definitionJson, it.scenarioId)
                )
            },
            scenarioSessions = source.scenarioSessions.map {
                it.copy(
                    chatId = id(WorldArchiveIdKind.CHAT, it.chatId),
                    scenarioId = id(WorldArchiveIdKind.SCENARIO, it.scenarioId),
                    definitionJson = scenarioJson(it.definitionJson, it.scenarioId)
                )
            },
            chats = source.chats.map { it.copy(chatId = id(WorldArchiveIdKind.CHAT, it.chatId)) },
            chatParticipants = source.chatParticipants.map {
                it.copy(
                    chatId = id(WorldArchiveIdKind.CHAT, it.chatId),
                    characterId = id(WorldArchiveIdKind.CHARACTER, it.characterId)
                )
            },
            chatPersonaParticipants = source.chatPersonaParticipants.map {
                it.copy(
                    chatId = id(WorldArchiveIdKind.CHAT, it.chatId),
                    personaId = id(WorldArchiveIdKind.PERSONA, it.personaId)
                )
            },
            messages = source.messages.map {
                it.copy(
                    messageId = id(WorldArchiveIdKind.MESSAGE, it.messageId),
                    chatId = id(WorldArchiveIdKind.CHAT, it.chatId),
                    variantGroupId = plan.variantGroupId(it.variantGroupId),
                    speakerId = when (it.speakerType) {
                        SpeakerType.PERSONA -> id(WorldArchiveIdKind.PERSONA, it.speakerId)
                        SpeakerType.CHARACTER -> id(WorldArchiveIdKind.CHARACTER, it.speakerId)
                        else -> it.speakerId
                    },
                    content = content(it.chatId, it.content)
                )
            },
            chatSettings = source.chatSettings.map {
                it.copy(chatId = id(WorldArchiveIdKind.CHAT, it.chatId))
            },
            generationMetadata = source.generationMetadata.map {
                it.copy(messageId = id(WorldArchiveIdKind.MESSAGE, it.messageId))
            },
            roleplayMemories = source.roleplayMemories.map {
                it.copy(
                    memoryId = id(WorldArchiveIdKind.MEMORY, it.memoryId),
                    chatId = id(WorldArchiveIdKind.CHAT, it.chatId),
                    characterId = id(WorldArchiveIdKind.CHARACTER, it.characterId),
                    sourceMessageId = optional(WorldArchiveIdKind.MESSAGE, it.sourceMessageId)
                )
            },
            // Restore the full, source-linked Story Memory graph. Neither a Wack nor a
            // Character's knowledge is inferred or granted during this transform.
            storyMemories = source.storyMemories.map { memory ->
                memory.copy(
                    memoryId = id(WorldArchiveIdKind.STORY_MEMORY, memory.memoryId),
                    chatId = id(WorldArchiveIdKind.CHAT, memory.chatId),
                    ownerId = id(WorldArchiveIdKind.CHAT, memory.ownerId),
                    sourceMessageId = optional(WorldArchiveIdKind.MESSAGE, memory.sourceMessageId),
                    sourceVariantGroupId = optional(
                        WorldArchiveIdKind.VARIANT_GROUP, memory.sourceVariantGroupId
                    ),
                    sourceSpeakerId = sourceSpeakerId(memory),
                    // A correctly sourced structured response can change encoded speaker IDs
                    // during import. Only re-anchor its hash when the ORIGINAL hash was valid;
                    // never resurrect stale knowledge invalidated by an edit or regeneration.
                    sourceSha256 = memory.sourceSha256?.let { originalHash ->
                        val oldSource = memory.sourceMessageId?.let { sourceMessageById[it] }
                        if (oldSource != null && oldSource.isPrimaryVariant &&
                            originalHash == StoryMemoryEligibilityPolicy.sha256(oldSource.content)) {
                            StoryMemoryEligibilityPolicy.sha256(
                                content(oldSource.chatId, oldSource.content)
                            )
                        } else originalHash
                    },
                    believedById = memory.believedById?.let { original ->
                        storyActorId(requireNotNull(memory.believedByKind),
                            original, memory.chatId)
                    },
                    previousStateMemoryId = optional(
                        WorldArchiveIdKind.STORY_MEMORY, memory.previousStateMemoryId
                    )
                )
            },
            storyMemoryActors = source.storyMemoryActors.map { actor ->
                val originalChat = requireNotNull(storyMemoryChat[actor.memoryId]) {
                    "Story Memory actor lost its chat owner during import"
                }
                actor.copy(
                    memoryId = id(WorldArchiveIdKind.STORY_MEMORY, actor.memoryId),
                    actorId = storyActorId(actor.actorKind, actor.actorId, originalChat)
                )
            },
            storyMemoryReturns = source.storyMemoryReturns.map {
                it.copy(
                    memoryId = id(WorldArchiveIdKind.STORY_MEMORY, it.memoryId),
                    chatId = id(WorldArchiveIdKind.CHAT, it.chatId)
                )
            },
            storyMemoryKnowledgeChanges = source.storyMemoryKnowledgeChanges.map {
                it.copy(
                    memoryId = id(WorldArchiveIdKind.STORY_MEMORY, it.memoryId),
                    characterId = id(WorldArchiveIdKind.CHARACTER, it.characterId)
                )
            },
            storyMemoryPerspectives = source.storyMemoryPerspectives.map {
                it.copy(
                    memoryId = id(WorldArchiveIdKind.STORY_MEMORY, it.memoryId),
                    characterId = id(WorldArchiveIdKind.CHARACTER, it.characterId)
                )
            },
            conversationSummaries = source.conversationSummaries.map {
                it.copy(chatId = id(WorldArchiveIdKind.CHAT, it.chatId))
            },
            relationships = source.relationships.map {
                it.copy(
                    relationshipId = id(WorldArchiveIdKind.RELATIONSHIP, it.relationshipId),
                    sourceCharacterId = id(WorldArchiveIdKind.CHARACTER, it.sourceCharacterId),
                    targetCharacterId = id(WorldArchiveIdKind.CHARACTER, it.targetCharacterId),
                    scopeChatId = optional(WorldArchiveIdKind.CHAT, it.scopeChatId)
                )
            },
            lorebooks = source.lorebooks.map { book ->
                book.copy(
                    lorebookId = id(WorldArchiveIdKind.LOREBOOK, book.lorebookId),
                    storyId = book.storyId?.let { old ->
                        if (old in selectedChatIds) id(WorldArchiveIdKind.CHAT, old) else old
                    }
                )
            },
            lorebookEntries = source.lorebookEntries.map {
                it.copy(
                    entryId = id(WorldArchiveIdKind.LOREBOOK_ENTRY, it.entryId),
                    lorebookId = id(WorldArchiveIdKind.LOREBOOK, it.lorebookId)
                )
            },
            chatLorebookAttachments = source.chatLorebookAttachments.map {
                it.copy(
                    chatId = id(WorldArchiveIdKind.CHAT, it.chatId),
                    lorebookId = id(WorldArchiveIdKind.LOREBOOK, it.lorebookId)
                )
            },
            chatLorebookOverlays = source.chatLorebookOverlays.map {
                it.copy(
                    chatId = id(WorldArchiveIdKind.CHAT, it.chatId),
                    entryId = id(WorldArchiveIdKind.LOREBOOK_ENTRY, it.entryId)
                )
            },
            npcDossiers = source.npcDossiers.map {
                it.copy(
                    npcId = id(WorldArchiveIdKind.NPC_DOSSIER, it.npcId),
                    chatId = id(WorldArchiveIdKind.CHAT, it.chatId),
                    extensionFieldsJson = npcExtension(it),
                    personaRelationshipsJson = keyedMap(it.personaRelationshipsJson,
                        "NPC Persona relationships", WorldArchiveIdKind.PERSONA),
                    characterKnowledgeJson = keyedMap(it.characterKnowledgeJson,
                        "NPC Character knowledge", WorldArchiveIdKind.CHARACTER)
                )
            },
            npcBankSettings = source.npcBankSettings.map {
                it.copy(chatId = id(WorldArchiveIdKind.CHAT, it.chatId))
            },
            npcUpdates = source.npcUpdates.map {
                val fieldName = when {
                    it.fieldName.startsWith("persona_relationship:") ->
                        "persona_relationship:" + id(WorldArchiveIdKind.PERSONA,
                            it.fieldName.removePrefix("persona_relationship:"))
                    it.fieldName.startsWith("character_knowledge:") ->
                        "character_knowledge:" + id(WorldArchiveIdKind.CHARACTER,
                            it.fieldName.removePrefix("character_knowledge:"))
                    else -> it.fieldName
                }
                it.copy(
                    updateId = id(WorldArchiveIdKind.NPC_REVISION, it.updateId),
                    chatId = id(WorldArchiveIdKind.CHAT, it.chatId),
                    npcId = id(WorldArchiveIdKind.NPC_DOSSIER, it.npcId),
                    sourceMessageId = id(WorldArchiveIdKind.MESSAGE, it.sourceMessageId),
                    variantGroupId = plan.variantGroupId(it.variantGroupId),
                    fieldName = fieldName
                )
            },
            npcLibraryBanks = source.npcLibraryBanks.map {
                it.copy(bankId = id(WorldArchiveIdKind.NPC_LIBRARY_BANK, it.bankId))
            },
            npcLibraryEntries = source.npcLibraryEntries.map {
                it.copy(
                    libraryNpcId = id(WorldArchiveIdKind.NPC_LIBRARY_ENTRY, it.libraryNpcId),
                    bankId = id(WorldArchiveIdKind.NPC_LIBRARY_BANK, it.bankId),
                    personaRelationshipsJson = keyedMap(it.personaRelationshipsJson,
                        "Library NPC Persona relationships", WorldArchiveIdKind.PERSONA),
                    characterKnowledgeJson = keyedMap(it.characterKnowledgeJson,
                        "Library NPC Character knowledge", WorldArchiveIdKind.CHARACTER)
                )
            },
            messageAttachments = rewrittenAttachments
        )
        WorldArchiveStateCodec.validate(mapped)
        return RewrittenWorld(mapped, attachmentMoves)
    }
}
