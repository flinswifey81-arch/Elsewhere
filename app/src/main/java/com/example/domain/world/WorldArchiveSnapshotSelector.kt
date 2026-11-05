package com.example.domain.world

import com.example.data.model.*
import com.example.domain.backup.BackupPayload
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * Pure, read-only World selector over an already collected local backup payload.
 *
 * No model calls, no database mutation, no ZIP writing and no automatic inclusion of
 * neighboring World chats. Every referenced resource must be present in the source data.
 * The encrypted archive writer must still verify and stage binaries and obtain a separate
 * explicit privacy review before exporting private notes or draft text.
 */
object WorldArchiveSnapshotSelector {
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val linksAdapter = moshi.adapter(WorldLinksV1::class.java)
    private val listAdapter = moshi.adapter<List<String>>(
        Types.newParameterizedType(List::class.java, String::class.java)
    )
    private val mapAdapter = moshi.adapter<Map<String, String>>(
        Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
    )

    /**
     * Select only the explicitly chosen World chats. If [selectedChatIds] is null, include
     * every chat explicitly linked to this World. An empty set intentionally exports just
     * the World settings/cast/resource links (unless continuity requires a chat).
     *
     * [privateDataReviewed] is a security gate for a future UI preview, NOT proof of consent
     * on its own. UI export must ask the user and always use encryption.
     */
    fun select(
        source: BackupPayload,
        worldId: String,
        selectedChatIds: Set<String>? = null,
        privateDataReviewed: Boolean = false
    ): WorldArchiveStateV1 {
        val world = requireNotNull(source.worlds.singleOrNull { it.worldId == worldId }) {
            "The selected World is missing or duplicated"
        }
        val allMemberships = source.worldMemberships.filter { it.worldId == worldId }
        val availableChatIds = allMemberships
            .filter { it.resourceType == WorldResourceType.CHAT.name }
            .map { it.resourceId }.toSet()
        val includedChatIds = selectedChatIds ?: availableChatIds
        require(includedChatIds.all { it in availableChatIds }) {
            "Cannot export chats that were not explicitly attached to this World"
        }
        val memberships = allMemberships.filter {
            it.resourceType != WorldResourceType.CHAT.name || it.resourceId in includedChatIds
        }
        val chats = source.chats.filter { it.chatId in includedChatIds }
        require(chats.size == includedChatIds.size) { "A selected World chat no longer exists" }

        val scenarioSessions = source.scenarioSessions.filter { it.chatId in includedChatIds }
        val links = requireNotNull(linksAdapter.fromJson(world.linksJson)) {
            "World links are malformed"
        }
        val scenarioIds = memberships.filter { it.resourceType == WorldResourceType.SCENARIO.name }
            .map { it.resourceId }.toMutableSet()
        scenarioIds.addAll(scenarioSessions.map { it.scenarioId })
        scenarioIds.addAll(links.castRoles.mapNotNull { it.scenarioId })
        val scenarios = source.scenarios.filter { it.scenarioId in scenarioIds }

        val messages = source.messages.filter { it.chatId in includedChatIds }
        val messageIds = messages.map { it.messageId }.toSet()
        val chatParticipants = source.chatParticipants.filter { it.chatId in includedChatIds }
        val chatPersonaParticipants = source.chatPersonaParticipants.filter { it.chatId in includedChatIds }
        val memories = source.roleplayMemories.filter { it.chatId in includedChatIds }
        val npcDossiers = source.npcDossiers.filter { it.chatId in includedChatIds }
        val npcUpdates = source.npcDossierUpdates.filter { it.chatId in includedChatIds }

        val characterIds = memberships.filter { it.resourceType == WorldResourceType.CHARACTER.name }
            .map { it.resourceId }.toMutableSet()
        characterIds.addAll(chatParticipants.map { it.characterId })
        characterIds.addAll(messages.filter { it.speakerType == SpeakerType.CHARACTER }.map { it.speakerId })
        characterIds.addAll(memories.map { it.characterId })
        characterIds.addAll(links.castRoles.filter { it.roleType == "CHARACTER" }.map { it.resourceId })
        val scenarioDefinitions = scenarios.map { ScenarioCodec.decode(it.definitionJson) } +
            scenarioSessions.map { ScenarioCodec.decode(it.definitionJson) }
        characterIds.addAll(scenarioDefinitions.flatMap { definition ->
            definition.npcs.mapNotNull { it.sourceCharacterId }
        })
        val scopedRelationships = source.relationships.filter { it.scopeChatId in includedChatIds }
        characterIds.addAll(scopedRelationships.flatMap { listOf(it.sourceCharacterId, it.targetCharacterId) })

        val personaIds = memberships.filter { it.resourceType == WorldResourceType.PERSONA.name }
            .map { it.resourceId }.toMutableSet()
        personaIds.addAll(chatPersonaParticipants.map { it.personaId })
        personaIds.addAll(messages.filter { it.speakerType == SpeakerType.PERSONA }.map { it.speakerId })
        personaIds.addAll(links.castRoles.filter { it.roleType == "PERSONA" }.map { it.resourceId })

        fun parseKnowledgeMap(raw: String): Map<String, String> =
            requireNotNull(mapAdapter.fromJson(raw)) { "Invalid NPC knowledge map" }
        npcDossiers.forEach { npc ->
            characterIds.addAll(parseKnowledgeMap(npc.characterKnowledgeJson).keys)
            personaIds.addAll(parseKnowledgeMap(npc.personaRelationshipsJson).keys)
        }

        val selectedNpcLibraryEntries = links.castRoles
            .filter { it.roleType == "NPC_LIBRARY" }.map { it.resourceId }.toSet()
        val bankIds = memberships.filter { it.resourceType == WorldResourceType.NPC_LIBRARY_BANK.name }
            .map { it.resourceId }.toMutableSet()
        bankIds.addAll(source.npcLibraryEntries.filter {
            it.libraryNpcId in selectedNpcLibraryEntries
        }.map { it.bankId })
        val npcLibraryBanks = source.npcLibraryBanks.filter { it.bankId in bankIds }
        val npcLibraryEntries = source.npcLibraryEntries.filter { it.bankId in bankIds }
        npcLibraryEntries.forEach { npc ->
            characterIds.addAll(parseKnowledgeMap(npc.characterKnowledgeJson).keys)
            personaIds.addAll(parseKnowledgeMap(npc.personaRelationshipsJson).keys)
        }
        // Reusable relationships are included only if BOTH endpoints already belong to the
        // selected World, not because another World happens to share one Character.
        val reusableRelationships = source.relationships.filter {
            it.scopeChatId == null &&
                it.sourceCharacterId in characterIds &&
                it.targetCharacterId in characterIds
        }
        val relationships = scopedRelationships + reusableRelationships
        val characters = source.characters.filter { it.characterId in characterIds }
        val personas = source.personas.filter { it.personaId in personaIds }

        val lorebookIds = memberships.filter { it.resourceType == WorldResourceType.LOREBOOK.name }
            .map { it.resourceId }.toMutableSet()
        val loreAttachments = source.chatLorebookAttachments.filter { it.chatId in includedChatIds }
        lorebookIds.addAll(loreAttachments.map { it.lorebookId })
        val defaultBooks = requireNotNull(listAdapter.fromJson(world.defaultLorebookIdsJson)) {
            "World default Lorebook IDs are malformed"
        }
        lorebookIds.addAll(defaultBooks)
        val loreOverlays = source.chatLorebookEntryOverlays.filter { it.chatId in includedChatIds }
        val overlayEntryIds = loreOverlays.map { it.entryId }.toSet()
        lorebookIds.addAll(source.lorebookEntries.filter {
            it.entryId in overlayEntryIds
        }.map { it.lorebookId })
        val lorebooks = source.lorebooks.filter { it.lorebookId in lorebookIds }

        if (!privateDataReviewed) {
            require(characters.none { !it.authorNotesJson.isNullOrBlank() } &&
                personas.none { !it.privateNotesJson.isNullOrBlank() } &&
                npcDossiers.none { it.authorNotes.isNotBlank() || it.secrets.isNotBlank() } &&
                npcLibraryEntries.none { it.authorNotes.isNotBlank() || it.secrets.isNotBlank() } &&
                source.chatSettings.filter { it.chatId in includedChatIds }
                    .none { !it.draftMessage.isNullOrBlank() }
            ) {
                "World archive contains private notes, secrets or drafts. Require explicit inclusion review."
            }
        }

        val selectedAttachments = source.messageAttachments.filter { it.chatId in includedChatIds }
        val state = WorldArchiveStateV1(
            format = WorldArchiveStateCodec.FORMAT,
            schemaVersion = WorldArchiveStateCodec.SCHEMA_VERSION,
            worldId = worldId,
            world = world,
            memberships = memberships,
            cast = source.worldCast.filter { it.worldId == worldId },
            continuity = source.worldContinuityEvents.filter { it.worldId == worldId },
            characters = characters,
            personas = personas,
            scenarios = scenarios,
            scenarioSessions = scenarioSessions,
            chats = chats,
            chatParticipants = chatParticipants,
            chatPersonaParticipants = chatPersonaParticipants,
            messages = messages,
            chatSettings = source.chatSettings.filter { it.chatId in includedChatIds },
            generationMetadata = source.generationMetadata.filter { it.messageId in messageIds },
            roleplayMemories = memories,
            conversationSummaries = source.conversationSummaries.filter { it.chatId in includedChatIds },
            relationships = relationships,
            lorebooks = lorebooks,
            lorebookEntries = source.lorebookEntries.filter { it.lorebookId in lorebookIds },
            chatLorebookAttachments = loreAttachments,
            chatLorebookOverlays = loreOverlays,
            npcDossiers = npcDossiers,
            npcBankSettings = source.npcBankSettings.filter { it.chatId in includedChatIds },
            npcUpdates = npcUpdates,
            npcLibraryBanks = npcLibraryBanks,
            npcLibraryEntries = npcLibraryEntries,
            messageAttachments = selectedAttachments.map { attachment ->
                WorldArchiveMessageAttachmentV1(
                    attachmentId = attachment.attachmentId,
                    chatId = attachment.chatId,
                    messageId = attachment.messageId,
                    assetPath = "assets/message-attachments/${attachment.attachmentId}",
                    mimeType = attachment.mimeType,
                    byteSize = attachment.byteSize,
                    pixelWidth = attachment.pixelWidth,
                    pixelHeight = attachment.pixelHeight,
                    sha256 = attachment.sha256,
                    sortPosition = attachment.sortPosition,
                    createdAt = attachment.createdAt
                )
            }
        )
        WorldArchiveStateCodec.validate(state)
        return state
    }
}
