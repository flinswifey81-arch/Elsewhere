package com.example

import com.example.data.model.CharacterEntity
import com.example.data.model.MessageEntity
import com.example.data.model.PersonaEntity
import com.example.data.model.ResponseLengthProfile
import com.example.data.model.SpeakerType
import com.example.domain.compiler.ContextCompilerV1
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnsembleCoLeadContextTest {
    private val character = CharacterEntity(
        characterId = "main",
        displayName = "Main",
        characterName = "Main",
        aliases = emptyList(),
        importSchemaVersion = 1,
        identityJson = null,
        appearanceJson = null,
        personalityJson = null,
        voiceJson = null,
        behaviorJson = null,
        backstoryJson = null,
        knowledgeJson = null,
        worldContextJson = null,
        writingRulesJson = null,
        examplesJson = null,
        authorNotesJson = null,
        extensionFieldsJson = null
    )
    private val persona = PersonaEntity(
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
    private val message = MessageEntity(
        chatId = "solo",
        speakerType = SpeakerType.PERSONA,
        speakerId = "persona",
        speakerDisplayNameSnapshot = "Persona",
        content = "Both of you, come here.",
        orderIndex = 1L
    )

    private fun systemPrompt(
        narratorEnabled: Boolean,
        coLeads: List<String> = emptyList(),
        groupNames: List<String> = emptyList()
    ): String = ContextCompilerV1().compileSoloContext(
        character = character,
        persona = persona,
        chatHistory = emptyList(),
        currentMessage = message,
        responseProfile = ResponseLengthProfile.NORMAL,
        customMin = null,
        customTargetMax = null,
        customHardMax = null,
        narratorEnabled = narratorEnabled,
        groupParticipantNames = groupNames,
        coLeadNpcNames = coLeads
    ).first().content

    @Test
    fun ordinarySoloEnsembleKeepsPrimaryFocus() {
        val prompt = systemPrompt(narratorEnabled = true)
        assertTrue(prompt.contains("primary Character remains the emotional and narrative center"))
        assertFalse(prompt.contains("have equal emotional and narrative importance"))
    }

    @Test
    fun soloEnsembleWithPinnedNpcGrantsSharedNarrativeImportance() {
        val prompt = systemPrompt(narratorEnabled = true, coLeads = listOf("Mara", "Mara", " "))
        assertTrue(prompt.contains("NPC co-leads (Mara) have equal emotional and narrative importance"))
        assertTrue(prompt.contains("Balance attention across scenes and turns naturally"))
        assertTrue(prompt.contains("not by demanding identical word counts"))
        assertFalse(prompt.contains("primary Character remains the emotional and narrative center"))
        assertTrue(prompt.contains("Never write dialogue, actions, thoughts, feelings, decisions, or internal state for the user's Persona"))
    }

    @Test
    fun narratorOffAndGroupIgnoreCoLeadDesignation() {
        val inactive = systemPrompt(narratorEnabled = false, coLeads = listOf("Mara"))
        assertFalse(inactive.contains("have equal emotional and narrative importance"))
        val group = systemPrompt(
            narratorEnabled = true,
            coLeads = listOf("Mara"),
            groupNames = listOf("Main", "Other")
        )
        assertFalse(group.contains("have equal emotional and narrative importance"))
        assertTrue(group.contains("Never write dialogue, actions, thoughts, feelings, or decisions for any other AI Character"))
    }
}
