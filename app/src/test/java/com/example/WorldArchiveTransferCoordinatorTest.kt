package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.*
import com.example.domain.repository.AppDataMaintenanceCoordinator
import com.example.domain.repository.MessageAttachmentRepository
import com.example.domain.repository.ProfileImageRepository
import com.example.domain.world.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class WorldArchiveTransferCoordinatorTest {
    private lateinit var database: AppDatabase
    private lateinit var transfer: WorldArchiveTransferCoordinator
    private lateinit var coordinator: AppDataMaintenanceCoordinator
    private lateinit var context: Context
    private val sessionId = UUID.randomUUID().toString()
    private val sourceWorldId = "source-$sessionId"
    private val sourceChatId = "chat-$sessionId"
    private val messageId = "message-$sessionId"

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        coordinator = AppDataMaintenanceCoordinator()
        val restorer = WorldArchiveAdditiveRestorer(database, context.filesDir, coordinator)
        transfer = WorldArchiveTransferCoordinator(
            context, database,
            MessageAttachmentRepository(context, database, coordinator),
            ProfileImageRepository(context, coordinator),
            restorer, coordinator
        )
    }

    @After fun tearDown() { database.close() }

    private suspend fun seedWorld(note: Boolean = false) {
        database.worldDao().insertWorld(
            WorldEntity(worldId = sourceWorldId, title = "A Clockwork Wonderland")
        )
        database.backupDao().insertChats(listOf(
            ChatEntity(chatId = sourceChatId, chatType = ChatType.SOLO, displayName = "The Tea Room")
        ))
        database.backupDao().insertMessages(listOf(
            MessageEntity(messageId = messageId, chatId = sourceChatId,
                speakerType = SpeakerType.SYSTEM, speakerId = "narrator",
                speakerDisplayNameSnapshot = "Narrator",
                content = "The clock struck thirteen.", orderIndex = 0)
        ))
        database.backupDao().insertChatSettings(listOf(
            ChatSettingsEntity(chatId = sourceChatId, draftMessage = if (note) "Never shared draft" else null)
        ))
        database.worldDao().insertMembership(
            WorldMembershipEntity(sourceWorldId, WorldResourceType.CHAT.name, sourceChatId)
        )
    }

    @Test fun previewExportInspectAndAdditiveImportKeepOldWorldIntact() = runBlocking {
        seedWorld()
        val preview = transfer.previewExport(sourceWorldId)
        assertEquals("A Clockwork Wonderland", preview.title)
        assertEquals(1, preview.messages)
        assertEquals(1, preview.chats)
        assertFalse(preview.containsPrivateMaterial)

        val output = ByteArrayOutputStream()
        val password = "a secure world password".toCharArray()
        transfer.exportReviewed(preview, output, password, includePrivateMaterial = false)
        assertTrue(password.all { it == '\u0000' })
        assertTrue(output.size() > 80)

        val unlock = "a secure world password".toCharArray()
        val candidate = transfer.inspectForImport(
            ByteArrayInputStream(output.toByteArray()), unlock
        )
        candidate.use {
            assertEquals(1, it.preview.chats)
            assertEquals(1, it.preview.messages)
            assertEquals("A Clockwork Wonderland", it.preview.worldTitle)
            val confirm = "a secure world password".toCharArray()
            val result = transfer.importReviewed(it, confirm)
            assertTrue(confirm.all { it == '\u0000' })
            assertNotEquals(sourceWorldId, result.worldId)
            assertEquals(2, database.worldDao().getWorlds().size)
            assertEquals(2, database.backupDao().getChats().size)
            assertEquals(2, database.backupDao().getMessages().size)
            assertEquals("The clock struck thirteen.",
                database.backupDao().getMessages().single { m ->
                    m.chatId == sourceChatId
                }.content)
            assertNotNull(database.worldDao().getWorld(sourceWorldId))
            assertNotNull(database.worldDao().getWorld(result.worldId))
        }
    }

    @Test fun exportRejectsChangesAfterApprovalBeforeWritingAnyBytes() = runBlocking {
        seedWorld()
        val approved = transfer.previewExport(sourceWorldId)
        database.backupDao().insertMessages(listOf(
            MessageEntity(messageId = "new-$sessionId", chatId = sourceChatId,
                speakerType = SpeakerType.SYSTEM, speakerId = "narrator",
                speakerDisplayNameSnapshot = "Narrator", content = "A new chapter.", orderIndex = 1)
        ))
        val output = ByteArrayOutputStream()
        val password = "a secure world password".toCharArray()
        val failedExport = runCatching {
            transfer.exportReviewed(approved, output, password, includePrivateMaterial = false)
        }
        assertTrue(failedExport.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, output.size())
        assertTrue(password.all { it == '\u0000' })
    }

    @Test fun privateDraftsRequireExplicitExportConsent() = runBlocking {
        seedWorld(note = true)
        val approved = transfer.previewExport(sourceWorldId)
        assertTrue(approved.containsUnsentDrafts)
        val result = runCatching {
            transfer.exportReviewed(approved, ByteArrayOutputStream(),
                "a secure world password".toCharArray(), includePrivateMaterial = false)
        }
        assertTrue(result.isFailure)
        val written = ByteArrayOutputStream()
        transfer.exportReviewed(approved, written,
            "a secure world password".toCharArray(), includePrivateMaterial = true)
        val candidate = transfer.inspectForImport(
            ByteArrayInputStream(written.toByteArray()),
            "a secure world password".toCharArray()
        )
        candidate.use { assertTrue(it.preview.containsUnsentDrafts) }
    }

    @Test fun archiveHistoryFollowsExplicitWorldLinksWithoutDeletingTheUnderlyingChat() = runBlocking {
        seedWorld()
        assertEquals(1, transfer.previewExport(sourceWorldId).chats)
        assertEquals(1, transfer.localChatChoices().count { it.chatId == sourceChatId })
        database.worldDao().deleteMembership(
            sourceWorldId, WorldResourceType.CHAT.name, sourceChatId
        )
        val withoutLink = transfer.previewExport(sourceWorldId)
        assertEquals(0, withoutLink.chats)
        assertEquals(0, withoutLink.messages)
        assertEquals(1, database.backupDao().getChats().size)
        assertEquals("The clock struck thirteen.",
            database.backupDao().getMessages().single().content)
        database.worldDao().insertMembership(
            WorldMembershipEntity(sourceWorldId, WorldResourceType.CHAT.name, sourceChatId)
        )
        assertEquals(1, transfer.previewExport(sourceWorldId).chats)
    }

    @Test fun wrongConfirmationPasswordDoesNotWriteImportedRows() = runBlocking {
        seedWorld()
        val preview = transfer.previewExport(sourceWorldId)
        val output = ByteArrayOutputStream()
        transfer.exportReviewed(preview, output,
            "a secure world password".toCharArray(), false)
        transfer.inspectForImport(ByteArrayInputStream(output.toByteArray()),
            "a secure world password".toCharArray()).use { reviewed ->
            val result = runCatching {
                transfer.importReviewed(reviewed, "wrong password here".toCharArray())
            }
            assertTrue(result.isFailure)
            assertEquals(listOf(sourceWorldId), database.worldDao().getWorlds().map { it.worldId })
            assertEquals(listOf(sourceChatId), database.backupDao().getChats().map { it.chatId })
        }
    }
}
