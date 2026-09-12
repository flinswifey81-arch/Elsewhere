package com.example

import com.example.data.remote.OpenRouterModelProvider
import com.squareup.moshi.Moshi
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
}
