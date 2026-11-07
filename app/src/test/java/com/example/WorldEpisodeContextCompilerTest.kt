package com.example

import com.example.data.model.CharacterEntity
import com.example.data.model.MessageEntity
import com.example.data.model.PersonaEntity
import com.example.data.model.ResponseLengthProfile
import com.example.data.model.SpeakerType
import com.example.domain.compiler.ContextCompilerV1
import com.example.domain.provider.Role
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class WorldEpisodeContextCompilerTest {
    private val character = CharacterEntity(
        characterId = "character-varka",
        displayName = "Varka", characterName = "Varka", aliases = emptyList(),
        importSchemaVersion = 1, identityJson = null, appearanceJson = null,
        personalityJson = null, voiceJson = null, behaviorJson = null, backstoryJson = null,
        knowledgeJson = null, worldContextJson = null, writingRulesJson = null,
        examplesJson = null, authorNotesJson = null, extensionFieldsJson = null
    )
    private val persona = PersonaEntity(
        personaId = "persona-test", displayName = "Player", personaName = "Player",
        aliases = emptyList(), importSchemaVersion = 1, identityJson = null,
        appearanceJson = null, personalityJson = null, roleplayProfileJson = null,
        backgroundJson = null, worldContextJson = null, privateNotesJson = null,
        extensionFieldsJson = null
    )
    private val greeting = MessageEntity(
        messageId = "greeting", chatId = "episode",
        speakerType = SpeakerType.CHARACTER, speakerId = character.characterId,
        speakerDisplayNameSnapshot = character.displayName,
        content = "The night watch is ready.", orderIndex = 1L
    )
    private val sharedWorld = "Shared World: Law & Disorder Mondstadt\n" +
        "Setting: The city has a night watch.\n" +
        "Accepted prior World continuity: The archway was repaired."

    @Test
    fun soloEpisodeReceivesApprovedWorldContextWithoutPersonaControl() {
        val prompt = ContextCompilerV1().compileSoloContext(
            isContinuation = true,
            character = character, persona = persona,
            chatHistory = emptyList(), currentMessage = greeting,
            responseProfile = ResponseLengthProfile.NORMAL,
            customMin = null, customTargetMax = null, customHardMax = null,
            worldContext = sharedWorld
        )
        assertEquals(Role.SYSTEM, prompt.first().role)
        val system = prompt.first().content
        assertTrue(system.contains("--- SHARED WORLD BASELINE & APPROVED CONTINUITY ---"))
        assertTrue(system.contains("The archway was repaired."))
        assertTrue(system.contains("not automatic private knowledge"))
        assertTrue(system.contains("Do not bring off-scene cast members into the scene"))
        assertTrue(system.contains("Never write dialogue for the user/Persona"))
    }

    @Test
    fun groupEpisodeGetsSameWorldBaselineButKeepsCharacterIsolation() {
        val prompt = ContextCompilerV1().compileGroupCharacterContext(
            character = character, persona = persona,
            activeCharacterNames = listOf("Varka", "Kaeya"),
            chatHistory = emptyList(), currentMessage = greeting,
            responseProfile = ResponseLengthProfile.NORMAL,
            customMin = null, customTargetMax = null, customHardMax = null,
            worldContext = sharedWorld
        )
        assertTrue(prompt.first().content.contains("The archway was repaired."))
        assertTrue(prompt.first().content.contains("Respond only as Varka"))
        assertTrue(prompt.first().content.contains("Never write dialogue, actions, thoughts, feelings, or decisions for any other AI Character"))
        assertEquals(Role.USER, prompt.last().role)
    }

    @Test
    fun anUnlinkedChatHasNoInventedWorldHistory() {
        val prompt = ContextCompilerV1().compileSoloContext(
            character = character, persona = persona,
            chatHistory = emptyList(), currentMessage = greeting,
            responseProfile = ResponseLengthProfile.NORMAL,
            customMin = null, customTargetMax = null, customHardMax = null
        )
        assertFalse(prompt.first().content.contains("--- SHARED WORLD BASELINE & APPROVED CONTINUITY ---"))
        assertFalse(prompt.first().content.contains("The archway was repaired."))
    }
}
