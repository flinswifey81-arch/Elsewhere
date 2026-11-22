package com.example

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.ChatEntity
import com.example.data.model.ChatType
import com.example.domain.repository.CampaignRepository
import com.example.domain.repository.ChatRepository
import com.example.domain.repository.WorldRepository
import com.example.ui.screens.WorldsScreen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CampaignWorldsScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: AppDatabase
    private lateinit var worlds: WorldRepository
    private lateinit var campaigns: CampaignRepository
    private lateinit var chats: ChatRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        worlds = WorldRepository(db)
        campaigns = CampaignRepository(db)
        chats = ChatRepository(db.chatDao(), db.messageDao())
    }
    @After fun tearDown() = db.close()

    @Test fun arcCanBeAuthoredAndCompletedFromTheCampaignUi() {
        val world = runBlocking { worlds.createWorld("Arc setting") }
        val campaign = runBlocking { campaigns.createCampaign(world.worldId, "Arc campaign") }
        compose.setContent {
            MaterialTheme {
                WorldsScreen(worlds, chatRepository = chats, campaignRepository = campaigns)
            }
        }
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("world_card_" + world.worldId)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("world_card_" + world.worldId).performClick()
        compose.onNodeWithTag("worlds_list")
            .performScrollToNode(hasTestTag("open_campaign_" + campaign.campaignId))
        compose.onNodeWithTag("open_campaign_" + campaign.campaignId).performClick()
        compose.onNodeWithTag("worlds_list")
            .performScrollToNode(hasTestTag("campaign_new_arc"))
        compose.onNodeWithTag("campaign_new_arc").assertIsEnabled().performClick()
        compose.onNodeWithTag("campaign_continuity_title").performTextInput("The First Arc")
        compose.onNodeWithTag("campaign_continuity_details")
            .performTextInput("Our story began here.")
        compose.onNodeWithTag("save_campaign_continuity").assertIsEnabled().performClick()
        compose.waitUntil(20_000) {
            runBlocking { campaigns.continuityItems(campaign.campaignId).size == 1 }
        }
        val arc = runBlocking { campaigns.continuityItems(campaign.campaignId).single() }
        assertEquals("ARC", arc.itemType)
        assertEquals("ACTIVE", arc.status)
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("edit_campaign_continuity_" + arc.itemId)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("edit_campaign_continuity_" + arc.itemId).performClick()
        compose.onNodeWithTag("campaign_continuity_complete").performClick()
        compose.onNodeWithTag("campaign_continuity_resolution")
            .performTextInput("The conclusion was peaceful.")
        compose.onNodeWithTag("save_campaign_continuity").assertIsEnabled().performClick()
        compose.waitUntil(20_000) {
            runBlocking {
                campaigns.continuityItems(campaign.campaignId)
                    .singleOrNull()?.status == "COMPLETE"
            }
        }
        assertEquals("The conclusion was peaceful.", runBlocking {
            campaigns.continuityItems(campaign.campaignId).single().resolution
        })
        assertEquals(0, runBlocking { worlds.getEvents(world.worldId).size })
    }

    @Test fun campaignJournalCanBeSavedThroughWorldUiWithoutChangingChat() {
        val world = runBlocking { worlds.createWorld("Journal World") }
        val campaign = runBlocking {
            campaigns.createCampaign(world.worldId, "Campaign A")
        }
        runBlocking {
            db.chatDao().insertChat(ChatEntity(
                chatId = "journal-test-chat", displayName = "Original RP", chatType = ChatType.SOLO
            ))
        }
        compose.setContent {
            MaterialTheme {
                WorldsScreen(worlds, chatRepository = chats, campaignRepository = campaigns)
            }
        }
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("world_card_" + world.worldId)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("world_card_" + world.worldId).performClick()
        compose.onNodeWithTag("worlds_list")
            .performScrollToNode(hasTestTag("open_campaign_" + campaign.campaignId))
        compose.onNodeWithTag("open_campaign_" + campaign.campaignId).performClick()
        compose.onNodeWithTag("worlds_list")
            .performScrollToNode(hasTestTag("add_campaign_journal"))
        compose.onNodeWithTag("add_campaign_journal").assertIsEnabled().performClick()
        compose.onNodeWithTag("campaign_journal_title").performTextInput("First Discovery")
        compose.onNodeWithTag("campaign_journal_body")
            .performTextInput("The observatory is still unexplored.")
        compose.onNodeWithTag("save_campaign_journal_entry").assertIsEnabled().performClick()
        compose.waitUntil(20_000) {
            runBlocking { campaigns.journal(campaign.campaignId).size == 1 }
        }
        val saved = runBlocking { campaigns.journal(campaign.campaignId).single() }
        assertEquals("JOURNAL", saved.entryType)
        assertEquals("First Discovery", saved.title)
        assertEquals("The observatory is still unexplored.", saved.body)
        assertNotNull(runBlocking { db.chatDao().getChatById("journal-test-chat") })

        compose.waitForIdle()
        compose.onNodeWithTag("worlds_list").performScrollToNode(
            hasTestTag("draft_world_from_journal_" + saved.journalEntryId)
        )
        compose.onNodeWithTag("draft_world_from_journal_" + saved.journalEntryId)
            .assertIsEnabled().performClick()
        compose.onNodeWithTag("world_draft_from_campaign_facts").performTextClearance()
        compose.onNodeWithTag("world_draft_from_campaign_facts")
            .performTextInput("Reviewed fact for another Campaign only if explicitly accepted.")
        compose.onNodeWithTag("propose_world_draft_from_campaign")
            .assertIsEnabled().performClick()
        compose.waitUntil(20_000) {
            runBlocking { worlds.getEvents(world.worldId).size == 1 }
        }
        val draft = runBlocking { worlds.getEvents(world.worldId).single() }
        assertEquals("DRAFT", draft.status)
        assertEquals("First Discovery", draft.title)
        assertEquals(
            "Reviewed fact for another Campaign only if explicitly accepted.",
            draft.facts
        )
        assertEquals(saved.body, runBlocking {
            campaigns.journal(campaign.campaignId).single().body
        })
        assertNotNull(runBlocking { db.chatDao().getChatById("journal-test-chat") })
    }

    @Test fun creatingACampaignThroughWorldUiPreservesStandaloneChat() {
        val world = runBlocking { worlds.createWorld("Independent setting") }
        runBlocking {
            db.chatDao().insertChat(ChatEntity(
                chatId = "old-rp", displayName = "Existing roleplay", chatType = ChatType.SOLO
            ))
        }
        var requestedChapter: Pair<String, String>? = null
        compose.setContent {
            MaterialTheme {
                WorldsScreen(
                    worlds,
                    chatRepository = chats,
                    campaignRepository = campaigns,
                    onCreateSoloCampaignChapter = { worldId, campaignId ->
                        requestedChapter = worldId to campaignId
                    }
                )
            }
        }
        compose.waitUntil(20_000) {
            compose.onAllNodesWithTag("world_card_" + world.worldId).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("world_card_" + world.worldId).performClick()
        // LazyColumn's Campaign section may be beyond the viewport; its child
        // semantics do not exist until the list scrolls it into composition.
        compose.onNodeWithTag("worlds_list").performScrollToNode(hasTestTag("create_campaign"))
        compose.onNodeWithTag("create_campaign").assertIsEnabled().performClick()
        compose.onNodeWithTag("campaign_title_field").performTextInput("My Campaign")
        compose.onNodeWithTag("save_campaign").assertIsEnabled().performClick()

        compose.waitUntil(20_000) {
            runBlocking { campaigns.forWorld(world.worldId).size == 1 }
        }
        val stored = runBlocking { campaigns.forWorld(world.worldId).single() }
        assertEquals("My Campaign", stored.title)
        assertEquals(world.worldId, stored.worldId)
        assertNotNull(runBlocking { db.chatDao().getChatById("old-rp") })
        assertEquals(0, runBlocking { campaigns.chapters(stored.campaignId).size })
        compose.onNodeWithTag("worlds_list")
            .performScrollToNode(hasTestTag("start_campaign_solo_chapter"))
        compose.onNodeWithTag("start_campaign_solo_chapter")
            .assertIsEnabled().performClick()
        assertEquals(world.worldId to stored.campaignId, requestedChapter)
    }
}
