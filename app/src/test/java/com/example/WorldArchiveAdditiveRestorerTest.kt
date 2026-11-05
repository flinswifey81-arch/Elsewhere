package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.*
import com.example.domain.repository.AppDataMaintenanceCoordinator
import com.example.domain.repository.ProfileImageKind
import com.example.domain.repository.ProfileImageRepository
import com.example.domain.world.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class WorldArchiveAdditiveRestorerTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var db: AppDatabase
    private lateinit var appFiles: File
    private lateinit var io: WorldArchiveEncryptedIO
    private lateinit var importer: WorldArchiveAdditiveRestorer

    private val importedWorld = WorldEntity(worldId = "world-source", title = "An Unrelated Wonderland")
    private val importedChat = ChatEntity(chatId = "chat-source", chatType = ChatType.SOLO, displayName = "Wonderland adventure")
    private val importedMessage = MessageEntity(
        messageId = "message-source", chatId = importedChat.chatId, orderIndex = 1,
        speakerType = SpeakerType.SYSTEM, speakerId = "narrator", speakerDisplayNameSnapshot = "Narrator",
        content = "We found the key beneath the teapot."
    )
    private val fileBytes = "PNG bytes for a deterministic fake test image".toByteArray()

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        appFiles = temp.newFolder("app-private-files")
        io = WorldArchiveEncryptedIO(temp.newFolder("archive-operations"))
        importer = WorldArchiveAdditiveRestorer(db, appFiles, AppDataMaintenanceCoordinator())
    }

    @After fun teardown() { db.close() }

    private fun digest(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun history(withImage: Boolean = false, scenario: ScenarioEntity? = null): WorldArchiveStateV1 =
        WorldArchiveStateV1(
            format = WorldArchiveStateCodec.FORMAT,
            schemaVersion = WorldArchiveStateCodec.SCHEMA_VERSION,
            worldId = importedWorld.worldId, world = importedWorld,
            memberships = listOf(
                WorldMembershipEntity(importedWorld.worldId, "CHAT", importedChat.chatId)
            ),
            chats = listOf(importedChat), messages = listOf(importedMessage),
            chatSettings = listOf(ChatSettingsEntity(importedChat.chatId)),
            scenarios = scenario?.let(::listOf).orEmpty(),
            messageAttachments = if (withImage) listOf(
                WorldArchiveMessageAttachmentV1(
                    attachmentId = "picture-source", chatId = importedChat.chatId,
                    messageId = importedMessage.messageId,
                    assetPath = "assets/message-attachments/picture-source",
                    mimeType = "image/png", byteSize = fileBytes.size.toLong(),
                    pixelWidth = 1, pixelHeight = 1,
                    sha256 = digest(fileBytes), sortPosition = 0, createdAt = 1L
                )
            ) else emptyList()
        )

    private fun encrypted(state: WorldArchiveStateV1): ByteArray {
        val output = ByteArrayOutputStream()
        val files = state.messageAttachments.map { row ->
            val file = temp.newFile().apply { writeBytes(fileBytes) }
            WorldArchiveEncryptedIO.AssetSource(
                row.assetPath, WorldArchiveEntryKind.MESSAGE_ATTACHMENT, row.attachmentId, file
            )
        }
        io.exportSelectedEncrypted(state, files, output, "sufficiently-long-password".toCharArray(), 1L)
        return output.toByteArray()
    }

    private suspend fun existingRecords() {
        db.worldDao().insertWorld(WorldEntity(worldId = "existing-world", title = "Untouched World"))
        db.backupDao().insertChats(listOf(
            ChatEntity(chatId = "existing-chat", chatType = ChatType.SOLO, displayName = "Already here")
        ))
        db.backupDao().insertMessages(listOf(
            MessageEntity(messageId = "existing-message", chatId = "existing-chat",
                speakerType = SpeakerType.SYSTEM, speakerId = "narrator",
                speakerDisplayNameSnapshot = "Narrator", content = "Don't erase me!", orderIndex = 1)
        ))
    }

    @Test fun additiveRestoreKeepsOldWorldAndCreatesNewChatAndImage() = runBlocking {
        existingRecords()
        val state = history(withImage = true)
        io.inspectEncrypted(ByteArrayInputStream(encrypted(state)),
            "sufficiently-long-password".toCharArray()).use { inspected ->
            val saved = importer.importAsNewWorld(inspected, "additive-world-session-123")
            assertNotEquals(importedWorld.worldId, saved.worldId)
            assertEquals(1, saved.chats)
            assertEquals(1, saved.messages)
            assertEquals(1, saved.imageFiles)
            assertNotNull(db.worldDao().getWorld("existing-world"))
            assertNotNull(db.worldDao().getWorld(saved.worldId))
            assertEquals("Don't erase me!", db.backupDao().getMessages()
                .single { it.messageId == "existing-message" }.content)
            assertEquals("We found the key beneath the teapot.",
                db.backupDao().getMessages().single { it.chatId != "existing-chat" }.content)
            assertEquals(2, db.backupDao().getChats().size)
            val attachment = db.backupDao().getMessageAttachments().single()
            assertArrayEquals(fileBytes, File(appFiles, attachment.relativePath).readBytes())
            assertEquals(attachment.sha256, digest(fileBytes))
        }
        assertTrue(File(appFiles, "world_import_recovery").listFiles().orEmpty().isEmpty())
    }

    @Test fun faultsAfterFilePromotionOrDatabaseWritesRollbackRowsAndImages() = runBlocking {
        existingRecords()
        val bytes = encrypted(history(withImage = true))
        for (checkpoint in WorldArchiveAdditiveRestorer.TestCheckpoint.entries) {
            io.inspectEncrypted(ByteArrayInputStream(bytes),
                "sufficiently-long-password".toCharArray()).use { inspected ->
                val thrown = runCatching {
                    importer.importAsNewWorld(
                        inspected, "rollback-session-${checkpoint.name}-12345"
                    ) { step ->
                        if (step == checkpoint) throw IllegalStateException("Injected at $checkpoint")
                    }
                }.exceptionOrNull()
                assertTrue("Failure injection should abort Room transaction", thrown is IllegalStateException)
            }
            assertEquals(listOf("existing-world"), db.worldDao().getWorlds().map { it.worldId })
            assertEquals(listOf("existing-chat"), db.backupDao().getChats().map { it.chatId })
            assertEquals(listOf("existing-message"), db.backupDao().getMessages().map { it.messageId })
            assertTrue(db.backupDao().getMessageAttachments().isEmpty())
            assertFalse(appFiles.walkTopDown().any {
                it.isFile && it.name.startsWith("world-import-")
            })
            assertTrue(File(appFiles, "world_import_recovery").listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun sameOperationCannotImportTwiceOrMergeByMatchingDisplayName() = runBlocking {
        existingRecords()
        val bytes = encrypted(history())
        io.inspectEncrypted(ByteArrayInputStream(bytes),
            "sufficiently-long-password".toCharArray()).use { inspected ->
            val one = importer.importAsNewWorld(inspected, "unique-import-session-12345")
            assertEquals(2, db.worldDao().getWorlds().size)
            assertTrue(runCatching {
                importer.importAsNewWorld(inspected, "unique-import-session-12345")
            }.isFailure)
            assertEquals(2, db.worldDao().getWorlds().size)
            assertEquals(2, db.backupDao().getChats().size)
            assertNotNull(db.worldDao().getWorld(one.worldId))
        }
    }

    @Test fun matchingCharacterNamesDoNotOverwriteExistingProfilePictures() = runBlocking {
        existingRecords()
        val existingCharacter = CharacterEntity(
            characterId = "character-existing", displayName = "The Porter",
            characterName = "The Porter", aliases = emptyList(), importSchemaVersion = 1,
            identityJson = null, appearanceJson = null, personalityJson = null,
            voiceJson = null, behaviorJson = null, backstoryJson = null,
            knowledgeJson = null, worldContextJson = null, writingRulesJson = null,
            examplesJson = null, authorNotesJson = null, extensionFieldsJson = null
        )
        val incomingCharacter = existingCharacter.copy(characterId = "character-source")
        db.backupDao().insertCharacters(listOf(existingCharacter))
        val existingProfile = File(
            appFiles, "profile_images/characters/" +
                ProfileImageRepository.storageKey(ProfileImageKind.CHARACTER, existingCharacter.characterId) + ".png"
        ).apply { parentFile!!.mkdirs(); writeText("This is the existing owner's picture") }

        val state = history().copy(characters = listOf(incomingCharacter))
        val originalImage = temp.newFile().apply { writeBytes(fileBytes) }
        val output = ByteArrayOutputStream()
        io.exportSelectedEncrypted(
            state, listOf(WorldArchiveEncryptedIO.AssetSource(
                "assets/avatars/character/the-porter.png", WorldArchiveEntryKind.CHARACTER_AVATAR,
                incomingCharacter.characterId, originalImage
            )),
            output, "sufficiently-long-password".toCharArray(), 1L
        )
        io.inspectEncrypted(ByteArrayInputStream(output.toByteArray()),
            "sufficiently-long-password".toCharArray()).use { archive ->
            val namespace = "separate-namesake-import-session-123"
            val importedCharacterId = archive.planNewWorldImport(namespace)
                .get(WorldArchiveIdKind.CHARACTER, incomingCharacter.characterId)
            val result = importer.importAsNewWorld(archive, namespace)
            assertEquals(1, result.imageFiles)
            val importedProfile = File(appFiles, "profile_images/characters/" +
                ProfileImageRepository.storageKey(ProfileImageKind.CHARACTER, importedCharacterId) + ".png")
            assertTrue(importedProfile.isFile)
            assertArrayEquals(fileBytes, importedProfile.readBytes())
            assertEquals("This is the existing owner's picture", existingProfile.readText())
            assertEquals(2, db.backupDao().getCharacters().size)
            assertEquals(
                2, db.backupDao().getCharacters().count { it.characterName == "The Porter" }
            )
        }
    }

    @Test fun scenarioCollisionCannotReplaceAnExistingTemplate() = runBlocking {
        existingRecords()
        val scenario = ScenarioEntity(
            scenarioId = "scenario-source", definitionJson = ScenarioCodec.encode(
                ScenarioDefinition(title = "Imported Scenario", premise = "A secret door")
            )
        )
        val state = history(scenario = scenario)
        val namespace = "scenario-collision-import-123"
        val mappedId = WorldArchiveIdPlanner.plan(state, namespace)
            .get(WorldArchiveIdKind.SCENARIO, scenario.scenarioId)
        db.scenarioDao().save(
            ScenarioEntity(scenarioId = mappedId, definitionJson = ScenarioCodec.encode(
                ScenarioDefinition(title = "Existing Scenario", premise = "Leave this alone")
            ))
        )
        io.inspectEncrypted(ByteArrayInputStream(encrypted(state)),
            "sufficiently-long-password".toCharArray()).use { inspected ->
            assertTrue(runCatching {
                importer.importAsNewWorld(inspected, namespace)
            }.isFailure)
        }
        val stillThere = requireNotNull(db.scenarioDao().get(mappedId))
        assertEquals("Existing Scenario", ScenarioCodec.decode(stillThere.definitionJson).title)
        assertEquals(1, db.worldDao().getWorlds().size)
        assertEquals(1, db.backupDao().getChats().size)
    }

    @Test fun crashRecoveryReclaimsUncommittedImagesButPreservesCommittedImages() = runBlocking {
        val pending = File(appFiles, "world_import_recovery").apply { mkdirs() }
        val uuid = UUID.randomUUID().toString()
        val path = "message_attachments/world-import-${UUID.randomUUID()}.png"
        val orphan = File(appFiles, path).apply { parentFile!!.mkdirs(); writeBytes(fileBytes) }
        File(pending, "$uuid.pending").writeText("$uuid\n$path\n")
        assertEquals(1, importer.recoverInterruptedImports())
        assertFalse(orphan.exists())
        assertFalse(File(pending, "$uuid.pending").exists())

        val committedId = UUID.randomUUID().toString()
        val committedPath = "message_attachments/world-import-${UUID.randomUUID()}.png"
        val committedFile = File(appFiles, committedPath).apply {
            parentFile!!.mkdirs(); writeBytes(fileBytes)
        }
        db.worldDao().insertWorld(WorldEntity(worldId = committedId, title = "Committed, don't erase"))
        File(pending, "$committedId.pending").writeText("$committedId\n$committedPath\n")
        assertEquals(1, importer.recoverInterruptedImports())
        assertTrue(committedFile.exists())
        assertFalse(File(pending, "$committedId.pending").exists())
        assertNotNull(db.worldDao().getWorld(committedId))
    }
}
