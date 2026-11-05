package com.example

import com.example.data.model.*
import com.example.domain.backup.BackupAppearanceSettings
import com.example.domain.backup.BackupPayload
import com.example.domain.world.*
import org.junit.Assert.*
import org.junit.Test

class WorldArchiveHistoryTest {
    private val world = WorldEntity(worldId = "world-a", title = "The Moonlit City")
    private val cast = WorldCastEntity(worldCharacterId = "cast-a", worldId = world.worldId, name = "Varka")
    private val chat = ChatEntity(chatId = "chat-a", displayName = "Varka & Piper", chatType = ChatType.SOLO)
    private val persona = PersonaEntity(
        personaId = "persona-a", displayName = "Piper", personaName = "Piper", aliases = emptyList(),
        importSchemaVersion = 1, identityJson = null, appearanceJson = null, personalityJson = null,
        roleplayProfileJson = null, backgroundJson = null, worldContextJson = null,
        privateNotesJson = null, extensionFieldsJson = null
    )
    private val character = CharacterEntity(
        characterId = "character-a", displayName = "Varka", characterName = "Varka",
        aliases = emptyList(), importSchemaVersion = 1, identityJson = null, appearanceJson = null,
        personalityJson = null, voiceJson = null, behaviorJson = null, backstoryJson = null,
        knowledgeJson = null, worldContextJson = null, writingRulesJson = null,
        examplesJson = null, authorNotesJson = null, extensionFieldsJson = null
    )
    private val playerTurn = MessageEntity(
        messageId = "message-player", chatId = chat.chatId,
        speakerType = SpeakerType.PERSONA, speakerId = persona.personaId,
        speakerDisplayNameSnapshot = "Piper", content = "Are you home?", orderIndex = 1
    )
    private val reply = MessageEntity(
        messageId = "message-bot", chatId = chat.chatId,
        speakerType = SpeakerType.CHARACTER, speakerId = character.characterId,
        speakerDisplayNameSnapshot = "Varka", content = "Yes, darling.", orderIndex = 2
    )
    private val alternate = reply.copy(
        messageId = "message-bot-variant", variantGroupId = reply.messageId,
        content = "I am now.", isPrimaryVariant = false
    )
    private val npc = NpcDossierEntity(
        npcId = "npc-a", chatId = chat.chatId, displayName = "The Porter"
    )
    private val npcUpdate = NpcDossierUpdateEntity(
        updateId = "npc-revision-1", chatId = chat.chatId, npcId = npc.npcId,
        sourceMessageId = alternate.messageId, variantGroupId = reply.messageId,
        sourceOrderIndex = 2, sourceRevision = 1,
        fieldName = "goals", oldValue = null,
        newValue = "Find the carriage", isApplied = false
    )
    private val book = LorebookEntity(lorebookId = "book-a", name = "Mondstadt")
    private val entry = LorebookEntryEntity(entryId = "lore-a", lorebookId = book.lorebookId, title = "The City")
    private val attachment = WorldArchiveMessageAttachmentV1(
        attachmentId = "image-a", chatId = chat.chatId, messageId = playerTurn.messageId,
        assetPath = "assets/message-attachments/image-a",
        mimeType = "image/png", byteSize = 100L, pixelWidth = 100, pixelHeight = 100,
        sha256 = "b".repeat(64), sortPosition = 0, createdAt = 1L
    )
    private fun state() = WorldArchiveStateV1(
        format = WorldArchiveStateCodec.FORMAT,
        schemaVersion = WorldArchiveStateCodec.SCHEMA_VERSION,
        worldId = world.worldId,
        world = world,
        memberships = listOf(
            WorldMembershipEntity(world.worldId, "CHAT", chat.chatId),
            WorldMembershipEntity(world.worldId, "LOREBOOK", book.lorebookId)
        ),
        cast = listOf(cast),
        continuity = listOf(
            WorldContinuityEventEntity(
                eventId = "event-1", worldId = world.worldId, title = "City Charter",
                facts = "A new law passed.", status = "ACCEPTED",
                sourceChatId = chat.chatId, relatedCastIdsJson = "[\"cast-a\"]"
            )
        ),
        characters = listOf(character),
        personas = listOf(persona),
        chats = listOf(chat),
        chatParticipants = listOf(ChatParticipantEntity(chat.chatId, character.characterId)),
        chatPersonaParticipants = listOf(ChatPersonaParticipantEntity(chat.chatId, persona.personaId)),
        messages = listOf(playerTurn, reply, alternate),
        chatSettings = listOf(ChatSettingsEntity(chat.chatId)),
        generationMetadata = listOf(GenerationMetadataEntity(reply.messageId, requestedModelId = "mock")),
        roleplayMemories = listOf(
            RoleplayMemoryEntity(memoryId = "memory-1", chatId = chat.chatId,
                characterId = character.characterId, category = MemoryCategory.PROMISE,
                content = "Promised to be home", sourceMessageId = playerTurn.messageId)
        ),
        lorebooks = listOf(book),
        lorebookEntries = listOf(entry),
        chatLorebookAttachments = listOf(ChatLorebookAttachmentEntity(chat.chatId, book.lorebookId)),
        chatLorebookOverlays = listOf(
            ChatLorebookEntryOverlayEntity(
                chatId = chat.chatId, entryId = entry.entryId,
                hasCurrentOverride = true, currentContent = "The current city law"
            )
        ),
        npcDossiers = listOf(npc),
        npcBankSettings = listOf(NpcBankSettingsEntity(chat.chatId, true)),
        npcUpdates = listOf(npcUpdate),
        messageAttachments = listOf(attachment)
    )

