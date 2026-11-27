package com.example.phonelab.systems

import org.junit.Assert.*
import org.junit.Test

class PhonePolicyTest {
    private val a = PhoneScope("world", "campaign-a", "lilly")
    private val b = PhoneScope("world", "campaign-b", "lilly")
    private val lead = ThreadKey(a, "bakugo")
    private val npc = ThreadKey(a, "mika")

    @Test fun contactAndPersonaOnlyRememberApprovedText() {
        val saved = PhoneCarry("m", lead, Destination.STORY_MEMORY, "Meet at eight", listOf("line-1"), 3)
        val dispatch = PhoneCarry("n", npc, Destination.STORY_MEMORY, "Drill at nine", listOf("line-2"), 2)
        assertEquals("Meet at eight", saved.storyText(a, "bakugo", 4))
        assertEquals("Meet at eight", saved.storyText(a, "lilly", 3))
        assertNull(saved.storyText(a, "mika", 4))
        assertNull(saved.storyText(a, "bakugo", 2))
        assertNull(saved.storyText(b, "bakugo", 8))
        assertNull(saved.storyText(a.copy(personaId = "other"), "bakugo", 8))
        assertEquals("Drill at nine", dispatch.storyText(a, "mika", 4))
        assertNull(dispatch.storyText(a, "bakugo", 4))
    }

    @Test fun loreScopeAndCanonReviewStaySeparate() {
        val approved = PhoneCarry("l", lead, Destination.PLAYTHROUGH_LORE, "Rooftop garden", listOf("line-1"), 3)
        assertEquals("Rooftop garden", approved.loreFor(a, 3))
        assertNull(approved.loreFor(a, 2))
        assertNull(approved.loreFor(b, 8))
        val draft = approved.copy(destination = Destination.WORLD_CANON_DRAFT)
        assertNull(draft.loreFor(a, 4))
        assertNull(draft.storyText(a, "bakugo", 4))
    }

    @Test fun textRequestKeepsWholeTurnsAndNeverReassignsThePersona() {
        val long = "x".repeat(4000)
        val thread = listOf(
            PhoneMessage("old", 1, Speaker.PERSONA, "old", 1),
            PhoneMessage("long", 1, Speaker.CONTACT, long, 2),
            PhoneMessage("u", 2, Speaker.PERSONA, long, 3),
            PhoneMessage("c", 2, Speaker.CONTACT, long, 4),
            PhoneMessage("new", 2, Speaker.PERSONA, long, 5)
        )
        val request = PhoneTextRequest.compile("Bakugo", "new", thread)
        assertEquals(listOf(PhoneTextRequest.Role.USER, PhoneTextRequest.Role.ASSISTANT, PhoneTextRequest.Role.USER),
            request.history.map { it.role })
        assertEquals(12000, request.history.sumOf { it.text.length })
        assertThrows(IllegalArgumentException::class.java) { PhoneTextRequest.compile("Bakugo", "old", thread) }
        assertThrows(IllegalArgumentException::class.java) { PhoneTextRequest.compile("Bakugo", "c", thread.dropLast(1)) }
    }
}
