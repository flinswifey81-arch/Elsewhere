package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.ChatEntity
import com.example.data.model.ChatType
import com.example.data.model.MessageEntity
import com.example.data.model.SpeakerType
import com.example.domain.repository.MessageRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Reader-only history search must not promote text into generation context. */
@RunWith(RobolectricTestRunner::class)
class ChatHistorySearchTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: MessageRepository

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = MessageRepository(db.messageDao())
    }

    @After fun teardown() = db.close()

    private suspend fun save(
        chat: String,
        id: String,
        text: String,
        position: Long,
        selected: Boolean = true,
        group: String = id
    ) {
        db.messageDao().insertMessage(MessageEntity(
            messageId = id,
            chatId = chat,
            speakerType = SpeakerType.CHARACTER,
            speakerId = "character",
            speakerDisplayNameSnapshot = "Character",
            content = text,
            orderIndex = position,
            variantGroupId = group,
            isPrimaryVariant = selected
        ))
    }

    @Test fun findsOldSelectedTurnWithoutLoadingNewestWindowOrAnotherChat() = runBlocking {
        db.chatDao().insertChat(ChatEntity(chatId = "main", displayName = "Main", chatType = ChatType.SOLO))
        db.chatDao().insertChat(ChatEntity(chatId = "other", displayName = "Other", chatType = ChatType.SOLO))
        save("main", "old", "The silver key was left in the watchtower.", 17)
        save("main", "discarded", "The silver key was destroyed.", 18,
            selected = false, group = "newest")
        save("main", "newest", "The party reached the inn.", 18, group = "newest")
        save("other", "private", "The silver key belongs to another story.", 19)
        for (n in 20..520) save("main", "filler-$n", "The road continued.", n.toLong())

        val actual = repository.searchSelectedHistoryForReader("main", "SILVER KEY")
        assertEquals(listOf("old"), actual.map { it.messageId })
        assertFalse(actual.any { it.chatId != "main" || !it.isPrimaryVariant })
        assertEquals(emptyList<MessageEntity>(),
            repository.searchSelectedHistoryForReader("main", "destroyed"))
    }

    @Test fun switchingPrimaryVariantImmediatelyChangesResults() = runBlocking {
        db.chatDao().insertChat(ChatEntity(chatId = "main", displayName = "Main", chatType = ChatType.SOLO))
        save("main", "original", "A blue feather marked the doorway.", 7, group = "branch")
        save("main", "alternate", "A red feather marked the doorway.", 7,
            selected = false, group = "branch")
        assertEquals(listOf("original"),
            repository.searchSelectedHistoryForReader("main", "feather")
                .map { it.messageId })

        db.messageDao().clearPrimaryVariants("branch")
        db.messageDao().setPrimaryVariant("alternate")

        assertEquals(emptyList<MessageEntity>(),
            repository.searchSelectedHistoryForReader("main", "blue feather"))
        assertEquals(listOf("alternate"),
            repository.searchSelectedHistoryForReader("main", "red feather")
                .map { it.messageId })
    }

    @Test fun preWackReaderHistoryIsVisibleAndPunctuationIsLiteral() = runBlocking {
        db.chatDao().insertChat(ChatEntity(chatId = "main", displayName = "Main", chatType = ChatType.SOLO))
        // Wack forbids generation access; it does not delete the visible transcript.
        save("main", "old", "I am 100%_ certain of the secret.", 4)
        save("main", "new", "I am 100 percent certain.", 200)
        assertEquals(listOf("old"),
            repository.searchSelectedHistoryForReader("main", "100%_").map { it.messageId })
        assertEquals(listOf("old"),
            repository.searchSelectedHistoryForReader("main", "secret").map { it.messageId })
        assertEquals(emptyList<MessageEntity>(),
            repository.searchSelectedHistoryForReader("main", "unknown"))
    }

    @Test fun indexedSearchReflectsEditsNewInsertsAndDeletesWithoutStaleHits() = runBlocking {
        db.chatDao().insertChat(ChatEntity(chatId = "main", displayName = "Main", chatType = ChatType.SOLO))
        save("main", "old", "A copper lantern rested on the table.", 5)
        assertEquals(listOf("old"),
            repository.searchSelectedIndexedWordsForReader("main", "copper lantern")
                .map { it.messageId })
        val old = requireNotNull(db.messageDao().getMessageById("old"))
        db.messageDao().updateMessage(old.copy(content = "A glass compass rested on the table."))
        assertTrue(repository.searchSelectedIndexedWordsForReader("main", "copper lantern").isEmpty())
        assertEquals(listOf("old"),
            repository.searchSelectedIndexedWordsForReader("main", "glass compass")
                .map { it.messageId })
        save("main", "new", "A copper lantern was found again.", 12)
        assertEquals(listOf("new"),
            repository.searchSelectedIndexedWordsForReader("main", "copper lantern")
                .map { it.messageId })
        db.messageDao().deleteMessageById("new")
        assertTrue(repository.searchSelectedIndexedWordsForReader("main", "copper lantern").isEmpty())
    }

    @Test fun indexIsUsedAndDoesNotHydrateWholeLongTranscript() = runBlocking {
        db.chatDao().insertChat(ChatEntity(chatId = "main", displayName = "Main", chatType = ChatType.SOLO))
        for (n in 1..2000) {
            save("main", "turn-$n", if (n == 18)
                "She hid the violet locket beneath the old staircase."
                else "A routine passage on the road.", n.toLong())
        }
        val actual = repository.searchSelectedIndexedWordsForReader("main", "violet locket")
        assertEquals(listOf("turn-18"), actual.map { it.messageId })
        db.openHelper.readableDatabase.query("""
            EXPLAIN QUERY PLAN
            SELECT m.messageId FROM messages AS m
            JOIN messages_search_fts AS f ON m.rowid = f.rowid
            WHERE messages_search_fts MATCH '"violet locket"'
              AND m.chatId = 'main' AND m.isPrimaryVariant = 1
        """.trimIndent()).use { cursor ->
            assertTrue(cursor.moveToFirst())
            val plan = buildList {
                do { add(cursor.getString(3)) } while (cursor.moveToNext())
            }.joinToString(" ")
            assertTrue("FTS must provide the matching row IDs: $plan",
                plan.contains("VIRTUAL TABLE INDEX"))
        }
    }

    @Test fun adversarialFtsOperatorsCannotEscapeLiteralPlayerSearch() = runBlocking {
        db.chatDao().insertChat(ChatEntity(chatId = "main", displayName = "Main", chatType = ChatType.SOLO))
        save("main", "literal", "A strange note said OR NEAR exactly.", 3)
        save("main", "other", "A strange note said OR nothing.", 4)
        assertEquals(listOf("literal"),
            repository.searchSelectedIndexedWordsForReader("main", "OR NEAR").map { it.messageId })
    }

    @Test fun exactSearchStillFindsPartialWordsExcludedByTheIndex() = runBlocking {
        db.chatDao().insertChat(ChatEntity(
            chatId = "main", displayName = "Main", chatType = ChatType.SOLO
        ))
        save("main", "partial", "The newsletter arrived at dawn.", 3)
        save("main", "whole", "The letter arrived at dusk.", 5)
        assertEquals(listOf("whole", "partial"),
            repository.searchSelectedHistoryForReader("main", "letter")
                .map { it.messageId })
        assertEquals(listOf("whole"),
            repository.searchSelectedIndexedWordsForReader("main", "letter")
                .map { it.messageId })
    }

    @Test fun newestResultsAreBoundedAndBadQueriesAreRejected() = runBlocking {
        db.chatDao().insertChat(ChatEntity(chatId = "main", displayName = "Main", chatType = ChatType.SOLO))
        for (n in 1..45) save("main", "turn-$n", "A hidden letter appears.", n.toLong())
        val results = repository.searchSelectedHistoryForReader("main", "letter")
        assertEquals(30, results.size)
        assertEquals("turn-45", results.first().messageId)
        assertEquals("turn-16", results.last().messageId)
        try {
            repository.searchSelectedHistoryForReader("main", " ")
            fail("Blank search must fail")
        } catch (_: IllegalArgumentException) { }
        try {
            repository.searchSelectedHistoryForReader("main", "letter", 1000)
            fail("Unbounded search must fail")
        } catch (_: IllegalArgumentException) { }
    }
}
