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
