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
        GenerationMetadataEntity::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun characterDao(): CharacterDao
    abstract fun personaDao(): PersonaDao
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao

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
    }
}
