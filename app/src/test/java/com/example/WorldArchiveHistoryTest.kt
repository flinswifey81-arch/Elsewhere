package com.example

import com.example.data.model.*
import com.example.domain.backup.BackupAppearanceSettings
import com.example.domain.backup.BackupPayload
import com.example.domain.world.*
import com.example.domain.story.StoryResponseCodec
import com.example.domain.story.StoryResponse
import com.example.domain.story.StorySegment
import com.example.domain.story.StorySegmentType
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
        assertNotEquals("memory-1", first.get(WorldArchiveIdKind.MEMORY, "memory-1"))
        assertNotEquals(first.get(WorldArchiveIdKind.MESSAGE, alternate.messageId),
            first.variantGroupId(reply.messageId))
        assertThrows(IllegalArgumentException::class.java) {
            WorldArchiveIdPlanner.plan(original, "import-session-123456", reservedIds = mapOf(
                WorldArchiveIdKind.CHAT to setOf(first.get(WorldArchiveIdKind.CHAT, chat.chatId))
            ))
        }
    }

    @Test
    fun semanticImportRewritePreservesVariantNpcLoreAndMemoryLinks() {
        val encoded = StoryResponseCodec.encode(StoryResponse(segments = listOf(
            StorySegment(StorySegmentType.CHARACTER, "Come home, darling.",
                speakerId = character.characterId, speakerName = "Varka"),
            StorySegment(StorySegmentType.NPC, "The carriage is ready.",
                speakerId = npc.npcId, speakerName = "The Porter")
        )))
        val original = state().copy(
            messages = state().messages.map { if (it.messageId == reply.messageId) {
                it.copy(content = encoded)
            } else it },
            npcUpdates = listOf(npcUpdate.copy(
                fieldName = "persona_relationship:persona-a", newValue = "Believes Piper is brave"
            )),
            npcDossiers = listOf(npc.copy(
                personaRelationshipsJson = "{\"persona-a\":\"trusted\"}",
                characterKnowledgeJson = "{\"character-a\":\"the commissioner\"}"
            ))
        )
        val plan = WorldArchiveIdPlanner.plan(original, "world-import-operation-123456")
        val result = WorldArchiveSemanticRemapper.rewrite(original, plan)
        val mapped = result.state
        WorldArchiveStateCodec.validate(mapped)

        assertNotEquals(original.worldId, mapped.worldId)
        assertEquals(plan.get(WorldArchiveIdKind.CHAT, chat.chatId), mapped.chats.single().chatId)
        val mappedReply = mapped.messages.single { it.messageId == plan.get(WorldArchiveIdKind.MESSAGE, reply.messageId) }
        val mappedAlternative = mapped.messages.single { it.messageId ==
            plan.get(WorldArchiveIdKind.MESSAGE, alternate.messageId) }
        assertEquals(mappedReply.messageId, mappedAlternative.variantGroupId)
        assertEquals(1, mapped.messages.count { it.variantGroupId == mappedReply.messageId && it.isPrimaryVariant })
        assertEquals("Are you home?", mapped.messages.single {
            it.messageId == plan.get(WorldArchiveIdKind.MESSAGE, playerTurn.messageId)
        }.content)

        val segments = requireNotNull(StoryResponseCodec.decode(mappedReply.content)).segments
        assertEquals("Come home, darling.", segments[0].content)
        assertEquals(plan.get(WorldArchiveIdKind.CHARACTER, character.characterId), segments[0].speakerId)
        assertEquals(plan.get(WorldArchiveIdKind.NPC_DOSSIER, npc.npcId), segments[1].speakerId)
        assertEquals("The carriage is ready.", segments[1].content)

        assertEquals(plan.get(WorldArchiveIdKind.MEMORY, "memory-1"), mapped.roleplayMemories.single().memoryId)
        assertEquals(plan.get(WorldArchiveIdKind.MESSAGE, playerTurn.messageId),
            mapped.roleplayMemories.single().sourceMessageId)
        assertEquals(plan.get(WorldArchiveIdKind.MESSAGE, alternate.messageId),
            mapped.npcUpdates.single().sourceMessageId)
        assertEquals(plan.variantGroupId(reply.messageId), mapped.npcUpdates.single().variantGroupId)
        assertEquals("persona_relationship:" + plan.get(WorldArchiveIdKind.PERSONA, persona.personaId),
            mapped.npcUpdates.single().fieldName)
        assertTrue(mapped.npcDossiers.single().personaRelationshipsJson.contains(
            plan.get(WorldArchiveIdKind.PERSONA, persona.personaId)))
        assertTrue(mapped.npcDossiers.single().characterKnowledgeJson.contains(
            plan.get(WorldArchiveIdKind.CHARACTER, character.characterId)))
        assertEquals(plan.get(WorldArchiveIdKind.LOREBOOK_ENTRY, entry.entryId),
            mapped.chatLorebookOverlays.single().entryId)
        assertEquals(plan.get(WorldArchiveIdKind.CONTINUITY_EVENT, "event-1"),
            mapped.continuity.single().eventId)
        assertEquals(mapOf(attachment.assetPath to mapped.messageAttachments.single().assetPath),
            result.attachmentFileMoves)
    }

    @Test
    fun sameNpcIdInTwoScenarioTemplatesGetsTwoDistinctImportedIdentities() {
        fun scenario(id: String) = ScenarioEntity(
            scenarioId = id,
            definitionJson = ScenarioCodec.encode(ScenarioDefinition(
                title = id, premise = "The same NPC label appears independently",
                npcs = listOf(ScenarioNpc(npcId = "npc-1", name = "Messenger"))
            ))
        )
        val original = state().copy(
            scenarios = listOf(scenario("scenario-one"), scenario("scenario-two")),
            memberships = state().memberships + listOf(
                WorldMembershipEntity(world.worldId, "SCENARIO", "scenario-one"),
                WorldMembershipEntity(world.worldId, "SCENARIO", "scenario-two")
            )
        )
        val plan = WorldArchiveIdPlanner.plan(original, "unique-world-import-567890")
        assertNotEquals(plan.scenarioNpcId("scenario-one", "npc-1"),
            plan.scenarioNpcId("scenario-two", "npc-1"))

        val mapped = WorldArchiveSemanticRemapper.rewrite(original, plan).state
        assertEquals(2, mapped.scenarios.size)
        val mappedNpcIds = mapped.scenarios.map {
            ScenarioCodec.decode(it.definitionJson).npcs.single().npcId
        }
        assertEquals(2, mappedNpcIds.distinct().size)
        assertFalse(mappedNpcIds.contains("npc-1"))
    }

    @Test
    fun structuredStoryWithUnparseableControlDataFailsClosedBeforeImport() {
        val broken = state().copy(messages = state().messages.map {
            if (it.messageId == reply.messageId)
                it.copy(content = "\u001eelsewhere-story-v1\n{broken")
            else it
        })
        val plan = WorldArchiveIdPlanner.plan(broken, "broken-world-archive-12345")
        assertThrows(IllegalArgumentException::class.java) {
            WorldArchiveSemanticRemapper.rewrite(broken, plan)
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
    fun selectorKeepsScenarioSessionSnapshotAndDoesNotRequireAPlayerTurn() {
        val sceneChat = chat.copy(chatId = "scenario-chat", chatType = ChatType.SCENARIO)
        val definition = ScenarioDefinition(
            title = "Episode One", premise = "A strange arrival",
            npcs = listOf(ScenarioNpc(
                npcId = "scene-npc", name = "Courier", sourceCharacterId = character.characterId
            ))
        )
        val template = ScenarioEntity(
            scenarioId = "scenario-template",
            definitionJson = ScenarioCodec.encode(definition)
        )
        val narrator = MessageEntity(
            messageId = "narrator-1", chatId = sceneChat.chatId,
            speakerType = SpeakerType.NARRATOR, speakerId = "narrator",
            speakerDisplayNameSnapshot = "Narrator",
            content = "A letter arrives at dawn.", orderIndex = 1
        )
        val source = sourcePayload().copy(
            chats = listOf(sceneChat),
            worldMemberships = listOf(WorldMembershipEntity(world.worldId, "CHAT", sceneChat.chatId)),
            scenarios = listOf(template),
            scenarioSessions = listOf(ScenarioSessionEntity(
                chatId = sceneChat.chatId, scenarioId = template.scenarioId,
                definitionJson = template.definitionJson
            )),
            messages = listOf(narrator),
            chatSettings = listOf(ChatSettingsEntity(sceneChat.chatId)),
            chatParticipants = emptyList(),
            chatPersonaParticipants = emptyList()
        )
        val state = WorldArchiveSnapshotSelector.select(source, world.worldId)
        assertEquals(1, state.scenarioSessions.size)
        assertEquals(definition, ScenarioCodec.decode(state.scenarioSessions.single().definitionJson))
        assertEquals(1, state.messages.size)
        assertEquals(SpeakerType.NARRATOR, state.messages.single().speakerType)

        assertThrows(IllegalArgumentException::class.java) {
            WorldArchiveSnapshotSelector.select(source.copy(scenarios = emptyList()), world.worldId)
        }
    }

    @Test
    fun selectorCannotDiscardActiveCoopStateSilently() {
        val room = CoopRoomEntity(
            chatId = chat.chatId, localRole = CoopRole.HOST,
            hostParticipantId = "host", localParticipantId = "host"
        )
        assertThrows(IllegalArgumentException::class.java) {
            WorldArchiveSnapshotSelector.select(
                sourcePayload().copy(coopRooms = listOf(room)), world.worldId
            )
        }
    }

    @Test
    fun selectorFreezesInheritedEnginePreferencesWithoutModifyingAppDefaults() {
        val source = sourcePayload().copy(
            chatSettings = listOf(ChatSettingsEntity(
                chatId = chat.chatId, useGlobalEngineDefaults = true
            )),
            engineDefaults = listOf(EngineDefaultsEntity(
                selectedModelId = "selected/user-model", temperature = 0.7f
            ))
        )
        val state = WorldArchiveSnapshotSelector.select(source, world.worldId)
        assertEquals("selected/user-model", state.chatSettings.single().selectedModelId)
        assertEquals(0.7f, state.chatSettings.single().temperature ?: 0f, 0.001f)
        assertFalse(state.chatSettings.single().useGlobalEngineDefaults)
        assertTrue(source.chatSettings.single().useGlobalEngineDefaults)
        assertThrows(IllegalArgumentException::class.java) {
            WorldArchiveSnapshotSelector.select(source.copy(engineDefaults = emptyList()), world.worldId)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorldArchiveStateCodec.validate(
                state.copy(chatSettings = listOf(ChatSettingsEntity(
                    chatId = chat.chatId, useGlobalEngineDefaults = true
                )))
            )
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
