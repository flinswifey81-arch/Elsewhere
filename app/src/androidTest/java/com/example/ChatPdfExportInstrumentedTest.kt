package com.example

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.AppDatabase
import com.example.data.model.MessageEntity
import com.example.data.model.SpeakerType
import com.example.domain.export.ChatPdfTurnRange
import com.example.domain.export.ChatTranscriptPdfExporter
import com.example.domain.repository.MessageRepository
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Production code, real native Android PdfDocument + PDF reader, and Room SQL
 * against ONLY a disposable in-memory test database. No user data or network.
 */
@RunWith(AndroidJUnit4::class)
class ChatPdfExportInstrumentedTest {
    @Test
    fun selectedVariantRangeExportsReadableMultipagePdf() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val outputFile = File(context.cacheDir, "qa-chat-transcript-selected-range.pdf")
        try {
            val dao = database.messageDao()
            val chatId = "disposable-pdf-qa"
            for (turn in 1..140) {
                val primary = MessageEntity(
                    messageId = "primary-" + turn,
                    chatId = chatId,
                    speakerType = if (turn % 2 == 0) SpeakerType.CHARACTER else SpeakerType.PERSONA,
                    speakerId = if (turn % 2 == 0) "rowan" else "player",
                    speakerDisplayNameSnapshot = if (turn % 2 == 0) "Rowan" else "Player",
                    content = "Turn " + turn + ": " +
                        ("Long standing memories deserve to be saved accurately. ").repeat(18) +
                        "\nNext paragraph, with a line break.",
                    createdAt = 1_700_000_000_000L + turn,
                    orderIndex = turn.toLong(),
                    variantGroupId = "group-" + turn,
                    isPrimaryVariant = true
                )
                dao.insertMessage(primary)
                // Discarded alternate must never become a PDF turn.
                if (turn == 12 || turn == 60 || turn == 129) {
                    dao.insertMessage(primary.copy(
                        messageId = "discarded-" + turn,
                        content = "UNSELECTED_PRIVATE_ALTERNATE",
                        isPrimaryVariant = false
                    ))
                }
            }
            val exporter = ChatTranscriptPdfExporter(MessageRepository(dao))
            assertEquals(140, exporter.selectedTurnCount(chatId))
            val buffer = ByteArrayOutputStream()
            val exported = exporter.export(
                chatId, "Long-form roleplay — 판타지", ChatPdfTurnRange(7, 131), buffer
            )
            assertEquals(125, exported.turns)
            assertTrue("Long transcript must cross multiple pages", exported.pages > 5)
            val bytes = buffer.toByteArray()
            assertTrue("Generated output must be a PDF", bytes.size > 4000)
            assertTrue("PDF signature", bytes.copyOfRange(0, 5).toString(Charsets.US_ASCII) == "%PDF-")
            outputFile.writeBytes(bytes)
            ParcelFileDescriptor.open(outputFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { pdf ->
                    assertEquals(exported.pages, pdf.pageCount)
                    for (index in listOf(0, pdf.pageCount - 1)) {
                        pdf.openPage(index).use { page ->
                            val bitmap = Bitmap.createBitmap(
                                300, 420, Bitmap.Config.ARGB_8888
                            )
                            try {
                                page.render(
                                    bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                                )
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                }
            }
        } finally {
            database.close()
            outputFile.delete()
        }
    }
}
