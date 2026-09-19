package com.example

import com.example.data.remote.OpenRouterModelProvider
import com.example.domain.provider.GenerationOptions
import com.example.domain.provider.Role
import com.example.domain.provider.RoleplayMessage
import com.example.domain.provider.StreamEvent
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterModelProviderTest {
    private class RecordingInterceptor : Interceptor {
        lateinit var request: okhttp3.Request
        lateinit var executingThread: Thread

        override fun intercept(chain: Interceptor.Chain): Response {
            request = chain.request()
            executingThread = Thread.currentThread()
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{\"data\":[]}".toResponseBody())
                .build()
        }
    }

    private class StreamingInterceptor(private val body: String) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response = Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", "text/event-stream")
            .body(body.toResponseBody("text/event-stream".toMediaType()))
            .build()
    }

    private fun provider(interceptor: RecordingInterceptor): OpenRouterModelProvider {
        return OpenRouterModelProvider(
            client = OkHttpClient.Builder().addInterceptor(interceptor).build(),
            moshi = Moshi.Builder().build(),
            apiKeyProvider = { "mock-openrouter-key" }
        )
    }

    @Test
    fun modelCatalogUsesMockedApiKeyInAuthorizationHeader() = runBlocking {
        val interceptor = RecordingInterceptor()

        provider(interceptor).getModels()

        assertEquals("Bearer mock-openrouter-key", interceptor.request.header("Authorization"))
        assertEquals("/api/v1/models", interceptor.request.url.encodedPath)
    }

    @Test
    fun endpointDiscoveryUsesSelectedModelIdAsOneEncodedPathSegment() = runBlocking {
        val interceptor = RecordingInterceptor()

        provider(interceptor).getEndpoints("openai/gpt-4.1-mini")

        assertEquals("Bearer mock-openrouter-key", interceptor.request.header("Authorization"))
        assertEquals(
            "/api/v1/models/openai%2Fgpt-4.1-mini/endpoints",
            interceptor.request.url.encodedPath
        )
    }

    @Test
    fun modelCatalogRequestExecutesOffCallingThread() = runBlocking {
        val interceptor = RecordingInterceptor()
        val callingThread = Thread.currentThread()

        provider(interceptor).getModels()

        assertNotEquals(callingThread, interceptor.executingThread)
    }

    @Test
    fun streamedFinishReasonIsPreservedWhenUsageArrivesInAnotherEvent() = runBlocking {
        val sse = """
            data: {"model":"mock/resolved","provider":"Mock Provider","choices":[{"delta":{"content":"Already received."},"finish_reason":null}]}

            data: {"model":"mock/resolved","provider":"Mock Provider","choices":[{"delta":{},"finish_reason":"length"}]}

            data: {"model":"mock/resolved","provider":"Mock Provider","choices":[],"usage":{"prompt_tokens":12,"completion_tokens":34,"total_tokens":46}}

            data: [DONE]

        """.trimIndent()
        val provider = OpenRouterModelProvider(
            client = OkHttpClient.Builder().addInterceptor(StreamingInterceptor(sse)).build(),
            moshi = Moshi.Builder().build(),
            apiKeyProvider = { "mock-openrouter-key" }
        )

        val events = withTimeout(5_000) {
            provider.streamResponse(
                messages = listOf(RoleplayMessage(Role.USER, "Hello")),
                options = GenerationOptions(modelId = "mock/requested")
            ).toList()
        }

        assertEquals("Already received.", events.filterIsInstance<StreamEvent.Content>().joinToString("") { it.text })
        val metadata = events.filterIsInstance<StreamEvent.Done>().single().metadata
        assertEquals("length", metadata?.finishReason)
        assertEquals(34, metadata?.completionTokens)
        assertEquals("mock/resolved", metadata?.resolvedModelId)
        assertTrue(events.none { it is StreamEvent.Error })
    }
}
