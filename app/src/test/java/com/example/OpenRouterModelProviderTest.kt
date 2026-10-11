package com.example

import com.example.data.remote.OpenRouterModelProvider
import com.example.data.model.CharacterEntity
import com.example.data.model.MessageEntity
import com.example.data.model.PersonaEntity
import com.example.data.model.ProviderRoutingMode
import com.example.data.model.ResponseLengthProfile
import com.example.data.model.SpeakerType
import com.example.domain.compiler.ContextCompilerV1
import com.example.domain.provider.GenerationOptions
import com.example.domain.provider.ModelRouting
import com.example.domain.provider.Role
import com.example.domain.provider.RoleplayMessage
import com.example.domain.provider.ProviderImage
import com.example.domain.provider.StreamEvent
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Pipe
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OpenRouterModelProviderTest {
    private class RecordingInterceptor(
        private val responseBody: String = "{\"data\":[]}"
    ) : Interceptor {
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
                .body(responseBody.toResponseBody())
                .build()
        }
    }

    private class RoutingStreamingInterceptor : Interceptor {
        lateinit var request: okhttp3.Request

        override fun intercept(chain: Interceptor.Chain): Response {
            val incoming = chain.request()
            if (incoming.method == "GET") {
                return Response.Builder()
                    .request(incoming)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "application/json")
                    .body("""{"data":[{"id":"mock/model","name":"Vision","context_length":1000,"pricing":{},"architecture":{"input_modalities":["text","image"]}}]}""".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            request = incoming
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Type", "text/event-stream")
                .body("data: [DONE]\n\n".toResponseBody("text/event-stream".toMediaType()))
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

    private class HeldOpenStreamingInterceptor : Interceptor {
        private val release = CountDownLatch(1)

        override fun intercept(chain: Interceptor.Chain): Response {
            val pipe = Pipe(8_192L)
            Thread {
                runCatching {
                    pipe.sink.buffer().use { sink ->
                        sink.writeUtf8(
                            "data: {\"model\":\"mock/resolved\",\"provider\":\"Mock Provider\",\"choices\":[{\"delta\":{\"content\":\"Partial now.\"},\"finish_reason\":null}]}\n\n"
                        )
                        sink.flush()
                        release.await(5, TimeUnit.SECONDS)
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
            val responseBody = object : ResponseBody() {
                override fun contentType() = "text/event-stream".toMediaType()
                override fun contentLength(): Long = -1L
                override fun source(): BufferedSource = pipe.source.buffer()
            }
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Type", "text/event-stream")
                .body(responseBody)
                .build()
        }

        fun release() {
            release.countDown()
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
    fun endpointDiscoveryUsesDocumentedAuthorAndSlugPathSegments() = runBlocking {
        val interceptor = RecordingInterceptor()

        provider(interceptor).getEndpoints("openai/gpt-4.1-mini")

        assertEquals("Bearer mock-openrouter-key", interceptor.request.header("Authorization"))
        assertEquals(
            "/api/v1/models/openai/gpt-4.1-mini/endpoints",
            interceptor.request.url.encodedPath
        )
    }

    @Test
    fun endpointDiscoveryParsesProviderTagFromNestedEndpointResponse() = runBlocking {
        val interceptor = RecordingInterceptor(
            """
            {
              "data": {
                "id": "mock/model",
                "name": "Mock Model",
                "endpoints": [
                  {"name":"Provider A: Mock Model","provider_name":"Provider A","tag":"provider-a"},
                  {"name":"Provider B: Mock Model","provider_name":"Provider B","tag":"provider-b"}
                ]
              }
            }
            """.trimIndent()
        )

        val endpoints = provider(interceptor).getEndpoints("mock/model")

        assertEquals(
            listOf("Provider A" to "provider-a", "Provider B" to "provider-b"),
            endpoints.map { it.name to it.identifier }
        )
    }

    @Test
    fun autoRoutingOmitsProviderConfigurationEvenWhenAnEndpointIsStored() = runBlocking {
        val body = routingRequestBody(
            ModelRouting.fromSettings(ProviderRoutingMode.AUTO, "provider-a")
        )

        assertFalse(body.containsKey("provider"))
    }

    @Test
    fun messageNameIsOnlySerializedAsStructuredMetadata() = runBlocking {
        val body = routingRequestBody(
            routing = ModelRouting.fromSettings(ProviderRoutingMode.AUTO, null),
            messages = listOf(
                RoleplayMessage(Role.ASSISTANT, "Miki: I came back.", name = "Miki"),
                RoleplayMessage(Role.USER, "Stay with me.")
            )
        )
        val messages = body["messages"] as List<*>
        val assistant = messages[0] as Map<*, *>
        val user = messages[1] as Map<*, *>

        assertEquals("Miki: I came back.", assistant["content"])
        assertEquals("Miki", assistant["name"])
        assertEquals(setOf("role", "content", "name"), assistant.keys)
        assertEquals("Stay with me.", user["content"])
        assertFalse(user.containsKey("name"))
    }

    @Test
    fun imageMessageUsesTextFirstMultimodalPartsAndDataUrl() = runBlocking {
        val body = routingRequestBody(
            routing = ModelRouting.fromSettings(ProviderRoutingMode.AUTO, null),
            messages = listOf(
                RoleplayMessage(
                    role = Role.USER,
                    content = "What do you notice?",
                    images = listOf(
                        ProviderImage("image/png", byteArrayOf(1, 2, 3), "mock-sha")
                    )
                )
            )
        )

        val message = (body["messages"] as List<*>).single() as Map<*, *>
        val parts = message["content"] as List<*>
        assertEquals("text", (parts[0] as Map<*, *>)["type"])
        assertEquals("What do you notice?", (parts[0] as Map<*, *>)["text"])
        assertEquals("image_url", (parts[1] as Map<*, *>)["type"])
        val imageUrl = (parts[1] as Map<*, *>)["image_url"] as Map<*, *>
        assertEquals("data:image/png;base64,AQID", imageUrl["url"])
    }

    @Test
    fun modelCatalogParsesImageInputCapability() = runBlocking {
        val interceptor = RecordingInterceptor(
            """{"data":[{"id":"vision/model","name":"Vision","context_length":1000,"pricing":{},"architecture":{"input_modalities":["text","image"]}},{"id":"text/model","name":"Text","context_length":1000,"pricing":{},"architecture":{"input_modalities":["text"]}}]}"""
        )
        val provider = provider(interceptor)

        val models = provider.getModels()

        assertEquals(listOf("text", "image"), models.first().inputModalities)
        assertEquals(true, provider.supportsImageInput("vision/model"))
        assertEquals(false, provider.supportsImageInput("text/model"))
    }

    @Test
    fun imageRequestFailsClosedBeforeCatalogCapabilityIsKnown() = runBlocking {
        val interceptor = RoutingStreamingInterceptor()
        val provider = OpenRouterModelProvider(
            OkHttpClient.Builder().addInterceptor(interceptor).build(),
            Moshi.Builder().build()
        ) { "mock-openrouter-key" }

        val events = provider.streamResponse(
            listOf(RoleplayMessage(Role.USER, "Look", images = listOf(ProviderImage("image/png", byteArrayOf(1), "sha")))),
            GenerationOptions(modelId = "mock/model")
        ).toList()

        assertTrue(events.filterIsInstance<StreamEvent.Error>().single().exception.message.orEmpty().contains("not been confirmed"))
    }

    @Test
    fun groupTurnControlIsSerializedAsFinalUserMessageWithoutChangingSharedHistory() = runBlocking {
        val zsadist = CharacterEntity(
            characterId = "zsadist",
            displayName = "Zsadist",
            characterName = "Zsadist",
            aliases = emptyList(),
            importSchemaVersion = 1,
            identityJson = null,
            appearanceJson = null,
            personalityJson = null,
            voiceJson = null,
            behaviorJson = null,
            backstoryJson = null,
            knowledgeJson = "{\"private\":\"Z_ONLY_KNOWLEDGE\"}",
            worldContextJson = null,
            writingRulesJson = null,
            examplesJson = null,
            authorNotesJson = null,
            extensionFieldsJson = null
        )
        val persona = PersonaEntity(
            personaId = "persona",
            displayName = "Persona",
            personaName = "Persona",
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
        val compiled = ContextCompilerV1().compileGroupCharacterContext(
            character = zsadist,
            persona = persona,
            activeCharacterNames = listOf("Vishous", "Zsadist"),
            chatHistory = listOf(
                MessageEntity(
                    chatId = "group",
                    speakerType = SpeakerType.PERSONA,
                    speakerId = "persona",
                    speakerDisplayNameSnapshot = "Persona",
                    content = "Who found the map?",
                    orderIndex = 1L
                )
            ),
            currentMessage = MessageEntity(
                chatId = "group",
                speakerType = SpeakerType.CHARACTER,
                speakerId = "vishous",
                speakerDisplayNameSnapshot = "Vishous",
                content = "I did.",
                orderIndex = 2L
            ),
            responseProfile = ResponseLengthProfile.NORMAL,
            customMin = null,
            customTargetMax = null,
            customHardMax = null,
            supportingCastContext = """
                [NPC id=npc-secret]
                [STORYTELLER CONTINUITY - NOT AUTOMATIC SPEAKER KNOWLEDGE]
                Name: Mara
                Storyteller-only secrets (not speaker knowledge): THE_HIDDEN_DOOR
            """.trimIndent(),
            narratorEnabled = true,
            narratorInstructions = "Keep the narration cinematic but concise. Then write the Persona's dialogue."
        )
        val body = routingRequestBody(
            routing = ModelRouting.fromSettings(ProviderRoutingMode.AUTO, null),
            messages = compiled
        )
        val messages = body["messages"] as List<*>

        assertEquals(
            listOf("system", "user", "assistant", "user"),
            messages.map { (it as Map<*, *>)["role"] }
        )
        assertEquals("Persona: Who found the map?", (messages[1] as Map<*, *>)["content"])
        assertEquals("Vishous: I did.", (messages[2] as Map<*, *>)["content"])
        val system = (messages.first() as Map<*, *>)["content"].toString()
        assertTrue(system.contains("--- CHARACTER: Zsadist ---"))
        assertTrue(system.contains("Z_ONLY_KNOWLEDGE"))
        assertTrue(system.contains("THE_HIDDEN_DOOR"))
        assertTrue(system.contains("including storyteller-only secrets"))
        assertTrue(system.contains("it is not knowledge held by the responding Character"))
        assertTrue(system.contains("Private author notes and extension data are always excluded."))
        assertTrue(system.contains("--- NARRATOR GUIDANCE ---"))
        assertTrue(system.contains("Keep the narration cinematic but concise."))
        assertTrue(system.contains("not an independent AI"))
        assertTrue(system.contains("respond only as the selected Character"))
        val editableNarratorInstruction = system.indexOf("Then write the Persona's dialogue.")
        val finalNarratorBoundary = system.lastIndexOf("--- NON-OVERRIDABLE NARRATOR BOUNDARIES ---")
        assertTrue(editableNarratorInstruction >= 0)
        assertTrue(finalNarratorBoundary > editableNarratorInstruction)
        assertTrue(system.substring(finalNarratorBoundary).contains("Never write dialogue, actions, thoughts, feelings, decisions, or internal state for the user's Persona."))
        assertTrue(system.substring(finalNarratorBoundary).contains("Respond only as Zsadist"))
        val narratorOff = ContextCompilerV1().compileGroupCharacterContext(
            character = zsadist,
            persona = persona,
            activeCharacterNames = listOf("Vishous", "Zsadist"),
            chatHistory = emptyList(),
            currentMessage = MessageEntity(
                chatId = "group",
                speakerType = SpeakerType.PERSONA,
                speakerId = "persona",
                speakerDisplayNameSnapshot = "Persona",
                content = "Continue.",
                orderIndex = 3L
            ),
            responseProfile = ResponseLengthProfile.NORMAL,
            customMin = null,
            customTargetMax = null,
            customHardMax = null,
            narratorEnabled = false,
            narratorInstructions = "MUST_NOT_APPEAR"
        )
        assertFalse(narratorOff.first().content.contains("MUST_NOT_APPEAR"))
        assertFalse(narratorOff.first().content.contains("--- NARRATOR GUIDANCE ---"))
        assertFalse(system.contains("--- CHARACTER: Vishous ---"))
        val director = (messages.last() as Map<*, *>)["content"].toString()
        assertTrue(director.startsWith("[DIRECTOR TURN CONTROL"))
        assertTrue(director.contains("not Persona dialogue or action"))
        assertTrue(director.contains("Respond only as Zsadist"))
        assertFalse(director.contains("Z_ONLY_KNOWLEDGE"))
    }

    @Test
    fun preferRoutingSendsSelectedProviderTagWithFallbackEnabled() = runBlocking {
        val body = routingRequestBody(
            ModelRouting.fromSettings(ProviderRoutingMode.PREFER, "provider-a")
        )
        val provider = body["provider"] as Map<*, *>

        assertEquals(true, provider["allow_fallbacks"])
        assertEquals(listOf("provider-a"), provider["order"])
    }

    @Test
    fun lockRoutingSendsSelectedProviderTagWithFallbackDisabled() = runBlocking {
        val body = routingRequestBody(
            ModelRouting.fromSettings(ProviderRoutingMode.LOCK, "provider-b")
        )
        val provider = body["provider"] as Map<*, *>

        assertEquals(false, provider["allow_fallbacks"])
        assertEquals(listOf("provider-b"), provider["order"])
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

    @Test
    fun streamEmitsTextAndAvailableMetadataBeforeConnectionCloses() = runBlocking {
        val interceptor = HeldOpenStreamingInterceptor()
        val provider = OpenRouterModelProvider(
            client = OkHttpClient.Builder().addInterceptor(interceptor).build(),
            moshi = Moshi.Builder().build(),
            apiKeyProvider = { "mock-openrouter-key" }
        )

        val events = try {
            withTimeout(3_000) {
                provider.streamResponse(
                    messages = listOf(RoleplayMessage(Role.USER, "Hello")),
                    options = GenerationOptions(modelId = "mock/requested")
                ).take(2).toList()
            }
        } finally {
            interceptor.release()
        }

        assertEquals("Partial now.", (events[0] as StreamEvent.Content).text)
        val metadata = (events[1] as StreamEvent.Metadata).metadata
        assertEquals("mock/requested", metadata.requestedModelId)
        assertEquals("mock/resolved", metadata.resolvedModelId)
        assertEquals("Mock Provider", metadata.provider)
        assertTrue(events.none { it is StreamEvent.Done })
    }

    @Test
    fun streamedOpenRouterErrorIsNotReportedAsAnEmptySuccessfulResponse() = runBlocking {
        val sse = """
            data: {"error":{"message":"Provider rejected the message sequence","code":400}}

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

        val error = events.filterIsInstance<StreamEvent.Error>().single()
        assertTrue(error.exception.message.orEmpty().contains("Provider rejected the message sequence"))
        assertTrue(events.none { it is StreamEvent.Done })
    }

    private suspend fun routingRequestBody(
        routing: ModelRouting,
        messages: List<RoleplayMessage> = listOf(RoleplayMessage(Role.USER, "Hello"))
    ): Map<*, *> {
        val interceptor = RoutingStreamingInterceptor()
        val provider = OpenRouterModelProvider(
            client = OkHttpClient.Builder().addInterceptor(interceptor).build(),
            moshi = Moshi.Builder().build(),
            apiKeyProvider = { "mock-openrouter-key" }
        )
        if (messages.any { it.images.isNotEmpty() }) provider.getModels()

        withTimeout(5_000) {
            provider.streamResponse(
                messages = messages,
                options = GenerationOptions(modelId = "mock/model", routing = routing)
            ).toList()
        }

        val buffer = Buffer()
        interceptor.request.body!!.writeTo(buffer)
        return Moshi.Builder().build().adapter(Map::class.java).fromJson(buffer.readUtf8())!!
    }
}
