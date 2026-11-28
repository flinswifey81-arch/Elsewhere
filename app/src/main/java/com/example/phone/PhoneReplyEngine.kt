package com.example.phone

import com.example.data.model.ProviderRoutingMode
import com.example.domain.provider.*
import com.example.domain.repository.ChatRepository
import com.example.phonelab.systems.PhoneTextRequest
import kotlinx.coroutines.flow.collect
import java.util.UUID

/** Explicit send/retry only. Never forwards the Story transcript or appends a Story turn. */
internal class PhoneReplyEngine(
    private val chats: ChatRepository,
    private val provider: ModelProvider,
    private val phone: StoryPhoneActions
) {
    suspend fun reply(pending: StoryPhoneActions.PendingText) {
        val settings = requireNotNull(chats.getEffectiveChatSettings(pending.target.chatId)) {
            "Story engine settings are unavailable."
        }
        require(settings.providerRoutingMode != ProviderRoutingMode.LOCK || !settings.providerEndpoint.isNullOrBlank()) {
            "Choose a provider endpoint in Story settings before retrying."
        }
        val messages = listOf(RoleplayMessage(Role.SYSTEM, pending.request.instructions +
            pending.portrayal.takeIf(String::isNotBlank)?.let { "\n\nContact portrayal notes:\n$it" }.orEmpty())) +
            pending.request.history.map {
                RoleplayMessage(if (it.role == PhoneTextRequest.Role.USER) Role.USER else Role.ASSISTANT, it.text)
            }
        val options = GenerationOptions(
            modelId = settings.selectedModelId ?: "openrouter/auto",
            routing = ModelRouting.fromSettings(settings.providerRoutingMode, settings.providerEndpoint),
            temperature = settings.temperature, topP = settings.topP, maxTokens = 450
        )
        val out = StringBuilder()
        var ended = false
        provider.streamResponse(messages, options).collect {
            when (it) {
                is StreamEvent.Content -> if (!ended) {
                    require(out.length + it.text.length <= 4000) { "The reply exceeded the phone's 4,000-character limit." }
                    out.append(it.text)
                }
                is StreamEvent.Done -> ended = true
                is StreamEvent.Error -> error("The Story engine could not complete this reply.")
                is StreamEvent.Metadata -> Unit
            }
        }
        val text = out.toString().trim()
        check(ended && text.isNotBlank()) { "No complete reply came back from the Story engine." }
        phone.storeContactReply(pending, UUID.randomUUID().toString(), text, System.currentTimeMillis())
    }
}
