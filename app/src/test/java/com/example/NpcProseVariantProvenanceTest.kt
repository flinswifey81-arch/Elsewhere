package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.ChatEntity
import com.example.data.model.ChatType
import com.example.data.model.MessageEntity
import com.example.data.model.PersonaEntity
import com.example.data.model.SpeakerType
import com.example.domain.repository.NpcRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies that auto-discovered ordinary-prose NPCs follow the same selected
 * reply and Wack boundaries as structured NPC updates, including Continue.
 * Real Room; zero model requests; no owner data or production packages.
 */
@RunWith(RobolectricTestRunner::class)
class NpcProseVariantProvenanceTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: NpcRepository

    @Before fun setup() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = NpcRepository(
            database, Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        )
        database.chatDao().insertChat(
            ChatEntity(chatId = "chat", displayName = "Solo provenance QA", chatType = ChatType.SOLO)
        )
        database.personaDao().insertPersona(
            PersonaEntity(
                personaId = "player", displayName = "Hazel", personaName = "Hazel",
                aliases = listOf("Hazel Alexander"), importSchemaVersion = 1,
                identityJson = null, appearanceJson = null, personalityJson = null,
                roleplayProfileJson = null, backgroundJson = null,
                worldContextJson = null, privateNotesJson = null, extensionFieldsJson = null
            )
        )
        repository.setEnabled("chat", true)
    }

    @After fun teardown() = database.close()

    private fun generated(
        id: String, text: String, group: String = "first-reply",
        order: Long = 15, primary: Boolean = true
    ) = MessageEntity(
        messageId = id, chatId = "chat",
        speakerType = SpeakerType.CHARACTER, speakerId = "rowan",
        speakerDisplayNameSnapshot = "Rowan", content = text,
        orderIndex = order, variantGroupId = group, isPrimaryVariant = primary
    )

    private suspend fun currentNames(): Set<String> =
        repository.getProjectedDossiers("chat").map { it.displayName }.toSet()

    @Test fun regenerateSelectDeleteAndWackReprojectOnlyTheSelectedProseSpeaker() = runBlocking {
        val original = generated(
            "reply-a", "\"The gate is shut,\" Liora Fen warned.",
            group = "prose-variants"
        )
        repository.persistGeneratedMessageAtomically(
            original, metadata = null, isNewVariant = false,
            updates = emptyList(), activePersonaId = "player"
        )
        assertEquals(setOf("Liora Fen"), currentNames())
        val lioraId = repository.getProjectedDossiers("chat").single().npcId

        val regenerated = generated(
            "reply-b", "\"The gate is shut,\" Mountain Shaper warned.",
            group = "prose-variants"
        )
        repository.persistGeneratedMessageAtomically(
            regenerated, metadata = null, isNewVariant = true,
            updates = emptyList(), activePersonaId = "player"
        )
        assertEquals(setOf("Mountain Shaper"), currentNames())
        val mountainId = repository.getProjectedDossiers("chat").single().npcId
        assertTrue(lioraId != mountainId)

        repository.selectVariant("prose-variants", "reply-a")
        assertEquals(setOf("Liora Fen"), currentNames())
        assertEquals(lioraId, repository.getProjectedDossiers("chat").single().npcId)

        repository.selectVariant("prose-variants", "reply-b")
        assertEquals(setOf("Mountain Shaper"), currentNames())

        repository.deleteMessageAndRepair(
            requireNotNull(database.messageDao().getMessageById("reply-b"))
        )
        assertEquals(setOf("Liora Fen"), currentNames())
        assertTrue(database.messageDao().getMessageById("reply-a")?.isPrimaryVariant == true)

        // The transcript and editable historical provenance remain stored;
        // none of the pre-Wack discoveries may enter a post-Wack prompt.
        assertTrue(repository.getProjectedDossiers("chat", afterOrderIndex = 15).isEmpty())
        assertTrue(repository.relevantContext(
            chatId = "chat", recentMessages = emptyList(), afterOrderIndex = 15
        ).isNullOrBlank())
        assertEquals(setOf("Liora Fen"), currentNames())
    }

    @Test fun continueCreatesOnlyNewProseSpeakersAndNoDuplicateOnReplay() = runBlocking {
        val initial = generated(
            "continued-reply", "\"We can pass,\" Liora Fen answered.",
            group = "continued-reply", order = 20
        )
        repository.persistGeneratedMessageAtomically(
            initial, metadata = null, isNewVariant = false,
            updates = emptyList(), activePersonaId = "player"
        )
        assertEquals(setOf("Liora Fen"), currentNames())
        val originalEvents = database.npcDao().getUpdatesForMessage(initial.messageId).size

        val addition = "\n\"The bridge is guarded,\" Mountain Shaper warned."
        repository.persistContinuationAtomically(
            initial.copy(content = initial.content + addition),
            metadata = null, updates = emptyList(), activePersonaId = "player"
        )
        assertEquals(setOf("Liora Fen", "Mountain Shaper"), currentNames())
        val afterFirst = database.npcDao().getUpdatesForMessage(initial.messageId).size
        assertEquals(originalEvents + 1, afterFirst)
        val namesAndIds = repository.getProjectedDossiers("chat")
            .associate { it.displayName to it.npcId }

        repository.persistContinuationAtomically(
            requireNotNull(database.messageDao().getMessageById(initial.messageId)),
            metadata = null, updates = emptyList(), activePersonaId = "player"
        )
        assertEquals(namesAndIds,
            repository.getProjectedDossiers("chat").associate { it.displayName to it.npcId })
        assertEquals(afterFirst, database.npcDao().getUpdatesForMessage(initial.messageId).size)

        repository.deleteMessageAndRepair(
            requireNotNull(database.messageDao().getMessageById(initial.messageId))
        )
        assertTrue(currentNames().isEmpty())
        assertTrue(database.npcDao().getUpdatesForMessage(initial.messageId).isEmpty())
    }

    @Test fun playerDialogueAndSingleUnnamedGuardDoNotBecomeNpcs() = runBlocking {
        val reply = generated(
            "guard-reply",
            "\"It is mine,\" Hazel answered. " +
                "\"Keep moving,\" the guard whispered. " +
                "\"The gate is safe,\" Rowan declared.",
            order = 8
        )
        repository.persistGeneratedMessageAtomically(
            reply, metadata = null, isNewVariant = false,
            updates = emptyList(), activePersonaId = "player"
        )
        assertTrue(currentNames().isEmpty())
    }
}
