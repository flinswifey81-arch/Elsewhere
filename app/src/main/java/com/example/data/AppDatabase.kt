package com.example.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.*

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
        ChatLorebookEntryOverlayEntity::class
    ],
    version = 4,
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

    companion object {
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
    }
}
