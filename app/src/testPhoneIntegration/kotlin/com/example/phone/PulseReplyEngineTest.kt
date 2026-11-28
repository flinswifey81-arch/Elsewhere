package com.example.phone

import com.example.domain.provider.*
import com.example.phonelab.PulseRefreshPlanner
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PulseReplyEngineTest {
    @Test fun explicitModelAndSelectedEventsOnlyWithNoFallback() = runBlocking {
        val requests = mutableListOf<Pair<List<RoleplayMessage>, GenerationOptions>>()
        val result = """{"posts":[{"accountId":"garage","eventId":"meet","kind":"rumor","text":"I heard someone might bring coffee."}]}"""
        var reply: List<StreamEvent> = listOf(StreamEvent.Content(result), StreamEvent.Done(null))
        val provider = object : ModelProvider {
            override suspend fun getModels() = emptyList<OpenRouterModel>()
            override suspend fun getEndpoints(modelId: String) = emptyList<ProviderEndpoint>()
            override fun streamResponse(messages: List<RoleplayMessage>, options: GenerationOptions) =
                flowOf(*reply.toTypedArray()).also { requests.add(messages to options) }
        }
        val preview = PulseRefreshPlanner.RequestPreview("Board",
            listOf(PulseRefreshPlanner.PublicEvent("meet", "The meeting was publicly announced.")), 4,
            listOf(PulseRefreshPlanner.PublicAccount("garage", "The Garage")))
        val engine = PulseReplyEngine(provider)
        assertTrue(runCatching { engine.generate(null, preview) }.isFailure)
        assertTrue(runCatching { engine.generate("openrouter/auto", preview) }.isFailure)
        assertTrue(requests.isEmpty())
        assertEquals("rumor", engine.generate("example/cheap-pulse", preview).single().kind)
        val (messages, options) = requests.single()
        assertEquals("example/cheap-pulse", options.modelId)
        assertFalse(options.routing.allowFallback)
        assertEquals(listOf(Role.SYSTEM, Role.USER), messages.map { it.role })
        assertTrue(messages.last().content.contains("The meeting was publicly announced."))
        reply = listOf(StreamEvent.Error(IllegalArgumentException("Unavailable")))
        assertTrue(runCatching { engine.generate("example/missing", preview) }.isFailure)
        assertEquals(listOf("example/cheap-pulse", "example/missing"), requests.map { it.second.modelId })
    }
}
