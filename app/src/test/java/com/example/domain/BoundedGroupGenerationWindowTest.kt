package com.example.domain

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
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

/** Real Room-backed selected-branch windows; no provider or network. */
@RunWith(RobolectricTestRunner::class)
class BoundedGroupGenerationWindowTest {
    private lateinit var db: AppDatabase
    private lateinit var messages: MessageRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        messages = MessageRepository(db.messageDao())
    }

    @After fun tearDown() = db.close()

    private fun turn(
        id: String, chat: String, order: Long,
        role: SpeakerType = SpeakerType.CHARACTER,
        selected: Boolean = true
    ) = MessageEntity(
        messageId = id, chatId = chat, speakerType = role,
        speakerId = if (role == SpeakerType.PERSONA) "persona" else "character",
        speakerDisplayNameSnapshot = if (role == SpeakerType.PERSONA) "Mara" else "Rowan",
        content = "Synthetic turn $id", orderIndex = order,
        isPrimaryVariant = selected
    )

    @Test fun tenThousandTurnWindowIsBoundedAndKeepsDistantPersona() = runBlocking {
        db.chatDao().insertChat(ChatEntity(chatId = "main", displayName = "Main", chatType = ChatType.GROUP))
        db.chatDao().insertChat(ChatEntity(chatId = "other", displayName = "Other", chatType = ChatType.GROUP))
        db.withTransaction {
            for (index in 1..10_000) {
                db.messageDao().insertMessage(
                    turn("m-$index", "main", index.toLong(),
                        if (index == 17 || index == 81) SpeakerType.PERSONA else SpeakerType.CHARACTER)
                )
            }
            db.messageDao().insertMessage(turn("alternate-hidden", "main", 20_000, selected = false))
            db.messageDao().insertMessage(turn("other-secret", "other", 30_000, SpeakerType.PERSONA))
        }
        val snapshot = messages.getSelectedGroupGenerationWindow("main", null)
        assertEquals(25, snapshot.messages.size)
        assertEquals("m-9976", snapshot.messages.first().messageId)
        assertEquals("m-10000", snapshot.messages.last().messageId)
        assertEquals("m-81", snapshot.latestPersona?.messageId)
        assertFalse(snapshot.messages.any { it.messageId == "alternate-hidden" })
        assertFalse(snapshot.messages.any { it.chatId != "main" })
        println("BOUNDED_GROUP_BENCHMARK total=10000 returned=${snapshot.messages.size} latestPersona=${snapshot.latestPersona?.messageId} providerCalls=0")

        val afterWack = messages.getSelectedGroupGenerationWindow("main", 100L)
        assertNull("Pre-Wack Persona must never reappear", afterWack.latestPersona)
        assertEquals("m-10000", afterWack.messages.last().messageId)
    }

    @Test fun selectedVariantsAndOrderIndexTiesFollowInsertionChronology() = runBlocking {
        db.chatDao().insertChat(ChatEntity(chatId = "chat", displayName = "Group", chatType = ChatType.GROUP))
        db.messageDao().insertMessage(turn("m1", "chat", 1, SpeakerType.PERSONA))
        db.messageDao().insertMessage(turn("m2", "chat", 2))
        db.messageDao().insertMessage(turn("alternate-hidden", "chat", 2, selected = false))
        db.messageDao().insertMessage(turn("m3", "chat", 2))
        val snapshot = messages.getSelectedGroupGenerationWindow("chat", null)
        assertEquals(listOf("m1", "m2", "m3"), snapshot.messages.map { it.messageId })
        assertEquals("m1", snapshot.latestPersona?.messageId)
        val afterWack = messages.getSelectedGroupGenerationWindow("chat", 1L)
        assertNull(afterWack.latestPersona)
        assertEquals(listOf("m1", "m2", "m3"), afterWack.messages.map { it.messageId })
        // We intentionally do not filter the latest-window current message
        // by Wack; the generation boundary gate rejects an old current turn.
    }
}
