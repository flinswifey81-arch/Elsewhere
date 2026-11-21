package com.example

import com.example.ui.screens.processBatchImports
import com.example.ui.screens.stageDuplicateJsonImports
import com.example.domain.repository.DuplicateIdException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchJsonImportTest {
    @Test
    fun oneFailureDoesNotCancelLaterImportsAndSummaryNamesEveryFile() = runBlocking {
        val attempted = mutableListOf<String>()
        val summary = processBatchImports(
            items = listOf("first.json", "broken.json", "last.json"),
            nameOf = { it },
            importer = { file ->
                attempted += file
                if (file == "broken.json") throw IllegalArgumentException("duplicate identifier")
            }
        )

        assertEquals(listOf("first.json", "broken.json", "last.json"), attempted)
        assertEquals(2, summary.successCount)
        assertEquals(1, summary.failureCount)
        val message = summary.message("character")
        assertTrue(message.contains("first.json"))
        assertTrue(message.contains("last.json"))
        assertTrue(message.contains("broken.json: duplicate identifier"))
    }

    @Test
    fun unreadableDisplayNameUsesFallbackWithoutSkippingTheImport() = runBlocking {
        val imported = mutableListOf<Int>()
        val summary = processBatchImports(
            items = listOf(1, 2),
            nameOf = { item ->
                if (item == 1) throw SecurityException("metadata unavailable")
                "second.json"
            },
            importer = { imported += it }
        )

        assertEquals(listOf(1, 2), imported)
        assertEquals(2, summary.successCount)
        assertEquals(0, summary.failureCount)
        val message = summary.message("file")
        assertTrue(message.contains("File 1"))
        assertTrue(message.contains("second.json"))
    }
    @Test
    fun duplicatesAreStagedOneByOneAndNeverAutoOverwritten() = runBlocking {
        val database = mutableMapOf("same-id" to "Original saved card")
        val attempted = mutableListOf<String>()
        val batch = stageDuplicateJsonImports(
            items = listOf(
                "new-card.json" to "unique-id|New card",
                "existing-card.json" to "same-id|Updated card",
                "new-again.json" to "unique-id|Different definition"
            ),
            nameOf = { it.first },
            read = { it.second },
            importer = { json ->
                val (id, newName) = json.split('|')
                attempted += id
                if (database.containsKey(id)) {
                    throw DuplicateIdException("ID exists", id)
                }
                database[id] = newName
            }
        )
        assertEquals(1, batch.completed.size)
        assertEquals(2, batch.duplicates.size)
        assertEquals(listOf("same-id", "unique-id"), batch.duplicates.map { it.id })
        assertEquals("Original saved card", database["same-id"])
        assertEquals("New card", database["unique-id"])
        assertEquals(listOf("new-card.json", "existing-card.json", "new-again.json"),
            listOf(batch.completed.single().fileName) + batch.duplicates.map { it.fileName })
        assertEquals(3, attempted.size)
    }

    @Test
    fun invalidJsonContinuesBatchWithoutTurningItIntoAReplacement() = runBlocking {
        val batch = stageDuplicateJsonImports(
            items = listOf("unreadable.json", "bad-card.json", "good.json"),
            nameOf = { it },
            read = {
                if (it == "unreadable.json") throw IllegalArgumentException("Unreadable")
                it
            },
            importer = {
                if (it == "bad-card.json") throw IllegalArgumentException("Not a valid card")
            }
        )
        assertEquals(1, batch.completed.count { it.succeeded })
        assertEquals(2, batch.completed.count { it.error != null })
        assertTrue(batch.duplicates.isEmpty())
        assertEquals("good.json", batch.completed.single { it.succeeded }.fileName)
    }

    @Test
    fun canceledOverwriteCountsAsKeptUnchangedNotFailure() {
        val summary = com.example.ui.screens.BatchImportSummary(
            listOf(
                com.example.ui.screens.BatchImportResult("updated.json", updated = true),
                com.example.ui.screens.BatchImportResult("kept.json", skipped = true),
                com.example.ui.screens.BatchImportResult("bad.json", error = "invalid schema")
            )
        )
        assertEquals(1, summary.successCount)
        assertEquals(1, summary.updatedCount)
        assertEquals(1, summary.skippedCount)
        assertEquals(1, summary.failureCount)
        assertTrue(summary.message("character").contains("Kept unchanged"))
        assertTrue(summary.message("character").contains("updated existing"))
    }

}
