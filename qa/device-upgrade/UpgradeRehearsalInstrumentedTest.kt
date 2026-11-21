package com.example

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CI-only test compiled into the temporary DEBUG androidTest APK on a fresh
 * emulator. Trigger the actual production AppContainer Room migration path,
 * not a parallel test database, then read back original synthetic records.
 * This source is never copied into production or release builds.
 */
@RunWith(AndroidJUnit4::class)
class UpgradeRehearsalInstrumentedTest {
    @Test
    fun originalRoom15DatabaseMigratesThroughTheInstalledV22App() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as ElsewhereApplication
        val original = runBlocking {
            app.container.chatRepository.getChatSummaryById("qa-solo")
        }
        assertNotNull("The real app must open and migrate the original v15 database", original)
        assertEquals("Synthetic Solo", original!!.chat.displayName)

        SQLiteDatabase.openDatabase(
            context.getDatabasePath("elsewhere_database").absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        ).use { database ->
            database.rawQuery("PRAGMA user_version", null).use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(com.example.data.APP_DATABASE_VERSION, cursor.getInt(0))
            }
            database.rawQuery(
                "SELECT content FROM messages WHERE messageId = ?",
                arrayOf("qa-turn1-selected")
            ).use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("I keep the silver key.", cursor.getString(0))
            }
            database.rawQuery(
                "SELECT content FROM messages WHERE messageId = ?",
                arrayOf("qa-turn1")
            ).use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("I keep the copper key.", cursor.getString(0))
            }
            database.rawQuery(
                "SELECT contextResetOrderIndex FROM chats WHERE chatId = ?",
                arrayOf("qa-solo")
            ).use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(3L, cursor.getLong(0))
            }
            database.rawQuery("SELECT COUNT(*) FROM story_memories", null).use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("An original v15 upgrade must not fabricate approved Story Memories",
                    0, cursor.getInt(0))
            }
        }
    }
}
