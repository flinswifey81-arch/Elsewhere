package com.example.domain.compiler

import com.example.data.model.CharacterEntity
import com.example.data.model.MessageEntity
import com.example.data.model.PersonaEntity

data class ContextAssemblyRequest(
    val character: CharacterEntity,
    val persona: PersonaEntity,
    val recentMessages: List<MessageEntity>,
    val additionalContext: String? = null
)

interface ContextCompiler {
    fun assemblePrompt(request: ContextAssemblyRequest): String
}

class DefaultContextCompiler : ContextCompiler {
    override fun assemblePrompt(request: ContextAssemblyRequest): String {
        // Placeholder for future complex compilation logic
        val builder = StringBuilder()
        
        builder.appendLine("You are ${request.character.characterName}.")
        builder.appendLine("You are talking to ${request.persona.personaName}.")
        
        builder.appendLine("\n--- Chat History ---")
        request.recentMessages.forEach { msg ->
            builder.appendLine("${msg.speakerDisplayNameSnapshot}: ${msg.content}")
        }
        
        return builder.toString()
    }
}
