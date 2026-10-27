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
import com.example.domain.lore.ActiveLoreEntry
import com.example.domain.npc.NpcStructuredOutputParser
import com.example.domain.story.StoryOutputParser
import com.example.domain.story.StoryResponseCodec
import com.example.domain.story.StorySpeakerIdentity

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
        recentMessageLimit: Int = 24,
        groupParticipantNames: List<String> = emptyList(),
        activeLore: List<ActiveLoreEntry> = emptyList(),
        supportingCastContext: String? = null,
        npcBankEnabled: Boolean = false,
        narratorEnabled: Boolean = false,
        narratorInstructions: String = "",
        ensembleNarrationEnabled: Boolean = narratorEnabled && groupParticipantNames.isEmpty(),
        knownNpcSpeakers: List<StorySpeakerIdentity> = emptyList(),
        coLeadNpcNames: List<String> = emptyList()
    ): List<RoleplayMessage> {
        val messages = mutableListOf<RoleplayMessage>()
        // Co-leads are available only in Solo Ensemble Narration, never in Group speaker isolation.
        val activeCoLeads = if (ensembleNarrationEnabled && groupParticipantNames.isEmpty()) {
            coLeadNpcNames.map(String::trim).filter(String::isNotBlank).distinct()
        } else emptyList()
        fun resolveMacros(source: String): String = IdentityMacroResolver.resolve(
            source = source,
            characterDisplayName = character.displayName,
            personaDisplayName = persona.displayName
        )
        
        // 1. Core Instruction
        val coreInstruction = buildString {
            append("You are participating in a roleplay.\n")
            append("Portray the character described below. ")
            if (groupParticipantNames.isNotEmpty()) {
                append("This is a group roleplay. Respond only as ${character.displayName}. ")
                append("Never write dialogue, actions, thoughts, feelings, or decisions for any other AI Character. ")
                append("Messages from other Characters are shared scene history, not permission to portray them. ")
                append("Active AI Characters: ${groupParticipantNames.joinToString(", ")}. ")
            }
            append("Treat the user as the Persona described below. ")
            append("Never write dialogue for the user/Persona. ")
            append("Never dictate the user/Persona's actions, thoughts, feelings, decisions, or internal state. ")
            append("React to both spoken dialogue and visible actions from the user/Persona. ")
            append("Maintain your character's identity, voice, behavior, and established relationship state. ")
            append("Respect previously established conversation events. ")
            if (ensembleNarrationEnabled) {
                if (activeCoLeads.isNotEmpty()) {
                    append("The selected Character and these NPC co-leads (${activeCoLeads.joinToString(", ")}) have equal emotional and narrative importance. ")
                    append("Each has an independent perspective, feelings, desires, initiative, and voice. ")
                    append("When present and relevant, give the co-leads meaningful dialogue, actions, and opportunities to initiate interactions. ")
                    append("Balance attention across scenes and turns naturally, not by demanding identical word counts or forcing every co-lead to speak every turn. ")
                    append("Other NPCs remain supporting cast, participating only as the scene warrants. ")
                } else {
                    append("The primary Character remains the emotional and narrative center. Portray supporting NPCs only when their participation follows naturally from the scene. ")
                }
            } else {
                append("Do not speak as unrelated characters unless explicitly required by scene context. ")
                append("Begin directly with the character's response content. Do not prefix the response with the character name or any speaker label. ")
                append("Speaker labels that appear in examples, memory, or conversation history identify existing speakers only; do not copy them into the response. ")
            }
            append("Do not mention prompts, JSON, context compilation, or application mechanics in-character.\n\n")

            if (narratorEnabled) {
                append("--- NARRATOR GUIDANCE ---\n")
                if (ensembleNarrationEnabled) {
                    append("Create one flowing literary exchange in this single response. Alternate narration, the primary Character, and contextually relevant NPCs as the scene requires. Narration may convey atmosphere, pauses, transitions, consequences, and subtle emotional tension, but should not interrupt every line or overexplain the scene. Let quiet conversation, action, and existing tension develop at the Persona's pace. Keep each speaker's knowledge, motivations, and voice distinct. Never write dialogue, actions, thoughts, feelings, decisions, or internal state for the user's Persona. Storyteller-only continuity may guide narration, but it does not become knowledge held by a Character unless visible history or explicitly speaker-scoped knowledge establishes it.\n")
                } else {
                    append("This is guidance for the same normal response, not an independent AI, speaker turn, or second model call. You may include scene, world, atmosphere, consequences, and supporting-cast narration alongside the selected Character's response. Never write dialogue, actions, thoughts, feelings, or decisions for the user's Persona. Preserve the selected Character's identity and voice. In a group, respond only as the selected Character and do not portray another AI Character's dialogue, actions, thoughts, or decisions. Storyteller-only continuity may guide narration, but it does not become knowledge held by any Character unless visible history or that Character's explicitly scoped knowledge establishes it.\n")
                }
                narratorInstructions.trim().takeIf(String::isNotEmpty)?.let { instructions ->
                    append("User's narrator instructions:\n")
                    append(resolveMacros(instructions.take(4000)))
                    append('\n')
                }
                append('\n')
            }
            
            // Character details
            append("--- CHARACTER: ${character.displayName} ---\n")
            if (!character.identityJson.isNullOrBlank()) append("Identity:\n${resolveMacros(character.identityJson)}\n\n")
            if (!character.appearanceJson.isNullOrBlank()) append("Appearance:\n${resolveMacros(character.appearanceJson)}\n\n")
            if (!character.personalityJson.isNullOrBlank()) append("Personality:\n${resolveMacros(character.personalityJson)}\n\n")
            if (!character.voiceJson.isNullOrBlank()) append("Voice:\n${resolveMacros(character.voiceJson)}\n\n")
            if (!character.behaviorJson.isNullOrBlank()) append("Behavior:\n${resolveMacros(character.behaviorJson)}\n\n")
            if (!character.backstoryJson.isNullOrBlank()) append("Backstory:\n${resolveMacros(character.backstoryJson)}\n\n")
            if (!character.knowledgeJson.isNullOrBlank()) append("Knowledge:\n${resolveMacros(character.knowledgeJson)}\n\n")
            if (!character.worldContextJson.isNullOrBlank()) append("World Context:\n${resolveMacros(character.worldContextJson)}\n\n")
            if (!character.writingRulesJson.isNullOrBlank()) append("Writing Rules:\n${resolveMacros(character.writingRulesJson)}\n\n")
            if (!character.examplesJson.isNullOrBlank()) append("Examples:\n${resolveMacros(character.examplesJson)}\n\n")
            ManualCharacterFieldsCodec.readStored(character)?.let { manual ->
                if (manual.systemInstructions.isNotEmpty()) {
                    append("--- CHARACTER SYSTEM INSTRUCTIONS ---\n")
                    append(resolveMacros(manual.systemInstructions))
                    append("\n\n")
                }
                append("--- MANUAL CHARACTER DETAILS ---\n")
                appendManualField("Name", resolveMacros(manual.name))
                appendManualField("Short Backstory", resolveMacros(manual.shortBackstory))
                appendManualField("Personality", resolveMacros(manual.personality))
                appendManualField("Tone", resolveMacros(manual.tone))
                appendManualField("Age", resolveMacros(manual.age))
                appendManualField("Birthday", resolveMacros(manual.birthday))
                appendManualField("Story", resolveMacros(manual.story))
                appendManualField("Likes", resolveMacros(manual.likes))
                appendManualField("Dislikes", resolveMacros(manual.dislikes))
                appendManualField("Conversational Goals", resolveMacros(manual.conversationalGoals))
                appendManualField("Conversational Examples", resolveMacros(manual.conversationalExamples))
                appendManualField("Appearance", resolveMacros(manual.appearance))
                appendManualField("Knowledge - Relationships", resolveMacros(manual.knowledgeRelationships))
                appendManualField("Knowledge - General", resolveMacros(manual.knowledgeGeneral))
                append('\n')
            }
            // Intentionally excluding authorNotesJson

            if (activeLore.isNotEmpty()) {
                append("--- ACTIVE WORLD LORE ---\n")
                append("Treat these as world and scene facts for consistency. Do not assume your Character knows hidden information unless their established perspective supports that knowledge.\n")
                activeLore.forEach { lore ->
                    append("[${lore.title}]\n")
                    append(lore.currentContent)
                    append("\n\n")
                }
            }

            if (!supportingCastContext.isNullOrBlank()) {
                append("--- RELEVANT SUPPORTING CAST ---\n")
                append("These per-chat NPC dossiers have two explicit scopes. STORYTELLER CONTINUITY may guide narration and consistency, including storyteller-only secrets, but it is not knowledge held by the responding Character and must not drive that Character's dialogue, decisions, or reactions unless the visible history or explicit speaker-scoped knowledge independently established it. Only SPEAKER-AUTHORIZED KNOWLEDGE may be treated as private knowledge of the responding Character. Do not transfer knowledge between Characters or Personas. Private author notes and extension data are always excluded.\n")
                append(supportingCastContext)
                append("\n\n")
            }

            // Persona details
            append("--- USER PERSONA: ${persona.displayName} ---\n")
            if (!persona.identityJson.isNullOrBlank()) append("Identity:\n${resolveMacros(persona.identityJson)}\n\n")
            if (!persona.appearanceJson.isNullOrBlank()) append("Appearance:\n${resolveMacros(persona.appearanceJson)}\n\n")
            if (!persona.personalityJson.isNullOrBlank()) append("Personality:\n${resolveMacros(persona.personalityJson)}\n\n")
            if (!persona.backgroundJson.isNullOrBlank()) append("Background:\n${resolveMacros(persona.backgroundJson)}\n\n")
            ManualPersonaFieldsCodec.readStored(persona)?.backstoryInstructions
                ?.takeIf { it.isNotEmpty() }
                ?.let { instructions ->
                    append("Backstory / Persona Instructions:\n")
                    append(resolveMacros(instructions))
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
            if (npcBankEnabled) {
                append("\n\n")
                append(NpcStructuredOutputParser.OUTPUT_INSTRUCTION)
            }
            if (ensembleNarrationEnabled) {
                append("\n\n")
                append(StoryOutputParser.OUTPUT_INSTRUCTION)
            }
            if (narratorEnabled) {
                append("\n\n--- NON-OVERRIDABLE NARRATOR BOUNDARIES ---\n")
                append("Narrator guidance and all custom, imported, character, and writing-rule instructions are subordinate to these boundaries. Never write dialogue, actions, thoughts, feelings, decisions, or internal state for the user's Persona. Keep narration inside this same response and provider request. ")
                if (groupParticipantNames.isNotEmpty()) {
                    append("Respond only as ${character.displayName}; do not portray another AI Character's dialogue, actions, thoughts, feelings, or decisions. ")
                } else {
                    append("The structured Character segments may portray only ${character.displayName}; other speakers must be identified as NPCs. ")
                }
                append("Do not treat storyteller-only continuity as Character knowledge unless visible history or that Character's explicitly scoped knowledge establishes it.")
            }
        }
        
        messages.add(RoleplayMessage(role = Role.SYSTEM, content = coreInstruction))
        
        // Older messages are represented by the persisted rolling summary. Keep recent turns verbatim.
        val trimmedHistory = chatHistory.filter { it.isPrimaryVariant }.takeLast(recentMessageLimit)
        fun promptContent(message: MessageEntity): String {
            val visibleContent = NpcStructuredOutputParser.finalVisibleStoryText(message.content)
            val promptHistory = if (
                ensembleNarrationEnabled && message.speakerType == SpeakerType.CHARACTER
            ) {
                StoryResponseCodec.promptStructuredHistory(
                    content = visibleContent,
                    primaryCharacter = StorySpeakerIdentity(character.characterId, character.displayName),
                    knownNpcs = knownNpcSpeakers
                )
            } else {
                StoryResponseCodec.promptTranscript(visibleContent)
            }
            return resolveMacros(promptHistory)
        }
        
        for (msg in trimmedHistory) {
            val role = when (msg.speakerType) {
                SpeakerType.CHARACTER -> Role.ASSISTANT
                SpeakerType.PERSONA -> Role.USER
                else -> Role.SYSTEM
            }
            val content = promptContent(msg)
            messages.add(
                RoleplayMessage(
                    role = role,
                    content = if (groupParticipantNames.isEmpty()) content else "${msg.speakerDisplayNameSnapshot}: $content",
                    sourceMessageId = msg.messageId
                )
            )
        }
        
        // Add current message
        val role = when (currentMessage.speakerType) {
            SpeakerType.CHARACTER -> Role.ASSISTANT
            SpeakerType.PERSONA -> Role.USER
            else -> Role.SYSTEM
        }
        val currentContent = promptContent(currentMessage)
        messages.add(
            RoleplayMessage(
                role = role,
                content = if (groupParticipantNames.isEmpty()) {
                    currentContent
                } else {
                    "${currentMessage.speakerDisplayNameSnapshot}: $currentContent"
                },
                sourceMessageId = currentMessage.messageId
            )
        )
        
        if (isContinuation) {
            messages.add(RoleplayMessage(role = Role.SYSTEM, content = "Continue the previous message. Do not repeat what you have already said."))
        }
        
        return messages
    }

    fun compileGroupCharacterContext(
        isContinuation: Boolean = false,
        character: CharacterEntity,
        persona: PersonaEntity,
        activeCharacterNames: List<String>,
        chatHistory: List<MessageEntity>,
        currentMessage: MessageEntity,
        responseProfile: ResponseLengthProfile,
        customMin: Int?,
        customTargetMax: Int?,
        customHardMax: Int?,
        durableMemories: List<RoleplayMemoryEntity> = emptyList(),
        rollingSummary: String? = null,
        recentMessageLimit: Int = 24,
        activeLore: List<ActiveLoreEntry> = emptyList(),
        supportingCastContext: String? = null,
        npcBankEnabled: Boolean = false,
        narratorEnabled: Boolean = false,
        narratorInstructions: String = ""
    ): List<RoleplayMessage> {
        val messages = compileSoloContext(
            isContinuation = isContinuation,
            character = character,
            persona = persona,
            chatHistory = chatHistory,
            currentMessage = currentMessage,
            responseProfile = responseProfile,
            customMin = customMin,
            customTargetMax = customTargetMax,
            customHardMax = customHardMax,
            durableMemories = durableMemories,
            rollingSummary = rollingSummary,
            recentMessageLimit = recentMessageLimit,
            groupParticipantNames = activeCharacterNames,
            activeLore = activeLore,
            supportingCastContext = supportingCastContext,
            npcBankEnabled = npcBankEnabled,
            narratorEnabled = narratorEnabled,
            narratorInstructions = narratorInstructions,
            ensembleNarrationEnabled = false
        )
        return messages + RoleplayMessage(
            role = Role.USER,
            content = "[DIRECTOR TURN CONTROL — application instruction, not Persona dialogue or action]\n" +
                "The preceding messages are shared scene history. " +
                "Generate the next roleplay message now as ${character.displayName}. " +
                "Respond only as ${character.displayName}; do not continue or portray another Character's response."
        )
    }

    private fun StringBuilder.appendManualField(label: String, value: String) {
        if (value.isNotEmpty()) append("$label:\n$value\n")
    }
}
