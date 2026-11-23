package com.example.domain

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.*
import com.example.domain.compiler.*
import com.example.domain.memory.*
import com.example.domain.repository.AppDataMaintenanceCoordinator
import com.example.domain.repository.StoryMemoryRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Offline, real Room / production prompt assembly acceptance; no ModelProvider. */
@RunWith(RobolectricTestRunner::class)
class MemoryV2CrowdedSceneAcceptanceTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: StoryMemoryRepository
    private val chatId = "crowded-story"
    private val narrator = StoryActorRef(StoryActorKind.NARRATOR, "narrator:$chatId")
    private val rowan = StoryActorRef(StoryActorKind.CHARACTER, "rowan")
    private val selene = StoryActorRef(StoryActorKind.CHARACTER, "selene")

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = StoryMemoryRepository(db, AppDataMaintenanceCoordinator())
    }

    @After fun teardown() = db.close()

    private fun memory(
        id: String, text: String, order: Long, claim: String = "FACT",
        review: String = "APPROVED", lifecycle: String = "ACTIVE",
        hidden: Boolean = false, pinned: Boolean = false,
        chat: String = chatId
    ) = StoryMemoryEntity(
        memoryId = id, chatId = chat, ownerId = chat, content = text,
        claim = claim, review = review, lifecycle = lifecycle,
        hidden = hidden, pinned = pinned, sourceOrderIndex = order
    )

    @Test fun crowdedLongStoryPreservesEvidenceRecipientPrivacyAndHardBudget() = runBlocking {
        db.chatDao().insertChat(ChatEntity(
            chatId = chatId, displayName = "Crowded story", chatType = ChatType.SCENARIO
        ))
        db.chatDao().insertChat(ChatEntity(
            chatId = "other-story", displayName = "Other story", chatType = ChatType.SCENARIO
        ))
        for (actor in listOf("rowan", "selene")) {
            db.chatDao().insertChatParticipant(
                ChatParticipantEntity(chatId = chatId, characterId = actor)
            )
        }
        val longBody = ("The journey continued across cobbled streets while the "
            + "travelers discussed the weather and observed the changing scenery. ").repeat(14)
        val history = (1..2_000).map { n ->
            MessageEntity(
                messageId = "turn-$n", chatId = chatId,
                speakerType = SpeakerType.NARRATOR, speakerId = "narrator:$chatId",
                speakerDisplayNameSnapshot = "Narrator",
                content = when {
                    n == 17 -> "The basalt portal opened when the amber signet was touched."
                    n >= 1_976 -> longBody + "Scene turn $n."
                    else -> "The expedition continued through passage $n."
                },
                createdAt = 1_700_000_000_000L + n, orderIndex = n.toLong(),
                variantGroupId = if (n == 17) "fork-17" else "turn-$n"
            )
        }
        db.withTransaction {
            history.forEach { db.messageDao().insertMessage(it) }
            db.messageDao().insertMessage(history[16].copy(
                messageId = "turn-17-unselected",
                content = "DISCARDED_PORTAL_BRANCH_SECRET", isPrimaryVariant = false
            ))
        }
        val oldSource = repository.saveFromMessage(chatId, "turn-17")
        val distractors = (1..144).map { n ->
            memory("distractor-$n",
                "The ordinary storeroom inventory entry $n was counted at sunrise.",
                100L + n)
        }
        db.storyMemoryDao().insertAll(distractors + listOf(
            memory("letter", "Nessa concealed the sealed letter behind the greenhouse mural.", 18),
            memory("superseded-vow", "Rowan swore to Mara he would return the silver compass.",
                19, "PROMISE", lifecycle = "SUPERSEDED", pinned = true),
            memory("fulfilled-vow", "Rowan fulfilled the promise to Mara: the silver compass was returned at dawn.",
                1_200, "PROMISE", lifecycle = "RESOLVED"),
            memory("argument", "Ari and Bea argued over the telescope.", 24, "RELATIONSHIP"),
            memory("reconciliation", "Ari and Bea reconciled beside the telescope.", 1_100, "RELATIONSHIP"),
            memory("pending", "Nessa stashed the missive in the forbidden vault.",
                1_500, review = "PENDING_REVIEW", pinned = true),
            memory("hidden", "Nessa stashed the missive inside the hidden crypt.",
                1_501, hidden = true, pinned = true),
            memory("private-heir", "The royal heir's true identity is the archivist Mira.",
                1_700, "SECRET")
        ))
        db.storyMemoryDao().insert(memory(
            "foreign", "Nessa stashed the missive beyond the other story's wall.",
            500, chat = "other-story"
        ))
        repository.setCharacterKnowledge(chatId, "private-heir", setOf("rowan"))

        val letter = repository.retrieveForGeneration(
            chatId, narrator, "Where did Nessa stash the missive?", maxMemories = 1
        )
        assertEquals(listOf("letter"), letter.selected.map { it.memoryId })
        assertEquals(1, letter.excludedCounts[StoryMemoryExclusion.NOT_APPROVED])
        assertEquals(1, letter.excludedCounts[StoryMemoryExclusion.HIDDEN])
        val vow = repository.retrieveForGeneration(
            chatId, narrator, "What oath did Rowan make to Mara about the compass?", maxMemories = 2
        )
        assertEquals(listOf("fulfilled-vow"), vow.selected.map { it.memoryId })
        assertEquals(1, vow.excludedCounts[StoryMemoryExclusion.SUPERSEDED])
        val timeline = repository.retrieveForGeneration(
            chatId, narrator, "How did Ari and Bea deal with the telescope?", maxMemories = 2
        )
        assertEquals(listOf("argument", "reconciliation"),
            timeline.selected.map { it.memoryId })
        val rowanSecret = repository.retrieveForGeneration(
            chatId, rowan, "royal heir identity", maxMemories = 2
        )
        val seleneSecret = repository.retrieveForGeneration(
            chatId, selene, "royal heir identity", maxMemories = 2
        )
        val narratorSecret = repository.retrieveForGeneration(
            chatId, narrator, "royal heir identity", maxMemories = 2
        )
        assertEquals(listOf("private-heir"), rowanSecret.selected.map { it.memoryId })
        assertFalse(seleneSecret.selected.any { it.memoryId == "private-heir" })
        assertFalse(narratorSecret.selected.any { it.memoryId == "private-heir" })

        val persona = PersonaEntity(
            personaId = "mara", displayName = "Mara", personaName = "Mara",
            aliases = emptyList(), importSchemaVersion = 1,
            identityJson = null, appearanceJson = null, personalityJson = null,
            roleplayProfileJson = null, backgroundJson = null,
            worldContextJson = null, privateNotesJson = null, extensionFieldsJson = null
        )
        val current = MessageEntity(
            messageId = "qa-current", chatId = chatId,
            speakerType = SpeakerType.PERSONA, speakerId = "mara",
            speakerDisplayNameSnapshot = "Mara",
            content = "Where did Nessa stash the missive?", orderIndex = 2_001
        )
        val recent = db.messageDao().getLatestPrimaryWindow(chatId, 24).asReversed()
        assertEquals(24, recent.size)
        assertTrue(recent.all { it.content.length > 1_000 })
        val scenario = ScenarioDefinition(
            title = "Crowded Chronicle", premise = "A multi-year journey",
            instructions = "Protect player agency and preserve authored world rules."
        )
        val compiler = ScenarioContextCompiler()
        val fitted = PromptOptionalContextFitter.fit(
            available = PromptOptionalContextChoices(
                includeLegacyMemories = false,
                includeStoryMemories = true,
                includeSummary = true
            ),
            modelContextWindowTokens = 8_192
        ) { choices ->
            compiler.compile(
                scenario, persona, ChatSettingsEntity(chatId = chatId), recent, current,
                worldContext = "AUTHORED_WORLD_RULES_PRESERVED",
                summary = if (choices.includeSummary)
                    "Generated irrelevant travel recap. ".repeat(500) else null,
                storyMemories = if (choices.includeStoryMemories)
                    letter.selected else emptyList()
            )
        }
        assertTrue("Must fit the hard context window", fitted.decision.allowed)
        assertTrue("Long transcript must be trimmed", fitted.historyTurnsRemoved > 0)
        assertTrue("Generated summary must yield first", fitted.summaryOmitted)
        assertFalse("Approved relevant memory must survive", fitted.storyMemoriesOmitted)
        val narratorPrompt = fitted.messages.joinToString("\n") { it.content }
        assertTrue(narratorPrompt.contains("greenhouse mural"))
        assertTrue(narratorPrompt.contains("AUTHORED_WORLD_RULES_PRESERVED"))
        assertTrue(narratorPrompt.contains(current.content))
        for (forbidden in listOf(
            "forbidden vault", "hidden crypt", "archivist Mira",
            "DISCARDED_PORTAL_BRANCH_SECRET", "Generated irrelevant travel recap."
        )) assertFalse("Excluded evidence in outgoing prompt", narratorPrompt.contains(forbidden))
        assertTrue(fitted.messages.any { it.sourceMessageId == current.messageId })
        assertTrue(ContextBudgetProbe.measure(fitted.messages)
            .utf8BytesByBucket.getValue(ContextBudgetBucket.MEMORY) > 0L)

        val character = CharacterEntity(
            characterId = "rowan", displayName = "Rowan", characterName = "Rowan",
            aliases = emptyList(), importSchemaVersion = 1,
            identityJson = "A cautious scholar.", appearanceJson = null,
            personalityJson = null, voiceJson = null, behaviorJson = null,
            backstoryJson = null, knowledgeJson = null, worldContextJson = null,
            writingRulesJson = null, examplesJson = null, authorNotesJson = null,
            extensionFieldsJson = null
        )
        val solo = ContextCompilerV1().compileSoloContext(
            character = character, persona = persona,
            chatHistory = recent, currentMessage = current,
            responseProfile = ResponseLengthProfile.NORMAL,
            customMin = null, customTargetMax = null, customHardMax = null,
            storyMemories = rowanSecret.selected
        )
        assertTrue(solo.first().content.contains("archivist Mira"))
        assertFalse(narratorPrompt.contains("archivist Mira"))

        assertTrue(repository.retrieveForGeneration(
            chatId, narrator, "basalt portal amber signet"
        ).selected.any { it.memoryId == oldSource.memoryId })
        db.chatDao().setContextResetBoundary(chatId, 1_990L, 1_990L)
        assertFalse(repository.retrieveForGeneration(
            chatId, narrator, "basalt portal amber signet"
        ).selected.any { it.memoryId == oldSource.memoryId })
        repository.restoreAcrossWack(chatId, oldSource.memoryId)
        assertTrue(repository.retrieveForGeneration(
            chatId, narrator, "basalt portal amber signet"
        ).selected.any { it.memoryId == oldSource.memoryId })
        db.withTransaction {
            db.messageDao().clearPrimaryVariants("fork-17")
            db.messageDao().setPrimaryVariant("turn-17-unselected")
        }
        assertFalse(repository.retrieveForGeneration(
            chatId, narrator, "basalt portal amber signet"
        ).selected.any { it.memoryId == oldSource.memoryId })

        // Counts only: do not put even synthetic secret text in CI logs.
        println("MEMORY_V2_CROWDED_SCENE_ACCEPTANCE messages=2000 candidateMemories=153 " +
            "letter=1 fulfilledPromise=1 chronology=2 " +
            "recipientIsolation=true wackAndVariantSafety=true " +
            "inputEstimate=" + fitted.decision.estimatedInputTokens +
            " inputCeiling=" + fitted.decision.inputCeilingTokens +
            " historyTurnsTrimmed=" + fitted.historyTurnsRemoved + " providerCalls=0")
    }
}
