package com.example

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.MessageEntity
import com.example.data.model.MessageAttachmentEntity
import com.example.data.model.SpeakerType
import com.example.domain.repository.MessageAttachmentRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MessageAttachmentRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: MessageAttachmentRepository
    private lateinit var source: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MessageAttachmentRepository(context, database)
        source = File(context.cacheDir, "attachment-source.png")
        Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888).let { bitmap ->
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @After
    fun tearDown() {
        database.close()
        source.delete()
    }

    @Test
    fun importedDraftIsCopiedPrivatelyAndPromotedWithMessage() = runBlocking {
        val draft = repository.importDraftImage("chat", Uri.fromFile(source))
        assertEquals(null, draft.messageId)
        assertEquals("image/png", draft.mimeType)
        assertFalse(draft.relativePath.contains(source.absolutePath))
        assertNotNull(repository.resolveFile(draft))

        val message = MessageEntity(
            messageId = "persona-message",
            chatId = "chat",
            speakerType = SpeakerType.PERSONA,
            speakerId = "persona",
            speakerDisplayNameSnapshot = "Persona",
            content = "Look at this",
            orderIndex = 1
        )
        repository.persistMessageWithDrafts(message)

        assertTrue(repository.getDrafts("chat").isEmpty())
        val attached = repository.getForMessage(message.messageId).single()
        val providerImage = repository.providerImagesForMessage(message.messageId).single()
        assertEquals(attached.sha256, providerImage.sha256)
        assertArrayEquals(repository.resolveFile(attached)!!.readBytes(), providerImage.bytes)
    }

    @Test
    fun removingDraftDeletesMetadataAndPrivateFile() = runBlocking {
        val draft = repository.importDraftImage("chat", Uri.fromFile(source))
        val privateFile = repository.resolveFile(draft)!!

        repository.removeDraft(draft)

        assertTrue(repository.getDrafts("chat").isEmpty())
        assertFalse(privateFile.exists())
    }

    @Test
    fun normalizationRemovesTrailingSourceMetadataBytes() = runBlocking {
        source.appendBytes("GPS_PAYLOAD_MARKER".toByteArray())

        val draft = repository.importDraftImage("chat", Uri.fromFile(source))
        val normalizedBytes = repository.resolveFile(draft)!!.readBytes()

        assertFalse(String(normalizedBytes, Charsets.ISO_8859_1).contains("GPS_PAYLOAD_MARKER"))
    }

    @Test
    fun reconciliationRemovesRowsWhoseDeviceLocalFileIsMissing() = runBlocking {
        val draft = repository.importDraftImage("chat", Uri.fromFile(source))
        repository.resolveFile(draft)!!.delete()

        repository.reconcileOrphanFiles()

        assertTrue(repository.getDrafts("chat").isEmpty())
    }

    @Test
    fun decodeSamplingKeepsAcceptedImagesWithinFourMegapixelWorkingSet() {
        assertEquals(1, MessageAttachmentRepository.calculateDecodeSampleSize(2000, 2000))
        assertEquals(2, MessageAttachmentRepository.calculateDecodeSampleSize(4000, 4000))
        assertEquals(2, MessageAttachmentRepository.calculateDecodeSampleSize(8000, 2000))
    }

    @Test
    fun aggregateRequestBudgetAcceptsBoundaryAndRejectsOneByteOver() {
        fun attachment(id: String, bytes: Long) = MessageAttachmentEntity(
            attachmentId = id,
            chatId = "chat",
            messageId = "message",
            relativePath = "message_attachments/$id.jpg",
            mimeType = "image/jpeg",
            byteSize = bytes,
            pixelWidth = 1,
            pixelHeight = 1,
            sha256 = id,
            sortPosition = 0
        )
        MessageAttachmentRepository.validateRequestBudget(
            listOf(
                attachment("a", MessageAttachmentRepository.MAX_IMAGE_BYTES),
                attachment("b", MessageAttachmentRepository.MAX_TOTAL_IMAGE_BYTES - MessageAttachmentRepository.MAX_IMAGE_BYTES)
            )
        )

        val failure = runCatching {
            MessageAttachmentRepository.validateRequestBudget(
                listOf(
                    attachment("a", MessageAttachmentRepository.MAX_IMAGE_BYTES),
                    attachment("b", MessageAttachmentRepository.MAX_TOTAL_IMAGE_BYTES - MessageAttachmentRepository.MAX_IMAGE_BYTES + 1)
                )
            )
        }
        assertTrue(failure.isFailure)
    }
}
