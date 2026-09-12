package com.example

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
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
}
