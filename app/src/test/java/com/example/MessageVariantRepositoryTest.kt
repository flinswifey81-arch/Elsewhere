package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.GenerationMetadataEntity
import com.example.data.model.MessageEntity
import com.example.data.model.SpeakerType
import com.example.domain.repository.MessageRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MessageVariantRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: MessageRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MessageRepository(database.messageDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun deletingSelectedVariantPromotesSurvivorAndRemovesMetadata() = runBlocking {
        val original = response("original", "Original", isPrimary = true)
        val replacement = response("replacement", "Replacement", isPrimary = true)
        repository.insertMessage(original)
        repository.insertGenerationMetadata(GenerationMetadataEntity(messageId = original.messageId))
        repository.insertVariantAndSelect(replacement)
        repository.insertGenerationMetadata(GenerationMetadataEntity(messageId = replacement.messageId))

        var variants = repository.getVariants("variant-group").first()
        assertEquals(listOf("replacement"), variants.filter { it.isPrimaryVariant }.map { it.messageId })

        repository.deleteMessage(replacement)

        variants = repository.getVariants("variant-group").first()
        assertEquals(1, variants.size)
        assertTrue(variants.single().isPrimaryVariant)
        assertEquals("original", variants.single().messageId)
        assertNull(repository.getGenerationMetadata("replacement"))
    }

    private fun response(id: String, content: String, isPrimary: Boolean) = MessageEntity(
        messageId = id,
        chatId = "chat",
        speakerType = SpeakerType.CHARACTER,
        speakerId = "character",
        speakerDisplayNameSnapshot = "Character",
        content = content,
        orderIndex = 2L,
        variantGroupId = "variant-group",
        isPrimaryVariant = isPrimary
    )
}
