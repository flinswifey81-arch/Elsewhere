package com.example

import com.example.data.model.NpcDossier
import com.example.domain.npc.NpcSpeakerPortraitResolver
import org.junit.Assert.assertEquals
import org.junit.Test

class NpcSpeakerPortraitResolverTest {
    private fun npc(id: String, name: String, aliases: List<String> = emptyList()) = NpcDossier(
        npcId = id, chatId = "chat", displayName = name, aliases = aliases,
        role = "", appearance = "", personality = "", relationshipToPersona = "",
        personaRelationships = emptyMap(), characterKnowledge = emptyMap(),
        goals = "", secrets = "", authorNotes = "", keepInScene = false,
        isManualBase = true, createdAt = 0, updatedAt = 0
    )

    @Test fun oldSpeakerSegmentsCanUseNewBankIdentity() {
        val cast = listOf(npc("liora", "Liora Fen", listOf("Red")))
        assertEquals("liora", NpcSpeakerPortraitResolver.resolveId("story-npc:old", "Liora Fen", cast))
        assertEquals("liora", NpcSpeakerPortraitResolver.resolveId(null, "red", cast))
    }

    @Test fun nameConflictsAndSharedAliasesStayUnresolved() {
        val cast = listOf(npc("mara", "Mara", listOf("Guide")), npc("rhea", "Rhea", listOf("Guide")))
        assertEquals(null, NpcSpeakerPortraitResolver.resolveId("mara", "Guide", cast))
        assertEquals("rhea", NpcSpeakerPortraitResolver.resolveId("mara", "Rhea", cast))
        assertEquals(null, NpcSpeakerPortraitResolver.resolveId("mara", "Unknown", cast))
        assertEquals("mara", NpcSpeakerPortraitResolver.resolveId("mara", null, cast))
    }
}
