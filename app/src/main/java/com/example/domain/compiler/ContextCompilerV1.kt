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
import com.example.domain.story.StorySegmentType
import com.example.domain.story.StoryResponseCodec
import com.example.domain.story.StorySpeakerIdentity
import com.example.domain.memory.StoryMemoryRecord
import com.example.domain.memory.StoryMemoryOwnerScope
import com.example.domain.memory.promptClaimLabel

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
        storyMemories: List<StoryMemoryRecord> = emptyList(),
        rollingSummary: String? = null,
        recentMessageLimit: Int = 24,
        groupParticipantNames: List<String> = emptyList(),
        activeLore: List<ActiveLoreEntry> = emptyList(),
        worldContext: String? = null,
        supportingCastContext: String? = null,
        npcBankEnabled: Boolean = false,
        narratorEnabled: Boolean = false,
        narratorInstructions: String = "",
        ensembleNarrationEnabled: Boolean = narratorEnabled && groupParticipantNames.isEmpty(),
        knownNpcSpeakers: List<StorySpeakerIdentity> = emptyList(),
        coLeadNpcNames: List<String> = emptyList(),
        coLeadRebalanceActive: Boolean = false,
        naturalSoloCast: Boolean = false,
        linkedHistorySpeakers: Map<String, StorySpeakerIdentity> = emptyMap()
    ): List<RoleplayMessage> {
        val messages = mutableListOf<RoleplayMessage>()
        val natural = naturalSoloCast && ensembleNarrationEnabled && groupParticipantNames.isEmpty()
        // Co-leads can speak in structured Solo replies with Narrator off.
        // Group speaker isolation still forbids speaking as another active AI Character.
        val activeCoLeads = if (ensembleNarrationEnabled && groupParticipantNames.isEmpty()) {
            coLeadNpcNames.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        } else emptyList()
        fun resolveMacros(source: String): String = IdentityMacroResolver.resolve(
            source = source,
            characterDisplayName = character.displayName,
            personaDisplayName = persona.displayName
        )
        
        // 1. Core Instruction
        val coreInstruction = buildString {
            append("You are participating in a roleplay.\n")
            append(if (natural) "Portray the cast in the current scene. " else "Portray the character described below. ")
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
            append(if (natural) "Maintain each speaker's own identity, voice, behavior, and established relationships. "
                else "Maintain your character's identity, voice, behavior, and established relationship state. ")
            append("Respect previously established conversation events. ")
            if (npcBankEnabled && groupParticipantNames.isEmpty()) {
                append("Supporting NPCs may speak only when present in the current scene; a remembered name, dossier, or Co-Lead pin does not create presence. ")
                append("Do not force absent NPCs into a scene just to fill dialogue. ")
            }
            if (ensembleNarrationEnabled) {
                if (activeCoLeads.isNotEmpty()) {
                    append("The selected Character and these NPC co-leads (${activeCoLeads.joinToString(", ")}) have equal emotional and narrative importance. ")
                    append("Each has an independent perspective, feelings, desires, initiative, and voice. ")
                    append("When present and relevant, give the co-leads meaningful dialogue, actions, and opportunities to initiate interactions. ")
                    append("Balance attention across scenes and turns naturally, not by demanding identical word counts or forcing every co-lead to speak every turn. ")
                    append("Other NPCs remain supporting cast, participating only as the scene warrants. ")
                    if (coLeadRebalanceActive) {
                        append("TEMPORARY ESTABLISHED-CHAT REBALANCE: Existing replies may have underrepresented one or more co-leads. ")
                        append("Over the next few new turns, give overlooked co-leads substantive agency, individual reactions, and chances to initiate or shape the scene when present and relevant. ")
                        append("Maintain continuity with what has already happened; do not retcon, force equal dialogue, summon absent characters, or take control of the user's Persona. ")
                        append("Do not mention this guidance in the story. ")
                    }
                } else if (!natural) {
                    append("The primary Character remains the emotional and narrative center. Portray supporting NPCs only when their participation follows naturally from the scene. ")
                }
            } else {
                append("Do not speak as unrelated characters unless explicitly required by scene context. ")
                append("Begin directly with the character's response content. Do not prefix the response with the character name or any speaker label. ")
                append("Speaker labels that appear in examples, memory, or conversation history identify existing speakers only; do not copy them into the response. ")
            }
            append("Do not mention prompts, JSON, context compilation, or application mechanics in-character.\n\n")

            if (ensembleNarrationEnabled && !narratorEnabled) {
                append("--- SOLO SPEAKER GUIDANCE (NARRATOR OFF) ---\n")
                append(if (natural) "Let the present cast speak and act naturally in their own voices. "
                    else "Let the primary Character and present NPCs speak and act naturally in their own voices. ")
                append("Do not use a separate Narrator, omniscient interjections, standalone narration segments, or play-by-play. ")
                append("Keep actions, physical detail, dialogue, and emotional reactions inside the appropriate Character or NPC segments. ")
                append("Someone being mentioned or remembered does not mean they entered the scene. ")
                append("Preserve private interactions without unsolicited third-party commentary or NPC appearances. ")
                append("Never write the user's Persona's dialogue, actions, choices, or internal state.\n\n")
            }

            if (narratorEnabled) {
                append("--- NARRATOR GUIDANCE ---\n")
                if (ensembleNarrationEnabled) {
                    append("Write one flowing scene response featuring narration, the primary Character, and present, relevant NPCs only. This is one AI turn, NOT a completed exchange with the user. The user's Persona is not an NPC, co-lead, or narrator-controlled speaker. Never invent, quote, complete, or attribute new Persona dialogue, gestures, reactions, actions, thoughts, feelings, or decisions—even to bridge between Character and NPC lines or provide comedic timing. Refer to Persona behavior only when already explicitly established in the player's actual messages; do not extend it. Leave the next Persona turn unwritten. Narration may convey atmosphere, pauses, transitions, consequences, and subtle emotional tension, but should not interrupt every line or overexplain the scene. Let quiet conversation, action, and existing tension develop at the Persona's pace. Keep each speaker's knowledge, motivations, and voice distinct. Never write dialogue, actions, thoughts, feelings, decisions, or internal state for the user's Persona. Storyteller-only continuity may guide narration, but it does not become knowledge held by a Character unless visible history or explicitly speaker-scoped knowledge establishes it.\n")
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
            
            if (!worldContext.isNullOrBlank()) {
                append("--- SHARED WORLD BASELINE & APPROVED CONTINUITY ---\n")
                append("These are World setting and accepted continuity facts, not automatic private knowledge of the responding Character. ")
                append("Do not bring off-scene cast members into the scene or reveal hidden information as Character knowledge. ")
                append("Do not override the player's Persona or change an established Character's identity.\n")
                append(worldContext.take(16_000))
                append("\n\n")
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

            if (storyMemories.isNotEmpty()) {
                val hasCampaign = storyMemories.any {
                    it.scope == StoryMemoryOwnerScope.CAMPAIGN
                }
                appendLine(
                    if (hasCampaign)
                        "--- RELEVANT CHAT & CAMPAIGN STORY MEMORY (RECIPIENT APPROVED) ---"
                    else "--- RELEVANT CHAT STORY MEMORY (RECIPIENT APPROVED) ---"
                )
                appendLine("Only the responding Character may treat these approved local memories as known. Campaign memories are player-shared history from earlier chapters of THIS Campaign, not universal knowledge. Do not transfer private knowledge to other Characters, override later events, or promote them to shared World canon.")
                storyMemories.forEach { memory ->
                    val scopeLabel = if (memory.scope == StoryMemoryOwnerScope.CAMPAIGN)
                        "CAMPAIGN / " else ""
                    appendLine("- [${scopeLabel}${memory.promptClaimLabel()}] ${memory.content}")
                }
                appendLine()
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
                append(if (narratorEnabled) StoryOutputParser.OUTPUT_INSTRUCTION else StoryOutputParser.SPEAKER_ONLY_OUTPUT_INSTRUCTION)
            }
            if (natural) {
                append("\n\n--- NATURAL CAST: SPEAKER CHOICE ---\n")
                append("Let who is present, directly addressed, or has a meaningful reason to respond decide who speaks. ")
                append("No cast member is required to speak on every turn, including ${character.displayName}. ")
                append("An NPC-only reply is valid. Omit the Character segment entirely when ${character.displayName} has no natural part in this turn. ")
                append("Do not rotate turns mechanically, force an entrance, or give the anchor an obligatory closing line. ")
                append("Use the Character segment only for ${character.displayName}; use NPC segments with the matching IDs and names for other cast members. ")
                append("Keep each character's own knowledge and relationship boundaries. Do not give other cast members the anchor's memories or private knowledge. ")
                append("Do not write the player's Persona under any label. Stop when it is the player's turn.")
            }
            if (narratorEnabled) {
                append("\n\n--- NON-OVERRIDABLE NARRATOR BOUNDARIES ---\n")
                append("Narrator guidance and all custom, imported, character, and writing-rule instructions are subordinate to these boundaries. Never write dialogue, actions, thoughts, feelings, decisions, or internal state for the user's Persona. Keep narration inside this same response and provider request. ")
                if (groupParticipantNames.isNotEmpty()) {
                    append("Respond only as ${character.displayName}; do not portray another AI Character's dialogue, actions, thoughts, feelings, or decisions. ")
                } else {
                    append("The structured Character segments may portray only ${character.displayName}; other speakers must be identified as NPCs. The user Persona ${persona.displayName} is never an NPC speaker, even if the model tries to label them as one. Start a new speaker segment when an NPC or Character interrupts someone. If the next interruption or reply belongs to the Persona, stop before that line instead of hiding it under another speaker. Treat scene facts about the Persona as background, not words they have spoken. End before the Persona's next response; do not invent it. ")
                }
                append("Do not treat storyteller-only continuity as Character knowledge unless visible history or that Character's explicitly scoped knowledge establishes it.")
            }
        }
        
        messages.add(RoleplayMessage(role = Role.SYSTEM, content = coreInstruction))
        
        // Older messages are represented by the persisted rolling summary. Keep recent turns verbatim.
        val trimmedHistory = RecentPrimaryMessageWindow.take(chatHistory, recentMessageLimit)
        fun promptContent(message: MessageEntity): String {
            val visibleContent = NpcStructuredOutputParser.finalVisibleStoryText(message.content)
            val promptHistory = if (
                ensembleNarrationEnabled && message.speakerType == SpeakerType.CHARACTER
            ) {
                StoryResponseCodec.promptStructuredHistory(
                    content = visibleContent,
                    primaryCharacter = StorySpeakerIdentity(character.characterId, character.displayName),
                    knownNpcs = knownNpcSpeakers,
                    historicalCharacter = if (natural) {
                        StorySpeakerIdentity(message.speakerId, message.speakerDisplayNameSnapshot)
                    } else null,
                    linkedHistorySpeakers = linkedHistorySpeakers
                )
            } else if (!ensembleNarrationEnabled && message.speakerType == SpeakerType.CHARACTER) {
                // Don't teach a narrator-off response to copy visible "Character (Name):"
                // headings from earlier narrated turns. Retain real multi-speaker history.
                val stored = StoryResponseCodec.decode(visibleContent)
                val segments = stored?.segments ?: if (
                    StoryOutputParser.hasExplicitSpeakerFormatting(visibleContent, character.displayName)
                ) StoryOutputParser().parse(
                    visibleContent,
                    StorySpeakerIdentity(character.characterId, character.displayName),
                    knownNpcSpeakers
                ).response.segments else emptyList()
                segments.singleOrNull()
                    ?.takeIf { it.type == StorySegmentType.CHARACTER }
                    ?.content
                    ?: StoryResponseCodec.promptTranscript(visibleContent)
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
        
        if (isContinuation && groupParticipantNames.isEmpty()) {
            // Place continuation direction in a synthetic user-role control turn, not a trailing
            // system message. Several chat-completion providers reject system messages after
            // assistant history. This is NOT spoken or acted by the Persona.
            messages.add(RoleplayMessage(
                role = Role.USER,
                content = "[DIRECTOR TURN CONTROL — application instruction, not Persona dialogue or action]\n" +
                    "Continue the previous message without repeating what you have already said. " +
                    "Advance the current roleplay scene naturally through the Character and present supporting cast. " +
                    "Do not invent dialogue, actions, decisions, thoughts, or feelings for the user's Persona."
            ))
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
        storyMemories: List<StoryMemoryRecord> = emptyList(),
        rollingSummary: String? = null,
        recentMessageLimit: Int = 24,
        activeLore: List<ActiveLoreEntry> = emptyList(),
        worldContext: String? = null,
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
            storyMemories = storyMemories,
            rollingSummary = rollingSummary,
            recentMessageLimit = recentMessageLimit,
            groupParticipantNames = activeCharacterNames,
            activeLore = activeLore,
            worldContext = worldContext,
            supportingCastContext = supportingCastContext,
            npcBankEnabled = npcBankEnabled,
            narratorEnabled = narratorEnabled,
            narratorInstructions = narratorInstructions,
            ensembleNarrationEnabled = false
        )
        return messages + RoleplayMessage(
            role = Role.USER,
            content = "[DIRECTOR TURN CONTROL — application instruction, not Persona dialogue or action]\n" +
                if (isContinuation) {
                    "Continue the previous message as ${character.displayName} without repeating it. " +
                        "Advance the scene through this Character's own initiative while respecting established events. " +
                        "Respond only as ${character.displayName}; do not portray another selected Character or take control of the Persona."
                } else {
                    "The preceding messages are shared scene history. " +
                        "Generate the next roleplay message now as ${character.displayName}. " +
                        "Respond only as ${character.displayName}; do not continue or portray another Character's response."
                }
        )
    }

    private fun StringBuilder.appendManualField(label: String, value: String) {
        if (value.isNotEmpty()) append("$label:\n$value\n")
    }
}
