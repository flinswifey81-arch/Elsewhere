package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.*
import com.example.domain.repository.AppDataMaintenanceCoordinator
import com.example.domain.repository.LorebookImportMode
import com.example.domain.repository.LorebookRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LorebookSameIdReplaceTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: LorebookRepository

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = LorebookRepository(
            database.lorebookDao(),
            Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build(),
            AppDataMaintenanceCoordinator()
        )
    }
    @After fun teardown() { database.close() }

    @Test fun matchingLorebookJsonUpdatesRetainedEntriesWithoutRemovingChatOverrides() = runBlocking {
        val original = LorebookEntity(lorebookId = "library-world", name = "Original")
        val retained = LorebookEntryEntity(
            entryId = "retained-entry", lorebookId = original.lorebookId,
            title = "Retained", canonContent = "Original canon", currentContent = "Old version"
        )
        val removed = LorebookEntryEntity(
            entryId = "deliberately-removed", lorebookId = original.lorebookId,
            title = "Removed from replacement", currentContent = "Will be deleted"
        )
        repository.saveDefinition(original, listOf(retained, removed))
        database.lorebookDao().upsertAttachment(
            ChatLorebookAttachmentEntity(
                chatId = "ongoing-rp", lorebookId = original.lorebookId,
                isEnabled = false, sortPosition = 8
            )
        )
        val overlay = ChatLorebookEntryOverlayEntity(
            chatId = "ongoing-rp", entryId = retained.entryId,
            hasCurrentOverride = true, currentContent = "My private chat override",
            manualActive = true
        )
        database.lorebookDao().upsertOverlay(overlay)
        database.lorebookDao().upsertOverlay(
            ChatLorebookEntryOverlayEntity(
                chatId = "ongoing-rp", entryId = removed.entryId,
                hasCurrentOverride = true, currentContent = "Only for removed entry"
            )
        )

        // Export then build an imported same-ID definition with one retained entry.
        val json = repository.exportJson(original.lorebookId)
        val parsed = repository.parseLorebookJson(json)
        val updated = parsed.copy(
            lorebook = parsed.lorebook.copy(name = "Improved Lorebook"),
            entries = listOf(parsed.entries.first { it.entryId == retained.entryId }
                .copy(title = "Updated retained entry", canonContent = "Fixed canon"))
        )
        // Reuse the repository's real exported schema instead of handcrafting an
        // incompatible format: replace only the relevant JSON fields.
        val modifiedJson = json
            .replace("\"name\": \"Original\"", "\"name\": \"Improved Lorebook\"")
            .replace("\"title\": \"Retained\"", "\"title\": \"Updated retained entry\"")
            .replace("\"Original canon\"", "\"Fixed canon\"")
        // Including both entries also verifies that retained overlay survives
        // even if the other entry is kept by the source file.
        assertEquals(original.lorebookId, repository.importJson(
            modifiedJson, LorebookImportMode.REPLACE_EXISTING
        ))
        assertEquals("Improved Lorebook", database.lorebookDao().getLorebook(original.lorebookId)?.name)
        val entryAfter = database.lorebookDao().getEntry(retained.entryId)
        assertEquals("Updated retained entry", entryAfter?.title)
        assertEquals("Fixed canon", entryAfter?.canonContent)
        assertEquals(retained.createdAt, entryAfter?.createdAt)
        assertEquals(original.createdAt,
            database.lorebookDao().getLorebook(original.lorebookId)?.createdAt)
        assertEquals(overlay,
            database.lorebookDao().getOverlays("ongoing-rp").single {
                it.entryId == retained.entryId
            })
        val attachment = database.lorebookDao().getAttachments("ongoing-rp").single()
        assertEquals(original.lorebookId, attachment.lorebookId)
        assertFalse(attachment.isEnabled)
        assertEquals(8, attachment.sortPosition)

        // A matching-ID overwrite cannot replace the whole Lorebook with a
        // different identity or remove the attached chat's state.
        assertEquals(1, database.lorebookDao().getLorebooksOnce().size)
        assertEquals("Updated retained entry",
            database.lorebookDao().getEntries(original.lorebookId)
                .single { it.entryId == retained.entryId }.title)
    }

    @Test fun replacingNonexistentLorebookFailsInsteadOfCreatingNewRecord() = runBlocking {
        val temporary = LorebookEntity(lorebookId = "not-installed", name = "Foreign")
        repository.saveDefinition(temporary, emptyList())
        val json = repository.exportJson(temporary.lorebookId)
        database.lorebookDao().deleteLorebook(temporary.lorebookId)

        assertTrue(runCatching {
            repository.importJson(json, LorebookImportMode.REPLACE_EXISTING)
        }.isFailure)
        assertTrue(database.lorebookDao().getLorebooksOnce().isEmpty())
    }
}
