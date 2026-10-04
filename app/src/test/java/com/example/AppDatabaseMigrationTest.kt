package com.example

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppDatabaseMigrationTest {
    private val databaseName = "migration-test.db"
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(databaseName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrationFrom1To2PreservesExistingMessagesAndInitializesVariants() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE messages (
                                messageId TEXT NOT NULL PRIMARY KEY,
                                chatId TEXT NOT NULL,
                                speakerType TEXT NOT NULL,
                                speakerId TEXT NOT NULL,
                                speakerDisplayNameSnapshot TEXT NOT NULL,
                                content TEXT NOT NULL,
                                createdAt INTEGER NOT NULL,
                                editedAt INTEGER,
                                orderIndex INTEGER NOT NULL
                            )
                            """.trimIndent()
                        )
                    }

                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) = Unit
                })
                .build()
        )

        try {
            val database = helper.writableDatabase
            database.execSQL(
                """
                INSERT INTO messages (
                    messageId, chatId, speakerType, speakerId, speakerDisplayNameSnapshot,
                    content, createdAt, editedAt, orderIndex
                ) VALUES ('message-1', 'chat-1', 'PERSONA', 'persona-1', 'Persona', 'Hello', 1, NULL, 1)
                """.trimIndent()
            )

            AppDatabase.MIGRATION_1_2.migrate(database)

            database.query(
                "SELECT content, variantGroupId, isPrimaryVariant FROM messages WHERE messageId = 'message-1'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Hello", cursor.getString(0))
                assertEquals("message-1", cursor.getString(1))
                assertEquals(1, cursor.getInt(2))
            }

            database.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('chat_settings', 'generation_metadata')"
            ).use { cursor ->
                assertEquals(2, cursor.count)
            }
        } finally {
            helper.close()
        }
    }

    @Test
    fun migrationFrom2To3AddsLocalMemoryTables() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) = Unit
                })
                .build()
        )

        try {
            val database = helper.writableDatabase
            AppDatabase.MIGRATION_2_3.migrate(database)

            database.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('roleplay_memories', 'conversation_summaries')"
            ).use { cursor ->
                assertEquals(2, cursor.count)
            }
            database.query(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND name IN ('index_roleplay_memories_chatId', 'index_roleplay_memories_characterId')"
            ).use { cursor ->
                assertEquals(2, cursor.count)
            }
        } finally {
            helper.close()
        }
    }

    @Test
    fun migrationFrom3To4ValidatesAgainstRoomAndPreservesExistingChatData() = runBlocking {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        createVersion3Schema(db)
                    }

                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) = Unit
                })
                .build()
        )

        helper.writableDatabase.apply {
            execSQL("INSERT INTO chats (chatId, displayName, chatType, createdAt, updatedAt) VALUES ('chat-keep', 'Keep Me', 'SOLO', 1, 2)")
            execSQL(
                "INSERT INTO messages (messageId, chatId, speakerType, speakerId, speakerDisplayNameSnapshot, content, createdAt, editedAt, orderIndex, variantGroupId, isPrimaryVariant) " +
                    "VALUES ('message-keep', 'chat-keep', 'PERSONA', 'persona-keep', 'Keeper', 'Preserve this', 3, NULL, 1, 'message-keep', 1)"
            )
        }
        helper.close()

        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()
        try {
            // Opening through Room executes 3 -> 4 and validates the entire resulting schema.
            migrated.openHelper.writableDatabase
            assertEquals("Keep Me", migrated.chatDao().getChatById("chat-keep")?.displayName)
            assertEquals("Preserve this", migrated.messageDao().getMessagesForChatOnce("chat-keep").single().content)
            assertTrue(migrated.lorebookDao().getLorebooksOnce().isEmpty())
        } finally {
            migrated.close()
        }
    }

    private fun createVersion3Schema(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        listOf(
            "CREATE TABLE IF NOT EXISTS `characters` (`characterId` TEXT NOT NULL, `displayName` TEXT NOT NULL, `characterName` TEXT NOT NULL, `aliases` TEXT NOT NULL, `importSchemaVersion` INTEGER NOT NULL, `identityJson` TEXT, `appearanceJson` TEXT, `personalityJson` TEXT, `voiceJson` TEXT, `behaviorJson` TEXT, `backstoryJson` TEXT, `knowledgeJson` TEXT, `worldContextJson` TEXT, `writingRulesJson` TEXT, `examplesJson` TEXT, `authorNotesJson` TEXT, `extensionFieldsJson` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `isArchived` INTEGER NOT NULL, PRIMARY KEY(`characterId`))",
            "CREATE TABLE IF NOT EXISTS `personas` (`personaId` TEXT NOT NULL, `displayName` TEXT NOT NULL, `personaName` TEXT NOT NULL, `aliases` TEXT NOT NULL, `importSchemaVersion` INTEGER NOT NULL, `identityJson` TEXT, `appearanceJson` TEXT, `personalityJson` TEXT, `roleplayProfileJson` TEXT, `backgroundJson` TEXT, `worldContextJson` TEXT, `privateNotesJson` TEXT, `extensionFieldsJson` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `isArchived` INTEGER NOT NULL, PRIMARY KEY(`personaId`))",
            "CREATE TABLE IF NOT EXISTS `chats` (`chatId` TEXT NOT NULL, `displayName` TEXT NOT NULL, `chatType` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`chatId`))",
            "CREATE TABLE IF NOT EXISTS `chat_participants` (`chatId` TEXT NOT NULL, `characterId` TEXT NOT NULL, `joinedAt` INTEGER NOT NULL, `isActive` INTEGER NOT NULL, `sortPosition` INTEGER NOT NULL, PRIMARY KEY(`chatId`, `characterId`))",
            "CREATE TABLE IF NOT EXISTS `chat_persona_participants` (`chatId` TEXT NOT NULL, `personaId` TEXT NOT NULL, `joinedAt` INTEGER NOT NULL, `isActive` INTEGER NOT NULL, `sortPosition` INTEGER NOT NULL, PRIMARY KEY(`chatId`, `personaId`))",
            "CREATE TABLE IF NOT EXISTS `messages` (`messageId` TEXT NOT NULL, `chatId` TEXT NOT NULL, `speakerType` TEXT NOT NULL, `speakerId` TEXT NOT NULL, `speakerDisplayNameSnapshot` TEXT NOT NULL, `content` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `editedAt` INTEGER, `orderIndex` INTEGER NOT NULL, `variantGroupId` TEXT NOT NULL, `isPrimaryVariant` INTEGER NOT NULL, PRIMARY KEY(`messageId`))",
            "CREATE TABLE IF NOT EXISTS `relationships` (`relationshipId` TEXT NOT NULL, `sourceCharacterId` TEXT NOT NULL, `targetCharacterId` TEXT NOT NULL, `targetNameSnapshot` TEXT NOT NULL, `relationshipType` TEXT NOT NULL, `dynamic` TEXT NOT NULL, `status` TEXT NOT NULL, `scopeChatId` TEXT, PRIMARY KEY(`relationshipId`))",
            "CREATE TABLE IF NOT EXISTS `chat_settings` (`chatId` TEXT NOT NULL, `selectedModelId` TEXT, `providerRoutingMode` TEXT NOT NULL, `providerEndpoint` TEXT, `temperature` REAL, `topP` REAL, `responseLengthProfile` TEXT NOT NULL, `customMin` INTEGER, `customTargetMax` INTEGER, `customHardMax` INTEGER, `draftMessage` TEXT, PRIMARY KEY(`chatId`))",
            "CREATE TABLE IF NOT EXISTS `generation_metadata` (`messageId` TEXT NOT NULL, `requestedModelId` TEXT, `resolvedModelId` TEXT, `promptTokens` INTEGER, `completionTokens` INTEGER, `totalTokens` INTEGER, `reasoningTokens` INTEGER, `cachedTokens` INTEGER, `reportedCost` REAL, `finishReason` TEXT, `provider` TEXT, `generationTimeMs` INTEGER, PRIMARY KEY(`messageId`))",
            "CREATE TABLE IF NOT EXISTS `roleplay_memories` (`memoryId` TEXT NOT NULL, `chatId` TEXT NOT NULL, `characterId` TEXT NOT NULL, `category` TEXT NOT NULL, `content` TEXT NOT NULL, `sourceMessageId` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`memoryId`))",
            "CREATE INDEX IF NOT EXISTS `index_roleplay_memories_chatId` ON `roleplay_memories` (`chatId`)",
            "CREATE INDEX IF NOT EXISTS `index_roleplay_memories_characterId` ON `roleplay_memories` (`characterId`)",
            "CREATE TABLE IF NOT EXISTS `conversation_summaries` (`chatId` TEXT NOT NULL, `summary` TEXT NOT NULL, `summarizedThroughOrderIndex` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`chatId`))"
        ).forEach(db::execSQL)
    }
}