    @Test
    fun roundTripPreservesBothVariantsAndExactNpcRevisionProvenance() {
        val original = state()
        val encoded = WorldArchiveStateCodec.encode(original)
        assertFalse(encoded.contains("relativePath"))
        val decoded = WorldArchiveStateCodec.parse(encoded)
        assertEquals(original, decoded)
        assertEquals(2, decoded.messages.count { it.variantGroupId == reply.messageId })
        assertEquals(alternate.messageId, decoded.npcUpdates.single().sourceMessageId)
        assertEquals(1, decoded.npcUpdates.single().sourceRevision)
        assertEquals("The current city law", decoded.chatLorebookOverlays.single().currentContent)
    }

    @Test
    fun rejectsCrossWorldChatsAndOrphanedNpcSourceVariantsAndMemories() {
        val original = state()
        val foreign = chat.copy(chatId = "chat-other")
        assertThrows(IllegalArgumentException::class.java) {
            WorldArchiveStateCodec.validate(original.copy(chats = original.chats + foreign))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorldArchiveStateCodec.validate(original.copy(
                npcUpdates = listOf(npcUpdate.copy(sourceMessageId = "deleted-message"))
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorldArchiveStateCodec.validate(original.copy(
                roleplayMemories = original.roleplayMemories.map {
                    it.copy(sourceMessageId = "some-other-chat")
                }
            ))
        }
    }

    @Test
    fun rejectsVariantGroupWithoutOnePrimaryAndUnresolvedLorebookCurrent() {
        val original = state()
        assertThrows(IllegalArgumentException::class.java) {
            WorldArchiveStateCodec.validate(original.copy(messages = original.messages.map {
                if (it.variantGroupId == reply.messageId) it.copy(isPrimaryVariant = false)
                else it
            }))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorldArchiveStateCodec.validate(original.copy(
                chatLorebookOverlays = listOf(ChatLorebookEntryOverlayEntity(chat.chatId, "unknown-lore"))
            ))
        }
    }

    @Test
    fun remappingPlanSeparatesPersonaAndCharacterAndKeepsVariantMessageAlias() {
        val original = state()
        val first = WorldArchiveIdPlanner.plan(original, namespace = "import-session-123456")
        val again = WorldArchiveIdPlanner.plan(original, namespace = "import-session-123456")
        val other = WorldArchiveIdPlanner.plan(original, namespace = "import-session-654321")

        assertEquals(first, again)
        assertNotEquals(first.get(WorldArchiveIdKind.CHAT, chat.chatId),
            other.get(WorldArchiveIdKind.CHAT, chat.chatId))
        assertNotEquals(first.get(WorldArchiveIdKind.PERSONA, persona.personaId),
            first.get(WorldArchiveIdKind.CHARACTER, character.characterId))
        assertEquals(first.get(WorldArchiveIdKind.MESSAGE, reply.messageId),
            first.variantGroupId(reply.messageId))
        assertEquals(first.get(WorldArchiveIdKind.MESSAGE, reply.messageId),
            first.metadataMessageId(reply.messageId))
        assertNotEquals(first.get(WorldArchiveIdKind.MESSAGE, alternate.messageId),
            first.variantGroupId(reply.messageId))
        assertThrows(IllegalArgumentException::class.java) {
            WorldArchiveIdPlanner.plan(original, "import-session-123456", reservedIds = mapOf(
                WorldArchiveIdKind.CHAT to setOf(first.get(WorldArchiveIdKind.CHAT, chat.chatId))
            ))
        }
    }

    @Test
    fun manifestMatchesEveryAttachmentAndRejectsOrphanedAssets() {
        val original = state()
        fun entry(path: String, kind: WorldArchiveEntryKind, sha: String = "a".repeat(64),
                  size: Long = 100L, owner: String? = null) =
            WorldArchiveEntryV1(path, kind, size, sha, owner)
        val manifest = WorldArchiveManifestV1(
            format = WorldArchiveManifestCodec.FORMAT,
            schemaVersion = WorldArchiveManifestCodec.SCHEMA_VERSION,
            worldId = world.worldId,
            createdAt = 1L,
            entries = listOf(
                entry(WorldArchiveManifestCodec.DEFINITION_PATH, WorldArchiveEntryKind.WORLD_DEFINITION),
                entry(WorldArchiveManifestCodec.STATE_PATH, WorldArchiveEntryKind.WORLD_STATE),
                entry(attachment.assetPath, WorldArchiveEntryKind.MESSAGE_ATTACHMENT,
                    attachment.sha256, attachment.byteSize, attachment.attachmentId)
            )
        )
        WorldArchiveStateCodec.validateWithManifest(original, manifest)
        assertThrows(IllegalArgumentException::class.java) {
            WorldArchiveStateCodec.validateWithManifest(original,
                manifest.copy(entries = manifest.entries.dropLast(1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorldArchiveStateCodec.validateWithManifest(original,
                manifest.copy(entries = manifest.entries.dropLast(1) +
                    entry(attachment.assetPath, WorldArchiveEntryKind.MESSAGE_ATTACHMENT,
                        attachment.sha256, attachment.byteSize, "wrong-owner")))
        }
    }

    @Test
    fun coOpMetadataCannotBeCarriedIntoAnotherActiveAppIdentity() {
        val original = state()
        assertThrows(IllegalArgumentException::class.java) {
            WorldArchiveStateCodec.validate(original.copy(messages = original.messages.map {
                if (it.messageId == reply.messageId) it.copy(responsibleDeviceId = "private-device-id")
                else it
            }))
        }
    }

    @Test
    fun selectorRejectsUnrelatedChatsAndCollectsOnlyWorldDependencies() {
        val unrelatedChat = chat.copy(chatId = "chat-b", displayName = "Other universe")
        val unrelatedMessage = playerTurn.copy(messageId = "other-message", chatId = unrelatedChat.chatId)
        val source = sourcePayload().copy(
            worlds = listOf(world, world.copy(worldId = "world-b")),
            worldMemberships = listOf(
                WorldMembershipEntity("world-a", "CHAT", chat.chatId),
                WorldMembershipEntity("world-b", "CHAT", unrelatedChat.chatId)
            ),
            chats = listOf(chat, unrelatedChat),
            messages = listOf(playerTurn, reply, alternate, unrelatedMessage),
            chatSettings = listOf(ChatSettingsEntity(chat.chatId), ChatSettingsEntity(unrelatedChat.chatId)),
            chatParticipants = listOf(ChatParticipantEntity(chat.chatId, character.characterId)),
            chatPersonaParticipants = listOf(ChatPersonaParticipantEntity(chat.chatId, persona.personaId))
        )
        val selected = WorldArchiveSnapshotSelector.select(source, "world-a")
        assertEquals(listOf(chat.chatId), selected.chats.map { it.chatId })
        assertEquals(setOf(playerTurn.messageId, reply.messageId, alternate.messageId),
            selected.messages.map { it.messageId }.toSet())
        assertEquals(setOf(character.characterId), selected.characters.map { it.characterId }.toSet())
        assertEquals(setOf(persona.personaId), selected.personas.map { it.personaId }.toSet())
        assertThrows(IllegalArgumentException::class.java) {
            WorldArchiveSnapshotSelector.select(source, "world-a", selectedChatIds = setOf(unrelatedChat.chatId))
        }
    }

    @Test
    fun selectorRequiresExplicitReviewForPrivateRoleplayNotes() {
        val privateCharacter = character.copy(authorNotesJson = "{\"private\":\"do not share\"}")
        val source = sourcePayload().copy(characters = listOf(privateCharacter))
        assertThrows(IllegalArgumentException::class.java) {
            WorldArchiveSnapshotSelector.select(source, "world-a")
        }
        val accepted = WorldArchiveSnapshotSelector.select(
            source, "world-a", privateDataReviewed = true
        )
        assertEquals(privateCharacter.authorNotesJson, accepted.characters.single().authorNotesJson)
    }

    private fun sourcePayload() = BackupPayload(
        characters = listOf(character), personas = listOf(persona),
        chats = listOf(chat),
        chatParticipants = listOf(ChatParticipantEntity(chat.chatId, character.characterId)),
        chatPersonaParticipants = listOf(ChatPersonaParticipantEntity(chat.chatId, persona.personaId)),
        messages = listOf(playerTurn, reply, alternate), relationships = emptyList(),
        chatSettings = listOf(ChatSettingsEntity(chat.chatId)), generationMetadata = emptyList(),
        roleplayMemories = emptyList(), conversationSummaries = emptyList(), lorebooks = emptyList(),
        lorebookEntries = emptyList(), chatLorebookAttachments = emptyList(),
        chatLorebookEntryOverlays = emptyList(), coopRooms = emptyList(), coopParticipants = emptyList(),
        coopPersonaOwnerships = emptyList(), coopEvents = emptyList(),
        coopMessageTombstones = emptyList(), messageAttachments = emptyList(),
        npcDossiers = emptyList(), npcBankSettings = emptyList(), npcDossierUpdates = emptyList(),
        appearance = BackupAppearanceSettings("SYSTEM", 1.0f, "DEFAULT"),
        worlds = listOf(world),
        worldMemberships = listOf(WorldMembershipEntity("world-a", "CHAT", chat.chatId)),
        worldCast = listOf(cast)
    )
}
