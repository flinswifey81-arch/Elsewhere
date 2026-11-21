package com.example

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.data.APP_DATABASE_VERSION
import com.example.data.AppDatabase
import com.example.data.model.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Compiled and executed ONLY in a separately checked-out, pinned real v15
 * source tree by the disposable v15→v21 CI job. Never compile this file in v21.
 *
 * Room v15 itself creates the complete v15 schema/identity hash and all
 * indexes/FKs. No PRAGMA user_version spoofing, reconstructed DDL, private
 * transcripts, production APK, or provider use.
 */
@RunWith(RobolectricTestRunner::class)
class FullV15RoomFixtureExportTest {
    @Test fun exportRoom15DatabaseWithLinkedSyntheticRows() = runBlocking {
        assertEquals(15, APP_DATABASE_VERSION)
        val destination = requireNotNull(System.getenv("ELSEWHERE_V15_FIXTURE_PATH")) {
            "The isolated CI runner must provide a disposable fixture path."
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "qa-v15-native-schema.db"
        context.deleteDatabase(name)
        val database = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .allowMainThreadQueries().build()
        try {
            database.openHelper.writableDatabase
            database.characterDao().insertCharacter(CharacterEntity(
                characterId = "rowan", displayName = "Rowan", characterName = "Rowan",
                aliases = listOf("The Scholar"), importSchemaVersion = 1,
                identityJson = "{\"name\":\"Rowan\"}",
                appearanceJson = null, personalityJson = null, voiceJson = null,
                behaviorJson = null, backstoryJson = null, knowledgeJson = null,
                worldContextJson = null, writingRulesJson = null, examplesJson = null,
                authorNotesJson = null, extensionFieldsJson = null
            ))
            database.characterDao().insertCharacter(CharacterEntity(
                characterId = "iris", displayName = "Iris", characterName = "Iris",
                aliases = emptyList(), importSchemaVersion = 1,
                identityJson = null, appearanceJson = null, personalityJson = null,
                voiceJson = null, behaviorJson = null, backstoryJson = null,
                knowledgeJson = null, worldContextJson = null, writingRulesJson = null,
                examplesJson = null, authorNotesJson = null, extensionFieldsJson = null
            ))
            database.personaDao().insertPersona(PersonaEntity(
                personaId = "mara", displayName = "Mara", personaName = "Mara",
                aliases = listOf("Player"), importSchemaVersion = 1,
                identityJson = "{\"name\":\"Mara\"}",
                appearanceJson = null, personalityJson = null,
                roleplayProfileJson = null, backgroundJson = null,
                worldContextJson = null, privateNotesJson = null,
                extensionFieldsJson = null
            ))
            database.chatDao().insertChat(ChatEntity(
                chatId = "qa-solo", displayName = "Synthetic Solo",
                chatType = ChatType.SOLO, createdAt = 100L, updatedAt = 800L,
                contextResetOrderIndex = 3L
            ))
            database.chatDao().insertChat(ChatEntity(
                chatId = "qa-group", displayName = "Synthetic Group",
                chatType = ChatType.GROUP, createdAt = 100L, updatedAt = 900L
            ))
            database.chatDao().insertChatParticipant(ChatParticipantEntity(
                chatId = "qa-solo", characterId = "rowan", sortPosition = 0
            ))
            database.chatDao().insertChatPersonaParticipant(ChatPersonaParticipantEntity(
                chatId = "qa-solo", personaId = "mara"
            ))
            for ((i, actor) in listOf("rowan", "iris").withIndex()) {
                database.chatDao().insertChatParticipant(ChatParticipantEntity(
                    chatId = "qa-group", characterId = actor, sortPosition = i
                ))
            }
            database.chatDao().insertChatPersonaParticipant(ChatPersonaParticipantEntity(
                chatId = "qa-group", personaId = "mara"
            ))
            database.chatDao().insertChatSettings(ChatSettingsEntity(
                chatId = "qa-solo", selectedModelId = "offline/fake",
                draftMessage = "UNSENT_DRAFT_UNALTERED",
                narratorEnabled = true, narratorInstructions = "Old custom narrator guidance"
            ))
            database.chatDao().insertChatSettings(ChatSettingsEntity(
                chatId = "qa-group", selectedModelId = "offline/fake",
                responseLengthProfile = ResponseLengthProfile.LONG
            ))
            database.messageDao().insertMessage(MessageEntity(
                messageId = "qa-turn1", chatId = "qa-solo",
                speakerType = SpeakerType.PERSONA, speakerId = "mara",
                speakerDisplayNameSnapshot = "Mara",
                content = "I keep the copper key.", orderIndex = 1L,
                createdAt = 101L, variantGroupId = "turn1-variants",
                isPrimaryVariant = false
            ))
            database.messageDao().insertMessage(MessageEntity(
                messageId = "qa-turn1-selected", chatId = "qa-solo",
                speakerType = SpeakerType.PERSONA, speakerId = "mara",
                speakerDisplayNameSnapshot = "Mara",
                content = "I keep the silver key.", orderIndex = 1L,
                createdAt = 102L, variantGroupId = "turn1-variants",
                isPrimaryVariant = true, editedAt = 103L
            ))
            database.messageDao().insertMessage(MessageEntity(
                messageId = "qa-turn2", chatId = "qa-solo",
                speakerType = SpeakerType.CHARACTER, speakerId = "rowan",
                speakerDisplayNameSnapshot = "Rowan",
                content = "The door opens.", orderIndex = 2L, createdAt = 104L
            ))
            database.messageDao().insertMessage(MessageEntity(
                messageId = "qa-group-turn", chatId = "qa-group",
                speakerType = SpeakerType.PERSONA, speakerId = "mara",
                speakerDisplayNameSnapshot = "Mara",
                content = "We search the moonlit hall.", orderIndex = 1L, createdAt = 105L
            ))
            database.messageDao().insertGenerationMetadata(GenerationMetadataEntity(
                messageId = "qa-turn2", requestedModelId = "offline/fake",
                promptTokens = 120, completionTokens = 44, totalTokens = 164,
                reportedCost = 0.00001, provider = "offline-mock"
            ))
            database.memoryDao().insertMemory(RoleplayMemoryEntity(
                memoryId = "qa-legacy-memory", chatId = "qa-solo",
                characterId = "rowan", category = MemoryCategory.PROMISE,
                content = "The key belongs to Mara.", sourceMessageId = "qa-turn1-selected",
                createdAt = 200L, updatedAt = 201L
            ))
            database.memoryDao().upsertSummary(ConversationSummaryEntity(
                chatId = "qa-solo",
                summary = "The key changed hands; the copper variant was discarded.",
                summarizedThroughOrderIndex = 2L, updatedAt = 202L
            ))
            database.messageAttachmentDao().insert(MessageAttachmentEntity(
                attachmentId = "qa-image-metadata", chatId = "qa-solo",
                messageId = "qa-turn1-selected", relativePath = "qa-synthetic/picture.png",
                mimeType = "image/png", byteSize = 8,
                pixelWidth = 1, pixelHeight = 1,
                sha256 = "qa-dummy-attachment-digest", sortPosition = 0
            ))
            database.lorebookDao().insertLorebook(LorebookEntity(
                lorebookId = "qa-lore", name = "Synthetic Legends"
            ))
            database.lorebookDao().insertEntry(LorebookEntryEntity(
                entryId = "qa-lore-entry", lorebookId = "qa-lore",
                title = "Silver key", canonContent = "The key is silver.",
                currentContent = "The key is silver.", keys = listOf("key")
            ))
            database.lorebookDao().upsertAttachment(ChatLorebookAttachmentEntity(
                chatId = "qa-solo", lorebookId = "qa-lore"
            ))
            database.lorebookDao().upsertOverlay(ChatLorebookEntryOverlayEntity(
                chatId = "qa-solo", entryId = "qa-lore-entry",
                manualActive = true, hasCurrentOverride = true,
                currentContent = "The key is engraved."
            ))
            val definition = ScenarioCodec.encode(ScenarioDefinition(
                title = "Old Castle", premise = "Explore the castle together."
            ))
            database.scenarioDao().save(ScenarioEntity(
                scenarioId = "qa-scenario", definitionJson = definition
            ))
            database.scenarioDao().insertSession(ScenarioSessionEntity(
                chatId = "qa-group", scenarioId = "qa-scenario",
                definitionJson = definition
            ))
            database.worldDao().insertWorld(WorldEntity(
                worldId = "qa-world", title = "Synthetic World",
                worldRules = "People retain separate knowledge."
            ))
            database.worldDao().insertMembership(WorldMembershipEntity(
                worldId = "qa-world", resourceType = "CHAT", resourceId = "qa-solo"
            ))
            database.worldDao().insertCastMember(WorldCastEntity(
                worldCharacterId = "qa-cast", worldId = "qa-world", name = "The Archivist"
            ))
            database.worldDao().insertEvent(WorldContinuityEventEntity(
                eventId = "qa-world-event", worldId = "qa-world",
                title = "Unapproved rumor", facts = "The old bell rang.",
                status = "DRAFT", sourceChatId = "qa-solo"
            ))
            database.npcDao().insertDossier(NpcDossierEntity(
                npcId = "qa-npc", chatId = "qa-solo", displayName = "Keeper",
                secrets = "PRIVATE_NPC_NOT_CANON", authorNotes = "Synthetic private note"
            ))
            database.npcDao().upsertSettings(NpcBankSettingsEntity(
                chatId = "qa-solo", isEnabled = true
            ))
            database.npcDao().insertUpdates(listOf(NpcDossierUpdateEntity(
                updateId = "qa-npc-update", chatId = "qa-solo", npcId = "qa-npc",
                sourceMessageId = "qa-turn2", variantGroupId = "qa-turn2",
                sourceOrderIndex = 2L, sourceRevision = 0,
                fieldName = "role", oldValue = null, newValue = "Guide",
                isApplied = true
            )))
            database.npcDao().insertLibraryBank(NpcLibraryBankEntity(
                bankId = "qa-npc-library", name = "Synthetic NPcs"
            ))
            database.npcDao().insertLibraryEntries(listOf(NpcLibraryEntryEntity(
                libraryNpcId = "qa-library-keeper", bankId = "qa-npc-library",
                displayName = "Reusable Keeper"
            )))
            database.coopDao().upsertRoom(CoopRoomEntity(
                roomId = "qa-coop", chatId = "qa-group",
                localRole = CoopRole.HOST,
                hostParticipantId = "qa-host", localParticipantId = "qa-host"
            ))
            database.coopDao().upsertParticipant(CoopParticipantEntity(
                participantId = "qa-host", roomId = "qa-coop",
                deviceId = "synthetic-device", displayName = "Host", isHost = true
            ))
            database.coopDao().upsertPersonaOwnership(CoopPersonaOwnershipEntity(
                roomId = "qa-coop", personaId = "mara",
                participantId = "qa-host", personaDisplayNameSnapshot = "Mara"
            ))
            database.coopDao().insertEvent(CoopEventEntity(
                eventId = "qa-event", roomId = "qa-coop",
                sequence = 1L, clientEventId = "qa-client-event",
                originParticipantId = "qa-host",
                eventType = CoopEventType.MESSAGE_UPSERT,
                payloadJson = "{}"
            ))
            database.coopDao().insertMessageTombstone(CoopMessageTombstoneEntity(
                roomId = "qa-coop", messageId = "deleted-synthetic-turn",
                deletedSequence = 2L, deletedAt = 500L
            ))
            val sql = database.openHelper.writableDatabase
            // The entire legacy schema comes from Room's original generated
            // implementation. The single relationship table has a stable,
            // simple standalone record as well.
            sql.execSQL(
                "INSERT INTO relationships " +
                    "(relationshipId,sourceCharacterId,targetCharacterId,targetNameSnapshot," +
                    "relationshipType,dynamic,status,scopeChatId) VALUES (?,?,?,?,?,?,?,?)",
                arrayOf("qa-rel", "rowan", "iris", "Iris",
                    "ally", "Professional trust", "active", "qa-group")
            )
            val count = sql.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' " +
                    "AND name NOT LIKE 'sqlite_%' AND name != 'room_master_table'"
            ).use { c -> assertTrue(c.moveToFirst()); c.getInt(0) }
            assertTrue("A hand-drawn partial schema is not acceptable: $count", count >= 30)
            sql.query("PRAGMA user_version").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(15, c.getInt(0))
            }
        } finally {
            database.close()
        }
        val file = context.getDatabasePath(name)
        assertTrue(file.exists())
        val output = java.io.File(destination)
        output.parentFile?.mkdirs()
        Files.copy(file.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
        assertTrue("Native Room v15 fixture must not be empty", output.length() > 30_000L)
        println("V15_NATIVE_FIXTURE_CREATED bytes=${output.length()} dataOrigin=synthetic source=RoomV15")
    }
}
