package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.EngineDefaultsEntity
import com.example.data.model.ManualPersonaFields
import com.example.data.model.ProviderRoutingMode
import com.example.data.model.ResponseLengthProfile
import com.example.data.model.ScenarioDefinition
import com.example.domain.repository.AppDataMaintenanceCoordinator
import com.example.domain.repository.CharacterRepository
import com.example.domain.repository.ChatRepository
import com.example.domain.repository.NpcRepository
import com.example.domain.repository.PersonaRepository
import com.example.domain.repository.ScenarioRepository
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
class UsabilityPassIntegrationTest {
    private lateinit var db: AppDatabase
    private lateinit var characters: CharacterRepository
    private lateinit var personas: PersonaRepository
    private lateinit var chats: ChatRepository
    private lateinit var npcs: NpcRepository

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val coordinator = AppDataMaintenanceCoordinator()
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        characters = CharacterRepository(db.characterDao(), moshi, dataCoordinator = coordinator)
        personas = PersonaRepository(db.personaDao(), moshi, dataCoordinator = coordinator)
        chats = ChatRepository(db.chatDao(), db.messageDao(), coordinator)
        npcs = NpcRepository(db, moshi, coordinator)
    }

    @After fun tearDown() = db.close()

    @Test fun importedRootAndNestedOpeningsRoundTripAndSelectedGreetingStartsChatWithoutGeneration() = runBlocking {
        val characterId = characters.importCharacterJson("""{
          "schema_version":1,"character_id":"opening-character","display_name":"Rin","character_name":"Rin",
          "first_mes":"Root greeting", "alternate_greetings":["Alternate one","Alternate two"],
          "data":{"greeting":"Nested greeting","alternate_greetings":["Nested alternate"]}
        }""".trimIndent())
        val character = requireNotNull(characters.getCharacterById(characterId))
        assertEquals(listOf("Root greeting", "Alternate one", "Alternate two", "Nested greeting", "Nested alternate"), characters.getOpeningMessages(character))
        val exported = characters.exportCharacterJson(characterId)
        assertTrue(exported.contains("alternate_greetings"))
        val editable = characters.getManualFields(character)
        assertEquals("Root greeting", editable.initialMessage)
        characters.updateManualCharacter(characterId, editable.copy(initialMessage = "Edited greeting"))
        val editedOpenings = characters.getOpeningMessages(requireNotNull(characters.getCharacterById(characterId)))
        assertEquals("Edited greeting", editedOpenings.first())
        assertTrue(editedOpenings.containsAll(listOf("Alternate one", "Alternate two", "Nested greeting", "Nested alternate")))
        val duplicateId = characters.duplicateCharacter(characterId)
        assertEquals(editedOpenings, characters.getOpeningMessages(requireNotNull(characters.getCharacterById(duplicateId))))
        val personaId = personas.createManualPersona(ManualPersonaFields(name = "Player"))
        val chatId = chats.createSoloChat("Opening", personaId, characterId, "Rin", "Alternate two")
        val first = db.messageDao().getMessagesForChatOnce(chatId).single()
        assertEquals("Alternate two", first.content)
        assertEquals(characterId, first.speakerId)
    }

    @Test fun reusableNpcBanksCopyEntireOrSelectedCastsWithIndependentIdsAndState() = runBlocking {
        val bankId = npcs.importLibraryBank("Court", """{"schema_version":1,"npcs":[
          {"npc_id":"a","display_name":"Aster","role":"Envoy"},
          {"npc_id":"b","display_name":"Briar","role":"Guard"}
        ]}""")
        val bank = requireNotNull(npcs.getLibraryBank(bankId))
        val firstChat = createChat("First")
        val secondChat = createChat("Second")
        assertEquals(2, npcs.addLibraryNpcsToChat(firstChat, bankId))
        val selected = bank.entries.single { it.displayName == "Briar" }
        assertEquals(1, npcs.addLibraryNpcsToChat(secondChat, bankId, setOf(selected.libraryNpcId)))
        val firstCopies = npcs.getProjectedDossiers(firstChat)
        val secondCopy = npcs.getProjectedDossiers(secondChat).single()
        assertEquals(setOf("Aster", "Briar"), firstCopies.map { it.displayName }.toSet())
        assertEquals("Briar", secondCopy.displayName)
        assertTrue(firstCopies.none { it.npcId == secondCopy.npcId || it.npcId in setOf("a", "b") })
        npcs.saveManual(secondCopy.copy(role = "Captain"))
        assertEquals("Guard", npcs.getProjectedDossiers(firstChat).single { it.displayName == "Briar" }.role)
        assertEquals("Guard", requireNotNull(npcs.getLibraryBank(bankId)).entries.single { it.displayName == "Briar" }.role)
    }

    @Test fun newChatsInheritGlobalEngineWhileExistingOverridesRemainStableAcrossModes() = runBlocking {
        chats.saveEngineDefaults(EngineDefaultsEntity(selectedModelId = "global/model", providerRoutingMode = ProviderRoutingMode.PREFER,
            providerEndpoint = "provider/one", temperature = .65f, topP = .8f, responseLengthProfile = ResponseLengthProfile.LONG))
        val chatId = createChat("Inherited")
        assertTrue(requireNotNull(chats.getChatSettings(chatId)).useGlobalEngineDefaults)
        val effective = requireNotNull(chats.getEffectiveChatSettings(chatId))
        assertEquals("global/model", effective.selectedModelId)
        assertEquals(.65f, effective.temperature)
        val existing = requireNotNull(chats.getChatSettings(chatId)).copy(useGlobalEngineDefaults = false, selectedModelId = "chat/model", narratorEnabled = true)
        chats.insertChatSettings(existing)
        chats.saveEngineDefaults((chats.getEngineDefaults()).copy(selectedModelId = "new/global"))
        val preserved = requireNotNull(chats.getEffectiveChatSettings(chatId))
        assertEquals("chat/model", preserved.selectedModelId)
        assertTrue(preserved.narratorEnabled)

        val characterId = characters.importCharacterJson("""{"schema_version":1,"character_id":"group-lead","display_name":"Lead","character_name":"Lead"}""")
        val personaId = personas.createManualPersona(ManualPersonaFields(name = "Group Player"))
        val groupId = chats.createGroupChat("Group", listOf(personaId), listOf(characterId))
        assertEquals("new/global", requireNotNull(chats.getEffectiveChatSettings(groupId)).selectedModelId)
        val scenarioRepository = ScenarioRepository(db)
        scenarioRepository.save("defaults-scenario", ScenarioDefinition(title = "World", premise = "Test"))
        val scenarioChatId = scenarioRepository.start("defaults-scenario", personaId)
        assertEquals("new/global", requireNotNull(chats.getEffectiveChatSettings(scenarioChatId)).selectedModelId)
    }

    @Test fun greetingNpcLibraryAndGlobalDefaultsCoexistWithoutChangingPerChatStoryFeatures() = runBlocking {
        chats.saveEngineDefaults(EngineDefaultsEntity(selectedModelId = "global/model"))
        val characterId = characters.importCharacterJson("""{"schema_version":1,"character_id":"combined","display_name":"Lead","character_name":"Lead","greeting":"Welcome."}""")
        val personaId = personas.createManualPersona(ManualPersonaFields(name = "Player"))
        val chatId = chats.createSoloChat("Combined", personaId, characterId, "Lead", characters.getInitialMessage(requireNotNull(characters.getCharacterById(characterId))))
        val bankId = npcs.importLibraryBank("One", """{"schema_version":1,"npcs":[{"npc_id":"n","display_name":"Guide"}]}""")
        npcs.addLibraryNpcsToChat(chatId, bankId)
        npcs.setEnabled(chatId, true)
        val stored = requireNotNull(chats.getChatSettings(chatId)).copy(narratorEnabled = true)
        chats.insertChatSettings(stored)
        assertEquals("Welcome.", db.messageDao().getMessagesForChatOnce(chatId).single().content)
        assertEquals("global/model", requireNotNull(chats.getEffectiveChatSettings(chatId)).selectedModelId)
        assertTrue(npcs.getSettings(chatId).isEnabled)
        assertTrue(requireNotNull(chats.getChatSettings(chatId)).narratorEnabled)
    }

    private suspend fun createChat(name: String): String {
        val character = characters.importCharacterJson("""{"schema_version":1,"character_id":"${java.util.UUID.randomUUID()}","display_name":"Lead","character_name":"Lead"}""")
        val persona = personas.createManualPersona(ManualPersonaFields(name = "Player"))
        return chats.createSoloChat(name, persona, character)
    }
}
