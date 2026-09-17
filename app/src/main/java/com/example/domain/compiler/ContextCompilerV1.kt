package com.example.domain.compiler

import com.example.data.model.CharacterEntity
import com.example.data.model.MessageEntity
import com.example.data.model.ManualCharacterFieldsCodec
import com.example.data.model.ManualPersonaFieldsCodec
import com.example.data.model.PersonaEntity
import com.example.data.model.ResponseLengthProfile
import com.example.data.model.RoleplayMemoryEntity
import com.example.data.model.SpeakerType
import com.example.domain.provider.Role
import com.example.domain.provider.RoleplayMessage

class ContextCompilerV1 {

    fun compileSoloContext(
        isContinuation: Boolean = false,
        character: CharacterEntity,
        persona: PersonaEntity,
        chatHistory: List<MessageEntity>,
        currentMessage: MessageEntity,
        responseProfile: ResponseLengthProfile,
        customMin: Int?,
        customTargetMax: Int?,
        customHardMax: Int?,
        durableMemories: List<RoleplayMemoryEntity> = emptyList(),
        rollingSummary: String? = null,
        recentMessageLimit: Int = 24
    ): List<RoleplayMessage> {
        val messages = mutableListOf<RoleplayMessage>()
        
        // 1. Core Instruction
        val coreInstruction = buildString {
            append("You are participating in a roleplay.\n")
            append("Portray the character described below. ")
            append("Treat the user as the Persona described below. ")
            append("Never write dialogue for the user/Persona. ")
            append("Never dictate the user/Persona's actions, thoughts, feelings, decisions, or internal state. ")
            append("React to both spoken dialogue and visible actions from the user/Persona. ")
            append("Maintain your character's identity, voice, behavior, and established relationship state. ")
            append("Respect previously established conversation events. ")
            append("Do not speak as unrelated characters unless explicitly required by scene context. ")
            append("Do not mention prompts, JSON, context compilation, or application mechanics in-character.\n\n")
            
            // Character details
            append("--- CHARACTER: ${character.displayName} ---\n")
            if (!character.identityJson.isNullOrBlank()) append("Identity:\n${character.identityJson}\n\n")
            if (!character.appearanceJson.isNullOrBlank()) append("Appearance:\n${character.appearanceJson}\n\n")
            if (!character.personalityJson.isNullOrBlank()) append("Personality:\n${character.personalityJson}\n\n")
            if (!character.voiceJson.isNullOrBlank()) append("Voice:\n${character.voiceJson}\n\n")
            if (!character.behaviorJson.isNullOrBlank()) append("Behavior:\n${character.behaviorJson}\n\n")
            if (!character.backstoryJson.isNullOrBlank()) append("Backstory:\n${character.backstoryJson}\n\n")
            if (!character.knowledgeJson.isNullOrBlank()) append("Knowledge:\n${character.knowledgeJson}\n\n")
            if (!character.worldContextJson.isNullOrBlank()) append("World Context:\n${character.worldContextJson}\n\n")
            if (!character.writingRulesJson.isNullOrBlank()) append("Writing Rules:\n${character.writingRulesJson}\n\n")
            if (!character.examplesJson.isNullOrBlank()) append("Examples:\n${character.examplesJson}\n\n")
            ManualCharacterFieldsCodec.readStored(character)?.let { manual ->
                if (manual.systemInstructions.isNotEmpty()) {
                    append("--- CHARACTER SYSTEM INSTRUCTIONS ---\n")
                    append(manual.systemInstructions)
                    append("\n\n")
                }
                append("--- MANUAL CHARACTER DETAILS ---\n")
                appendManualField("Name", manual.name)
                appendManualField("Short Backstory", manual.shortBackstory)
                appendManualField("Personality", manual.personality)
                appendManualField("Tone", manual.tone)
                appendManualField("Age", manual.age)
                appendManualField("Birthday", manual.birthday)
                appendManualField("Story", manual.story)
                appendManualField("Likes", manual.likes)
                appendManualField("Dislikes", manual.dislikes)
                appendManualField("Conversational Goals", manual.conversationalGoals)
                appendManualField("Conversational Examples", manual.conversationalExamples)
                appendManualField("Appearance", manual.appearance)
                appendManualField("Knowledge - Relationships", manual.knowledgeRelationships)
                appendManualField("Knowledge - General", manual.knowledgeGeneral)
                append('\n')
            }
            // Intentionally excluding authorNotesJson

            // Persona details
            append("--- USER PERSONA: ${persona.displayName} ---\n")
            if (!persona.identityJson.isNullOrBlank()) append("Identity:\n${persona.identityJson}\n\n")
            if (!persona.appearanceJson.isNullOrBlank()) append("Appearance:\n${persona.appearanceJson}\n\n")
            if (!persona.personalityJson.isNullOrBlank()) append("Personality:\n${persona.personalityJson}\n\n")
            if (!persona.backgroundJson.isNullOrBlank()) append("Background:\n${persona.backgroundJson}\n\n")
            ManualPersonaFieldsCodec.readStored(persona)?.backstoryInstructions
                ?.takeIf { it.isNotEmpty() }
                ?.let { instructions ->
                    append("Backstory / Persona Instructions:\n")
                    append(instructions)
                    append("\n\n")
                }
            // Intentionally excluding privateNotesJson

            if (durableMemories.isNotEmpty()) {
                append("--- DURABLE ROLEPLAY MEMORY ---\n")
                durableMemories.forEach { memory ->
                    append("- [${memory.category.name}] ${memory.content}\n")
                }
                append('\n')
            }

            if (!rollingSummary.isNullOrBlank()) {
                append("--- ROLLING CONVERSATION SUMMARY ---\n")
                append(rollingSummary)
                append("\n\n")
            }
            
            // Length Instruction
            append("--- LENGTH INSTRUCTION ---\n")
            val lengthInstruction = when (responseProfile) {
                ResponseLengthProfile.SHORT -> "Target length is 400-800 characters. Absolute maximum is 1000 characters."
                ResponseLengthProfile.NORMAL -> "Target length is 900-1600 characters. Absolute maximum is 1900 characters."
                ResponseLengthProfile.LONG -> "Target length is 1600-2600 characters. Absolute maximum is 3000 characters."
                ResponseLengthProfile.CUSTOM -> {
                    val min = customMin ?: 900
                    val target = customTargetMax ?: 1600
                    val max = customHardMax ?: 1900
                    "Target length is $min-$target characters. Absolute maximum is $max characters."
                }
            }
            append(lengthInstruction)
        }
        
        messages.add(RoleplayMessage(role = Role.SYSTEM, content = coreInstruction))
        
        // Older messages are represented by the persisted rolling summary. Keep recent turns verbatim.
        val trimmedHistory = chatHistory.filter { it.isPrimaryVariant }.takeLast(recentMessageLimit)
        
        for (msg in trimmedHistory) {
            val role = when (msg.speakerType) {
                SpeakerType.CHARACTER -> Role.ASSISTANT
                SpeakerType.PERSONA -> Role.USER
                else -> Role.SYSTEM
            }
            messages.add(RoleplayMessage(role = role, content = msg.content, name = msg.speakerDisplayNameSnapshot))
        }
        
        // Add current message
        val role = when (currentMessage.speakerType) {
            SpeakerType.CHARACTER -> Role.ASSISTANT
            SpeakerType.PERSONA -> Role.USER
            else -> Role.SYSTEM
        }
        messages.add(RoleplayMessage(role = role, content = currentMessage.content, name = currentMessage.speakerDisplayNameSnapshot))
        
        if (isContinuation) {
            messages.add(RoleplayMessage(role = Role.SYSTEM, content = "Continue the previous message. Do not repeat what you have already said."))
        }
        
        return messages
    }

    private fun StringBuilder.appendManualField(label: String, value: String) {
        if (value.isNotEmpty()) append("$label:\n$value\n")
    }
}
