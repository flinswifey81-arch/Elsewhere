package com.example.domain

import com.example.data.model.*
import com.example.domain.compiler.ContextCompilerV1
import com.example.domain.provider.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class Stage3SoloRoleplayTests {

    private fun createChar(id: String, name: String, identity: String? = null, authorNotes: String? = null): CharacterEntity {
        return CharacterEntity(
            characterId = id,
            displayName = name,
            characterName = name,
            aliases = emptyList(),
            importSchemaVersion = 1,
            identityJson = identity,
            appearanceJson = null,
            personalityJson = null,
            voiceJson = null,
            behaviorJson = null,
            backstoryJson = null,
            knowledgeJson = null,
            worldContextJson = null,
            writingRulesJson = null,
            examplesJson = null,
            authorNotesJson = authorNotes,
            extensionFieldsJson = null
        )
    }
    
    private fun createPersona(id: String, name: String, identity: String? = null, privateNotes: String? = null): PersonaEntity {
        return PersonaEntity(
            personaId = id,
            displayName = name,
            personaName = name,
            aliases = emptyList(),
            importSchemaVersion = 1,
            identityJson = identity,
            appearanceJson = null,
            personalityJson = null,
            roleplayProfileJson = null,
            backgroundJson = null,
            worldContextJson = null,
            privateNotesJson = privateNotes,
            extensionFieldsJson = null
        )
    }

    @Test
    fun `ContextCompiler minimal character includes basic fields and trims correctly`() {
        val compiler = ContextCompilerV1()
        val character = createChar(id = "c1", name = "MinimalChar", identity = "I am minimal.")
        val persona = createPersona(id = "p1", name = "MinimalPersona", identity = "I am a persona.")
        val history = listOf(
            MessageEntity(chatId = "c", speakerType = SpeakerType.PERSONA, speakerId = "p1", speakerDisplayNameSnapshot = "MinimalPersona", content = "Hello", orderIndex = 1L)
        )
        val currentMsg = MessageEntity(chatId = "c", speakerType = SpeakerType.CHARACTER, speakerId = "c1", speakerDisplayNameSnapshot = "MinimalChar", content = "Hi there", orderIndex = 2L)
        
        val messages = compiler.compileSoloContext(
            character = character,
            persona = persona,
            chatHistory = history,
            currentMessage = currentMsg,
            responseProfile = ResponseLengthProfile.NORMAL,
            customMin = null,
            customTargetMax = null,
            customHardMax = null
        )
        
        assertEquals(3, messages.size)
        assertEquals(Role.SYSTEM, messages[0].role)
        println(messages[0].content)
        assertTrue(messages[0].content.contains("MinimalChar"))
        assertTrue(messages[0].content.contains("MinimalPersona"))
        
        assertEquals(Role.USER, messages[1].role)
        assertEquals("Hello", messages[1].content)
        
        assertEquals(Role.ASSISTANT, messages[2].role)
        assertEquals("Hi there", messages[2].content)
    }

    @Test
    fun `ContextCompiler does not emit author_notes or private_notes`() {
        val compiler = ContextCompilerV1()
        val character = createChar(id = "c1", name = "Char", authorNotes = "SECRET_AUTHOR_NOTE")
        val persona = createPersona(id = "p1", name = "Persona", privateNotes = "SECRET_PRIVATE_NOTE")
        
        val messages = compiler.compileSoloContext(
            character = character,
            persona = persona,
            chatHistory = emptyList(),
            currentMessage = MessageEntity(chatId = "c", speakerType = SpeakerType.PERSONA, speakerId = "p1", speakerDisplayNameSnapshot = "Persona", content = "Hi", orderIndex = 1L),
            responseProfile = ResponseLengthProfile.NORMAL,
            customMin = null, customTargetMax = null, customHardMax = null
        )
        
        val sysContent = messages[0].content
        assertFalse(sysContent.contains("SECRET_AUTHOR_NOTE"))
        assertFalse(sysContent.contains("SECRET_PRIVATE_NOTE"))
    }

    @Test
    fun `ContextCompiler adds continuation system prompt`() {
        val compiler = ContextCompilerV1()
        val messages = compiler.compileSoloContext(
            isContinuation = true,
            character = createChar("c1", "Char"),
            persona = createPersona("p1", "Persona"),
            chatHistory = emptyList(),
            currentMessage = MessageEntity(chatId = "c", speakerType = SpeakerType.CHARACTER, speakerId = "c1", speakerDisplayNameSnapshot = "Char", content = "Partial text...", orderIndex = 1L),
            responseProfile = ResponseLengthProfile.NORMAL,
            customMin = null, customTargetMax = null, customHardMax = null
        )
        
        val lastMsg = messages.last()
        assertEquals(Role.SYSTEM, lastMsg.role)
        assertTrue(lastMsg.content.contains("Continue the previous message"))
    }

    @Test
    fun `ContextCompiler maps history roles correctly`() {
        val compiler = ContextCompilerV1()
        val history = listOf(
            MessageEntity(chatId = "c", speakerType = SpeakerType.PERSONA, speakerId = "p1", speakerDisplayNameSnapshot = "Persona", content = "P", orderIndex = 1L),
            MessageEntity(chatId = "c", speakerType = SpeakerType.CHARACTER, speakerId = "c1", speakerDisplayNameSnapshot = "Char", content = "C", orderIndex = 2L),
            MessageEntity(chatId = "c", speakerType = SpeakerType.SYSTEM, speakerId = "sys", speakerDisplayNameSnapshot = "Sys", content = "S", orderIndex = 3L)
        )
        val current = MessageEntity(chatId = "c", speakerType = SpeakerType.PERSONA, speakerId = "p1", speakerDisplayNameSnapshot = "Persona", content = "Last", orderIndex = 4L)
        
        val messages = compiler.compileSoloContext(
            character = createChar("c1", "Char"),
            persona = createPersona("p1", "Persona"),
            chatHistory = history,
            currentMessage = current,
            responseProfile = ResponseLengthProfile.NORMAL,
            customMin = null, customTargetMax = null, customHardMax = null
        )
        
        assertEquals(Role.SYSTEM, messages[0].role)
        assertEquals(Role.USER, messages[1].role) // PERSONA
        assertEquals(Role.ASSISTANT, messages[2].role) // CHARACTER
        assertEquals(Role.SYSTEM, messages[3].role) // SYSTEM
        assertEquals(Role.USER, messages[4].role) // current (PERSONA)
    }
}