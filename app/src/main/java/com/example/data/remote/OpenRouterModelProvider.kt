package com.example.data.remote

import com.example.data.model.GenerationMetadataEntity
import com.example.data.model.ProviderRoutingMode
import com.example.domain.provider.*
import com.example.domain.repository.SettingsRepository
import com.squareup.moshi.Moshi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.UUID

class OpenRouterModelProvider(
    private val client: OkHttpClient,
    private val moshi: Moshi,
    private val apiKeyProvider: suspend () -> String?
) : ModelProvider {

    constructor(
        client: OkHttpClient,
        moshi: Moshi,
        settingsRepository: SettingsRepository
    ) : this(client, moshi, settingsRepository::getApiKey)

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun getModels(): List<OpenRouterModel> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider() ?: throw IllegalStateException("API key not configured")

        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/models")
            .header("Authorization", "Bearer $apiKey")
            .header("HTTP-Referer", "https://github.com/google/ai-studio")
            .header("X-Title", "Elsewhere")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Unexpected code ${response.code}")
            }
            val body = response.body?.string() ?: ""
            val adapter = moshi.adapter(Map::class.java)
            val parsed = adapter.fromJson(body)
            val data = parsed?.get("data") as? List<Map<String, Any>> ?: emptyList()
            
            data.map { item ->
                val pricing = item["pricing"] as? Map<String, Any>
                OpenRouterModel(
                    id = item["id"]?.toString() ?: "",
                    name = item["name"]?.toString() ?: "",
                    contextLength = (item["context_length"] as? Number)?.toInt() ?: 0,
                    pricingPrompt = pricing?.get("prompt")?.toString() ?: "0",
                    pricingCompletion = pricing?.get("completion")?.toString() ?: "0"
                )
            }
        }
    }

    override suspend fun getEndpoints(modelId: String): List<ProviderEndpoint> = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider() ?: throw IllegalStateException("API key not configured")
            
        val request = Request.Builder()
            .url(
                HttpUrl.Builder()
                    .scheme("https")
                    .host("openrouter.ai")
                    .addPathSegments("api/v1/models")
                    .addPathSegment(modelId)
                    .addPathSegment("endpoints")
                    .build()
            )
            .header("Authorization", "Bearer $apiKey")
            .header("HTTP-Referer", "https://github.com/google/ai-studio")
            .header("X-Title", "Elsewhere")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                // If it 404s, some models might not support this API endpoint.
                if (response.code == 404) return@use emptyList()
                throw IOException("Unexpected code ${response.code}")
            }
            val body = response.body?.string() ?: ""
            val adapter = moshi.adapter(Map::class.java)
            val parsed = adapter.fromJson(body)
            val data = parsed?.get("data") as? Map<*, *> ?: return@use emptyList()
            val endpoints = data["endpoints"] as? List<*> ?: return@use emptyList()

            endpoints.mapNotNull { endpoint ->
                val item = endpoint as? Map<*, *> ?: return@mapNotNull null
                val tag = (item["tag"] ?: item["identifier"])
                    ?.toString()
                    ?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                ProviderEndpoint(
                    name = item["provider_name"]?.toString()
                        ?: item["name"]?.toString()
                        ?: tag,
                    identifier = tag
                )
            }.distinctBy(ProviderEndpoint::identifier)
        }
    }

    override fun streamResponse(
        messages: List<RoleplayMessage>,
        options: GenerationOptions
    ): Flow<StreamEvent> = callbackFlow {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrEmpty()) {
            trySend(StreamEvent.Error(IllegalStateException("API key not configured")))
            close()
            return@callbackFlow
        }

        val requestBodyMap = mutableMapOf<String, Any>(
            "model" to options.modelId,
            "stream" to true,
            "messages" to messages.map { msg ->
                val m = mutableMapOf("role" to msg.role.name.lowercase(), "content" to msg.content)
                if (msg.name != null) m["name"] = msg.name
                m
            }
        )

        options.maxTokens?.let { requestBodyMap["max_tokens"] = it }
        options.temperature?.let { requestBodyMap["temperature"] = it }
        options.topP?.let { requestBodyMap["top_p"] = it }
        if (options.stopSequences.isNotEmpty()) {
            requestBodyMap["stop"] = options.stopSequences
        }

        if (options.routing.mode != ProviderRoutingMode.AUTO) {
            val providerConfig = mutableMapOf<String, Any>()
            providerConfig["allow_fallbacks"] = options.routing.allowFallback
            if (options.routing.preferredEndpoints.isNotEmpty()) {
                providerConfig["order"] = options.routing.preferredEndpoints
            }
            requestBodyMap["provider"] = providerConfig
        }

        val jsonBody = moshi.adapter(Map::class.java).toJson(requestBodyMap)

        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("HTTP-Referer", "https://github.com/google/ai-studio")
            .header("X-Title", "Elsewhere")
            .post(jsonBody.toRequestBody(jsonMediaType))
            .build()

        val startTime = System.currentTimeMillis()
        var finalMetadata: GenerationMetadataEntity? = null
        val currentMessageId = UUID.randomUUID().toString() // Generate a dummy ID, UI should map it
        var resolvedModelId: String? = null
        var providerName: String? = null
        var promptTokens: Int? = null
        var completionTokens: Int? = null
        var totalTokens: Int? = null
        var reasoningTokens: Int? = null
        var cachedTokens: Int? = null
        var reportedCost: Double? = null
        var finishReason: String? = null
        val accumulatedContent = StringBuilder()

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    // Normal finish, wait for closed
                    return
                }
                try {
                    val parsed = moshi.adapter(Map::class.java).fromJson(data)
                    
                    val choices = parsed?.get("choices") as? List<*>
                    val firstChoice = choices?.firstOrNull() as? Map<*, *>
                    val delta = firstChoice?.get("delta") as? Map<*, *>
                    val content = delta?.get("content")?.toString()
                    
                    if (!content.isNullOrEmpty()) {
                        // Keep text independent from metadata-only chunks; emit the complete stream once closed.
                        accumulatedContent.append(content)
                    }
                    
                    (firstChoice?.get("finish_reason") as? String)?.let { finishReason = it }
                    (parsed?.get("model") as? String)?.let { resolvedModelId = it }
                    parsed?.get("provider")?.toString()?.let { providerName = it }

                    val usage = parsed?.get("usage") as? Map<String, Any>
                    if (usage != null) {
                        (usage["prompt_tokens"] as? Number)?.toInt()?.let { promptTokens = it }
                        (usage["completion_tokens"] as? Number)?.toInt()?.let { completionTokens = it }
                        (usage["total_tokens"] as? Number)?.toInt()?.let { totalTokens = it }
                        val completionDetails = usage["completion_tokens_details"] as? Map<String, Any>
                        val promptDetails = usage["prompt_tokens_details"] as? Map<String, Any>
                        (completionDetails?.get("reasoning_tokens") as? Number)?.toInt()?.let { reasoningTokens = it }
                        (promptDetails?.get("cached_tokens") as? Number)?.toInt()?.let { cachedTokens = it }
                        (usage["cost"] as? Number)?.toDouble()?.let { reportedCost = it }
                    }

                    if (finishReason != null || usage != null || resolvedModelId != null || providerName != null) {
                        finalMetadata = GenerationMetadataEntity(
                            messageId = currentMessageId, // Will be updated by caller
                            requestedModelId = options.modelId,
                            resolvedModelId = resolvedModelId,
                            promptTokens = promptTokens,
                            completionTokens = completionTokens,
                            totalTokens = totalTokens,
                            reasoningTokens = reasoningTokens,
                            cachedTokens = cachedTokens,
                            reportedCost = reportedCost,
                            finishReason = finishReason,
                            provider = providerName,
                            generationTimeMs = System.currentTimeMillis() - startTime
                        )
                    }
                } catch (e: Exception) {
                    // Ignore malformed chunks
                }
            }

            override fun onClosed(eventSource: EventSource) {
                if (accumulatedContent.isNotEmpty()) {
                    trySendBlocking(StreamEvent.Content(accumulatedContent.toString()))
                }
                finalMetadata = finalMetadata?.copy(
                    generationTimeMs = System.currentTimeMillis() - startTime
                )
                trySend(StreamEvent.Done(finalMetadata))
                close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (t != null) {
                    trySend(StreamEvent.Error(t))
                } else if (response != null && !response.isSuccessful) {
                    val err = response.body?.string() ?: "Unknown error"
                    trySend(StreamEvent.Error(IOException("HTTP ${response.code}: $err")))
                } else {
                    trySend(StreamEvent.Error(IOException("Unknown streaming failure")))
                }
                close()
            }
        }

        val eventSource = EventSources.createFactory(client).newEventSource(request, listener)

        awaitClose {
            eventSource.cancel()
        }
    }
}
