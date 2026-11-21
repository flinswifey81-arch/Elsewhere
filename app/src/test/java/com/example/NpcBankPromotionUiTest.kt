package com.example

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.ChatEntity
import com.example.data.model.ChatType
import com.example.data.model.NpcDossier
import com.example.domain.repository.AppDataMaintenanceCoordinator
import com.example.domain.repository.ChatRepository
import com.example.domain.repository.NpcRepository
import com.example.ui.screens.NpcBankScreen
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NpcBankPromotionUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: AppDatabase
    private lateinit var npc: NpcRepository
    private lateinit var chats: ChatRepository

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        val coordinator = AppDataMaintenanceCoordinator()
        npc = NpcRepository(db, Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory()).build(), coordinator)
        chats = ChatRepository(db.chatDao(), db.messageDao(), coordinator)
        runBlocking {
            db.chatDao().insertChat(ChatEntity(
                chatId = "storybook", displayName = "Old story",
                chatType = ChatType.SOLO
            ))
            npc.saveManual(NpcDossier(
                npcId = "ui-npc", chatId = "storybook", displayName = "Mara",
                aliases = emptyList(), role = "Keeper",
                appearance = "Green coat", personality = "Focused",
                relationshipToPersona = "Private to this RP",
                personaRelationships = emptyMap(), characterKnowledge = emptyMap(),
                goals = "Guard the library", secrets = "Keep secret",
                authorNotes = "Private author note",
                keepInScene = false, isManualBase = true,
                createdAt = 1L, updatedAt = 1L
            ))
        }
    }
    @After fun teardown() { db.close() }

    @Test fun npcConversionRequiresExplicitConfirmationAndRetainsOriginalDossier() {
        compose.setContent {
            MaterialTheme {
                NpcBankScreen(
                    chatId = "storybook",
                    repository = npc,
                    chatRepository = chats,
                    onNavigateBack = {}
                )
            }
        }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("promote_npc_ui-npc")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("promote_npc_ui-npc").performScrollTo().performClick()
        compose.onNodeWithTag("confirm_promote_npc").assertExists()
        assertNull(runBlocking { db.characterDao().getCharacterById("ui-npc") })
        compose.onNodeWithText("Cancel").performClick()
        assertNull(runBlocking { db.characterDao().getCharacterById("ui-npc") })
        compose.onNodeWithTag("promote_npc_ui-npc").performClick()
        compose.onNodeWithTag("confirm_promote_npc").performClick()
        compose.waitUntil(15_000) {
            runBlocking { db.characterDao().getCharacterById("ui-npc") != null }
        }
        assertNotNull(runBlocking { db.npcDao().getDossier("ui-npc") })
        assertEquals("Mara", runBlocking {
            db.characterDao().getCharacterById("ui-npc")?.displayName
        })
    }
}
