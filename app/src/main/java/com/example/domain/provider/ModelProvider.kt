package com.example.domain.provider

import kotlinx.coroutines.flow.Flow

enum class Role { SYSTEM, USER, ASSISTANT }

data class RoleplayMessage(
    val role: Role,
    val content: String,
    val name: String? = null, // Some models support 'name'
    val sourceMessageId: String? = null,
    val images: List<ProviderImage> = emptyList()
)

data class ProviderEndpoint(
    val name: String,
    val identifier: String
)

data class ModelRouting(
    val mode: com.example.data.model.ProviderRoutingMode,
    val allowFallback: Boolean = true,
    val preferredEndpoints: List<String> = emptyList()
) {
    companion object {
        fun fromSettings(
            mode: com.example.data.model.ProviderRoutingMode,
            endpoint: String?
        ) = ModelRouting(
            mode = mode,
            allowFallback = mode != com.example.data.model.ProviderRoutingMode.LOCK,
            preferredEndpoints = endpoint?.takeIf(String::isNotBlank)?.let(::listOf).orEmpty()
        )
    }
}

data class GenerationOptions(
    val modelId: String,
    val routing: ModelRouting = ModelRouting(com.example.data.model.ProviderRoutingMode.AUTO),
    val maxTokens: Int? = null,
    val temperature: Float? = null,
    val topP: Float? = null,
    val stopSequences: List<String> = emptyList()
)

data class GenerationResult(
    val content: String,
    val metadata: com.example.data.model.GenerationMetadataEntity? = null
)

sealed class StreamEvent {
    data class Content(val text: String) : StreamEvent()
    /** Latest provider metadata observed while the stream is still open. */
    data class Metadata(val metadata: com.example.data.model.GenerationMetadataEntity) : StreamEvent()
    data class Done(val metadata: com.example.data.model.GenerationMetadataEntity?) : StreamEvent()
    data class Error(val exception: Throwable) : StreamEvent()
}

interface ModelProvider {
    suspend fun getModels(): List<OpenRouterModel>
    suspend fun getEndpoints(modelId: String): List<ProviderEndpoint>

    /** Null means the provider catalog has not established capability either way. */
    suspend fun supportsImageInput(modelId: String): Boolean? = null

    fun streamResponse(
        messages: List<RoleplayMessage>,
        options: GenerationOptions
    ): Flow<StreamEvent>
}

data class OpenRouterModel(
    val id: String,
    val name: String,
    val contextLength: Int,
    val pricingPrompt: String,
    val pricingCompletion: String,
    val inputModalities: List<String> = emptyList()
)
