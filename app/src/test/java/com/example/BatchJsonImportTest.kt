package com.example

import com.example.ui.screens.processBatchImports
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
}
