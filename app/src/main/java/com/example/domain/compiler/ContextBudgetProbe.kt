package com.example.domain.compiler

import com.example.domain.provider.GenerationOptions
import com.example.domain.provider.Role
import com.example.domain.provider.RoleplayMessage
import java.nio.charset.StandardCharsets

/**
 * Stage 0 diagnostic: classifies the EXACT assembled prompt that will be given
 * to the provider. Counts only; caller MUST NOT log prompt text or identifiers.
 *
 * Rough estimate = UTF-8 byte count / 4 rounded up. This is NOT a tokenizer,
 * NOT a context limit, NOT a billed token count, and excludes image token cost.
 * Section headings are currently an implementation detail of the two compilers.
 */
enum class ContextBudgetBucket {
    CORE, CHARACTER, PERSONA, WORLD, SCENARIO, LOREBOOK,
    SUPPORTING_CAST, MEMORY, SUMMARY, TRANSCRIPT, DIRECTOR
}

data class ContextBudgetSnapshot(
    val utf8BytesByBucket: Map<ContextBudgetBucket, Long>,
    val messageCount: Int,
    val attachedImageCount: Int,
    val explicitOutputTokenCeiling: Int?
) {
    val inputUtf8Bytes: Long get() = utf8BytesByBucket.values.sum()
    val approximateInputTokens: Long get() = (inputUtf8Bytes + 3L) / 4L

    fun approximateTokens(bucket: ContextBudgetBucket): Long =
        ((utf8BytesByBucket[bucket] ?: 0L) + 3L) / 4L

    /** Text-only hypothetical cost. Caller supplies an explicit USD / 1M rate.
     * Image cost, provider overhead, output and actual tokenizer differences are excluded.
     * Returns null if rate is unknown. Does not guess a model's pricing.
     */
    fun approximateTextInputUsd(usdPerMillionInputTokens: Double?): Double? =
        usdPerMillionInputTokens
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?.let { approximateInputTokens * it / 1_000_000.0 }

    /** Privacy-respecting diagnostic with numerical counts and no user data. */
    fun countsOnlyDebugLine(): String = buildString {
        append("approxInputTokens=").append(approximateInputTokens)
        append(" utf8Bytes=").append(inputUtf8Bytes)
        append(" messages=").append(messageCount)
        append(" images=").append(attachedImageCount)
        append(" outputCeiling=").append(explicitOutputTokenCeiling ?: "unset")
        ContextBudgetBucket.entries.forEach {
            append(' ').append(it.name.lowercase()).append('=').append(approximateTokens(it))
        }
    }
}

object ContextBudgetProbe {
    private val headingPattern = Regex("^--- (.+?) ---\\s*$")

    fun measure(
        messages: List<RoleplayMessage>,
        options: GenerationOptions? = null
    ): ContextBudgetSnapshot {
        val bytes = ContextBudgetBucket.entries.associateWith { 0L }.toMutableMap()
        fun add(bucket: ContextBudgetBucket, text: String) {
            bytes[bucket] = bytes.getValue(bucket) + text.toByteArray(StandardCharsets.UTF_8).size
        }
        for (message in messages) {
            when {
                message.sourceMessageId != null -> add(ContextBudgetBucket.TRANSCRIPT, message.content)
                message.content.startsWith("[DIRECTOR TURN CONTROL") -> add(ContextBudgetBucket.DIRECTOR, message.content)
                message.role == Role.SYSTEM -> {
                    var bucket = ContextBudgetBucket.CORE
                    val lines = message.content.split('\n')
                    lines.forEachIndexed { index, line ->
                        val header = headingPattern.matchEntire(line)?.groupValues?.get(1)
                        if (header != null) bucket = classify(header)
                        add(bucket, line + if (index < lines.lastIndex) "\n" else "")
                    }
                }
                else -> add(ContextBudgetBucket.DIRECTOR, message.content)
            }
        }
        return ContextBudgetSnapshot(
            utf8BytesByBucket = bytes,
            messageCount = messages.size,
            attachedImageCount = messages.sumOf { it.images.size },
            explicitOutputTokenCeiling = options?.maxTokens
        )
    }

    private fun classify(heading: String): ContextBudgetBucket {
        val normalized = heading.uppercase()
        return when {
            normalized.startsWith("SHARED WORLD BASELINE") ||
                normalized.startsWith("WORLD AND SETTING") ||
                normalized.startsWith("WORLD RULES AND LORE") -> ContextBudgetBucket.WORLD
            normalized.startsWith("CHARACTER:") ||
                normalized.startsWith("CHARACTER SYSTEM") ||
                normalized.startsWith("MANUAL CHARACTER") -> ContextBudgetBucket.CHARACTER
            normalized.startsWith("USER PERSONA:") ||
                normalized.startsWith("PLAYER PERSONA") ||
                normalized.startsWith("PLAYER IDENTITY") ||
                normalized.startsWith("PLAYER APPEARANCE") ||
                normalized.startsWith("PLAYER PERSONALITY") ||
                normalized.startsWith("PLAYER ROLEPLAY PROFILE") ||
                normalized.startsWith("PLAYER BACKGROUND") ||
                normalized.startsWith("PLAYER WORLD CONTEXT") ||
                normalized.startsWith("PLAYER PERSONA INSTRUCTIONS") -> ContextBudgetBucket.PERSONA
            normalized.startsWith("ACTIVE WORLD LORE") ||
                normalized.startsWith("ACTIVE LORE:") -> ContextBudgetBucket.LOREBOOK
            normalized.startsWith("RELEVANT SUPPORTING CAST") ||
                normalized.startsWith("CREATOR-DEFINED NPC:") -> ContextBudgetBucket.SUPPORTING_CAST
            normalized.startsWith("DURABLE ROLEPLAY MEMORY") ||
                normalized.startsWith("SESSION MEMORY") -> ContextBudgetBucket.MEMORY
            normalized.startsWith("ROLLING CONVERSATION SUMMARY") ||
                normalized.startsWith("EARLIER SESSION SUMMARY") -> ContextBudgetBucket.SUMMARY
            normalized.startsWith("SCENARIO ") ||
                normalized.startsWith("INITIAL SITUATION") ||
                normalized.startsWith("LOCATIONS") ||
                normalized.startsWith("FACTIONS") ||
                normalized.startsWith("NARRATION STYLE") -> ContextBudgetBucket.SCENARIO
            else -> ContextBudgetBucket.CORE
        }
    }
}
