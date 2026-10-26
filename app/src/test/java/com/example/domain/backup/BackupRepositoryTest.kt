package com.example.domain.backup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.BuildConfig
import com.example.R
import com.example.data.APP_DATABASE_VERSION
import com.example.data.AppDatabase
import com.example.data.model.*
import com.example.domain.repository.AppearanceRepository
import com.example.domain.repository.AppearanceSettings
import com.example.domain.repository.AppDataMaintenanceCoordinator
import com.example.domain.repository.ColorPalette
import com.example.domain.repository.MessageAttachmentRepository
import com.example.domain.repository.MessageRepository
import com.example.domain.repository.ProfileImageKind
import com.example.domain.repository.ProfileImageRepository
import com.example.domain.repository.StaleAppDataMutationException
import com.example.domain.repository.ThemeMode
import com.example.domain.story.StoryResponse
import com.example.domain.story.StoryResponseCodec
import com.example.domain.story.StorySegment
import com.example.domain.story.StorySegmentType
import com.example.domain.coop.CoopCodec
import com.example.domain.coop.CoopMessagePayload
import com.example.domain.coop.CoopParticipantDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
class BackupRepositoryTest {
    private lateinit var context: Context
    private val databases = mutableListOf<AppDatabase>()
    private val scopes = mutableListOf<CoroutineScope>()
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cleanPrivateBackupFiles()
    }

    @After
    fun tearDown() {
        databases.forEach(AppDatabase::close)
        scopes.forEach(CoroutineScope::cancel)
        cleanPrivateBackupFiles()
    }

    @Test
    fun scenarioBackupRestoresDefinitionsSnapshotsNarratorMemoryAndArtwork() = runBlocking {
        val source = environment()
        source.database.personaDao().insertPersona(completePayload().personas.first())
        val scenarios = com.example.domain.repository.ScenarioRepository(source.database)
        val definition = ScenarioDefinition(title = "Court", premise = "A royal invitation", npcs = listOf(ScenarioNpc(name = "Steward")))
        scenarios.save("scenario-1", definition)
        val chatId = scenarios.start("scenario-1", PERSONA_ID)
        source.database.memoryDao().insertMemory(RoleplayMemoryEntity(chatId = chatId, characterId = "narrator:$chatId", category = MemoryCategory.EVENT, content = "The gate is closed"))
        val artwork = pngBytes(Color.BLUE)
        writeAvatar(ProfileImageKind.SCENARIO, "scenario-1", artwork)
        val encrypted = ByteArrayOutputStream()
        source.repository.exportEncrypted(encrypted, PASSPHRASE.toCharArray())
        val target = environment()
        target.repository.restoreEncrypted(ByteArrayInputStream(encrypted.toByteArray()), PASSPHRASE.toCharArray())
        assertEquals(source.database.scenarioDao().getAll(), target.database.scenarioDao().getAll())
        assertEquals(source.database.scenarioDao().sessions(), target.database.scenarioDao().sessions())
        assertEquals(source.database.backupDao().getRoleplayMemories(), target.database.backupDao().getRoleplayMemories())
        assertArrayEquals(artwork, target.profileImages.resolveImage(ProfileImageKind.SCENARIO, "scenario-1")!!.readBytes())
    }

    @Test
    fun payloadFromBeforeScenarioModeDefaultsScenarioCollectionsToEmpty() {
        val currentJson = BackupArchiveIO(moshi, BuildConfig.APPLICATION_ID)
            .payloadToJson(completePayload())
        val legacyJson = currentJson.replace(
            Regex(",\\s*\"scenarios\"\\s*:\\s*\\[\\]\\s*,\\s*\"scenarioSessions\"\\s*:\\s*\\[\\]"),
            ""
        )
        assertNotEquals(currentJson, legacyJson)

        val decoded = requireNotNull(moshi.adapter(BackupPayload::class.java).fromJson(legacyJson))

        assertTrue(decoded.scenarios.isEmpty())
        assertTrue(decoded.scenarioSessions.isEmpty())
        assertEquals(completePayload().chats, decoded.chats)
        assertEquals(completePayload().personas, decoded.personas)
    }

    @Test
    fun payloadFromBeforeUsabilityPassDefaultsEngineAndNpcLibraryCollectionsToEmpty() {
        val payload = completePayload().copy(engineDefaults = emptyList(), npcLibraryBanks = emptyList(), npcLibraryEntries = emptyList())
        val currentJson = BackupArchiveIO(moshi, BuildConfig.APPLICATION_ID).payloadToJson(payload)
        val legacyJson = currentJson
            .replace(Regex(",\\s*\"engineDefaults\"\\s*:\\s*\\[\\]"), "")
            .replace(Regex(",\\s*\"npcLibraryBanks\"\\s*:\\s*\\[\\]"), "")
            .replace(Regex(",\\s*\"npcLibraryEntries\"\\s*:\\s*\\[\\]"), "")
            .replace(Regex(",\\s*\"useGlobalEngineDefaults\"\\s*:\\s*false"), "")
        val decoded = requireNotNull(moshi.adapter(BackupPayload::class.java).fromJson(legacyJson))
        assertTrue(decoded.engineDefaults.isEmpty())
        assertTrue(decoded.npcLibraryBanks.isEmpty())
        assertTrue(decoded.npcLibraryEntries.isEmpty())
        assertFalse(decoded.chatSettings.single().useGlobalEngineDefaults)
        assertEquals(payload.chats, decoded.chats)
    }

    @Test
    fun encryptedBackupPreservesNarratedEnsembleEnvelope() = runBlocking {
        val source = environment()
        val storyContent = StoryResponseCodec.encode(
            StoryResponse(
                segments = listOf(
                    StorySegment(StorySegmentType.NARRATION, "The lamps dim."),
                    StorySegment(
                        StorySegmentType.CHARACTER,
                        "*He glances at the door.* \"Stay close.\"",
                        CHARACTER_ID,
                        "Archive Character"
                    ),
                    StorySegment(StorySegmentType.NPC, "\"Too late.\"", "npc-1", "The Steward")
                )
            )
        )
        val payload = completePayload().let { original ->
            original.copy(
                messages = original.messages.map { message ->
                    if (message.messageId == CHARACTER_MESSAGE_ID) message.copy(content = storyContent) else message
                },
                messageAttachments = emptyList()
            )
        }
        source.insert(payload)
        source.appearance.replaceSettings(payload.appearance.toDomain())
        val encrypted = ByteArrayOutputStream()
        source.repository.exportEncrypted(encrypted, PASSPHRASE.toCharArray())

        val target = environment()
        target.repository.restoreEncrypted(
            ByteArrayInputStream(encrypted.toByteArray()),
            PASSPHRASE.toCharArray()
        )

        val restored = target.database.messageDao().getMessageById(CHARACTER_MESSAGE_ID)
        assertEquals(storyContent, restored?.content)
        assertEquals(3, StoryResponseCodec.decode(requireNotNull(restored).content)?.segments?.size)
    }

    @Test
    fun encryptedRoundTripPreservesEveryDurableCategoryAndSanitizesDeviceSecrets() = runBlocking {
        val source = environment()
        val originalPayload = completePayload()
        source.insert(originalPayload)
        source.appearance.replaceSettings(originalPayload.appearance.toDomain())

        val attachmentBytes = pngBytes(Color.RED)
        val attachmentFile = File(context.filesDir, originalPayload.messageAttachments.single().relativePath)
        attachmentFile.parentFile!!.mkdirs()
        attachmentFile.writeBytes(attachmentBytes)
        val characterAvatarBytes = pngBytes(Color.BLUE)
        val personaAvatarBytes = pngBytes(Color.GREEN)
        writeAvatar(ProfileImageKind.CHARACTER, CHARACTER_ID, characterAvatarBytes)
        writeAvatar(ProfileImageKind.PERSONA, PERSONA_ID, personaAvatarBytes)

        val encrypted = ByteArrayOutputStream()
        val preview = source.repository.exportEncrypted(encrypted, PASSPHRASE.toCharArray())
        assertEquals(1, preview.characters)
        assertEquals(1, preview.personas)
        assertEquals(1, preview.chats)
        assertEquals(2, preview.messages)
        assertEquals(1, preview.messageAttachments)
        assertEquals(2, preview.profileImages)
        assertTrue(preview.includesDrafts)
        assertTrue(preview.includesCoopHistory)

        File(context.filesDir, "message_attachments").deleteRecursively()
        File(context.filesDir, "profile_images").deleteRecursively()
        val target = environment()
        val inspected = target.repository.inspectEncrypted(
            ByteArrayInputStream(encrypted.toByteArray()),
            PASSPHRASE.toCharArray()
        )
        assertEquals(preview.copy(createdAt = inspected.createdAt), inspected)

        val result = target.repository.restoreEncrypted(
            ByteArrayInputStream(encrypted.toByteArray()),
            PASSPHRASE.toCharArray()
        )
        assertEquals(1, result.restoredChats)
        assertEquals(2, result.restoredMessages)
        assertEquals(3, result.restoredFiles)
        assertTrue(result.safetyBackupAvailable)

        val restored = target.readPayload()
        assertEquals(originalPayload.characters, restored.characters)
        assertEquals(originalPayload.personas, restored.personas)
        assertEquals(originalPayload.chats, restored.chats)
        assertEquals(originalPayload.chatParticipants, restored.chatParticipants)
        assertEquals(originalPayload.chatPersonaParticipants, restored.chatPersonaParticipants)
        assertEquals(
            originalPayload.messages.map { it.copy(responsibleDeviceId = null) },
            restored.messages
        )
        assertEquals(originalPayload.relationships, restored.relationships)
        assertEquals(originalPayload.chatSettings, restored.chatSettings)
        assertEquals(originalPayload.generationMetadata, restored.generationMetadata)
        assertEquals(originalPayload.roleplayMemories, restored.roleplayMemories)
        assertEquals(originalPayload.conversationSummaries, restored.conversationSummaries)
        assertEquals(originalPayload.lorebooks, restored.lorebooks)
        assertEquals(originalPayload.lorebookEntries, restored.lorebookEntries)
        assertEquals(originalPayload.chatLorebookAttachments, restored.chatLorebookAttachments)
        assertEquals(originalPayload.chatLorebookEntryOverlays, restored.chatLorebookEntryOverlays)
        assertEquals(originalPayload.coopPersonaOwnerships, restored.coopPersonaOwnerships)
        assertEquals(originalPayload.coopMessageTombstones, restored.coopMessageTombstones)
        assertEquals(originalPayload.npcDossiers, restored.npcDossiers)
        assertEquals(originalPayload.npcBankSettings, restored.npcBankSettings)
        assertEquals(originalPayload.npcDossierUpdates, restored.npcDossierUpdates)
        assertEquals(originalPayload.engineDefaults, restored.engineDefaults)
        assertEquals(originalPayload.npcLibraryBanks, restored.npcLibraryBanks)
        assertEquals(originalPayload.npcLibraryEntries, restored.npcLibraryEntries)
        assertEquals(originalPayload.appearance, restored.appearance)

        val restoredRoom = restored.coopRooms.single()
        assertEquals(CoopConnectionState.DISCONNECTED, restoredRoom.connectionState)
        assertEquals(null, restoredRoom.hostAddress)
        assertEquals(0, restoredRoom.hostPort)
        assertEquals("", restoredRoom.hostPublicKey)
        assertEquals("", restoredRoom.hostKeyFingerprint)
        assertEquals("", restoredRoom.pairingSalt)
        assertEquals("", restoredRoom.pairingCodeHash)
        assertEquals(null, restoredRoom.activeGenerationRequestId)
        val restoredParticipant = restored.coopParticipants.single()
        assertEquals("", restoredParticipant.deviceId)
        assertEquals("", restoredParticipant.identityPublicKey)
        assertFalse(restoredParticipant.isConnected)
        val restoredEvent = restored.coopEvents.single { it.eventType == CoopEventType.MESSAGE_UPSERT }
        assertEquals("", restoredEvent.originPayloadJson)
        assertEquals("", restoredEvent.originSignature)
        assertEquals(null, CoopCodec(moshi).messageFromJson(restoredEvent.payloadJson).responsibleDeviceId)
        val restoredParticipantEvent = restored.coopEvents.single {
            it.eventType == CoopEventType.PARTICIPANT_UPSERT
        }
        val restoredParticipantPayload = CoopCodec(moshi).participantFromJson(restoredParticipantEvent.payloadJson)
        assertEquals("", restoredParticipantPayload.deviceId)
        assertEquals("", restoredParticipantPayload.identityPublicKey)
        assertFalse(restoredParticipantPayload.isConnected)

        val restoredAttachment = restored.messageAttachments.single()
        assertNotEquals(originalPayload.messageAttachments.single().relativePath, restoredAttachment.relativePath)
        assertArrayEquals(attachmentBytes, target.attachments.resolveFile(restoredAttachment)!!.readBytes())
        assertArrayEquals(
            characterAvatarBytes,
            target.profileImages.resolveImage(ProfileImageKind.CHARACTER, CHARACTER_ID)!!.readBytes()
        )
        assertArrayEquals(
            personaAvatarBytes,
            target.profileImages.resolveImage(ProfileImageKind.PERSONA, PERSONA_ID)!!.readBytes()
        )
        assertEquals(ThemeMode.DARK, target.appearance.appearanceSettings.first().themeMode)
        assertEquals(ColorPalette.FOREST_GREEN, target.appearance.appearanceSettings.first().colorPalette)
    }

    @Test
    fun wrongPassphraseAndCiphertextCorruptionFailBeforeRestore() = runBlocking {
        val source = environment()
        val encrypted = ByteArrayOutputStream()
        source.repository.exportEncrypted(encrypted, PASSPHRASE.toCharArray())
        val bytes = encrypted.toByteArray()

        val wrongPassword = runCatching {
            source.repository.inspectEncrypted(ByteArrayInputStream(bytes), "incorrect-password".toCharArray())
        }.exceptionOrNull()
        assertTrue(wrongPassword is BackupValidationException)

        val corrupted = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 0x01).toByte() }
        val corruptResult = runCatching {
            source.repository.inspectEncrypted(ByteArrayInputStream(corrupted), PASSPHRASE.toCharArray())
        }.exceptionOrNull()
        assertTrue(corruptResult is BackupValidationException)
    }

    @Test
    fun missingAttachmentMakesExportFailInsteadOfCreatingIncompleteBackup() = runBlocking {
        val source = environment()
        val payload = completePayload()
        source.insert(payload)

        val failure = runCatching {
            source.repository.exportEncrypted(ByteArrayOutputStream(), PASSPHRASE.toCharArray())
        }.exceptionOrNull()

        assertTrue(failure is BackupValidationException)
        assertTrue(failure!!.message!!.contains("missing"))
    }

    @Test
    fun attachmentDimensionsMustMatchTheStagedImage() = runBlocking {
        val source = environment()
        val payload = completePayload().let { original ->
            original.copy(
                messageAttachments = original.messageAttachments.map { it.copy(pixelWidth = 2) }
            )
        }
        source.insert(payload)
        File(context.filesDir, payload.messageAttachments.single().relativePath).apply {
            parentFile!!.mkdirs()
            writeBytes(pngBytes(Color.RED))
        }

        val failure = runCatching {
            source.repository.exportEncrypted(ByteArrayOutputStream(), PASSPHRASE.toCharArray())
        }.exceptionOrNull()

        assertTrue(failure is BackupValidationException)
        assertTrue(failure!!.message!!.contains("metadata"))
    }

    @Test
    fun androidSystemBackupRulesExcludeEveryPrivateStorageDomain() {
        val expectedDomains = setOf(
            "file",
            "database",
            "sharedpref",
            "device_file",
            "device_database",
            "device_sharedpref"
        )
        assertEquals(expectedDomains, excludedRootDomains(R.xml.backup_rules))
        assertEquals(expectedDomains, excludedRootDomains(R.xml.data_extraction_rules))
    }

    @Test
    fun interruptedRestoreAfterDatabaseReplacementAutomaticallyRecoversExactPreviousData() = runBlocking {
        assertExactRecoveryAt(RestoreStage.AFTER_DATABASE_REPLACED)
    }

    @Test
    fun interruptedRestoreAfterProfileReplacementAutomaticallyRecoversExactPreviousData() = runBlocking {
        assertExactRecoveryAt(RestoreStage.AFTER_PROFILE_IMAGES_REPLACED)
    }

    @Test
    fun restoreRejectsAValidBackupThatChangedAfterInspection() = runBlocking {
        val source = environment()
        val payload = completePayload()
        source.insert(payload)
        File(context.filesDir, payload.messageAttachments.single().relativePath).apply {
            parentFile!!.mkdirs()
            writeBytes(pngBytes(Color.RED))
        }
        val reviewed = ByteArrayOutputStream()
        source.repository.exportEncrypted(reviewed, PASSPHRASE.toCharArray())
        val inspection = source.repository.inspectEncryptedForRestore(
            ByteArrayInputStream(reviewed.toByteArray()),
            PASSPHRASE.toCharArray()
        )

        source.database.backupDao().insertCharacters(listOf(character("added-later", "Changed archive")))
        val changed = ByteArrayOutputStream()
        source.repository.exportEncrypted(changed, PASSPHRASE.toCharArray())
        val target = environment()
        target.database.backupDao().insertCharacters(listOf(character("untouched", "Untouched")))

        val failure = runCatching {
            target.repository.restoreEncrypted(
                ByteArrayInputStream(changed.toByteArray()),
                PASSPHRASE.toCharArray(),
                expectedEncryptedSha256 = inspection.encryptedSha256
            )
        }.exceptionOrNull()

        assertTrue(failure is BackupValidationException)
        assertTrue(failure!!.message!!.contains("changed"))
        assertEquals(listOf("untouched"), target.database.backupDao().getCharacters().map { it.characterId })
        assertFalse(target.repository.hasSafetyBackup())
    }

    private suspend fun assertExactRecoveryAt(failureStage: RestoreStage) {
        val source = environment()
        val sourcePayload = completePayload(deviceMarker = "source-device")
        source.insert(sourcePayload)
        File(context.filesDir, sourcePayload.messageAttachments.single().relativePath).apply {
            parentFile!!.mkdirs()
            writeBytes(pngBytes(Color.RED))
        }
        writeAvatar(ProfileImageKind.CHARACTER, CHARACTER_ID, pngBytes(Color.BLUE))
        writeAvatar(ProfileImageKind.PERSONA, PERSONA_ID, pngBytes(Color.GREEN))
        val encrypted = ByteArrayOutputStream()
        source.repository.exportEncrypted(encrypted, PASSPHRASE.toCharArray())

        val target = environment(faultInjector = { stage ->
            if (stage == failureStage) error("simulated interruption")
        })
        val previousAttachmentBytes = pngBytes(Color.MAGENTA)
        val previousPayload = completePayload(deviceMarker = "private-before-restore").let { payload ->
            payload.copy(
                characters = listOf(character(id = CHARACTER_ID, name = "Before restore")),
                messageAttachments = payload.messageAttachments.map {
                    it.copy(
                        byteSize = previousAttachmentBytes.size.toLong(),
                        sha256 = sha256Lower(previousAttachmentBytes)
                    )
                }
            )
        }
        target.insert(previousPayload)
        File(context.filesDir, previousPayload.messageAttachments.single().relativePath).apply {
            parentFile!!.mkdirs()
            writeBytes(previousAttachmentBytes)
        }
        val previousCharacterAvatar = pngBytes(Color.CYAN)
        val previousPersonaAvatar = pngBytes(Color.YELLOW)
        writeAvatar(ProfileImageKind.CHARACTER, CHARACTER_ID, previousCharacterAvatar)
        writeAvatar(ProfileImageKind.PERSONA, PERSONA_ID, previousPersonaAvatar)

        val failure = runCatching {
            target.repository.restoreEncrypted(
                ByteArrayInputStream(encrypted.toByteArray()),
                PASSPHRASE.toCharArray()
            )
        }.exceptionOrNull()

        assertTrue(failure is BackupValidationException)
        assertTrue(failure!!.message, failure.message!!.contains("recovered"))
        val recovered = target.readPayload()
        assertEquals("Before restore", recovered.characters.single().displayName)
        assertEquals("private-before-restore", recovered.messages.first().responsibleDeviceId)
        assertEquals("private-before-restore", recovered.coopParticipants.single().deviceId)
        assertEquals("private-before-restore-key", recovered.coopParticipants.single().identityPublicKey)
        val recoveredMessagePayload = CoopCodec(moshi).messageFromJson(
            recovered.coopEvents.single { it.eventType == CoopEventType.MESSAGE_UPSERT }.payloadJson
        )
        assertEquals("private-before-restore", recoveredMessagePayload.responsibleDeviceId)
        val recoveredAttachment = recovered.messageAttachments.single()
        assertEquals(previousPayload.messageAttachments.single().relativePath, recoveredAttachment.relativePath)
        assertArrayEquals(previousAttachmentBytes, target.attachments.resolveFile(recoveredAttachment)!!.readBytes())
        assertArrayEquals(
            previousCharacterAvatar,
            target.profileImages.resolveImage(ProfileImageKind.CHARACTER, CHARACTER_ID)!!.readBytes()
        )
        assertArrayEquals(
            previousPersonaAvatar,
            target.profileImages.resolveImage(ProfileImageKind.PERSONA, PERSONA_ID)!!.readBytes()
        )
        assertTrue(target.repository.hasSafetyBackup())
        assertFalse(target.repository.hasInterruptedRestore())
    }

    @Test
    fun archiveRejectsFutureVersionsAndTraversalEntries() {
        val archive = BackupArchiveIO(moshi, BuildConfig.APPLICATION_ID)
        val manifest = BackupManifest(
            formatVersion = BackupArchiveIO.FORMAT_VERSION,
            applicationId = BuildConfig.APPLICATION_ID,
            appVersionCode = 1,
            appVersionName = "test",
            databaseVersion = APP_DATABASE_VERSION,
            createdAt = 1,
            payloadSha256 = "0".repeat(64),
            files = emptyList(),
            excludedCategories = emptyList(),
            coopRestoreMode = "OFFLINE_NON_RESUMABLE_HISTORY"
        )
        assertTrue(runCatching { archive.validateManifest(manifest.copy(formatVersion = 2)) }.exceptionOrNull() is BackupValidationException)
        assertTrue(
            runCatching { archive.validateManifest(manifest.copy(databaseVersion = APP_DATABASE_VERSION + 1)) }
                .exceptionOrNull() is BackupValidationException
        )

        val malicious = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("../escape"))
                zip.write(byteArrayOf(1))
                zip.closeEntry()
            }
        }.toByteArray()
        val workspace = File(context.cacheDir, "backup-traversal-${System.nanoTime()}")
        try {
            val failure = runCatching {
                archive.readPlainZip(ByteArrayInputStream(malicious), workspace)
            }.exceptionOrNull()
            assertTrue(failure is BackupValidationException)
            assertFalse(File(context.cacheDir, "escape").exists())
        } finally {
            workspace.deleteRecursively()
        }
    }

    @Test
    fun releaseAndSandboxBackupPoliciesAcceptOnlyTheirMigrationSources() {
        val legacyId = "com.aistudio.elsewhere.abwzy"
        val productionId = "$legacyId.v15"
        val testingId = "$productionId.test"
        val productionArchive = BackupArchiveIO(moshi, setOf(productionId, legacyId))
        val testingArchive = BackupArchiveIO(moshi, setOf(testingId, productionId))
        val manifest = BackupManifest(
            formatVersion = BackupArchiveIO.FORMAT_VERSION,
            applicationId = productionId,
            appVersionCode = 1,
            appVersionName = "test",
            databaseVersion = APP_DATABASE_VERSION,
            createdAt = 1,
            payloadSha256 = "0".repeat(64),
            files = emptyList(),
            excludedCategories = emptyList(),
            coopRestoreMode = "OFFLINE_NON_RESUMABLE_HISTORY"
        )

        testingArchive.validateManifest(manifest)
        productionArchive.validateManifest(manifest)

        val legacyManifest = manifest.copy(applicationId = legacyId)
        productionArchive.validateManifest(legacyManifest)
        assertTrue(
            runCatching { testingArchive.validateManifest(legacyManifest) }
                .exceptionOrNull() is BackupValidationException
        )

        val testingManifest = manifest.copy(applicationId = testingId)
        testingArchive.validateManifest(testingManifest)
        assertTrue(
            runCatching { productionArchive.validateManifest(testingManifest) }
                .exceptionOrNull() is BackupValidationException
        )
    }

    @Test
    fun snapshotBlocksConcurrentRepositoryWriteUntilImmutableFilesAreStaged() = runBlocking {
        val snapshotReached = CountDownLatch(1)
        val allowSnapshot = CountDownLatch(1)
        val source = environment(
            snapshotHook = {
                snapshotReached.countDown()
                check(allowSnapshot.await(5, TimeUnit.SECONDS)) { "Snapshot test gate timed out" }
            }
        )
        source.insert(completePayload().copy(messageAttachments = emptyList()))
        val output = ByteArrayOutputStream()
        val export = async(Dispatchers.IO) {
            source.repository.exportEncrypted(output, PASSPHRASE.toCharArray())
        }
        assertTrue(snapshotReached.await(5, TimeUnit.SECONDS))

        val lateMessage = MessageEntity(
            messageId = "late-during-snapshot",
            chatId = CHAT_ID,
            speakerType = SpeakerType.SYSTEM,
            speakerId = "system",
            speakerDisplayNameSnapshot = "System",
            content = "Written after the immutable snapshot boundary.",
            orderIndex = 99_999L
        )
        val lateWrite = async(Dispatchers.IO) {
            MessageRepository(source.database.messageDao()).insertMessage(lateMessage)
        }
        delay(150)
        assertFalse(lateWrite.isCompleted)

        allowSnapshot.countDown()
        export.await()
        lateWrite.await()
        assertNotNull(source.database.messageDao().getMessageById(lateMessage.messageId))

        val restored = environment()
        restored.repository.restoreEncrypted(
            ByteArrayInputStream(output.toByteArray()),
            PASSPHRASE.toCharArray()
        )
        assertEquals(null, restored.database.messageDao().getMessageById(lateMessage.messageId))
    }

    @Test
    fun restoreRejectsMutationsQueuedAgainstTheReplacedDataSet() = runBlocking {
        val source = environment()
        source.insert(completePayload().copy(messageAttachments = emptyList()))
        val encrypted = ByteArrayOutputStream()
        source.repository.exportEncrypted(encrypted, PASSPHRASE.toCharArray())

        val restoreReached = CountDownLatch(1)
        val allowRestore = CountDownLatch(1)
        val target = environment(
            faultInjector = { stage ->
                if (stage == RestoreStage.AFTER_SAFETY_BACKUP) {
                    restoreReached.countDown()
                    check(allowRestore.await(5, TimeUnit.SECONDS)) { "Restore test gate timed out" }
                }
            }
        )
        val preRestoreRevision = AppDataMaintenanceCoordinator.Global.captureRevision()
        val restore = async(Dispatchers.IO) {
            target.repository.restoreEncrypted(
                ByteArrayInputStream(encrypted.toByteArray()),
                PASSPHRASE.toCharArray()
            )
        }
        assertTrue(restoreReached.await(5, TimeUnit.SECONDS))

        val postRestoreMessage = MessageEntity(
            messageId = "queued-during-restore",
            chatId = CHAT_ID,
            speakerType = SpeakerType.SYSTEM,
            speakerId = "system",
            speakerDisplayNameSnapshot = "System",
            content = "This write must land after restore, not be silently erased.",
            orderIndex = 100_000L
        )
        val queuedWrite = async(Dispatchers.IO) {
            runCatching {
                MessageRepository(target.database.messageDao()).insertMessage(postRestoreMessage)
            }.exceptionOrNull()
        }
        val queuedAppearanceWrite = async(Dispatchers.IO) {
            runCatching { target.appearance.updateFontSize(20f) }.exceptionOrNull()
        }
        val queuedAttachmentMaintenance = async(Dispatchers.IO) {
            runCatching { target.attachments.reconcileOrphanFiles() }.exceptionOrNull()
        }
        val queuedAvatarMutation = async(Dispatchers.IO) {
            runCatching {
                target.profileImages.removeImage(ProfileImageKind.CHARACTER, CHARACTER_ID)
            }.exceptionOrNull()
        }
        delay(150)
        assertFalse(queuedWrite.isCompleted)
        assertFalse(queuedAppearanceWrite.isCompleted)
        assertFalse(queuedAttachmentMaintenance.isCompleted)
        assertFalse(queuedAvatarMutation.isCompleted)

        allowRestore.countDown()
        restore.await()
        listOf(
            queuedWrite.await(),
            queuedAppearanceWrite.await(),
            queuedAttachmentMaintenance.await(),
            queuedAvatarMutation.await()
        ).forEach { failure ->
            assertTrue(failure is StaleAppDataMutationException)
        }
        assertEquals(null, target.database.messageDao().getMessageById(postRestoreMessage.messageId))
        assertEquals(16f, target.appearance.appearanceSettings.first().fontSize)

        val staleGenerationFailure = runCatching {
            AppDataMaintenanceCoordinator.Global.withMutation(preRestoreRevision) {
                target.database.messageDao().insertMessage(postRestoreMessage)
            }
        }.exceptionOrNull()
        assertTrue(staleGenerationFailure is StaleAppDataMutationException)

        MessageRepository(target.database.messageDao()).insertMessage(postRestoreMessage)
        assertEquals(postRestoreMessage, target.database.messageDao().getMessageById(postRestoreMessage.messageId))
    }

    private fun environment(
        faultInjector: (RestoreStage) -> Unit = {},
        snapshotHook: suspend () -> Unit = {}
    ): Environment {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also(databases::add)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also(scopes::add)
        val dataStoreFile = File(context.filesDir, "datastore/backup-test-${System.nanoTime()}.preferences_pb")
        val appearance = AppearanceRepository.forDataStore(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { dataStoreFile })
        )
        val attachments = MessageAttachmentRepository(context, database)
        val profileImages = ProfileImageRepository(context)
        return Environment(
            database = database,
            appearance = appearance,
            attachments = attachments,
            profileImages = profileImages,
            repository = BackupRepository(
                context,
                database,
                appearance,
                attachments,
                profileImages,
                moshi,
                faultInjector = faultInjector,
                dataCoordinator = AppDataMaintenanceCoordinator.Global,
                snapshotHook = snapshotHook
            )
        )
    }

    private suspend fun Environment.insert(payload: BackupPayload) {
        val dao = database.backupDao()
        dao.insertCharacters(payload.characters)
        dao.insertPersonas(payload.personas)
        dao.insertChats(payload.chats)
        dao.insertChatParticipants(payload.chatParticipants)
        dao.insertChatPersonaParticipants(payload.chatPersonaParticipants)
        dao.insertMessages(payload.messages)
        dao.insertRelationships(payload.relationships)
        dao.insertChatSettings(payload.chatSettings)
        dao.insertGenerationMetadata(payload.generationMetadata)
        dao.insertRoleplayMemories(payload.roleplayMemories)
        dao.insertConversationSummaries(payload.conversationSummaries)
        dao.insertLorebooks(payload.lorebooks)
        dao.insertLorebookEntries(payload.lorebookEntries)
        dao.insertChatLorebookAttachments(payload.chatLorebookAttachments)
        dao.insertChatLorebookEntryOverlays(payload.chatLorebookEntryOverlays)
        dao.insertCoopRooms(payload.coopRooms)
        dao.insertCoopParticipants(payload.coopParticipants)
        dao.insertCoopPersonaOwnerships(payload.coopPersonaOwnerships)
        dao.insertCoopEvents(payload.coopEvents)
        dao.insertCoopMessageTombstones(payload.coopMessageTombstones)
        dao.insertMessageAttachments(payload.messageAttachments)
        dao.insertNpcDossiers(payload.npcDossiers)
        dao.insertNpcBankSettings(payload.npcBankSettings)
        dao.insertNpcDossierUpdates(payload.npcDossierUpdates)
        dao.insertEngineDefaults(payload.engineDefaults)
        dao.insertNpcLibraryBanks(payload.npcLibraryBanks)
        dao.insertNpcLibraryEntries(payload.npcLibraryEntries)
    }

    private suspend fun Environment.readPayload(): BackupPayload {
        val dao = database.backupDao()
        val settings = appearance.appearanceSettings.first()
        return BackupPayload(
            characters = dao.getCharacters(),
            personas = dao.getPersonas(),
            chats = dao.getChats(),
            chatParticipants = dao.getChatParticipants(),
            chatPersonaParticipants = dao.getChatPersonaParticipants(),
            messages = dao.getMessages(),
            relationships = dao.getRelationships(),
            chatSettings = dao.getChatSettings(),
            generationMetadata = dao.getGenerationMetadata(),
            roleplayMemories = dao.getRoleplayMemories(),
            conversationSummaries = dao.getConversationSummaries(),
            lorebooks = dao.getLorebooks(),
            lorebookEntries = dao.getLorebookEntries(),
            chatLorebookAttachments = dao.getChatLorebookAttachments(),
            chatLorebookEntryOverlays = dao.getChatLorebookEntryOverlays(),
            coopRooms = dao.getCoopRooms(),
            coopParticipants = dao.getCoopParticipants(),
            coopPersonaOwnerships = dao.getCoopPersonaOwnerships(),
            coopEvents = dao.getCoopEvents(),
            coopMessageTombstones = dao.getCoopMessageTombstones(),
            messageAttachments = dao.getMessageAttachments(),
            npcDossiers = dao.getNpcDossiers(),
            npcBankSettings = dao.getNpcBankSettings(),
            npcDossierUpdates = dao.getNpcDossierUpdates(),
            engineDefaults = dao.getEngineDefaults(),
            npcLibraryBanks = dao.getNpcLibraryBanks(),
            npcLibraryEntries = dao.getNpcLibraryEntries(),
            appearance = BackupAppearanceSettings(
                settings.themeMode.name,
                settings.fontSize,
                settings.colorPalette.storageId,
                settings.legacyAccentOverride
            )
        )
    }

    private fun completePayload(deviceMarker: String = "must-not-leave-device"): BackupPayload {
        val attachmentBytes = pngBytes(Color.RED)
        val personaMessage = MessageEntity(
            messageId = PERSONA_MESSAGE_ID,
            chatId = CHAT_ID,
            speakerType = SpeakerType.PERSONA,
            speakerId = PERSONA_ID,
            speakerDisplayNameSnapshot = "Persona",
            content = "Hello",
            createdAt = 30,
            editedAt = 31,
            orderIndex = 1,
            variantGroupId = "persona-variant",
            responsibleParticipantId = PARTICIPANT_ID,
            responsibleDeviceId = deviceMarker,
            coOpEventId = EVENT_ID
        )
        val participant = CoopParticipantEntity(
            PARTICIPANT_ID,
            ROOM_ID,
            deviceMarker,
            "$deviceMarker-key",
            "Host",
            true,
            true,
            63,
            64,
            0
        )
        val codec = CoopCodec(moshi)
        return BackupPayload(
            characters = listOf(character(CHARACTER_ID, "Archive Character")),
            personas = listOf(
                PersonaEntity(
                    personaId = PERSONA_ID,
                    displayName = "Archive Persona",
                    personaName = "Persona",
                    aliases = listOf("P"),
                    importSchemaVersion = 1,
                    identityJson = "{\"pronouns\":\"she/her\"}",
                    appearanceJson = "{\"hair\":\"dark\"}",
                    personalityJson = "{\"summary\":\"steady\"}",
                    roleplayProfileJson = "{\"boundaries\":[\"one\"]}",
                    backgroundJson = "{\"summary\":\"history\"}",
                    worldContextJson = "{\"setting_name\":\"Elsewhere\"}",
                    privateNotesJson = "{\"private\":true}",
                    extensionFieldsJson = "{\"future\":1}",
                    createdAt = 10,
                    updatedAt = 11
                )
            ),
            chats = listOf(ChatEntity(CHAT_ID, "Durable chat", ChatType.GROUP, 20, 21, 1)),
            chatParticipants = listOf(ChatParticipantEntity(CHAT_ID, CHARACTER_ID, 22, true, 0)),
            chatPersonaParticipants = listOf(ChatPersonaParticipantEntity(CHAT_ID, PERSONA_ID, 23, true, 0)),
            messages = listOf(
                personaMessage,
                MessageEntity(
                    messageId = CHARACTER_MESSAGE_ID,
                    chatId = CHAT_ID,
                    speakerType = SpeakerType.CHARACTER,
                    speakerId = CHARACTER_ID,
                    speakerDisplayNameSnapshot = "Archive Character",
                    content = "Response",
                    createdAt = 32,
                    orderIndex = 2,
                    variantGroupId = "character-variant",
                    isPrimaryVariant = false
                )
            ),
            relationships = listOf(
                RelationshipEntity("relationship", CHARACTER_ID, CHARACTER_ID, "Self", "ally", "dynamic", "active", CHAT_ID)
            ),
            chatSettings = listOf(
                ChatSettingsEntity(
                    chatId = CHAT_ID,
                    selectedModelId = "provider/model",
                    providerRoutingMode = ProviderRoutingMode.LOCK,
                    providerEndpoint = "endpoint",
                    temperature = 0.7f,
                    topP = 0.9f,
                    responseLengthProfile = ResponseLengthProfile.CUSTOM,
                    customMin = 100,
                    customTargetMax = 200,
                    customHardMax = 300,
                    draftMessage = "unsent draft",
                    narratorEnabled = true,
                    narratorInstructions = "Narrate carefully"
                )
            ),
            generationMetadata = listOf(
                GenerationMetadataEntity(
                    messageId = CHARACTER_MESSAGE_ID,
                    requestedModelId = "requested",
                    resolvedModelId = "resolved",
                    promptTokens = 10,
                    completionTokens = 20,
                    totalTokens = 30,
                    reasoningTokens = 2,
                    cachedTokens = 3,
                    reportedCost = 0.01,
                    finishReason = "stop",
                    provider = "provider",
                    generationTimeMs = 400,
                    isPartial = true,
                    wasUserStopped = true
                )
            ),
            roleplayMemories = listOf(
                RoleplayMemoryEntity("memory", CHAT_ID, CHARACTER_ID, MemoryCategory.PROMISE, "A promise", PERSONA_MESSAGE_ID, 40, 41)
            ),
            conversationSummaries = listOf(ConversationSummaryEntity(CHAT_ID, "Summary", 2, 42)),
            lorebooks = listOf(LorebookEntity("lorebook", 1, "Lore", "Description", true, "story", CHAT_ID, 50, 51)),
            lorebookEntries = listOf(
                LorebookEntryEntity(
                    entryId = "lore-entry",
                    lorebookId = "lorebook",
                    title = "Entry",
                    keys = listOf("key"),
                    secondaryKeys = listOf("secondary"),
                    canonContent = "Canon",
                    currentContent = "Current",
                    tags = listOf("tag"),
                    notes = "notes",
                    createdAt = 52,
                    updatedAt = 53
                )
            ),
            chatLorebookAttachments = listOf(ChatLorebookAttachmentEntity(CHAT_ID, "lorebook", true, 0, 54)),
            chatLorebookEntryOverlays = listOf(
                ChatLorebookEntryOverlayEntity(CHAT_ID, "lore-entry", true, "Override", true, 55)
            ),
            coopRooms = listOf(
                CoopRoomEntity(
                    roomId = ROOM_ID,
                    chatId = CHAT_ID,
                    localRole = CoopRole.HOST,
                    hostParticipantId = PARTICIPANT_ID,
                    localParticipantId = PARTICIPANT_ID,
                    payerMode = CoopPayerMode.HOST,
                    selectedPayerParticipantId = PARTICIPANT_ID,
                    hostAddress = "192.0.2.1",
                    hostPort = 42424,
                    hostPublicKey = "public-key",
                    hostKeyFingerprint = "fingerprint",
                    pairingSalt = "pairing-salt",
                    pairingCodeHash = "pairing-hash",
                    connectionState = CoopConnectionState.CONNECTED,
                    orderBase = 60,
                    nextHostSequence = 3,
                    lastAppliedSequence = 2,
                    activeGenerationRequestId = "active-request",
                    createdAt = 61,
                    updatedAt = 62
                )
            ),
            coopParticipants = listOf(participant),
            coopPersonaOwnerships = listOf(
                CoopPersonaOwnershipEntity(ROOM_ID, PERSONA_ID, PARTICIPANT_ID, "Persona", true, 0)
            ),
            coopEvents = listOf(
                CoopEventEntity(
                    EVENT_ID,
                    ROOM_ID,
                    1,
                    "client-event",
                    PARTICIPANT_ID,
                    CoopEventType.MESSAGE_UPSERT,
                    codec.messageToJson(CoopMessagePayload.from(personaMessage)),
                    "$deviceMarker-signed-payload",
                    "$deviceMarker-signature",
                    65
                ),
                CoopEventEntity(
                    PARTICIPANT_EVENT_ID,
                    ROOM_ID,
                    2,
                    "participant-client-event",
                    PARTICIPANT_ID,
                    CoopEventType.PARTICIPANT_UPSERT,
                    codec.participantToJson(CoopParticipantDto.from(participant)),
                    "$deviceMarker-participant-payload",
                    "$deviceMarker-participant-signature",
                    66
                )
            ),
            coopMessageTombstones = listOf(CoopMessageTombstoneEntity(ROOM_ID, "deleted-message", 3, 67)),
            messageAttachments = listOf(
                MessageAttachmentEntity(
                    ATTACHMENT_ID,
                    CHAT_ID,
                    PERSONA_MESSAGE_ID,
                    "message_attachments/original.png",
                    "image/png",
                    attachmentBytes.size.toLong(),
                    1,
                    1,
                    sha256Lower(attachmentBytes),
                    0,
                    70
                )
            ),
            npcDossiers = listOf(
                NpcDossierEntity(
                    "npc",
                    CHAT_ID,
                    "NPC",
                    "[\"Alias\"]",
                    "role",
                    "appearance",
                    "personality",
                    "relationship",
                    "{\"$PERSONA_ID\":\"trusted\"}",
                    "{\"$CHARACTER_ID\":\"known\"}",
                    "goals",
                    "secret continuity",
                    "local author notes",
                    true,
                    true,
                    "{\"future\":true}",
                    71,
                    72
                )
            ),
            npcBankSettings = listOf(NpcBankSettingsEntity(CHAT_ID, true)),
            npcDossierUpdates = listOf(
                NpcDossierUpdateEntity(
                    "npc-update",
                    CHAT_ID,
                    "npc",
                    CHARACTER_MESSAGE_ID,
                    "character-variant",
                    2,
                    1,
                    "goals",
                    "old",
                    "new",
                    true,
                    73
                )
            ),
            engineDefaults = listOf(
                EngineDefaultsEntity(selectedModelId = "default/model", temperature = 0.55f)
            ),
            npcLibraryBanks = listOf(
                NpcLibraryBankEntity("library-bank", "Reusable cast", 74, 75)
            ),
            npcLibraryEntries = listOf(
                NpcLibraryEntryEntity(
                    libraryNpcId = "library-npc", bankId = "library-bank", displayName = "Library NPC",
                    role = "advisor", secrets = "private continuity", authorNotes = "private notes", sortPosition = 0,
                    createdAt = 76, updatedAt = 77
                )
            ),
            appearance = BackupAppearanceSettings(ThemeMode.DARK.name, 19f, ColorPalette.FOREST_GREEN.storageId)
        )
    }

    private fun character(id: String, name: String) = CharacterEntity(
        characterId = id,
        displayName = name,
        characterName = name,
        aliases = listOf("Alias"),
        importSchemaVersion = 1,
        identityJson = "{\"role\":\"test\"}",
        appearanceJson = "{\"summary\":\"appearance\"}",
        personalityJson = "{\"summary\":\"personality\"}",
        voiceJson = "{\"cadence\":\"measured\"}",
        behaviorJson = "{\"general\":\"steady\"}",
        backstoryJson = "{\"summary\":\"history\"}",
        knowledgeJson = "{\"facts\":[\"one\"]}",
        worldContextJson = "{\"setting_name\":\"Elsewhere\"}",
        writingRulesJson = "{\"pov\":\"third\"}",
        examplesJson = "{\"dialogue_examples\":[\"Hello\"]}",
        authorNotesJson = "{\"private\":true}",
        extensionFieldsJson = "{\"future\":true}",
        createdAt = 1,
        updatedAt = 2
    )

    private fun writeAvatar(kind: ProfileImageKind, profileId: String, bytes: ByteArray) {
        val file = File(
            context.filesDir,
            "profile_images/${kind.directoryName}/${ProfileImageRepository.storageKey(kind, profileId)}.png"
        )
        file.parentFile!!.mkdirs()
        file.writeBytes(bytes)
    }

    private fun pngBytes(color: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        bitmap.recycle()
        return output.toByteArray()
    }

    private fun excludedRootDomains(resourceId: Int): Set<String> {
        val parser = context.resources.getXml(resourceId)
        val domains = mutableSetOf<String>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (
                parser.eventType == XmlPullParser.START_TAG && parser.name == "exclude" &&
                parser.getAttributeValue(null, "path") == "."
            ) {
                parser.getAttributeValue(null, "domain")?.let(domains::add)
            }
            parser.next()
        }
        parser.close()
        return domains
    }

    private fun BackupAppearanceSettings.toDomain() = AppearanceSettings(
        ThemeMode.valueOf(themeMode),
        fontSize,
        requireNotNull(ColorPalette.fromStorage(colorPalette)),
        legacyAccentOverride
    )

    private fun sha256Lower(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun cleanPrivateBackupFiles() {
        listOf("restore_safety", "message_attachments", "profile_images").forEach {
            File(context.filesDir, it).deleteRecursively()
        }
        context.filesDir.listFiles()
            ?.filter { it.name.startsWith(".profile-images-before-") }
            ?.forEach(File::deleteRecursively)
    }

    private data class Environment(
        val database: AppDatabase,
        val appearance: AppearanceRepository,
        val attachments: MessageAttachmentRepository,
        val profileImages: ProfileImageRepository,
        val repository: BackupRepository
    )

    companion object {
        private const val PASSPHRASE = "correct horse battery staple"
        private const val CHARACTER_ID = "character"
        private const val PERSONA_ID = "persona"
        private const val CHAT_ID = "chat"
        private const val PERSONA_MESSAGE_ID = "persona-message"
        private const val CHARACTER_MESSAGE_ID = "character-message"
        private const val ROOM_ID = "room"
        private const val PARTICIPANT_ID = "participant"
        private const val EVENT_ID = "event"
        private const val PARTICIPANT_EVENT_ID = "participant-event"
        private const val ATTACHMENT_ID = "attachment"
    }
}
