package com.example

import com.example.domain.npc.NpcStructuredOutputParser
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NpcStructuredOutputParserTest {
    private val parser = NpcStructuredOutputParser(Moshi.Builder().build())

    @Test
    fun validTerminalBlockIsRemovedAndParsed() {
        val output = """
            She shuts the archive door behind her.
            <elsewhere_npc_updates>
            {"schema_version":1,"updates":[{"display_name":"Mara Voss","aliases":["Mara"],"role":"Archivist","goals":"Protect the ledger"}]}
            </elsewhere_npc_updates>
        """.trimIndent()

        val parsed = parser.parse(output)

        assertEquals("She shuts the archive door behind her.", parsed.storyText)
        assertEquals(1, parsed.updates.size)
        assertEquals("Mara Voss", parsed.updates.single().displayName)
        assertEquals("Protect the ledger", parsed.updates.single().goals)
        assertEquals(null, parsed.warning)
    }

    @Test
    fun malformedBlockIsStrippedWithoutProducingUpdates() {
        val output = "Visible story\n<elsewhere_npc_updates>{not-json}</elsewhere_npc_updates>"

        val parsed = parser.parse(output)

        assertEquals("Visible story", parsed.storyText)
        assertTrue(parsed.updates.isEmpty())
        assertNotNull(parsed.warning)
    }

    @Test
    fun unknownMutationFieldsRejectWholeBlock() {
        val output = "Visible\n<elsewhere_npc_updates>" +
            "{\"schema_version\":1,\"updates\":[{\"display_name\":\"Mara\",\"author_notes\":\"leak\"}]}" +
            "</elsewhere_npc_updates>"

        val parsed = parser.parse(output)

        assertEquals("Visible", parsed.storyText)
        assertTrue(parsed.updates.isEmpty())
        assertNotNull(parsed.warning)
    }

    @Test
    fun missingClosingMarkerDoesNotClutterVisibleStory() {
        val parsed = parser.parse("Visible\n<elsewhere_npc_updates>{\"schema_version\":1")

        assertEquals("Visible", parsed.storyText)
        assertTrue(parsed.updates.isEmpty())
        assertNotNull(parsed.warning)
    }

    @Test
    fun reportedMisspelledTerminalMarkerIsRemovedButNeverParsed() {
        val output = """
            Mara folds her ink-stained gloves over the missing ledger.
            <elsewisere_npc_updates>
        """.trimIndent()

        val parsed = parser.parse(output)

        assertEquals(
            "Mara folds her ink-stained gloves over the missing ledger.",
            NpcStructuredOutputParser.visibleStoryText(output)
        )
        assertEquals("Mara folds her ink-stained gloves over the missing ledger.", parsed.storyText)
        assertTrue(parsed.updates.isEmpty())
        assertNotNull(parsed.warning)
    }

    @Test
    fun misspelledMetadataBlockCannotProduceNpcUpdates() {
        val output = "Visible\n<elsewisere_npc_updates>" +
            "{\"schema_version\":1,\"updates\":[{\"display_name\":\"Mara\"}]}" +
            "</elsewisere_npc_updates>"

        val parsed = parser.parse(output)

        assertEquals("Visible", parsed.storyText)
        assertTrue(parsed.updates.isEmpty())
        assertNotNull(parsed.warning)
    }

    @Test
    fun truncatedTerminalMarkerIsHiddenFromStreamingAndFinalText() {
        val output = "Visible\n<elsewhere_npc_upd"

        assertEquals("Visible", NpcStructuredOutputParser.visibleStoryText(output))
        val parsed = parser.parse(output)
        assertEquals("Visible", parsed.storyText)
        assertTrue(parsed.updates.isEmpty())
        assertNotNull(parsed.warning)
    }

    @Test
    fun ordinaryLiteralTagInsideProseIsPreserved() {
        val inline = "The manual prints <elsewhere_npc_updates> as a literal example."
        val lineStart = "Visible\n<elsewhere_npc_updates> is painted on the plaque."
        val inlineEnvelope = "The manual prints <elsewhere_npc_updates>" +
            "{\"schema_version\":1,\"updates\":[{\"display_name\":\"Literal Mara\"}]}" +
            "</elsewhere_npc_updates> as its full example."
        val terminalInlineEnvelope = inlineEnvelope.removeSuffix(" as its full example.")

        assertEquals(inline, parser.parse(inline).storyText)
        assertEquals(lineStart, parser.parse(lineStart).storyText)
        assertEquals(inlineEnvelope, parser.parse(inlineEnvelope).storyText)
        assertTrue(parser.parse(inlineEnvelope).updates.isEmpty())
        assertEquals(terminalInlineEnvelope, parser.parse(terminalInlineEnvelope).storyText)
        assertTrue(parser.parse(terminalInlineEnvelope).updates.isEmpty())
        assertEquals(lineStart, NpcStructuredOutputParser.visibleStoryText(lineStart))
        assertEquals("Visible\n<", parser.parse("Visible\n<").storyText)
        assertEquals("Visible\n<", NpcStructuredOutputParser.finalVisibleStoryText("Visible\n<"))
    }

    @Test
    fun existingNpcCannotAutomaticallyChangeIdentityOrCoreProfile() {
        val output = "Visible\n<elsewhere_npc_updates>" +
            "{\"schema_version\":1,\"updates\":[{\"npc_id\":\"npc-1\",\"display_name\":\"Renamed\",\"appearance\":\"Changed\"}]}" +
            "</elsewhere_npc_updates>"

        val parsed = parser.parse(output)

        assertTrue(parsed.updates.isEmpty())
        assertNotNull(parsed.warning)
    }

    @Test
    fun existingNpcMayEvolveApprovedFields() {
        val output = "Visible\n<elsewhere_npc_updates>" +
            "{\"schema_version\":1,\"updates\":[{\"npc_id\":\"npc-1\",\"role\":\"Guide\",\"goals\":\"Open the gate\",\"secrets\":\"Knows the route\"}]}" +
            "</elsewhere_npc_updates>"

        val parsed = parser.parse(output)

        assertEquals("Guide", parsed.updates.single().role)
        assertEquals(null, parsed.warning)
    }
}
