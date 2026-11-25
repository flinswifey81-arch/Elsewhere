package com.example

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.domain.repository.DuplicateIdException
import com.example.ui.screens.ProfileJsonImportWorkflow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A matching ID cannot trigger a write until the person explicitly confirms. */
@RunWith(RobolectricTestRunner::class)
class ProfileJsonImportWorkflowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun matchingCharacterIdWaitsForConfirmationBeforeOverwriting() {
        var current = "Old bot definition"
        val calls = mutableListOf<Pair<Boolean, Boolean>>()
        compose.setContent {
            var showOptions by remember { mutableStateOf(true) }
            MaterialTheme {
                ProfileJsonImportWorkflow(
                    type = "Character",
                    showOptions = showOptions,
                    onCloseOptions = { showOptions = false },
                    importProfile = { json, replace, asNew ->
                        calls += replace to asNew
                        if (!replace && !asNew)
                            throw DuplicateIdException("Character ID exists", "same-bot-id")
                        if (replace) current = json
                        "same-bot-id"
                    },
                    findExistingName = { "Itsuki" }
                )
            }
        }
        compose.onNodeWithText("Paste JSON").performClick()
        compose.onNodeWithText("JSON Content")
            .performTextInput("""{"character_id":"same-bot-id","display_name":"Itsuki"}""")
        compose.onNodeWithText("Import").performClick()
        compose.onNodeWithText("Character ID already exists").assertExists()
        compose.onNodeWithTag("confirm_replace_character_json").assertExists()
        assertEquals("Old bot definition", current)
        assertEquals(listOf(false to false), calls)

        compose.onNodeWithTag("confirm_replace_character_json").performClick()
        compose.waitUntil(8_000) { current.contains("same-bot-id") }
        assertEquals(listOf(false to false, true to false), calls)
        compose.onNodeWithText("Character Import").assertExists()
    }

    @Test fun choosingKeepExistingNeverReplacesTheCard() {
        val calls = mutableListOf<Pair<Boolean, Boolean>>()
        compose.setContent {
            var open by remember { mutableStateOf(true) }
            MaterialTheme {
                ProfileJsonImportWorkflow(
                    type = "Persona",
                    showOptions = open,
                    onCloseOptions = { open = false },
                    importProfile = { _, replace, asNew ->
                        calls += replace to asNew
                        throw DuplicateIdException("Persona ID exists", "same-persona")
                    },
                    findExistingName = { "Piper" }
                )
            }
        }
        compose.onNodeWithText("Paste JSON").performClick()
        compose.onNodeWithText("JSON Content").performTextInput(
            """{"persona_id":"same-persona","display_name":"Piper"}"""
        )
        compose.onNodeWithText("Import").performClick()
        compose.onNodeWithText("Persona ID already exists").assertExists()
        compose.onNodeWithText("Keep existing").performClick()
        assertEquals(listOf(false to false), calls)
        compose.onNodeWithText("Persona Import").assertExists()
        compose.onNodeWithText("Kept unchanged", substring = true).assertExists()
    }
}
