package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.ChatEntity
import com.example.data.model.ChatType
import com.example.data.model.MessageEntity
import com.example.data.model.SpeakerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Isolates Room's empty-history initial Flow emission from ChatDetailViewModel.
 * If this succeeds but the ViewModel remains at historyLoaded=false, the
 * defect is in collector startup/lifecycle/state propagation, not SQL alone.
 * No provider requests and no real app data.
 */
@RunWith(RobolectricTestRunner::class)
class ChatHistoryFlowIsolationTest {
    private lateinit var database: AppDatabase
    private val chatId = "synthetic-empty-history"

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database.chatDao().insertChat(
            ChatEntity(chatId = chatId, displayName = "Synthetic empty history", chatType = ChatType.SOLO)
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun newRoomMessageStreamEmitsEmptyListWithoutWaitingForAnInsert() = runBlocking {
        val first = withTimeout(10_000) {
            database.messageDao().getMessagesForChat(chatId).first()
        }
        assertTrue("An empty chat must still emit an initial history snapshot", first.isEmpty())
    }

    @Test
    fun concurrentIndependentCollectorsEachReceiveInitialEmptySnapshot() = runBlocking {
        val collectors = (0 until 12).map {
            async(Dispatchers.Default) {
                withTimeout(15_000) {
                    database.messageDao().getMessagesForChat(chatId).first().size
                }
            }
        }
        assertEquals(List(12) { 0 }, collectors.awaitAll())
    }

    @Test
    fun messageStreamObservesFirstInsertedMessageAfterInitialEmptySnapshot() = runBlocking {
        val dao = database.messageDao()
        assertTrue(withTimeout(10_000) { dao.getMessagesForChat(chatId).first() }.isEmpty())
        val row = MessageEntity(
            messageId = "first-message", chatId = chatId,
            speakerType = SpeakerType.PERSONA, speakerId = "persona",
            speakerDisplayNameSnapshot = "Persona",
            content = "Synthetic test message", orderIndex = 1L
        )
        dao.insertMessage(row)
        val after = withTimeout(10_000) {
            dao.getMessagesForChat(chatId).first { it.isNotEmpty() }
        }
        assertEquals(listOf("first-message"), after.map { it.messageId })
    }
}
