package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.CharacterEntity
import com.example.data.model.ChatSettingsEntity
import com.example.data.model.ConversationSummaryEntity
import com.example.data.model.GenerationMetadataEntity
import com.example.data.model.MemoryCategory
import com.example.data.model.MessageEntity
import com.example.data.model.PersonaEntity
import com.example.data.model.RoleplayMemoryEntity
import com.example.data.model.SpeakerType
import com.example.domain.provider.GenerationOptions
import com.example.domain.provider.ModelProvider
import com.example.domain.provider.OpenRouterModel
import com.example.domain.provider.ProviderEndpoint
import com.example.domain.provider.RoleplayMessage
import com.example.domain.provider.StreamEvent
import com.example.domain.repository.CharacterRepository
import com.example.domain.repository.ChatRepository
import com.example.domain.repository.MemoryRepository
import com.example.domain.repository.MessageRepository
import com.example.domain.repository.PersonaRepository
import com.example.ui.screens.ChatDetailViewModel
import com.example.ui.screens.ChatUiState
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatDeletionTest {
    private lateinit var database: AppDatabase
    private lateinit var chatRepository: ChatRepository
    private lateinit var messageRepository: MessageRepository
    private lateinit var memoryRepository: MemoryRepository
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        chatRepository = ChatRepository(database.chatDao(), database.messageDao())
        messageRepository = MessageRepository(database.messageDao())
        memoryRepository = MemoryRepository(database.memoryDao())
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun deletingOneChatRemovesAllChatScopedDataAndPreservesSharedAndUnrelatedData() = runBlocking {
        database.characterDao().insertCharacter(character())
        database.personaDao().insertPersona(persona())
        val deletedChatId = chatRepository.createSoloChat("Delete me", "persona-1", "character-1")
        val keptChatId = chatRepository.createSoloChat("Keep me", "persona-1", "character-1")

        chatRepository.insertChatSettings(ChatSettingsEntity(chatId = deletedChatId, draftMessage = "private draft"))
        chatRepository.insertChatSettings(ChatSettingsEntity(chatId = keptChatId, draftMessage = "keep draft"))

        val original = message(deletedChatId, "deleted-original", "deleted-group", true)
        val regenerated = message(deletedChatId, "deleted-regenerated", "deleted-group", false)
        val keptMessage = message(keptChatId, "kept-message", "kept-group", true)
        messageRepository.insertMessage(original)
        messageRepository.insertMessage(regenerated)
        messageRepository.insertMessage(keptMessage)
        messageRepository.insertGenerationMetadata(GenerationMetadataEntity(messageId = original.messageId))
        messageRepository.insertGenerationMetadata(GenerationMetadataEntity(messageId = regenerated.messageId))
        messageRepository.insertGenerationMetadata(GenerationMetadataEntity(messageId = keptMessage.messageId))

        memoryRepository.upsertSummary(ConversationSummaryEntity(deletedChatId, "delete summary", 1L))
        memoryRepository.upsertSummary(ConversationSummaryEntity(keptChatId, "keep summary", 1L))
        memoryRepository.insertIfNew(memory(deletedChatId, "delete memory"))
        memoryRepository.insertIfNew(memory(keptChatId, "keep memory"))

        insertRelationship("deleted-relationship", deletedChatId)
        insertRelationship("kept-relationship", keptChatId)
        insertRelationship("global-relationship", null)

        chatRepository.deleteChat(deletedChatId)

        assertNull(chatRepository.getChatSummaryById(deletedChatId))
        assertTrue(messageRepository.getMessagesForChat(deletedChatId).first().isEmpty())
        assertNull(messageRepository.getGenerationMetadata(original.messageId))
        assertNull(messageRepository.getGenerationMetadata(regenerated.messageId))
        assertNull(memoryRepository.getSummaryOnce(deletedChatId))
        assertTrue(memoryRepository.getMemoriesOnce(deletedChatId, "character-1").isEmpty())
        assertNull(chatRepository.getChatSettings(deletedChatId))
        assertEquals(0, countRows("chat_participants", "chatId", deletedChatId))
        assertEquals(0, countRows("chat_persona_participants", "chatId", deletedChatId))
        assertEquals(0, countRows("relationships", "scopeChatId", deletedChatId))

        assertNotNull(chatRepository.getChatSummaryById(keptChatId))
        assertEquals(listOf("kept-message"), messageRepository.getMessagesForChat(keptChatId).first().map { it.messageId })
        assertNotNull(messageRepository.getGenerationMetadata(keptMessage.messageId))
        assertEquals("keep summary", memoryRepository.getSummaryOnce(keptChatId)?.summary)
        assertEquals(listOf("keep memory"), memoryRepository.getMemoriesOnce(keptChatId, "character-1").map { it.content })
        assertNotNull(chatRepository.getChatSettings(keptChatId))
        assertEquals(1, countRows("relationships", "scopeChatId", keptChatId))
        assertEquals(1, countNullRows("relationships", "scopeChatId"))
        assertNotNull(database.characterDao().getCharacterById("character-1"))
        assertNotNull(database.personaDao().getPersonaById("persona-1"))
        assertEquals(listOf(keptChatId), chatRepository.getAllChats().first().map { it.chatId })
    }

    @Test
    fun deletingCurrentlyActiveChatMovesViewModelToDeletedState() = runBlocking {
        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val characterRepository = CharacterRepository(database.characterDao(), moshi)
        val personaRepository = PersonaRepository(database.personaDao(), moshi)
        database.characterDao().insertCharacter(character())
        database.personaDao().insertPersona(persona())
        val chatId = chatRepository.createSoloChat("Active chat", "persona-1", "character-1")
        chatRepository.insertChatSettings(ChatSettingsEntity(chatId = chatId))

        val viewModel = ChatDetailViewModel(
            chatId = chatId,
            chatRepository = chatRepository,
            messageRepository = messageRepository,
            characterRepository = characterRepository,
            personaRepository = personaRepository,
            modelProvider = NoOpModelProvider,
            memoryRepository = memoryRepository
        )
        withTimeout(5_000) { viewModel.uiState.first { it is ChatUiState.Success } }

        viewModel.deleteChat()

        withTimeout(5_000) { viewModel.uiState.first { it is ChatUiState.Deleted } }
        assertTrue(viewModel.uiState.value is ChatUiState.Deleted)
        assertNull(chatRepository.getChatSummaryById(chatId))
    }

    private fun message(chatId: String, messageId: String, variantGroupId: String, isPrimary: Boolean) =
        MessageEntity(
            messageId = messageId,
            chatId = chatId,
            speakerType = SpeakerType.CHARACTER,
            speakerId = "character-1",
            speakerDisplayNameSnapshot = "Rowan",
            content = messageId,
            orderIndex = System.currentTimeMillis(),
            variantGroupId = variantGroupId,
            isPrimaryVariant = isPrimary
        )

    private fun memory(chatId: String, content: String) = RoleplayMemoryEntity(
        chatId = chatId,
        characterId = "character-1",
        category = MemoryCategory.EVENT,
        content = content
    )

    private fun insertRelationship(relationshipId: String, scopeChatId: String?) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO relationships (relationshipId, sourceCharacterId, targetCharacterId, targetNameSnapshot, relationshipType, dynamic, status, scopeChatId) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf(relationshipId, "character-1", "character-2", "Other", "friend", "steady", "active", scopeChatId)
        )
    }

    private fun countRows(table: String, column: String, value: String): Int =
        database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM $table WHERE $column = ?",
            arrayOf(value)
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun countNullRows(table: String, column: String): Int =
        database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM $table WHERE $column IS NULL"
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun character() = CharacterEntity(
        characterId = "character-1",
        displayName = "Rowan",
        characterName = "Rowan",
        aliases = emptyList(),
        importSchemaVersion = 1,
        identityJson = null,
        appearanceJson = null,
        personalityJson = null,
        voiceJson = null,
        behaviorJson = null,
        backstoryJson = null,
        knowledgeJson = null,
        worldContextJson = null,
        writingRulesJson = null,
        examplesJson = null,
        authorNotesJson = null,
        extensionFieldsJson = null
    )

    private fun persona() = PersonaEntity(
        personaId = "persona-1",
        displayName = "Mara",
        personaName = "Mara",
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

    private object NoOpModelProvider : ModelProvider {
        override suspend fun getModels(): List<OpenRouterModel> = emptyList()
        override suspend fun getEndpoints(modelId: String): List<ProviderEndpoint> = emptyList()
        override fun streamResponse(
            messages: List<RoleplayMessage>,
            options: GenerationOptions
        ): Flow<StreamEvent> = emptyFlow()
    }
}
