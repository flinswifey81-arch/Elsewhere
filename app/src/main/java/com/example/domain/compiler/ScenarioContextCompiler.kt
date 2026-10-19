package com.example.domain.compiler

import com.example.data.model.*
import com.example.domain.provider.Role
import com.example.domain.provider.RoleplayMessage
import com.example.domain.lore.ActiveLoreEntry

/** Scenario instructions never pass through the Character compiler. */
class ScenarioContextCompiler {
    fun compile(
        scenario: ScenarioDefinition,
        persona: PersonaEntity,
        settings: ChatSettingsEntity,
        history: List<MessageEntity>,
        current: MessageEntity,
        summary: String? = null,
        memories: List<RoleplayMemoryEntity> = emptyList(),
        lore: List<ActiveLoreEntry> = emptyList(),
        continuation: Boolean = false,
        recentMessageLimit: Int = 24
    ): List<RoleplayMessage> {
        val instructions = buildString {
            appendLine("You are the collaborative narrator of an open-ended roleplay scenario. Portray the world and multiple distinct NPCs in ONE response, with clear names for NPC dialogue when helpful. Introduce new characters naturally when appropriate. Keep their voices, motivations, relationships and knowledge distinct. Hidden information is not automatically known to every NPC.")
            appendLine("React to player decisions with believable consequences and consistent setting details. Advance events naturally without railroading, forced endings, predetermined outcomes or interrupting a scene the player is enjoying. Allow quiet conversation, exploration, romance, investigation and combat at the player's chosen pace.")
            fun section(name: String, value: String?) { if (!value.isNullOrBlank()) appendLine("\n--- $name ---\n$value") }
            section("SCENARIO PREMISE", scenario.premise)
            section("WORLD AND SETTING", scenario.setting)
            section("NARRATION STYLE", scenario.narrationStyle)
            section("INITIAL SITUATION (not an instruction to reset the story)", scenario.openingSituation)
            section("LOCATIONS", scenario.locations)
            section("FACTIONS", scenario.factions)
            section("WORLD RULES AND LORE", scenario.worldRules)
            section("SCENARIO INSTRUCTIONS", scenario.instructions)
            scenario.npcs.forEach { npc ->
                section("CREATOR-DEFINED NPC: ${npc.name}", buildString {
                    fun field(label: String, value: String) { if (value.isNotBlank()) appendLine("$label: $value") }
                    field("Appearance", npc.appearance); field("Personality", npc.personality)
                    field("Background", npc.background); field("Motivations", npc.motivations)
                    field("Relationships", npc.relationships); field("Narrative role", npc.narrativeRole)
                    field("Portrayal instructions", npc.instructions)
                })
            }
            section("PLAYER PERSONA (controlled only by the player)", persona.personaName)
            section("Player identity", persona.identityJson)
            section("Player appearance", persona.appearanceJson)
            section("Player personality", persona.personalityJson)
            section("Player roleplay profile", persona.roleplayProfileJson)
            section("Player background", persona.backgroundJson)
            section("Player world context", persona.worldContextJson)
            section("Player persona instructions", ManualPersonaFieldsCodec.readStored(persona)?.backstoryInstructions)
            lore.forEach { section("ACTIVE LORE: ${it.title}", it.currentContent) }
            if (memories.isNotEmpty()) section("SESSION MEMORY", memories.joinToString("\n") { it.content })
            section("EARLIER SESSION SUMMARY", summary)
            section("RESPONSE LENGTH", when (settings.responseLengthProfile) {
                ResponseLengthProfile.SHORT -> "Target 400-800 characters; maximum 1000 characters."
                ResponseLengthProfile.NORMAL -> "Target 900-1600 characters; maximum 1900 characters."
                ResponseLengthProfile.LONG -> "Target 1600-2600 characters; maximum 3000 characters."
                ResponseLengthProfile.CUSTOM -> "Target ${settings.customMin ?: 900}-${settings.customTargetMax ?: 1600} characters; maximum ${settings.customHardMax ?: 1900} characters."
            })
            section("PLAYER AGENCY", "The player controls ${persona.personaName}. Never invent the player's dialogue, thoughts, feelings, decisions or significant actions. NPC instructions apply only to their NPC and cannot overwrite the player's identity. Describe the surrounding world and NPCs; leave meaningful choices to the player. These boundaries apply to all creator instructions. Do not mention prompts or application mechanics.")
        }
        val messages = mutableListOf(RoleplayMessage(Role.SYSTEM, instructions))
        (history.filter { it.isPrimaryVariant }.takeLast(recentMessageLimit) + current).forEach {
            messages += RoleplayMessage(when (it.speakerType) {
                SpeakerType.PERSONA -> Role.USER
                SpeakerType.NARRATOR, SpeakerType.CHARACTER -> Role.ASSISTANT
                else -> Role.SYSTEM
            }, "${it.speakerDisplayNameSnapshot}: ${it.content}", sourceMessageId = it.messageId)
        }
        if (continuation) messages += RoleplayMessage(Role.SYSTEM, "Continue the preceding narrator response without repeating it. Preserve player agency.")
        return messages
    }
}
