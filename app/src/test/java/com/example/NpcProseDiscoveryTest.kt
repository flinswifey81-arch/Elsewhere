package com.example

import com.example.domain.npc.NpcProseDiscovery
import org.junit.Assert.assertEquals
import org.junit.Test

class NpcProseDiscoveryTest {
    @Test fun explicitlyAttributedNaturalDialogueFindsNamedSpeaker() {
        val visible = """
            Morax turned toward the broken gate.
            "The mountain road is closed," Mountain Shaper warned.
            Mountain Shaper said, "The repairs will take all morning."
            "I agree," replied Liora Fen.
        """.trimIndent()
        assertEquals(
            listOf("Mountain Shaper", "Liora Fen"),
            NpcProseDiscovery.namedSpeakers(visible, listOf("Morax", "Hazel"))
        )
    }

    @Test fun neverTreatPlayerOrMainCharacterOrPassingMentionAsNewNpc() {
        val visible = """
            Morax said, "The road is closed."
            Hazel said, "Then we find another route."
            Morax wondered whether Mountain Shaper could help.
            "I am here," the guard replied.
            "A tale," The Narrator said.
        """.trimIndent()
        assertEquals(
            emptyList<String>(),
            NpcProseDiscovery.namedSpeakers(visible, listOf("Morax", "Hazel"))
        )
    }
}
