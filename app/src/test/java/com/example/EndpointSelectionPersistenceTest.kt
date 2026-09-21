package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.ChatSettingsEntity
import com.example.data.model.ProviderRoutingMode
import com.example.domain.provider.GenerationOptions
import com.example.domain.provider.ModelProvider
import com.example.domain.provider.OpenRouterModel
import com.example.domain.provider.ProviderEndpoint
import com.example.domain.provider.RoleplayMessage
import com.example.domain.provider.StreamEvent
import com.example.domain.repository.ChatRepository
import com.example.ui.screens.ChatSettingsViewModel
import com.example.ui.screens.selectedEndpointDisplayName
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EndpointSelectionPersistenceTest {
    private lateinit var database: AppDatabase
    private lateinit var chatRepository: ChatRepository
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        chatRepository = ChatRepository(database.chatDao())
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun endpointSelectionPersistsAcrossViewModelRecreationAndDoesNotChangeAnotherChat() = runBlocking {
        chatRepository.insertChatSettings(
            ChatSettingsEntity(
                chatId = "chat-a",
                selectedModelId = "mock/model",
                providerRoutingMode = ProviderRoutingMode.PREFER
            )
        )
        val unrelatedSettings = ChatSettingsEntity(
            chatId = "chat-b",
            selectedModelId = "other/model",
            providerRoutingMode = ProviderRoutingMode.LOCK,
            providerEndpoint = "unrelated-provider",
            temperature = 0.4f
        )
        chatRepository.insertChatSettings(unrelatedSettings)

        val firstViewModel = ChatSettingsViewModel("chat-a", chatRepository, EndpointProvider)
        withTimeout(5_000) {
            firstViewModel.uiState.first { !it.isLoading && it.endpoints.size == 2 }
        }

        firstViewModel.onEndpointSelected("provider-a")

        assertEquals("provider-a", firstViewModel.uiState.value.settings?.providerEndpoint)
        assertEquals("mock/model", firstViewModel.uiState.value.settings?.selectedModelId)
        withTimeout(5_000) {
            chatRepository.getChatSettingsFlow("chat-a").first {
                it?.providerEndpoint == "provider-a"
            }
        }

        val reopenedViewModel = ChatSettingsViewModel("chat-a", chatRepository, EndpointProvider)
        val reopenedState = withTimeout(5_000) {
            reopenedViewModel.uiState.first {
                !it.isLoading && it.endpoints.size == 2 && it.settings?.providerEndpoint == "provider-a"
            }
        }
        assertEquals("Provider A", selectedEndpointDisplayName("provider-a", reopenedState.endpoints))
        assertEquals(ProviderRoutingMode.PREFER, reopenedState.settings?.providerRoutingMode)

        reopenedViewModel.onEndpointSelected("provider-b")

        withTimeout(5_000) {
            chatRepository.getChatSettingsFlow("chat-a").first {
                it?.providerEndpoint == "provider-b"
            }
        }
        val changed = chatRepository.getChatSettings("chat-a")!!
        assertEquals("provider-b", changed.providerEndpoint)
        assertEquals("mock/model", changed.selectedModelId)
        assertEquals(ProviderRoutingMode.PREFER, changed.providerRoutingMode)
        assertEquals(unrelatedSettings, chatRepository.getChatSettings("chat-b"))
    }

    @Test
    fun endpointDisplayUsesProviderNameWithoutChangingStoredTag() {
        val endpoints = listOf(ProviderEndpoint(name = "Provider A", identifier = "provider-a"))

        assertEquals("Provider A", selectedEndpointDisplayName("provider-a", endpoints))
        assertEquals("unknown-provider", selectedEndpointDisplayName("unknown-provider", endpoints))
        assertEquals("Select Endpoint", selectedEndpointDisplayName(null, endpoints))
    }

    private object EndpointProvider : ModelProvider {
        override suspend fun getModels(): List<OpenRouterModel> = listOf(
            OpenRouterModel("mock/model", "Mock Model", 8_192, "0", "0")
        )

        override suspend fun getEndpoints(modelId: String): List<ProviderEndpoint> = listOf(
            ProviderEndpoint(name = "Provider A", identifier = "provider-a"),
            ProviderEndpoint(name = "Provider B", identifier = "provider-b")
        )

        override fun streamResponse(
            messages: List<RoleplayMessage>,
            options: GenerationOptions
        ): Flow<StreamEvent> = emptyFlow()
    }
}
