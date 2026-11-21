package com.example

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.ChatEntity
import com.example.data.model.ChatType
import com.example.data.model.MessageAttachmentEntity
import com.example.data.model.MessageEntity
import com.example.data.model.SpeakerType
import com.example.domain.repository.AppDataMaintenanceCoordinator
import com.example.domain.repository.MessageAttachmentRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** UI metadata stays bounded while the complete archive remains available. */
@RunWith(RobolectricTestRunner::class)
class ChatImageMetadataPaginationTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: MessageAttachmentRepository

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = MessageAttachmentRepository(
            context, db, AppDataMaintenanceCoordinator()
        )
    }

    @After fun tearDown() = db.close()

    private fun message(
        id: String, order: Long, chat: String = "main",
        group: String = id, selected: Boolean = true
    ) = MessageEntity(
        messageId = id, chatId = chat, orderIndex = order,
        speakerType = SpeakerType.PERSONA, speakerId = "persona",
        speakerDisplayNameSnapshot = "Mara", content = "Story event $id",
        variantGroupId = group, isPrimaryVariant = selected
    )

    private fun attachment(id: String, messageId: String?, chat: String = "main") =
        MessageAttachmentEntity(
            attachmentId = id, chatId = chat, messageId = messageId,
            relativePath = "story-images/$id.png",
            mimeType = "image/png", byteSize = 123,
            pixelWidth = 4, pixelHeight = 4,
            sha256 = "synthetic-hash-$id", sortPosition = 0
        )

    @Test fun longChatLoadsOnlyVisibleImageMetadataPlusEveryDraft() = runBlocking {
        db.chatDao().insertChat(ChatEntity(
            chatId = "main", displayName = "Very long story", chatType = ChatType.SOLO
        ))
        db.chatDao().insertChat(ChatEntity(
            chatId = "other", displayName = "Another story", chatType = ChatType.SOLO
        ))
        db.withTransaction {
            for (i in 1..10_000) {
                db.messageDao().insertMessage(message("m-$i", i.toLong()))
                if (i % 10 == 0) {
                    db.messageAttachmentDao().insert(
                        attachment("image-$i", "m-$i")
                    )
                }
            }
            // Alternate generated text can have a later insertion order;
            // its image still belongs to the selected bubble's logical group.
            db.messageDao().insertMessage(message(
                "alt-9950", 20_000, group = "m-9950", selected = false
            ))
            db.messageAttachmentDao().insert(attachment("alt-image", "alt-9950"))
            db.messageAttachmentDao().insert(attachment("draft-a", null))
            db.messageAttachmentDao().insert(attachment("draft-b", null))
            db.messageDao().insertMessage(message("private", 99_999, "other"))
            db.messageAttachmentDao().insert(attachment("private-image", "private", "other"))
        }

        val first = repository.observeVisibleForChat("main", 100).first()
        assertEquals(13, first.size)
        assertEquals(setOf("draft-a", "draft-b"),
            first.filter { it.messageId == null }.map { it.attachmentId }.toSet())
        assertTrue(first.any { it.attachmentId == "alt-image" })
        assertTrue(first.any { it.attachmentId == "image-9950" })
        assertTrue(first.none { it.attachmentId == "image-9900" })
        assertTrue(first.none { it.attachmentId == "private-image" })

        val second = repository.observeVisibleForChat("main", 200).first()
        assertEquals(23, second.size)
        assertTrue(second.any { it.attachmentId == "image-9810" })
        assertTrue(second.none { it.attachmentId == "image-9800" })

        val all = db.messageAttachmentDao().getForChat("main")
        assertEquals(1_003, all.size)
        assertTrue(all.any { it.attachmentId == "image-10" })
        println("CHAT_IMAGE_PAGINATION total=10000 archivedImages=${all.size} " +
            "visibleMetadata=${first.size} olderPageMetadata=${second.size} providerCalls=0")
    }

    @Test fun variantSelectionAndDraftAttachmentNeverDropImageMetadata() = runBlocking {
        db.chatDao().insertChat(ChatEntity(
            chatId = "main", displayName = "Images", chatType = ChatType.SOLO
        ))
        db.messageDao().insertMessage(message("m1", 1))
        db.messageDao().insertMessage(message(
            "alternate", 100, group = "m1", selected = false
        ))
        db.messageAttachmentDao().insert(attachment("image-primary", "m1"))
        db.messageAttachmentDao().insert(attachment("image-alt", "alternate"))
        db.messageAttachmentDao().insert(attachment("draft-image", null))
        val before = repository.observeVisibleForChat("main", 1).first()
        assertEquals(3, before.size)

        db.messageDao().clearPrimaryVariants("m1")
        db.messageDao().setPrimaryVariant("alternate")
        val afterSwitch = repository.observeVisibleForChat("main", 1).first()
        assertEquals(before.map { it.attachmentId }.toSet(),
            afterSwitch.map { it.attachmentId }.toSet())

        // Sending a draft turns it into a persisted message image, never
        // loses the metadata, and keeps it outside the draft composer.
        db.messageAttachmentDao().attachDrafts("main", "m1")
        val afterSend = repository.observeVisibleForChat("main", 1).first()
        assertEquals(3, afterSend.size)
        assertEquals(0, afterSend.count { it.messageId == null })
        assertEquals("m1", afterSend.single { it.attachmentId == "draft-image" }.messageId)
        assertEquals(3, db.messageAttachmentDao().getForChat("main").size)
    }

    @Test fun chatWithOnlyDraftsShowsThemWithoutAnyMessages() = runBlocking {
        db.chatDao().insertChat(ChatEntity(
            chatId = "main", displayName = "New story", chatType = ChatType.SOLO
        ))
        db.messageAttachmentDao().insert(attachment("unsent-1", null))
        val rows = repository.observeVisibleForChat("main", 100).first()
        assertEquals(listOf("unsent-1"), rows.map { it.attachmentId })
        assertNull(rows.single().messageId)
    }
}
