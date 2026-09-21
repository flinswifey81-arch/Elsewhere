package com.example.domain.compiler

import com.example.data.model.CharacterEntity
import com.example.data.model.ManualCharacterFields
import com.example.data.model.ManualCharacterFieldsCodec
import com.example.data.model.ManualPersonaFields
import com.example.data.model.ManualPersonaFieldsCodec
import com.example.data.model.MessageEntity
import com.example.data.model.PersonaEntity
import com.example.data.model.ResponseLengthProfile
import com.example.data.model.SpeakerType
import com.example.domain.provider.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityMacroResolverTest {

    @Test
    fun exactMacrosResolveEveryOccurrenceAndOtherBraceTextIsPreserved() {
        val source = "{char} greets {user}. {char} trusts {user}. {character} {User} {other} plain { braces }."

        val resolved = IdentityMacroResolver.resolve(source, "Sylus", "Shai")

        assertEquals(
            "Sylus greets Shai. Sylus trusts Shai. {character} {User} {other} plain { braces }.",
            resolved
        )
        assertEquals("{user}", IdentityMacroResolver.resolve("{char}", "{user}", "Shai"))
    }

    @Test
    fun compiledCharacterAndPersonaFieldsResolveWithoutMutatingStoredSources() {
        val importedCharacterSource = "Do not let {char} narrate {user}."
        val manualCharacterSource = "Keep {char} focused on {user}."
        val importedPersonaSource = "{user} has known {char} for years."
        val manualPersonaSource = "Write {user} as distinct from {char}."
        val characterExtensions = ManualCharacterFieldsCodec.write(
            existingExtensionFieldsJson = null,
            fields = ManualCharacterFields(
                name = "{char}",
                systemInstructions = manualCharacterSource,
                conversationalGoals = "Ask {user} what {user} wants."
            )
        )
        val personaExtensions = ManualPersonaFieldsCodec.write(
            existingExtensionFieldsJson = null,
            fields = ManualPersonaFields(
                name = "{user}",
                backstoryInstructions = manualPersonaSource
            )
        )
        val character = character(
            displayName = "Sylus",
            identityJson = importedCharacterSource,
            extensionFieldsJson = characterExtensions
        )
        val persona = persona(
            displayName = "Shai",
            backgroundJson = importedPersonaSource,
            extensionFieldsJson = personaExtensions
        )

        val systemPrompt = compile(character, persona).first { it.role == Role.SYSTEM }.content

        assertTrue(systemPrompt.contains("Do not let Sylus narrate Shai."))
        assertTrue(systemPrompt.contains("Keep Sylus focused on Shai."))
        assertTrue(systemPrompt.contains("Ask Shai what Shai wants."))
        assertTrue(systemPrompt.contains("Shai has known Sylus for years."))
        assertTrue(systemPrompt.contains("Write Shai as distinct from Sylus."))
        assertFalse(systemPrompt.contains("{char}"))
        assertFalse(systemPrompt.contains("{user}"))
        assertEquals(importedCharacterSource, character.identityJson)
        assertEquals(characterExtensions, character.extensionFieldsJson)
        assertEquals(importedPersonaSource, persona.backgroundJson)
        assertEquals(personaExtensions, persona.extensionFieldsJson)
    }

    @Test
    fun activeDisplayNamesAreResolvedFreshForEveryCompilation() {
        val source = "{char} protects {user}; {user} challenges {char}."
        val originalCharacter = character(displayName = "Sylus", identityJson = source)
        val originalPersona = persona(displayName = "Shai")

        val firstPrompt = compile(originalCharacter, originalPersona).first().content
        val renamedPrompt = compile(
            originalCharacter.copy(displayName = "Zayne"),
            originalPersona.copy(displayName = "Riven")
        ).first().content

        assertTrue(firstPrompt.contains("Sylus protects Shai; Shai challenges Sylus."))
        assertTrue(renamedPrompt.contains("Zayne protects Riven; Riven challenges Zayne."))
        assertEquals(source, originalCharacter.identityJson)
    }

    @Test
    fun conversationContentResolvesAtCompilationWithoutChangingStoredMessages() {
        val historySource = "{char} waits for {user}."
        val currentSource = "Tell {char} that {user} has arrived."
        val history = MessageEntity(
            messageId = "assistant-1",
            chatId = "chat-1",
            speakerType = SpeakerType.CHARACTER,
            speakerId = "character-1",
            speakerDisplayNameSnapshot = "Old Character Label",
            content = historySource,
            orderIndex = 1L
        )
        val current = currentMessage(content = currentSource)

        val compiled = compile(character("Sylus"), persona("Shai"), listOf(history), current)

        assertEquals("Sylus waits for Shai.", compiled.first { it.role == Role.ASSISTANT }.content)
        assertEquals("Tell Sylus that Shai has arrived.", compiled.last { it.role == Role.USER }.content)
        assertEquals(historySource, history.content)
        assertEquals(currentSource, current.content)
    }

    private fun compile(
        character: CharacterEntity,
        persona: PersonaEntity,
        history: List<MessageEntity> = emptyList(),
        current: MessageEntity = currentMessage()
    ) = ContextCompilerV1().compileSoloContext(
        character = character,
        persona = persona,
        chatHistory = history,
        currentMessage = current,
        responseProfile = ResponseLengthProfile.NORMAL,
        customMin = null,
        customTargetMax = null,
        customHardMax = null
    )

    private fun currentMessage(content: String = "Hello") = MessageEntity(
        messageId = "user-1",
        chatId = "chat-1",
        speakerType = SpeakerType.PERSONA,
        speakerId = "persona-1",
        speakerDisplayNameSnapshot = "Old Persona Label",
        content = content,
        orderIndex = 2L
    )

    private fun character(
        displayName: String,
        identityJson: String? = null,
        extensionFieldsJson: String? = null
    ) = CharacterEntity(
        characterId = "character-1",
        displayName = displayName,
        characterName = displayName,
        aliases = emptyList(),
        importSchemaVersion = 1,
        identityJson = identityJson,
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
        extensionFieldsJson = extensionFieldsJson
    )

    private fun persona(
        displayName: String,
        backgroundJson: String? = null,
        extensionFieldsJson: String? = null
    ) = PersonaEntity(
        personaId = "persona-1",
        displayName = displayName,
        personaName = displayName,
        aliases = emptyList(),
        importSchemaVersion = 1,
        identityJson = null,
        appearanceJson = null,
        personalityJson = null,
        roleplayProfileJson = null,
        backgroundJson = backgroundJson,
        worldContextJson = null,
        privateNotesJson = null,
        extensionFieldsJson = extensionFieldsJson
    )
}
