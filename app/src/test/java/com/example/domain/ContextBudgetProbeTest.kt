package com.example.domain

import com.example.domain.compiler.ContextBudgetBucket
import com.example.domain.compiler.ContextBudgetProbe
import com.example.domain.provider.GenerationOptions
import com.example.domain.provider.Role
import com.example.domain.provider.RoleplayMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** No Room, OpenRouter call, or private story data. Classification only. */
class ContextBudgetProbeTest {
    @Test
    fun everyByteIsAttributedAndTheDebugOutputContainsOnlyNumbers() {
        val privateWorldFact = "SECRET_COMPASS_STASH"
        val messages = listOf(
            RoleplayMessage(Role.SYSTEM, """
                Keep Persona agency intact.

                --- SHARED WORLD BASELINE ---
                $privateWorldFact

                --- CHARACTER: Rowan ---
                Character background.

                --- USER PERSONA: Mara ---
                Persona background.

                --- ACTIVE WORLD LORE ---
                Local lore.

                --- RELEVANT SUPPORTING CAST ---
                Two NPCs.

                --- DURABLE ROLEPLAY MEMORY ---
                The promise.

                --- ROLLING CONVERSATION SUMMARY ---
                Earlier scenes.

                --- LENGTH INSTRUCTION ---
                Output guidance.
            """.trimIndent()),
            RoleplayMessage(Role.USER, "Mara enters the library.", sourceMessageId = "msg-3"),
            RoleplayMessage(Role.USER, "[DIRECTOR TURN CONTROL — no Persona speech]")
        )
        val sample = ContextBudgetProbe.measure(messages)
        val actualBytes = messages.sumOf { it.content.toByteArray(Charsets.UTF_8).size.toLong() }
        assertEquals(actualBytes, sample.inputUtf8Bytes)
        assertEquals(3, sample.messageCount)
        assertEquals((actualBytes + 3) / 4, sample.approximateInputTokens)
        assertNull(sample.explicitOutputTokenCeiling)
        listOf(
            ContextBudgetBucket.CORE, ContextBudgetBucket.WORLD, ContextBudgetBucket.CHARACTER,
            ContextBudgetBucket.PERSONA, ContextBudgetBucket.LOREBOOK, ContextBudgetBucket.SUPPORTING_CAST,
            ContextBudgetBucket.MEMORY, ContextBudgetBucket.SUMMARY,
            ContextBudgetBucket.TRANSCRIPT, ContextBudgetBucket.DIRECTOR
        ).forEach { bucket -> assertTrue("Missing $bucket", sample.utf8BytesByBucket.getValue(bucket) > 0) }
        val log = sample.countsOnlyDebugLine()
        assertTrue(log.contains("outputCeiling=unset"))
        assertFalse(log.contains(privateWorldFact))
        assertFalse(log.contains("Mara enters"))
        assertFalse(log.contains("msg-3"))
    }

    @Test
    fun costsRequireSuppliedPricingAndDoNotPretendToBeProviderBilling() {
        val sample = ContextBudgetProbe.measure(
            listOf(RoleplayMessage(Role.USER, "Text-only test", sourceMessageId = "test")),
            GenerationOptions(modelId = "mock/offline", maxTokens = 1200)
        )
        assertEquals(1200, sample.explicitOutputTokenCeiling)
        assertNull(sample.approximateTextInputUsd(null))
        assertNull(sample.approximateTextInputUsd(Double.NaN))
        assertNull(sample.approximateTextInputUsd(-1.0))
        assertEquals(sample.approximateInputTokens * 1.7 / 1_000_000.0,
            sample.approximateTextInputUsd(1.7)!!, 1e-12)
    }

    @Test
    fun scenarioSpecificSectionsAreNotSilentlyCountedAsChatHistory() {
        val system = RoleplayMessage(Role.SYSTEM, """
            Narrator agency guidance.
            --- SCENARIO PREMISE ---
            Our road.
            --- WORLD AND SETTING ---
            Border kingdom.
            --- CREATOR-DEFINED NPC: Rowan ---
            A scout.
            --- SESSION MEMORY ---
            A promise.
            --- EARLIER SESSION SUMMARY ---
            The last chapter.
            --- PLAYER PERSONA (controlled only by the player) ---
            Mara.
        """.trimIndent())
        val sample = ContextBudgetProbe.measure(listOf(system))
        for (bucket in listOf(
            ContextBudgetBucket.CORE, ContextBudgetBucket.SCENARIO, ContextBudgetBucket.WORLD,
            ContextBudgetBucket.SUPPORTING_CAST, ContextBudgetBucket.MEMORY, ContextBudgetBucket.SUMMARY,
            ContextBudgetBucket.PERSONA
        )) assertTrue("Missing $bucket", sample.utf8BytesByBucket.getValue(bucket) > 0)
        assertEquals(0L, sample.utf8BytesByBucket.getValue(ContextBudgetBucket.TRANSCRIPT))
    }

    @Test
    fun newApprovedStoryMemorySectionsAreMeasuredAsMemoryNotAuthoredCore() {
        val soloMemory = "ROWAN_SUBJECTIVE_BELIEF"
        val scenarioMemory = "SCENARIO_APPROVED_CONTINUITY"
        val samples = listOf(
            RoleplayMessage(Role.SYSTEM, """
                Never control the player.
                --- CHARACTER: Rowan ---
                User-authored canon remains protected.
                --- RELEVANT CHAT STORY MEMORY (RECIPIENT APPROVED) ---
                $soloMemory
                --- ROLLING CONVERSATION SUMMARY ---
                Synthetic recap.
            """.trimIndent()),
            RoleplayMessage(Role.SYSTEM, """
                Narrator guidance.
                --- APPROVED LOCAL STORY MEMORY (NARRATOR CONTINUITY, NOT UNIVERSAL NPC KNOWLEDGE) ---
                $scenarioMemory
                --- PLAYER PERSONA (controlled only by the player) ---
                Mara.
            """.trimIndent())
        )
        for ((i, sample) in samples.withIndex()) {
            val measured = ContextBudgetProbe.measure(listOf(sample))
            val marker = if (i == 0) soloMemory else scenarioMemory
            assertTrue(
                "Approved story memory bytes went to CORE instead of MEMORY",
                measured.utf8BytesByBucket.getValue(ContextBudgetBucket.MEMORY) >=
                    marker.toByteArray(Charsets.UTF_8).size
            )
            assertTrue(measured.utf8BytesByBucket.getValue(ContextBudgetBucket.CORE) > 0)
            assertTrue(measured.utf8BytesByBucket.getValue(ContextBudgetBucket.PERSONA) > 0 ||
                measured.utf8BytesByBucket.getValue(ContextBudgetBucket.CHARACTER) > 0)
            assertFalse(measured.countsOnlyDebugLine().contains(marker))
            assertEquals(
                sample.content.toByteArray(Charsets.UTF_8).size.toLong(),
                measured.inputUtf8Bytes
            )
        }
    }

    @Test
    fun unicodeIsCountedAsUtf8AndImagesAreNotClaimedAsPricedTokens() {
        val messages = listOf(RoleplayMessage(Role.USER, "한글 🌙", sourceMessageId = "unicode"))
        val sample = ContextBudgetProbe.measure(messages)
        assertEquals("한글 🌙".toByteArray(Charsets.UTF_8).size.toLong(), sample.inputUtf8Bytes)
        assertEquals(0, sample.attachedImageCount)
        assertTrue(sample.countsOnlyDebugLine().contains("images=0"))
    }
}
