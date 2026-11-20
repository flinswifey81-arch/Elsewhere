package com.example.domain

import com.example.data.model.ChatSettingsEntity
import com.example.data.model.MessageEntity
import com.example.data.model.PersonaEntity
import com.example.data.model.ScenarioDefinition
import com.example.data.model.SpeakerType
import com.example.domain.compiler.PromptOptionalContextChoices
import com.example.domain.compiler.PromptOptionalContextFitter
import com.example.domain.compiler.PromptPreflight
import com.example.domain.compiler.ScenarioContextCompiler
import com.example.domain.provider.Role
import com.example.domain.provider.RoleplayMessage
import org.junit.Assert.*
import org.junit.Test

class PromptOptionalContextFitterTest {
    private val all = PromptOptionalContextChoices(
        includeLegacyMemories = true,
        includeStoryMemories = true,
        includeSummary = true
    )
    private val canon = "AUTHORED_CHARACTER_AND_PERSONA_CANON_" + "C".repeat(7_000)
    private val current = RoleplayMessage(Role.USER,
        "CURRENT_PLAYER_TURN_" + "P".repeat(2_000), sourceMessageId = "current")

    private fun fakeCompile(options: PromptOptionalContextChoices): List<RoleplayMessage> =
        listOf(
            RoleplayMessage(
                Role.SYSTEM, canon +
                    (if (options.includeLegacyMemories) "LEGACY_MEMORY_" + "L".repeat(2_000) else "") +
                    (if (options.includeStoryMemories) "APPROVED_STORY_" + "S".repeat(2_500) else "") +
                    (if (options.includeSummary) "GENERATED_SUMMARY_" + "M".repeat(1_000) else "")
            ),
            current
        )

    @Test fun rebuildDropsLeastTrustedOptionalContextWhileProtectingAuthoredCanon() {
        val full = fakeCompile(all)
        val fit = PromptOptionalContextFitter.fit(all, 8_192, compile = ::fakeCompile)
        assertTrue(fit.decision.allowed)
        assertTrue(fit.legacyMemoriesOmitted)
        assertFalse(fit.storyMemoriesOmitted)
        assertTrue(fit.summaryOmitted)
        assertEquals(0, fit.historyTurnsRemoved)
        assertTrue(fit.messages.first().content.contains(canon))
        assertTrue(fit.messages.first().content.contains("APPROVED_STORY_"))
        assertFalse(fit.messages.first().content.contains("LEGACY_MEMORY_"))
        assertFalse(fit.messages.first().content.contains("GENERATED_SUMMARY_"))
        assertEquals(current, fit.messages.last())
        assertEquals(canon, full.first().content.substring(0, canon.length))
        assertEquals(6_144, fit.decision.inputCeilingTokens)
        val notice = requireNotNull(fit.countsOnlyNotice())
        assertTrue(notice.contains("legacy generated memory"))
        assertTrue(notice.contains("generated conversation summary"))
        assertFalse(notice.contains("LEGACY_MEMORY_"))
        assertFalse(notice.contains("CURRENT_PLAYER_TURN_"))
    }

    @Test fun noOmissionsOrWarningWhenPromptAlreadyFits() {
        val short = listOf(
            RoleplayMessage(Role.SYSTEM, "Authoritative character definition"),
            RoleplayMessage(Role.USER, "Stay near the camp", sourceMessageId = "turn")
        )
        val fit = PromptOptionalContextFitter.fit(all, 8_192) { short }
        assertTrue(fit.decision.allowed)
        assertEquals(short, fit.messages)
        assertFalse(fit.legacyMemoriesOmitted)
        assertFalse(fit.storyMemoriesOmitted)
        assertFalse(fit.summaryOmitted)
        assertNull(fit.countsOnlyNotice())
    }

