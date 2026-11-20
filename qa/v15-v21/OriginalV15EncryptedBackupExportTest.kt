package com.example

import android.content.Context
import android.graphics.Bitmap
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.APP_DATABASE_VERSION
import com.example.data.AppDatabase
import com.example.data.model.*
import com.example.domain.backup.BackupRepository
import com.example.domain.repository.AppearanceRepository
import com.example.domain.repository.MessageAttachmentRepository
import com.example.domain.repository.ProfileImageKind
import com.example.domain.repository.ProfileImageRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Compile this QA-only test against the pinned real 1.5.2/Room-v15 source.
 * The resulting encrypted archive is produced by v15 BackupRepository itself,
 * not reconstructed using the new app's backward-compatibility serializer.
 * Nothing here contains user data or usable secrets.
 */
@RunWith(RobolectricTestRunner::class)
class OriginalV15EncryptedBackupExportTest {
    companion object {
        const val SYNTHETIC_PASSWORD = "QA_ONLY_random_example_passphrase_not_a_user_secret"
    }

    @Test fun historicalV15RepositoryExportsEncryptedArchiveWithRealBinaryFiles() = runBlocking {
        assertEquals(15, APP_DATABASE_VERSION)
        val path = requireNotNull(System.getenv("ELSEWHERE_V15_ENCRYPTED_PATH")) {
            "Dedicated QA must pass an ephemeral, non-repository archive path"
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val avatarDirectory = File(context.filesDir, "profile_images")
        val attachmentDirectory = File(context.filesDir, "message_attachments")
        try {
            val avatar = ProfileImageRepository(context)
            val attachments = MessageAttachmentRepository(context, database)
            val settingsFile = context.preferencesDataStoreFile(
                "qa-backup-v15-${System.nanoTime()}"
            )
            val appearance = AppearanceRepository.forDataStore(
                PreferenceDataStoreFactory.create(scope = scope, produceFile = { settingsFile })
            )
            val original = BackupRepository(
                context, database, appearance, attachments, avatar,
                Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
            )
            database.characterDao().insertCharacter(CharacterEntity(
                characterId = "v15-rowan", displayName = "Rowan", characterName = "Rowan",
                aliases = listOf("Scholar"), importSchemaVersion = 1,
                identityJson = "{\"role\":\"traveler\"}",
                appearanceJson = null, personalityJson = null, voiceJson = null,
                behaviorJson = null, backstoryJson = null, knowledgeJson = null,
                worldContextJson = null, writingRulesJson = null, examplesJson = null,
                authorNotesJson = null, extensionFieldsJson = null
            ))
            database.personaDao().insertPersona(PersonaEntity(
                personaId = "v15-mara", displayName = "Mara", personaName = "Mara",
                aliases = emptyList(), importSchemaVersion = 1,
                identityJson = "{\"name\":\"Mara\"}", appearanceJson = null,
                personalityJson = null, roleplayProfileJson = null, backgroundJson = null,
                worldContextJson = null, privateNotesJson = null, extensionFieldsJson = null
            ))
            database.chatDao().insertChat(ChatEntity(
                chatId = "v15-story", displayName = "Old Lantern Story",
                chatType = ChatType.SOLO, contextResetOrderIndex = 3L
            ))
            database.chatDao().insertChatParticipant(
                ChatParticipantEntity(chatId = "v15-story", characterId = "v15-rowan")
            )
            database.chatDao().insertChatPersonaParticipant(
                ChatPersonaParticipantEntity(chatId = "v15-story", personaId = "v15-mara")
            )
            database.chatDao().insertChatSettings(ChatSettingsEntity(
                chatId = "v15-story", selectedModelId = "offline/fake",
                draftMessage = "UNSENT_V15_PLAYER_DRAFT",
                narratorEnabled = true, narratorInstructions = "Keep the player's choices free."
            ))
            database.messageDao().insertMessage(MessageEntity(
                messageId = "v15-discarded", chatId = "v15-story",
                speakerType = SpeakerType.PERSONA, speakerId = "v15-mara",
                speakerDisplayNameSnapshot = "Mara",
                content = "I found a brass key.", orderIndex = 1L,
                variantGroupId = "v15-variant-group", isPrimaryVariant = false
            ))
            database.messageDao().insertMessage(MessageEntity(
                messageId = "v15-selected", chatId = "v15-story",
                speakerType = SpeakerType.PERSONA, speakerId = "v15-mara",
                speakerDisplayNameSnapshot = "Mara",
                content = "I found the silver key.", orderIndex = 1L,
                variantGroupId = "v15-variant-group", isPrimaryVariant = true
            ))
            database.messageDao().insertMessage(MessageEntity(
                messageId = "v15-response", chatId = "v15-story",
                speakerType = SpeakerType.CHARACTER, speakerId = "v15-rowan",
                speakerDisplayNameSnapshot = "Rowan", content = "We must protect it.",
                orderIndex = 2L
            ))
            database.memoryDao().insertMemory(RoleplayMemoryEntity(
                memoryId = "v15-memory", chatId = "v15-story",
                characterId = "v15-rowan", category = MemoryCategory.PROMISE,
                content = "The silver key stays with Mara.",
                sourceMessageId = "v15-selected"
            ))
            database.memoryDao().upsertSummary(ConversationSummaryEntity(
                chatId = "v15-story", summary = "The silver key was discovered.",
                summarizedThroughOrderIndex = 2L
            ))
            database.lorebookDao().insertLorebook(LorebookEntity(
                lorebookId = "v15-lore", name = "Lantern Lore"
            ))
            database.lorebookDao().insertEntry(LorebookEntryEntity(
                entryId = "v15-entry", lorebookId = "v15-lore", title = "The key",
                canonContent = "The key is silver.", currentContent = "The key is silver.",
                keys = listOf("silver")
            ))
            database.lorebookDao().upsertAttachment(ChatLorebookAttachmentEntity(
                chatId = "v15-story", lorebookId = "v15-lore"
            ))
            database.worldDao().insertWorld(WorldEntity(
                worldId = "v15-world", title = "Lantern World",
                worldRules = "Knowledge is not shared automatically."
            ))
            database.worldDao().insertMembership(WorldMembershipEntity(
                worldId = "v15-world", resourceType = "CHAT", resourceId = "v15-story"
            ))
            database.npcDao().insertDossier(NpcDossierEntity(
                npcId = "v15-keeper", chatId = "v15-story",
                displayName = "Keeper", secrets = "SYNTHETIC_ONLY_PRIVATE_FACT",
                authorNotes = "SYNTHETIC_ONLY_AUTHOR_NOTE"
            ))
            val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(0xFF336699.toInt())
            val bytes = ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
            bitmap.recycle()
            val pngHash = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            val relative = "message_attachments/v15-qa-photo.png"
            File(context.filesDir, relative).apply {
                parentFile!!.mkdirs()
                writeBytes(bytes)
            }
            database.messageAttachmentDao().insert(MessageAttachmentEntity(
                attachmentId = "v15-photo", chatId = "v15-story",
                messageId = "v15-selected", relativePath = relative,
                mimeType = "image/png", byteSize = bytes.size.toLong(),
                pixelWidth = 1, pixelHeight = 1, sha256 = pngHash,
                sortPosition = 0
            ))
            val avatarFile = File(context.filesDir,
                "profile_images/${ProfileImageKind.CHARACTER.directoryName}/" +
                    "${ProfileImageRepository.storageKey(ProfileImageKind.CHARACTER, "v15-rowan")}.png")
            avatarFile.parentFile!!.mkdirs()
            avatarFile.writeBytes(bytes)

            val encrypted = File(path)
            encrypted.parentFile?.mkdirs()
            val preview = FileOutputStream(encrypted).use { output ->
                original.exportEncrypted(output, SYNTHETIC_PASSWORD.toCharArray())
            }
            assertEquals(15, preview.databaseVersion)
            assertEquals(1, preview.chats)
            assertEquals(3, preview.messages)
            assertEquals(1, preview.messageAttachments)
            assertTrue(preview.profileImages > 0)
            assertTrue(encrypted.isFile && encrypted.length() > 100L)
            println(
                "V15_ORIGINAL_ENCRYPTED_EXPORT_COMPLETE bytes=${encrypted.length()} " +
                    "dbVersion=${preview.databaseVersion} messages=${preview.messages} " +
                    "binaryCount=${preview.messageAttachments + preview.profileImages} " +
                    "source=actual-v15-BackupRepository userData=false"
            )
        } finally {
            database.close()
            scope.cancel()
            avatarDirectory.deleteRecursively()
            attachmentDirectory.deleteRecursively()
        }
    }
}
