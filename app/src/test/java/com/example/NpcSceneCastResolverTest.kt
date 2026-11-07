package com.example

import com.example.data.model.MessageEntity
import com.example.data.model.NpcDossier
import com.example.data.model.SpeakerType
import com.example.domain.npc.NpcSceneCastResolver
import com.example.domain.npc.NpcScenePresence
import com.example.domain.story.StoryResponse
import com.example.domain.story.StoryResponseCodec
import com.example.domain.story.StorySegment
import com.example.domain.story.StorySegmentType
import org.junit.Assert.assertEquals
import org.junit.Test

class NpcSceneCastResolverTest {
    private fun npc(mode: String = "AUTO", pinned: Boolean = false) = NpcDossier(
        npcId = "npc-1", chatId = "chat", displayName = "Mara",
        aliases = listOf("Archivist Voss"), role = "", appearance = "", personality = "",
        relationshipToPersona = "", personaRelationships = emptyMap(),
        characterKnowledge = emptyMap(), goals = "", secrets = "", authorNotes = "",
        keepInScene = pinned, isManualBase = true, createdAt = 1, updatedAt = 1, sceneMode = mode
    )

    private fun msg(text: String, idx: Long) = MessageEntity(
        chatId = "chat", speakerType = SpeakerType.PERSONA,
        speakerId = "persona", speakerDisplayNameSnapshot = "Persona",
        content = text, orderIndex = idx
    )

    private fun resolve(npc: NpcDossier, vararg texts: String) =
        NpcSceneCastResolver.resolve(listOf(npc), texts.mapIndexed { i, s -> msg(s, i.toLong()) }).single().presence

    @Test fun mentionOrPinDoesNotTeleportSomeone() {
        assertEquals(NpcScenePresence.UNKNOWN, resolve(npc(pinned = true), "I remember Mara's house."))
        assertEquals(NpcScenePresence.UNKNOWN, resolve(npc(), "Have you heard from Mara?"))
    }

    @Test fun clearEntrancesAndExitsUpdateAutomaticPresence() {
        assertEquals(NpcScenePresence.PRESENT, resolve(npc(), "Mara enters the room."))
        assertEquals(NpcScenePresence.PRESENT, resolve(npc(), "Archivist Voss is here."))
        assertEquals(NpcScenePresence.AWAY, resolve(npc(), "Mara arrives.", "Mara leaves."))
        assertEquals(NpcScenePresence.PRESENT, resolve(npc(), "Mara leaves.", "Mara joins us."))
    }

    @Test fun manualOverrideBeatsAnyInferredCue() {
        assertEquals(NpcScenePresence.AWAY, resolve(npc("AWAY", pinned = true), "Mara enters."))
        assertEquals(NpcScenePresence.PRESENT, resolve(npc("HERE"), "Mara leaves."))
    }

    @Test fun attributedNpcSegmentConfirmsPresenceWithoutExplicitEntrance() {
        val encoded = StoryResponseCodec.encode(StoryResponse(segments = listOf(
            StorySegment(StorySegmentType.CHARACTER, "The door is open.", "character", "Rowan"),
            StorySegment(StorySegmentType.NPC, "\"I know.\"", "npc-1", "Mara")
        )))
        assertEquals(NpcScenePresence.PRESENT, resolve(npc(), encoded))
    }

    @Test fun noHistoryMeansNotConfirmedPresent() {
        assertEquals(NpcScenePresence.UNKNOWN, NpcSceneCastResolver.resolve(listOf(npc()), emptyList()).single().presence)
    }
}
