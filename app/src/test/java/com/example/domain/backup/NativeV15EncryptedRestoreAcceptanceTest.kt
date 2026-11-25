package com.example.domain.backup

import android.content.Context
import android.graphics.Bitmap
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.model.*
import com.example.domain.repository.AppearanceRepository
import com.example.domain.repository.MessageAttachmentRepository
import com.example.domain.repository.ProfileImageKind
import com.example.domain.repository.ProfileImageRepository
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Requires a real encrypted export from the pinned v15 BackupRepository, not a
 * "v15-shaped" archive created by the current v21 serializer. Uses synthetic
 * data, isolated Room, short-lived test password, disposable private files.
 */
@RunWith(RobolectricTestRunner::class)
class NativeV15EncryptedRestoreAcceptanceTest {
    private lateinit var context: Context
    private val databases = mutableListOf<AppDatabase>()
    private val scopes = mutableListOf<CoroutineScope>()
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    private companion object {
        const val QA_PASSWORD = "QA_ONLY_random_example_passphrase_not_a_user_secret"
    }

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clean()
    }

    @After fun tearDown() {
        databases.forEach(AppDatabase::close)
        scopes.forEach(CoroutineScope::cancel)
        clean()
    }

    private fun fixture(): ByteArray {
        if (System.getenv("ELSEWHERE_REQUIRE_V15_ENCRYPTED") != "1") {
            assumeTrue("Requires the pinned original v15 encrypted archive CI job", false)
        }
        val path = requireNotNull(System.getenv("ELSEWHERE_V15_ENCRYPTED_PATH"))
        return File(path).also { require(it.isFile && it.length() > 100L) }.readBytes()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun png(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        val bytes = ByteArrayOutputStream().use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
        bitmap.recycle()
        return bytes
    }

    private fun clean() {
        listOf("restore_safety", "message_attachments", "profile_images").forEach {
            File(context.filesDir, it).deleteRecursively()
        }
        context.filesDir.listFiles()?.filter {
            it.name.startsWith(".profile-images-before-") ||
                it.name.startsWith(".profile-images-restore-")
        }?.forEach { it.deleteRecursively() }
    }

    private data class Environment(
        val db: AppDatabase,
        val attachments: MessageAttachmentRepository,
        val profiles: ProfileImageRepository,
        val backups: BackupRepository
    )

    private fun environment(fault: (RestoreStage) -> Unit = {}): Environment {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build().also(databases::add)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also(scopes::add)
        val dsFile = File(context.filesDir,
            "datastore/v15-restore-test-${System.nanoTime()}.preferences_pb")
        val appearance = AppearanceRepository.forDataStore(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { dsFile })
        )
        val attachments = MessageAttachmentRepository(context, db)
        val profiles = ProfileImageRepository(context)
        return Environment(
            db, attachments, profiles,
            BackupRepository(
                context, db, appearance, attachments, profiles, moshi,
                faultInjector = fault
            )
        )
    }

    private suspend fun Environment.seedPreviousState(): Pair<ByteArray, ByteArray> {
        db.characterDao().insertCharacter(CharacterEntity(
            characterId = "previous-character",
            displayName = "Pre-existing Character",
            characterName = "Pre-existing Character",
            aliases = emptyList(), importSchemaVersion = 1,
            identityJson = null, appearanceJson = null, personalityJson = null,
            voiceJson = null, behaviorJson = null, backstoryJson = null,
            knowledgeJson = null, worldContextJson = null, writingRulesJson = null,
            examplesJson = null, authorNotesJson = null, extensionFieldsJson = null
        ))
        db.chatDao().insertChat(ChatEntity(
            chatId = "previous-chat", displayName = "Must survive failed restore",
            chatType = ChatType.SOLO
        ))
        db.messageDao().insertMessage(MessageEntity(
            messageId = "previous-message", chatId = "previous-chat",
            speakerType = SpeakerType.PERSONA, speakerId = "previous-persona",
            speakerDisplayNameSnapshot = "Previous player",
            content = "PREVIOUS_PRIVATE_TRANSCRIPT_UNCHANGED", orderIndex = 1L
        ))
        db.memoryDao().upsertSummary(ConversationSummaryEntity(
            chatId = "previous-chat", summary = "PREVIOUS_PRIVATE_SUMMARY",
            summarizedThroughOrderIndex = 1L
        ))
        val image = png(0xFFDD6633.toInt())
        val avatar = png(0xFF66DD33.toInt())
        val relative = "message_attachments/previous-image.png"
        File(context.filesDir, relative).apply {
            parentFile!!.mkdirs()
            writeBytes(image)
        }
        db.messageAttachmentDao().insert(MessageAttachmentEntity(
            attachmentId = "previous-attachment",
            chatId = "previous-chat", messageId = "previous-message",
            relativePath = relative, mimeType = "image/png",
            byteSize = image.size.toLong(), pixelWidth = 1, pixelHeight = 1,
            sha256 = sha256(image), sortPosition = 0
        ))
        File(context.filesDir,
            "profile_images/${ProfileImageKind.CHARACTER.directoryName}/" +
                "${ProfileImageRepository.storageKey(ProfileImageKind.CHARACTER, "previous-character")}.png"
        ).apply {
            parentFile!!.mkdirs()
            writeBytes(avatar)
        }
        return image to avatar
    }

    private suspend fun Environment.assertPreviousState(
        originalImage: ByteArray,
        originalAvatar: ByteArray
    ) {
        assertEquals("Must survive failed restore",
            db.chatDao().getChatById("previous-chat")?.displayName)
        assertEquals("PREVIOUS_PRIVATE_TRANSCRIPT_UNCHANGED",
            db.messageDao().getMessageById("previous-message")?.content)
        assertEquals("PREVIOUS_PRIVATE_SUMMARY",
            db.memoryDao().getSummaryOnce("previous-chat")?.summary)
        assertEquals(null, db.chatDao().getChatById("v15-story"))
        val previous = db.messageAttachmentDao().getForChat("previous-chat").single()
        assertArrayEquals(originalImage, attachments.resolveFile(previous)!!.readBytes())
        assertArrayEquals(originalAvatar,
            profiles.resolveImage(ProfileImageKind.CHARACTER, "previous-character")!!.readBytes())
    }

    @Test fun genuineV15CiphertextInspectAndRestorePreservesHistoryAndBinaryImages() = runBlocking {
        val cipher = fixture()
        val initialDigest = sha256(cipher)
        val target = environment()
        val inspection = target.backups.inspectEncryptedForRestore(
            ByteArrayInputStream(cipher), QA_PASSWORD.toCharArray()
        )
        assertEquals(15, inspection.preview.databaseVersion)
        assertEquals(1, inspection.preview.chats)
        assertEquals(3, inspection.preview.messages)
        assertEquals(1, inspection.preview.memories)
        assertEquals(1, inspection.preview.messageAttachments)
        assertTrue(inspection.preview.profileImages > 0)
        assertEquals(initialDigest, inspection.encryptedSha256.lowercase())
        val result = target.backups.restoreEncrypted(
            ByteArrayInputStream(cipher), QA_PASSWORD.toCharArray(),
            expectedEncryptedSha256 = inspection.encryptedSha256
        )
        assertEquals(1, result.restoredChats)
        assertEquals(3, result.restoredMessages)
        assertTrue(result.restoredFiles >= 2)
        assertTrue(target.backups.hasSafetyBackup())
        assertFalse(target.backups.hasInterruptedRestore())

        val stored = target.db.chatDao().getChatById("v15-story")
        assertEquals("Old Lantern Story", stored?.displayName)
        assertEquals(3L, stored?.contextResetOrderIndex)
        val variants = target.db.messageDao().getVariantsOnce("v15-variant-group")
        assertEquals(2, variants.size)
        assertEquals("v15-selected", variants.single { it.isPrimaryVariant }.messageId)
        assertEquals("I found the silver key.",
            target.db.messageDao().getMessageById("v15-selected")?.content)
        assertEquals("The silver key stays with Mara.",
            target.db.memoryDao().getMemoriesOnce("v15-story", "v15-rowan").single().content)
        assertEquals("The silver key was discovered.",
            target.db.memoryDao().getSummaryOnce("v15-story")?.summary)
        assertEquals("UNSENT_V15_PLAYER_DRAFT",
            target.db.chatDao().getChatSettings("v15-story")?.draftMessage)
        assertEquals("Lantern Lore", target.db.lorebookDao().getLorebooksOnce().single().name)
        assertEquals("Lantern World", target.db.worldDao().getWorlds().single().title)
        assertEquals("Keeper", target.db.npcDao().getDossiers("v15-story").single().displayName)
        assertTrue(target.db.storyMemoryDao().getAll().isEmpty())
        assertTrue(target.db.storyMemoryDao().getAllKnowledgeChanges().isEmpty())
        assertTrue(target.db.storyMemoryDao().getAllPerspectives().isEmpty())

        val image = target.db.messageAttachmentDao().getForChat("v15-story").single()
        assertEquals("v15-photo", image.attachmentId)
        assertTrue("Restored image must get a managed, fresh path",
            image.relativePath.startsWith("message_attachments/restore-"))
        val file = target.attachments.resolveFile(image)
        assertTrue(file != null && file.isFile)
        assertEquals(image.byteSize, file!!.length())
        assertEquals(image.sha256, sha256(file.readBytes()))
        assertNotNull(target.profiles.resolveImage(ProfileImageKind.CHARACTER, "v15-rowan"))
        assertEquals(initialDigest, sha256(cipher))
        println(
            "V15_CIPHERTEXT_TO_V21_RESTORE_PASS messages=3 variants=2 " +
                "images=2 legacyMemory=1 wackPreserved=true noInventedStoryMemory=true"
        )
    }

    @Test fun wrongPasswordTamperAndChangedAfterInspectionNeverReplaceExistingData() = runBlocking {
        val cipher = fixture()
        val target = environment()
        val (image, avatar) = target.seedPreviousState()
        val good = target.backups.inspectEncryptedForRestore(
            ByteArrayInputStream(cipher), QA_PASSWORD.toCharArray()
        )
        val wrongPass = runCatching {
            target.backups.restoreEncrypted(
                ByteArrayInputStream(cipher),
                "QA_ONLY_WRONG_PASSWORD_NOT_A_SECRET".toCharArray()
            )
        }.exceptionOrNull()
        assertTrue(wrongPass is BackupValidationException)
        val changed = cipher.copyOf().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        val corrupted = runCatching {
            target.backups.restoreEncrypted(
                ByteArrayInputStream(changed), QA_PASSWORD.toCharArray()
            )
        }.exceptionOrNull()
        assertTrue(corrupted is BackupValidationException)
        val digestMismatch = runCatching {
            target.backups.restoreEncrypted(
                ByteArrayInputStream(cipher), QA_PASSWORD.toCharArray(),
                expectedEncryptedSha256 = "0".repeat(64)
            )
        }.exceptionOrNull()
        assertTrue(digestMismatch is BackupValidationException)
        assertTrue(good.encryptedSha256.isNotBlank())
        assertFalse("Rejected ciphertext must not trigger data replacement",
            target.backups.hasSafetyBackup())
        target.assertPreviousState(image, avatar)
        println("V15_CIPHERTEXT_PREVALIDATION_FAILURES_PASS wrongPassword=true tamper=true shaGate=true")
    }

    private suspend fun assertRecoveryAt(stage: RestoreStage) {
        val cipher = fixture()
        val initialDigest = sha256(cipher)
        val target = environment { step ->
            if (step == stage) error("SYNTHETIC_RESTORE_INTERRUPTION_${stage.name}")
        }
        val (image, avatar) = target.seedPreviousState()
        val failure = runCatching {
            target.backups.restoreEncrypted(
                ByteArrayInputStream(cipher), QA_PASSWORD.toCharArray()
            )
        }.exceptionOrNull()
        assertTrue(failure is BackupValidationException)
        assertTrue(failure?.message.orEmpty().contains("recovered"))
        assertTrue(target.backups.hasSafetyBackup())
        assertFalse(target.backups.hasInterruptedRestore())
        target.assertPreviousState(image, avatar)
        val recoveredAgain = target.backups.recoverLastRestore()
        assertTrue(recoveredAgain.safetyBackupAvailable)
        target.assertPreviousState(image, avatar)
        assertEquals(initialDigest, sha256(cipher))
        println("V15_CIPHERTEXT_ROLLBACK_PASS phase=${stage.name} " +
            "previousChat=true previousImage=true previousAvatar=true")
    }

    @Test fun genuineV15EncryptedRestoreFailureAfterDatabaseReplacementRecoversExactPreviousData() =
        runBlocking { assertRecoveryAt(RestoreStage.AFTER_DATABASE_REPLACED) }

    @Test fun genuineV15EncryptedRestoreFailureAfterProfileReplacementRecoversExactPreviousData() =
        runBlocking { assertRecoveryAt(RestoreStage.AFTER_PROFILE_IMAGES_REPLACED) }
}
