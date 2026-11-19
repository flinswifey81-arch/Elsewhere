package com.example

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
import com.example.ui.screens.selectVisibleChatHistory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The chat UI must hydrate only complete *logical groups*, including all
 * variants, while archived rows stay available to Room, export and memory.
 * These are real Room queries; no provider calls or network traffic.
 */
@RunWith(RobolectricTestRunner::class)
class ChatHistoryPaginationTest {
    private lateinit var db: AppDatabase
    private lateinit var messages: MessageRepository

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        messages = MessageRepository(db.messageDao())
    }

    @After fun teardown() = db.close()

    private fun msg(
        index: Int,
        chatId: String = "main",
        selected: Boolean = true,
        groupId: String = "m-$index",
        messageId: String = "m-$index",
        orderIndex: Long = index.toLong()
    ) = MessageEntity(
        messageId = messageId,
        chatId = chatId,
        speakerType = if (index % 2 == 0) SpeakerType.PERSONA else SpeakerType.CHARACTER,
        speakerId = "actor",
        speakerDisplayNameSnapshot = "Actor",
        content = "Long fictional story turn $index",
        orderIndex = orderIndex,
        variantGroupId = groupId,
        isPrimaryVariant = selected
    )

    @Test fun tenThousandTurnChatOpensWithOnly100CompleteGroupsAndLoadsOlder() = runBlocking {
        db.chatDao().insertChat(ChatEntity(
            chatId = "main", displayName = "Long RP", chatType = ChatType.SOLO
        ))
        db.chatDao().insertChat(ChatEntity(
            chatId = "other", displayName = "Private RP", chatType = ChatType.SOLO
        ))
        db.withTransaction {
            for (n in 1..10_000) db.messageDao().insertMessage(msg(n))
            db.messageDao().insertMessage(
                msg(9_950, selected = false, messageId = "discarded-9950",
                    orderIndex = 20_000L)
            )
            db.messageDao().insertMessage(
                msg(9_999, selected = false, messageId = "orphaned-9999",
                    orderIndex = 9_999L)
            )
            db.messageDao().insertMessage(msg(999_999, chatId = "other"))
        }

        val firstRows = messages.observeRecentMessageGroups("main", 100).first()
        val firstPage = selectVisibleChatHistory(firstRows, 100)
        assertTrue(firstPage.hasEarlierGroups)
        assertEquals(100, firstPage.groupedMessages.size)
        assertEquals("m-9901", firstPage.groupedMessages.first().primaryMessage.messageId)
        assertEquals("m-10000", firstPage.groupedMessages.last().primaryMessage.messageId)
        assertEquals(102, firstRows.size) // 100 groups + sentinel + rejected variant
        assertEquals(101, firstPage.messages.size) // visible 100 groups + one alternate
        assertEquals(
            setOf("m-9950", "discarded-9950"),
            firstPage.groupedMessages.single {
                it.primaryMessage.messageId == "m-9950"
            }.variants.map { it.messageId }.toSet()
        )
        assertFalse(firstRows.any { it.chatId == "other" })
        assertFalse(firstPage.messages.any { it.messageId == "m-9900" })

        val olderRows = messages.observeRecentMessageGroups("main", 200).first()
        val older = selectVisibleChatHistory(olderRows, 200)
        assertEquals("m-9801", older.groupedMessages.first().primaryMessage.messageId)
        assertEquals(200, older.groupedMessages.size)
        assertTrue(older.hasEarlierGroups)
        assertEquals("m-10000", older.groupedMessages.last().primaryMessage.messageId)
        assertEquals(10_002, db.messageDao().getMessagesForChatOnce("main").size)
        println(
            "CHAT_HISTORY_PAGINATION total=10000 initialGroups=${firstPage.groupedMessages.size} " +
                "initialRows=${firstPage.messages.size} olderGroups=${older.groupedMessages.size} providerCalls=0"
        )
    }

    @Test fun allRegenerationVariantsStayTogetherWhenGroupCrossesPageBoundary() = runBlocking {
        db.chatDao().insertChat(ChatEntity(
            chatId = "main", displayName = "Variants", chatType = ChatType.SOLO
        ))
        db.withTransaction {
            for (i in 1..210) db.messageDao().insertMessage(msg(i))
            for (v in 1..8) {
                db.messageDao().insertMessage(msg(110, selected = false,
                    messageId = "alternate-$v", orderIndex = (210 + v).toLong()))
            }
        }
        val initial = selectVisibleChatHistory(
            messages.observeRecentMessageGroups("main", 100).first(), 100
        )
        assertFalse(initial.messages.any { it.messageId.startsWith("alternate-") })
        val older = selectVisibleChatHistory(
            messages.observeRecentMessageGroups("main", 101).first(), 101
        )
        val boundaryGroup = older.groupedMessages.single {
            it.primaryMessage.messageId == "m-110"
        }
        assertEquals(9, boundaryGroup.variants.size)
        assertEquals(9, older.messages.count { it.variantGroupId == "m-110" })
        assertFalse(older.hasEarlierGroups.not()) // older groups still exist
    }

    @Test fun tinyChatAndUnselectedOnlyGroupRetainLegacyDisplayFallback() = runBlocking {
        db.chatDao().insertChat(ChatEntity(
            chatId = "main", displayName = "Tiny", chatType = ChatType.SOLO
        ))
        db.messageDao().insertMessage(msg(1, selected = false))
        db.messageDao().insertMessage(msg(2))
        val actual = selectVisibleChatHistory(
            messages.observeRecentMessageGroups("main", 100).first(), 100
        )
        assertFalse(actual.hasEarlierGroups)
        assertEquals(listOf("m-1", "m-2"),
            actual.groupedMessages.map { it.primaryMessage.messageId })
        assertEquals(2, actual.messages.size)
    }
}