    @Test fun oldUnprotectedTurnsTrimBeforeOptionalMaterialAndRecentTurnsSurvive() {
        val old = (1..8).map {
            RoleplayMessage(Role.ASSISTANT, "OLD_TRANSCRIPT_" + "t".repeat(2_200),
                sourceMessageId = "old-$it")
        }
        val allContent = PromptOptionalContextChoices(false, false, true)
        val director = RoleplayMessage(Role.USER, "[DIRECTOR TURN CONTROL] Continue.")
        val fit = PromptOptionalContextFitter.fit(
            available = allContent,
            modelContextWindowTokens = 8_192,
            protectedRecentTurns = 2
        ) { flags ->
            listOf(RoleplayMessage(
                Role.SYSTEM, "WORLD_CANON_" + "w".repeat(1_000) +
                    (if (flags.includeSummary) "GENERATED_SUMMARY_" else "")
            )) + old + current + director
        }
        assertTrue(fit.decision.allowed)
        assertTrue(fit.historyTurnsRemoved > 0)
        assertFalse(fit.summaryOmitted)
        assertTrue(fit.messages.contains(current))
        assertTrue(fit.messages.contains(director))
        assertTrue(fit.messages.contains(old.last()))
        assertFalse(fit.messages.contains(old.first()))
        assertTrue(fit.messages.first().content.contains("WORLD_CANON_"))
    }

    @Test fun failsBeforeProviderWhenProtectedCoreIsStillTooLarge() {
        val giant = RoleplayMessage(Role.SYSTEM, "IMPORTANT_PRIVATE_AUTHOR_CANON_" + "Q".repeat(30_000))
        val fit = PromptOptionalContextFitter.fit(all, 8_192) { choices ->
            listOf(giant.copy(content = giant.content +
                (if (choices.includeSummary) "OPTIONAL_SUMMARY_" else "") +
                (if (choices.includeLegacyMemories) "OPTIONAL_LEGACY_" else "") +
                (if (choices.includeStoryMemories) "OPTIONAL_STORY_" else "")
            ), current)
        }
        assertFalse(fit.decision.allowed)
        assertTrue(fit.legacyMemoriesOmitted)
        assertTrue(fit.storyMemoriesOmitted)
        assertTrue(fit.summaryOmitted)
        assertTrue(fit.messages.first().content.startsWith("IMPORTANT_PRIVATE_AUTHOR_CANON_"))
        assertTrue(fit.messages.contains(current))
        assertTrue(fit.decision.estimatedInputTokens > fit.decision.inputCeilingTokens)
    }

    @Test fun realScenarioCompilerRebuildKeepsWorldPersonaAndPlayerAgency() {
        val persona = PersonaEntity(
            personaId = "mara", displayName = "Mara", personaName = "Mara",
            aliases = emptyList(), importSchemaVersion = 1,
            identityJson = "PERSONA_APPROVED_IDENTITY",
            appearanceJson = null, personalityJson = null, roleplayProfileJson = null,
            backgroundJson = null, worldContextJson = null,
            privateNotesJson = null, extensionFieldsJson = null
        )
        val scenario = ScenarioDefinition(
            title = "Bridge", premise = "SAVED_SCENARIO_PREMISE",
            worldRules = "MAGIC_RULES_CANNOT_BE_TRIMMED"
        )
        val currentTurn = MessageEntity(
            chatId = "scenario", messageId = "current-scene",
            speakerType = SpeakerType.PERSONA, speakerId = "mara",
            speakerDisplayNameSnapshot = "Mara",
            content = "I choose the bridge.", orderIndex = 10L
        )
        val summary = "EARLIER_GENERATED_SUMMARY_" + "s".repeat(17_000)
        val compiler = ScenarioContextCompiler()
        val available = PromptOptionalContextChoices(false, false, true)
        val compiled = { choices: PromptOptionalContextChoices ->
            compiler.compile(
                scenario, persona, ChatSettingsEntity("scenario"),
                history = emptyList(), current = currentTurn,
                worldContext = "WORLD_CANON_MUST_REMAIN",
                summary = if (choices.includeSummary) summary else null
            )
        }
        val fit = PromptOptionalContextFitter.fit(available, 8_192, compile = compiled)
        assertTrue(fit.decision.allowed)
        assertTrue(fit.summaryOmitted)
        assertEquals(0, fit.historyTurnsRemoved)
        val first = fit.messages.first().content
        for (evidence in listOf(
            "SAVED_SCENARIO_PREMISE", "WORLD_CANON_MUST_REMAIN",
            "MAGIC_RULES_CANNOT_BE_TRIMMED", "PERSONA_APPROVED_IDENTITY",
            "Never invent the player's dialogue"
        )) assertTrue("Missing protected scenario element: $evidence", first.contains(evidence))
        assertFalse(first.contains("EARLIER_GENERATED_SUMMARY_"))
        assertTrue(fit.messages.any { it.sourceMessageId == "current-scene" })
        assertTrue(PromptPreflight.inspect(fit.messages, 8_192).allowed)
    }
}
