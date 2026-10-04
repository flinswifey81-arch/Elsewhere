package com.example

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.CharacterEntity
import com.example.data.model.ChatSettingsEntity
import com.example.data.model.LorebookEntity
import com.example.data.model.LorebookEntryEntity
import com.example.data.model.MessageEntity
import com.example.data.model.PersonaEntity
import com.example.data.model.SpeakerType
import com.example.di.AppContainer
import com.example.domain.provider.GenerationOptions
import com.example.domain.provider.ModelProvider
import com.example.domain.provider.OpenRouterModel
import com.example.domain.provider.ProviderEndpoint
import com.example.domain.provider.RoleplayMessage
import com.example.domain.provider.StreamEvent
import com.example.domain.repository.AppearanceRepository
import com.example.domain.repository.CharacterRepository
import com.example.domain.repository.ChatRepository
import com.example.domain.repository.LorebookRepository
import com.example.domain.repository.MemoryRepository
import com.example.domain.repository.MessageRepository
import com.example.domain.repository.PersonaRepository
import com.example.domain.repository.SettingsRepository
import com.example.ui.screens.ChatLorebooksScreen
import com.example.ui.screens.ContextInspectorScreen
import com.example.ui.screens.LorebooksScreen
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LorebookUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var database: AppDatabase
    private lateinit var repository: LorebookRepository
    private lateinit var context: Context
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LorebookRepository(
            database.lorebookDao(),
            moshi
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun manualEditorExposesAllLoreEntryControls() {
        composeRule.setContent { MaterialTheme { LorebooksScreen(repository) } }
        composeRule.onNodeWithTag("new_lorebook_fab").performClick()
        composeRule.onNodeWithTag("lorebook_editor").assertExists()
        composeRule.onNodeWithText("Lorebook name").assertExists()
        composeRule.onNodeWithText("Description").assertExists()
        composeRule.onNodeWithText("Lorebook enabled").assertExists()
        composeRule.onNodeWithTag("lorebook_editor_list").performScrollToIndex(4)
        composeRule.onNodeWithTag("add_lorebook_entry").performClick()

        listOf(
            "Title",
            "Entry enabled",
            "Keyword",
            "Always",
            "Manual",
            "Keywords (one per line)",
            "Additional keywords (one per line)",
            "Match any",
            "Case sensitive",
            "Whole words",
            "Canon content",
            "Current content sent when active",
            "Reset Current to Canon",
            "Priority (any 32-bit integer)",
            "Tags (one per line; private metadata)",
            "Private notes (never sent to the model)"
        ).forEach { composeRule.onNodeWithText(it).assertExists() }
    }

    @Test
    fun chatScreenAttachesBookAndPersistsManualActivation() {
        val book = LorebookEntity(lorebookId = "ui-book", name = "UI Book")
        runBlocking {
            repository.saveDefinition(
                book,
                listOf(
                    LorebookEntryEntity(
                        entryId = "ui-entry",
                        lorebookId = book.lorebookId,
                        title = "Manual Scene",
                        activationMode = "manual",
                        currentContent = "Scene fact"
                    )
                )
            )
        }

        composeRule.setContent {
            MaterialTheme {
                ChatLorebooksScreen(chatId = "ui-chat", repository = repository, onNavigateBack = {})
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("chat_lorebooks_list").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("chat_lorebooks_list").performScrollToIndex(1)
        composeRule.onNodeWithTag("attach_lorebook_ui-book").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("manual_lore_ui-entry").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Edit Chat Current").assertExists()
        composeRule.onNodeWithTag("manual_lore_ui-entry").performClick()
        composeRule.waitUntil(5_000) {
            runBlocking {
                repository.getChatLorebookStates("ui-chat")
                    .single { it.lorebook.lorebookId == "ui-book" }
                    .overlays["ui-entry"]?.manualActive == true
            }
        }
    }

    @Test
    fun storyIdPersistsAndSaveValidationErrorsRemainVisibleInEditor() {
        composeRule.setContent { MaterialTheme { LorebooksScreen(repository) } }
        composeRule.onNodeWithTag("new_lorebook_fab").performClick()
        composeRule.onNodeWithTag("lorebook_name").performTextReplacement("Story-bound Book")
        composeRule.onNodeWithTag("lorebook_story_id").performTextInput("story-evermere")
        composeRule.onNodeWithTag("save_lorebook").performClick()
        composeRule.waitUntil(5_000) {
            runBlocking { repository.getLorebook("story-bound-missing") == null &&
                repository.observeLorebooks().let { database.lorebookDao().getLorebooksOnce() }
                    .any { it.name == "Story-bound Book" && it.storyId == "story-evermere" } }
        }
        composeRule.onNodeWithText("Lorebook saved.").assertExists()
        composeRule.onNodeWithText("OK").performClick()

        composeRule.onNodeWithTag("new_lorebook_fab").performClick()
        composeRule.onNodeWithTag("lorebook_name").performTextReplacement("X".repeat(201))
        composeRule.onNodeWithTag("save_lorebook").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Lorebook Error").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Lorebook Error").assertExists()
        composeRule.onNodeWithText("Lorebook name is required", substring = true).assertExists()
        composeRule.onNodeWithTag("lorebook_editor").assertExists()
    }

    @Test
    fun pastedJsonPreviewShowsStoryScopeAndEntryDetailsBeforeImport() {
        val json = javaClass.classLoader?.getResourceAsStream("valid_lorebook_v1.json")
            ?.bufferedReader()?.use { it.readText() }
            ?.replace("\"story_id\": null", "\"story_id\": \"story-evermere\"")
        assertNotNull(json)

        composeRule.setContent { MaterialTheme { LorebooksScreen(repository) } }
        composeRule.onNodeWithTag("import_lorebook_fab").performClick()
        composeRule.onNodeWithText("Paste JSON").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput(json!!)
        composeRule.onNodeWithText("Validate & Preview").performClick()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Import Preview").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("story_id story-evermere", substring = true).assertExists()
        composeRule.onNodeWithText("The Moonwell").assertExists()
        composeRule.onNodeWithText("Evermere Guest Right").assertExists()
        composeRule.onAllNodesWithText("Private notes included", substring = true).assertCountEquals(2)
    }

    @Test
    fun canonRequiresUnlockAndExplicitUpdateWhileResetCopiesCanonToCurrent() {
        val book = LorebookEntity(lorebookId = "canon-book", name = "Canon Book")
        runBlocking {
            repository.saveDefinition(
                book,
                listOf(
                    LorebookEntryEntity(
                        entryId = "canon-entry",
                        lorebookId = book.lorebookId,
                        title = "Canon Entry",
                        canonContent = "ORIGINAL_CANON",
                        canonLocked = true,
                        currentContent = "OLD_CURRENT"
                    )
                )
            )
        }

        composeRule.setContent { MaterialTheme { LorebooksScreen(repository) } }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("lorebook_item_canon-book").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("lorebook_item_canon-book").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("lorebook_editor").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("lorebook_editor_list").performScrollToIndex(5)
        composeRule.onNodeWithContentDescription("Edit Canon Entry").performClick()
        composeRule.onNodeWithTag("lore_entry_canon_locked").assertIsOn().performScrollTo().performClick().assertIsOff()
        composeRule.onNodeWithTag("update_lore_entry_canon").performScrollTo().performClick()
        composeRule.onNodeWithTag("new_lore_entry_canon").performTextReplacement("UPDATED_CANON")
        composeRule.onNodeWithText("Update Canon").performClick()
        composeRule.onNodeWithTag("lore_entry_current").performTextReplacement("TEMPORARY_CURRENT")
        composeRule.onNodeWithTag("reset_lore_entry_current").performScrollTo().performClick()
        composeRule.onNodeWithText("Reset Current").performClick()
        composeRule.onNodeWithTag("save_lore_entry").performClick()
        composeRule.onNodeWithTag("save_lorebook").performClick()

        composeRule.waitUntil(5_000) {
            runBlocking {
                repository.getLorebook("canon-book")?.entries?.singleOrNull()?.let {
                    it.canonContent == "UPDATED_CANON" &&
                        it.currentContent == "UPDATED_CANON" &&
                        !it.canonLocked
                } == true
            }
        }
    }

    @Test
    fun groupInspectorCompilesForExplicitlySelectedCharacter() {
        val characterRepository = CharacterRepository(database.characterDao(), moshi)
        val personaRepository = PersonaRepository(database.personaDao(), moshi)
        val chatRepository = ChatRepository(database.chatDao(), database.messageDao())
        val messageRepository = MessageRepository(database.messageDao())
        val memoryRepository = MemoryRepository(database.memoryDao())
        val chatId = runBlocking {
            database.characterDao().insertCharacter(inspectorCharacter("inspector-a", "Inspector A", "A_INSPECTOR_KNOWLEDGE"))
            database.characterDao().insertCharacter(inspectorCharacter("inspector-b", "Inspector B", "B_INSPECTOR_KNOWLEDGE"))
            database.personaDao().insertPersona(inspectorPersona())
            val createdChatId = chatRepository.createGroupChat(
                displayName = "Inspector Group",
                personaIds = listOf("inspector-persona"),
                characterIds = listOf("inspector-a", "inspector-b")
            )
            chatRepository.insertChatSettings(ChatSettingsEntity(chatId = createdChatId))
            messageRepository.insertMessage(
                MessageEntity(
                    chatId = createdChatId,
                    speakerType = SpeakerType.PERSONA,
                    speakerId = "inspector-persona",
                    speakerDisplayNameSnapshot = "Inspector Persona",
                    content = "Who answers?",
                    orderIndex = 1L
                )
            )
            createdChatId
        }
        val container = object : AppContainer {
            override val modelProvider: ModelProvider = EmptyModelProvider
            override val characterRepository = characterRepository
            override val personaRepository = personaRepository
            override val settingsRepository = SettingsRepository(context)
            override val appearanceRepository = AppearanceRepository(context)
            override val chatRepository = chatRepository
            override val messageRepository = messageRepository
            override val memoryRepository = memoryRepository
            override val lorebookRepository = repository
        }

        composeRule.setContent {
            MaterialTheme {
                ContextInspectorScreen(
                    chatId = chatId,
                    characterId = "inspector-b",
                    appContainer = container,
                    onNavigateBack = {}
                )
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Character: Inspector B").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("B_INSPECTOR_KNOWLEDGE", substring = true).assertExists()
        composeRule.onNodeWithText("A_INSPECTOR_KNOWLEDGE", substring = true).assertDoesNotExist()
        Unit
    }

    private fun inspectorCharacter(id: String, name: String, knowledge: String) = CharacterEntity(
        characterId = id,
        displayName = name,
        characterName = name,
        aliases = emptyList(),
        importSchemaVersion = 1,
        identityJson = null,
        appearanceJson = null,
        personalityJson = null,
        voiceJson = null,
        behaviorJson = null,
        backstoryJson = null,
        knowledgeJson = knowledge,
        worldContextJson = null,
        writingRulesJson = null,
        examplesJson = null,
        authorNotesJson = null,
        extensionFieldsJson = null
    )

    private fun inspectorPersona() = PersonaEntity(
        personaId = "inspector-persona",
        displayName = "Inspector Persona",
        personaName = "Inspector Persona",
        aliases = emptyList(),
        importSchemaVersion = 1,
        identityJson = null,
        appearanceJson = null,
        personalityJson = null,
        roleplayProfileJson = null,
        backgroundJson = null,
        worldContextJson = null,
        privateNotesJson = null,
        extensionFieldsJson = null
    )

    private object EmptyModelProvider : ModelProvider {
        override suspend fun getModels(): List<OpenRouterModel> = emptyList()
        override suspend fun getEndpoints(modelId: String): List<ProviderEndpoint> = emptyList()
        override fun streamResponse(
            messages: List<RoleplayMessage>,
            options: GenerationOptions
        ): Flow<StreamEvent> = emptyFlow()
    }
}
