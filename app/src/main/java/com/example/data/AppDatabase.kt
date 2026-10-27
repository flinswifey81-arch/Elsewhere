package com.example.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.*

const val APP_DATABASE_VERSION = 11

@Database(
    entities = [
        CharacterEntity::class,
        PersonaEntity::class,
        ChatEntity::class,
        ChatParticipantEntity::class,
        ChatPersonaParticipantEntity::class,
        MessageEntity::class,
        RelationshipEntity::class,
        ChatSettingsEntity::class,
        GenerationMetadataEntity::class,
        RoleplayMemoryEntity::class,
        ConversationSummaryEntity::class,
        LorebookEntity::class,
        LorebookEntryEntity::class,
        ChatLorebookAttachmentEntity::class,
        ChatLorebookEntryOverlayEntity::class,
        CoopRoomEntity::class,
        CoopParticipantEntity::class,
        CoopPersonaOwnershipEntity::class,
        CoopEventEntity::class,
        CoopMessageTombstoneEntity::class,
        MessageAttachmentEntity::class,
        NpcDossierEntity::class,
        NpcBankSettingsEntity::class,
        NpcDossierUpdateEntity::class,
        ScenarioEntity::class,
        ScenarioSessionEntity::class,
        EngineDefaultsEntity::class,
        NpcLibraryBankEntity::class,
        NpcLibraryEntryEntity::class
    ],
    version = APP_DATABASE_VERSION,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun characterDao(): CharacterDao
    abstract fun personaDao(): PersonaDao
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao
    abstract fun lorebookDao(): LorebookDao
    abstract fun coopDao(): CoopDao
    abstract fun messageAttachmentDao(): MessageAttachmentDao
    abstract fun npcDao(): NpcDao
    abstract fun scenarioDao(): ScenarioDao
    abstract fun backupDao(): BackupDao

    companion object {
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_settings ADD COLUMN coLeadEnsembleEnabled INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_settings ADD COLUMN useGlobalEngineDefaults INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS engine_defaults (defaultsId TEXT NOT NULL PRIMARY KEY, selectedModelId TEXT, providerRoutingMode TEXT NOT NULL, providerEndpoint TEXT, temperature REAL, topP REAL, responseLengthProfile TEXT NOT NULL, customMin INTEGER, customTargetMax INTEGER, customHardMax INTEGER)")
                db.execSQL("CREATE TABLE IF NOT EXISTS npc_library_banks (bankId TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS npc_library_entries (libraryNpcId TEXT NOT NULL PRIMARY KEY, bankId TEXT NOT NULL, displayName TEXT NOT NULL, aliasesJson TEXT NOT NULL, role TEXT NOT NULL, appearance TEXT NOT NULL, personality TEXT NOT NULL, relationshipToPersona TEXT NOT NULL, personaRelationshipsJson TEXT NOT NULL, characterKnowledgeJson TEXT NOT NULL, goals TEXT NOT NULL, secrets TEXT NOT NULL, authorNotes TEXT NOT NULL, keepInScene INTEGER NOT NULL, extensionFieldsJson TEXT, sortPosition INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_npc_library_entries_bankId ON npc_library_entries(bankId)")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS scenarios (scenarioId TEXT NOT NULL PRIMARY KEY, definitionJson TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS scenario_sessions (chatId TEXT NOT NULL PRIMARY KEY, scenarioId TEXT NOT NULL, definitionJson TEXT NOT NULL, FOREIGN KEY(chatId) REFERENCES chats(chatId) ON UPDATE NO ACTION ON DELETE CASCADE)")
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Modify messages table
                db.execSQL("ALTER TABLE messages ADD COLUMN variantGroupId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN isPrimaryVariant INTEGER NOT NULL DEFAULT 1")
                db.execSQL("UPDATE messages SET variantGroupId = messageId")
                
                // Create chat_settings table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS chat_settings (
                        chatId TEXT NOT NULL PRIMARY KEY,
                        selectedModelId TEXT,
                        providerRoutingMode TEXT NOT NULL,
                        providerEndpoint TEXT,
                        temperature REAL,
                        topP REAL,
                        responseLengthProfile TEXT NOT NULL,
                        customMin INTEGER,
                        customTargetMax INTEGER,
                        customHardMax INTEGER,
                        draftMessage TEXT
                    )
                """.trimIndent())
                
                // Create generation_metadata table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS generation_metadata (
                        messageId TEXT NOT NULL PRIMARY KEY,
                        requestedModelId TEXT,
                        resolvedModelId TEXT,
                        promptTokens INTEGER,
                        completionTokens INTEGER,
                        totalTokens INTEGER,
                        reasoningTokens INTEGER,
                        cachedTokens INTEGER,
                        reportedCost REAL,
                        finishReason TEXT,
                        provider TEXT,
                        generationTimeMs INTEGER
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS roleplay_memories (
                        memoryId TEXT NOT NULL PRIMARY KEY,
                        chatId TEXT NOT NULL,
                        characterId TEXT NOT NULL,
                        category TEXT NOT NULL,
                        content TEXT NOT NULL,
                        sourceMessageId TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_roleplay_memories_chatId ON roleplay_memories(chatId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_roleplay_memories_characterId ON roleplay_memories(characterId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS conversation_summaries (
                        chatId TEXT NOT NULL PRIMARY KEY,
                        summary TEXT NOT NULL,
                        summarizedThroughOrderIndex INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS lorebooks (
                        lorebookId TEXT NOT NULL PRIMARY KEY,
                        schemaVersion INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        scopeType TEXT NOT NULL,
                        storyId TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS lorebook_entries (
                        entryId TEXT NOT NULL PRIMARY KEY,
                        lorebookId TEXT NOT NULL,
                        title TEXT NOT NULL,
                        enabled INTEGER NOT NULL,
                        activationMode TEXT NOT NULL,
                        keys TEXT NOT NULL,
                        secondaryKeys TEXT NOT NULL,
                        matchMode TEXT NOT NULL,
                        caseSensitive INTEGER NOT NULL,
                        wholeWords INTEGER NOT NULL,
                        canonContent TEXT NOT NULL,
                        canonLocked INTEGER NOT NULL,
                        currentContent TEXT NOT NULL,
                        priority INTEGER NOT NULL,
                        position TEXT NOT NULL,
                        tags TEXT NOT NULL,
                        notes TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_lorebook_entries_lorebookId ON lorebook_entries(lorebookId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS chat_lorebook_attachments (
                        chatId TEXT NOT NULL,
                        lorebookId TEXT NOT NULL,
                        isEnabled INTEGER NOT NULL,
                        sortPosition INTEGER NOT NULL,
                        attachedAt INTEGER NOT NULL,
                        PRIMARY KEY(chatId, lorebookId)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_lorebook_attachments_lorebookId ON chat_lorebook_attachments(lorebookId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS chat_lorebook_entry_overlays (
                        chatId TEXT NOT NULL,
                        entryId TEXT NOT NULL,
                        hasCurrentOverride INTEGER NOT NULL,
                        currentContent TEXT NOT NULL,
                        manualActive INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(chatId, entryId)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_lorebook_entry_overlays_entryId ON chat_lorebook_entry_overlays(entryId)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN responsibleParticipantId TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN responsibleDeviceId TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN coOpEventId TEXT")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_messages_coOpEventId ON messages(coOpEventId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS coop_rooms (
                        roomId TEXT NOT NULL PRIMARY KEY,
                        chatId TEXT NOT NULL,
                        localRole TEXT NOT NULL,
                        hostParticipantId TEXT NOT NULL,
                        localParticipantId TEXT NOT NULL,
                        payerMode TEXT NOT NULL,
                        selectedPayerParticipantId TEXT,
                        hostAddress TEXT,
                        hostPort INTEGER NOT NULL,
                        hostPublicKey TEXT NOT NULL,
                        hostKeyFingerprint TEXT NOT NULL,
                        pairingSalt TEXT NOT NULL,
                        pairingCodeHash TEXT NOT NULL,
                        connectionState TEXT NOT NULL,
                        orderBase INTEGER NOT NULL,
                        nextHostSequence INTEGER NOT NULL,
                        lastAppliedSequence INTEGER NOT NULL,
                        activeGenerationRequestId TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_coop_rooms_chatId ON coop_rooms(chatId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS coop_participants (
                        participantId TEXT NOT NULL PRIMARY KEY,
                        roomId TEXT NOT NULL,
                        deviceId TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        isHost INTEGER NOT NULL,
                        isConnected INTEGER NOT NULL,
                        joinedAt INTEGER NOT NULL,
                        lastSeenAt INTEGER NOT NULL,
                        sortPosition INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_coop_participants_roomId ON coop_participants(roomId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_coop_participants_deviceId ON coop_participants(deviceId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS coop_persona_ownership (
                        roomId TEXT NOT NULL,
                        personaId TEXT NOT NULL,
                        participantId TEXT NOT NULL,
                        personaDisplayNameSnapshot TEXT NOT NULL,
                        isActive INTEGER NOT NULL,
                        sortPosition INTEGER NOT NULL,
                        PRIMARY KEY(roomId, personaId)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_coop_persona_ownership_participantId ON coop_persona_ownership(participantId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS coop_events (
                        eventId TEXT NOT NULL PRIMARY KEY,
                        roomId TEXT NOT NULL,
                        sequence INTEGER NOT NULL,
                        clientEventId TEXT NOT NULL,
                        originParticipantId TEXT NOT NULL,
                        eventType TEXT NOT NULL,
                        payloadJson TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_coop_events_roomId_sequence ON coop_events(roomId, sequence)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_coop_events_roomId_clientEventId ON coop_events(roomId, clientEventId)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE generation_metadata ADD COLUMN isPartial INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE generation_metadata ADD COLUMN wasUserStopped INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE chat_settings ADD COLUMN narratorEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE chat_settings ADD COLUMN narratorInstructions TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE coop_participants ADD COLUMN identityPublicKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE coop_events ADD COLUMN originPayloadJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE coop_events ADD COLUMN originSignature TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS message_attachments (
                        attachmentId TEXT NOT NULL PRIMARY KEY,
                        chatId TEXT NOT NULL,
                        messageId TEXT,
                        relativePath TEXT NOT NULL,
                        mimeType TEXT NOT NULL,
                        byteSize INTEGER NOT NULL,
                        pixelWidth INTEGER NOT NULL,
                        pixelHeight INTEGER NOT NULL,
                        sha256 TEXT NOT NULL,
                        sortPosition INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_message_attachments_chatId ON message_attachments(chatId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_message_attachments_messageId ON message_attachments(messageId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS npc_dossiers (
                        npcId TEXT NOT NULL PRIMARY KEY,
                        chatId TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        aliasesJson TEXT NOT NULL,
                        role TEXT NOT NULL,
                        appearance TEXT NOT NULL,
                        personality TEXT NOT NULL,
                        relationshipToPersona TEXT NOT NULL,
                        personaRelationshipsJson TEXT NOT NULL,
                        characterKnowledgeJson TEXT NOT NULL,
                        goals TEXT NOT NULL,
                        secrets TEXT NOT NULL,
                        authorNotes TEXT NOT NULL,
                        keepInScene INTEGER NOT NULL,
                        isManualBase INTEGER NOT NULL,
                        extensionFieldsJson TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_npc_dossiers_chatId ON npc_dossiers(chatId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS npc_bank_settings (
                        chatId TEXT NOT NULL PRIMARY KEY,
                        isEnabled INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS npc_dossier_updates (
                        updateId TEXT NOT NULL PRIMARY KEY,
                        chatId TEXT NOT NULL,
                        npcId TEXT NOT NULL,
                        sourceMessageId TEXT NOT NULL,
                        variantGroupId TEXT NOT NULL,
                        sourceOrderIndex INTEGER NOT NULL,
                        sourceRevision INTEGER NOT NULL,
                        fieldName TEXT NOT NULL,
                        oldValue TEXT,
                        newValue TEXT NOT NULL,
                        isApplied INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_npc_dossier_updates_chatId ON npc_dossier_updates(chatId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_npc_dossier_updates_npcId ON npc_dossier_updates(npcId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_npc_dossier_updates_sourceMessageId ON npc_dossier_updates(sourceMessageId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_npc_dossier_updates_variantGroupId ON npc_dossier_updates(variantGroupId)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_npc_dossier_updates_sourceMessageId_sourceRevision_npcId_fieldName " +
                        "ON npc_dossier_updates(sourceMessageId, sourceRevision, npcId, fieldName)"
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chats ADD COLUMN contextResetOrderIndex INTEGER")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS coop_message_tombstones (
                        roomId TEXT NOT NULL,
                        messageId TEXT NOT NULL,
                        deletedSequence INTEGER NOT NULL,
                        deletedAt INTEGER NOT NULL,
                        PRIMARY KEY(roomId, messageId)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_coop_message_tombstones_messageId " +
                        "ON coop_message_tombstones(messageId)"
                )
                // Version 7 already persisted the ordered co-op event log. Reconstruct deletion
                // history so a peer cannot resurrect a message ID that was deleted before this
                // durable tombstone table existed.
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO coop_message_tombstones
                        (roomId, messageId, deletedSequence, deletedAt)
                    SELECT
                        roomId,
                        json_extract(payloadJson, '$.messageId'),
                        MAX(sequence),
                        MAX(createdAt)
                    FROM coop_events
                    WHERE eventType = 'MESSAGE_DELETE'
                        AND json_valid(payloadJson) = 1
                        AND typeof(json_extract(payloadJson, '$.messageId')) = 'text'
                        AND length(json_extract(payloadJson, '$.messageId')) BETWEEN 1 AND 200
                    GROUP BY roomId, json_extract(payloadJson, '$.messageId')
                    """.trimIndent()
                )
            }
        }
    }
}
