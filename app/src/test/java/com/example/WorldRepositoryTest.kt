package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.ChatEntity
import com.example.data.model.ChatType
import com.example.data.model.WorldResourceType
import com.example.domain.repository.AppDataMaintenanceCoordinator
import com.example.domain.repository.WorldRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorldRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var worlds: WorldRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        worlds = WorldRepository(database, AppDataMaintenanceCoordinator())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun worldsCanLinkAnExistingChatWithoutTakingOwnershipOfItsHistory() = runBlocking {
        val chat = ChatEntity(chatId = "chat-original", displayName = "Piper and her Alphas", chatType = ChatType.SOLO)
        database.chatDao().insertChat(chat)
        val world = worlds.createWorld("Law & Disorder: Mondstadt")
        worlds.attach(world.worldId, WorldResourceType.CHAT, chat.chatId)

        assertEquals("CHAT", worlds.getMemberships(world.worldId).single().resourceType)
        assertEquals(chat.chatId, worlds.getMemberships(world.worldId).single().resourceId)

        // Reject both missing references and accidental duplicate World membership.
        assertTrue(runCatching {
            worlds.attach(world.worldId, WorldResourceType.CHAT, "absent-chat")
        }.isFailure)
        assertTrue(runCatching {
            worlds.attach(world.worldId, WorldResourceType.CHAT, chat.chatId)
        }.isFailure)

        worlds.deleteWorld(world.worldId)
        assertTrue(database.worldDao().getWorlds().isEmpty())
        assertTrue(database.worldDao().getMemberships(world.worldId).isEmpty())
        assertNotNull(database.chatDao().getChatById(chat.chatId))
    }

    @Test
    fun castAndContinuityStayScopedToTheirWorld() = runBlocking {
        val first = worlds.createWorld("Mondstadt")
        val second = worlds.createWorld("Another Teyvat AU")
        val varka = worlds.createCastMember(first.worldId, "Varka", "Police Commissioner")
        val otherVarka = worlds.createCastMember(second.worldId, "Varka", "A different AU")
        assertTrue(varka.worldCharacterId != otherVarka.worldCharacterId)
        assertEquals(1, worlds.getCast(first.worldId).size)
        assertEquals(1, worlds.getCast(second.worldId).size)

        val event = worlds.draftEvent(first.worldId, "First meeting", "Piper meets Flins and Varka.")
        assertEquals("DRAFT", worlds.getEvents(first.worldId).single().status)
        assertTrue(runCatching { worlds.acceptEvent(second.worldId, event.eventId) }.isFailure)
        assertEquals("DRAFT", worlds.getEvents(first.worldId).single().status)
        worlds.acceptEvent(first.worldId, event.eventId)
        assertEquals("ACCEPTED", worlds.getEvents(first.worldId).single().status)
        assertTrue(runCatching { worlds.acceptEvent(first.worldId, event.eventId) }.isFailure)

        worlds.deleteWorld(first.worldId)
        assertTrue(worlds.getCast(first.worldId).isEmpty())
        assertTrue(worlds.getEvents(first.worldId).isEmpty())
        assertEquals(1, worlds.getCast(second.worldId).size)
        assertFalse(database.worldDao().getWorlds().isEmpty())
    }
}
