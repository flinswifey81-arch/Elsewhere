package com.example.domain.backup

import android.content.Context
import android.graphics.BitmapFactory
import androidx.room.withTransaction
import com.example.BuildConfig
import com.example.data.APP_DATABASE_VERSION
import com.example.data.AppDatabase
import com.example.data.model.*
import com.example.domain.repository.AppearanceRepository
import com.example.domain.repository.AppearanceSettings
import com.example.domain.repository.AppDataMaintenanceCoordinator
import com.example.domain.repository.ColorPalette
import com.example.domain.repository.MessageAttachmentRepository
import com.example.domain.repository.ProfileImageKind
import com.example.domain.repository.ProfileImageRepository
import com.example.domain.repository.ThemeMode
import com.example.domain.coop.CoopCodec
import com.squareup.moshi.Moshi
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class BackupRepository internal constructor(
    private val context: Context,
    private val database: AppDatabase,
    private val appearanceRepository: AppearanceRepository,
    private val messageAttachmentRepository: MessageAttachmentRepository,
    private val profileImageRepository: ProfileImageRepository,
    moshi: Moshi,
    private val faultInjector: (RestoreStage) -> Unit = {},
    private val dataCoordinator: AppDataMaintenanceCoordinator = AppDataMaintenanceCoordinator.Global,
    private val snapshotHook: suspend () -> Unit = {},
    private val beforeDataReplacement: suspend () -> Unit = {}
) {
    private val archiveIO = BackupArchiveIO(
        moshi,
        buildSet {
            add(BuildConfig.APPLICATION_ID)
            BuildConfig.BACKUP_IMPORT_SOURCE_APPLICATION_ID
                .takeIf(String::isNotBlank)
                ?.let(::add)
        }
    )
    private val coopCodec = CoopCodec(moshi)
    private val backupDao = database.backupDao()
    private val operationMutex = Mutex()
    private val safetyDirectory = File(context.filesDir, SAFETY_DIRECTORY)
    private val safetyBackupFile = File(safetyDirectory, SAFETY_BACKUP_NAME)
    private val restoreMarkerFile = File(safetyDirectory, RESTORE_MARKER_NAME)

    suspend fun exportEncrypted(output: OutputStream, passphrase: CharArray): BackupPreview =
        withContext(Dispatchers.IO) {
            operationMutex.withLock {
                val plain = File.createTempFile("elsewhere-export-", ".zip", context.cacheDir)
                var snapshot: BackupSnapshot? = null
                try {
                    snapshot = createSnapshot(BackupSnapshotMode.PORTABLE)
                    FileOutputStream(plain).use { archiveIO.writePlainZip(snapshot, it) }
                    validatePlainArchive(plain)
                    FileInputStream(plain).use { input -> BackupCrypto.encrypt(input, output, passphrase) }
                    snapshot.toPreview()
                } finally {
                    passphrase.fill('\u0000')
                    snapshot?.stagingDirectory?.deleteRecursively()
                    plain.delete()
                }
            }
        }

    suspend fun inspectEncrypted(input: InputStream, passphrase: CharArray): BackupPreview =
        inspectEncryptedForRestore(input, passphrase).preview

    suspend fun inspectEncryptedForRestore(input: InputStream, passphrase: CharArray): BackupInspection =
        withContext(Dispatchers.IO) {
            operationMutex.withLock {
                val validated = decryptAndValidate(input, passphrase)
                try {
                    BackupInspection(
                        preview = validated.toPreview(),
                        encryptedSha256 = requireNotNull(validated.encryptedSha256)
                    )
                } finally {
                    validated.workingDirectory.deleteRecursively()
                }
            }
        }

    suspend fun restoreEncrypted(
        input: InputStream,
        passphrase: CharArray,
        expectedEncryptedSha256: String? = null
    ): BackupRestoreResult =
        withContext(Dispatchers.IO) {
            operationMutex.withLock {
                val validated = decryptAndValidate(input, passphrase)
                try {
                    if (
                        expectedEncryptedSha256 != null &&
                        !expectedEncryptedSha256.equals(validated.encryptedSha256, ignoreCase = true)
                    ) {
                        throw BackupValidationException(
                            "The selected backup changed after it was reviewed. Validate it again before restoring."
                        )
                    }
                    dataCoordinator.withDataReplacement {
                        beforeDataReplacement()
                        validatePayload(validated.payload, validated.manifest)
                        validateExtractedFiles(validated)
                        writeSafetyBackup()
                        restoreMarkerFile.parentFile?.mkdirs()
                        restoreMarkerFile.writeText("restore_started_at=${System.currentTimeMillis()}\n")
                        faultInjector(RestoreStage.AFTER_SAFETY_BACKUP)
                        try {
                            val result = applyValidatedBackup(
                                validated,
                                injectFaults = true,
                                sanitizePortable = true
                            )
                            restoreMarkerFile.delete()
                            result.copy(safetyBackupAvailable = safetyBackupFile.isFile)
                        } catch (restoreError: Exception) {
                            val recoveryAttempt = runCatching { applySafetyBackupInternal() }
                            val recovered = recoveryAttempt.isSuccess
                            recoveryAttempt.exceptionOrNull()?.let(restoreError::addSuppressed)
                            if (recovered) restoreMarkerFile.delete()
                            val message = if (recovered) {
                                "Restore failed and the previous local data was recovered."
                            } else {
                                "Restore was interrupted. The pre-restore safety copy is still available."
                            }
                            throw BackupValidationException(message, restoreError)
                        }
                    }
                } finally {
                    validated.workingDirectory.deleteRecursively()
                }
            }
        }

    suspend fun recoverLastRestore(): BackupRestoreResult = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            if (!safetyBackupFile.isFile) throw BackupValidationException("No pre-restore safety copy is available.")
            dataCoordinator.withDataReplacement {
                beforeDataReplacement()
                val result = applySafetyBackupInternal()
                restoreMarkerFile.delete()
                result.copy(safetyBackupAvailable = true)
            }
        }
    }

    fun hasSafetyBackup(): Boolean = safetyBackupFile.isFile

    fun hasInterruptedRestore(): Boolean = restoreMarkerFile.isFile && safetyBackupFile.isFile

    private suspend fun createSnapshot(mode: BackupSnapshotMode): BackupSnapshot =
        dataCoordinator.withSnapshotMaintenance { createSnapshotLocked(mode) }

    private suspend fun createSnapshotLocked(mode: BackupSnapshotMode): BackupSnapshot {
        val appearance = appearanceRepository.appearanceSettings.first()
        val portable = mode == BackupSnapshotMode.PORTABLE
        val payload = database.withTransaction {
            BackupPayload(
                characters = backupDao.getCharacters(),
                scenarios = database.scenarioDao().getAll(),
                scenarioSessions = database.scenarioDao().sessions(),
                personas = backupDao.getPersonas(),
                chats = backupDao.getChats(),
                chatParticipants = backupDao.getChatParticipants(),
                chatPersonaParticipants = backupDao.getChatPersonaParticipants(),
                messages = backupDao.getMessages().map {
                    if (portable) it.copy(responsibleDeviceId = null) else it
                },
                relationships = backupDao.getRelationships(),
                chatSettings = backupDao.getChatSettings(),
                generationMetadata = backupDao.getGenerationMetadata(),
                roleplayMemories = backupDao.getRoleplayMemories(),
                conversationSummaries = backupDao.getConversationSummaries(),
                lorebooks = backupDao.getLorebooks(),
                lorebookEntries = backupDao.getLorebookEntries(),
                chatLorebookAttachments = backupDao.getChatLorebookAttachments(),
                chatLorebookEntryOverlays = backupDao.getChatLorebookEntryOverlays(),
                coopRooms = backupDao.getCoopRooms().map { if (portable) sanitizeCoopRoom(it) else it },
                coopParticipants = backupDao.getCoopParticipants().map {
                    if (portable) sanitizeCoopParticipant(it) else it
                },
                coopPersonaOwnerships = backupDao.getCoopPersonaOwnerships(),
                coopEvents = backupDao.getCoopEvents().map { if (portable) sanitizeCoopEvent(it) else it },
                coopMessageTombstones = backupDao.getCoopMessageTombstones(),
                messageAttachments = backupDao.getMessageAttachments(),
                npcDossiers = backupDao.getNpcDossiers(),
                npcBankSettings = backupDao.getNpcBankSettings(),
                npcDossierUpdates = backupDao.getNpcDossierUpdates(),
                appearance = appearance.toBackup()
            )
        }
        snapshotHook()
        val stagingDirectory = File(context.cacheDir, "elsewhere-snapshot-${UUID.randomUUID()}")
        try {
            val binaries = collectBinarySources(payload, stagingDirectory)
            val payloadJson = archiveIO.payloadToJson(payload)
            val manifest = BackupManifest(
                formatVersion = BackupArchiveIO.FORMAT_VERSION,
                applicationId = BuildConfig.APPLICATION_ID,
                appVersionCode = BuildConfig.VERSION_CODE.toLong(),
                appVersionName = BuildConfig.VERSION_NAME,
                databaseVersion = APP_DATABASE_VERSION,
                createdAt = System.currentTimeMillis(),
                payloadSha256 = sha256(payloadJson.toByteArray(Charsets.UTF_8)),
                files = binaries.map { it.manifest },
                excludedCategories = if (portable) EXCLUDED_CATEGORIES else emptyList(),
                coopRestoreMode = if (portable) COOP_RESTORE_MODE else EXACT_RECOVERY_MODE
            )
            validatePayload(payload, manifest)
            return BackupSnapshot(manifest, payload, payloadJson, binaries, stagingDirectory)
        } catch (error: Exception) {
            stagingDirectory.deleteRecursively()
            throw error
        }
    }

    private fun collectBinarySources(payload: BackupPayload, stagingDirectory: File): List<BackupBinarySource> {
        if (!stagingDirectory.mkdirs() && !stagingDirectory.isDirectory) {
            throw BackupValidationException("Backup staging space is unavailable.")
        }
        val result = mutableListOf<BackupBinarySource>()
        payload.messageAttachments.forEach { attachment ->
            val source = messageAttachmentRepository.resolveFile(attachment)
                ?: throw BackupValidationException(
                    "Image attachment ${attachment.attachmentId} is missing. Remove or repair it before backup."
                )
            val staged = File(stagingDirectory, "attachment-${safeToken(attachment.attachmentId)}")
            source.copyTo(staged, overwrite = false)
            val actualHash = BackupArchiveIO.sha256(staged)
            if (staged.length() != attachment.byteSize || !actualHash.equals(attachment.sha256, ignoreCase = true)) {
                throw BackupValidationException("Image attachment ${attachment.attachmentId} failed integrity validation.")
            }
            val extension = when (attachment.mimeType) {
                "image/png" -> "png"
                "image/jpeg" -> "jpg"
                else -> "bin"
            }
            val entryName = "files/message_attachments/${safeToken(attachment.attachmentId)}.$extension"
            result += BackupBinarySource(
                BackupFileManifest(
                    entryName = entryName,
                    kind = BackupFileKind.MESSAGE_ATTACHMENT,
                    ownerId = attachment.attachmentId,
                    byteSize = staged.length(),
                    sha256 = actualHash,
                    mimeType = attachment.mimeType
                ),
                staged
            )
        }
        payload.scenarios.forEach { scenario ->
            profileImageRepository.resolveImage(ProfileImageKind.SCENARIO, scenario.scenarioId)?.let { source ->
                result += profileSource(BackupFileKind.SCENARIO_AVATAR, scenario.scenarioId, source, stagingDirectory)
            }
        }
        payload.characters.forEach { character ->
            profileImageRepository.resolveImage(ProfileImageKind.CHARACTER, character.characterId)?.let { source ->
                result += profileSource(
                    BackupFileKind.CHARACTER_AVATAR,
                    character.characterId,
                    source,
                    stagingDirectory
                )
            }
        }
        payload.personas.forEach { persona ->
            profileImageRepository.resolveImage(ProfileImageKind.PERSONA, persona.personaId)?.let { source ->
                result += profileSource(
                    BackupFileKind.PERSONA_AVATAR,
                    persona.personaId,
                    source,
                    stagingDirectory
                )
            }
        }
        return result.sortedBy { it.manifest.entryName }
    }

    private fun profileSource(
        kind: BackupFileKind,
        ownerId: String,
        source: File,
        stagingDirectory: File
    ): BackupBinarySource {
        val directory = when (kind) { BackupFileKind.SCENARIO_AVATAR -> "scenarios"; BackupFileKind.CHARACTER_AVATAR -> "characters"; else -> "personas" }
        val staged = File(stagingDirectory, "avatar-${kind.name.lowercase()}-${safeToken(ownerId)}")
        source.copyTo(staged, overwrite = false)
        return BackupBinarySource(
            BackupFileManifest(
                entryName = "files/profile_images/$directory/${safeToken(ownerId)}.png",
                kind = kind,
                ownerId = ownerId,
                byteSize = staged.length(),
                sha256 = BackupArchiveIO.sha256(staged),
                mimeType = "image/png"
            ),
            staged
        )
    }

    private fun decryptAndValidate(input: InputStream, passphrase: CharArray): ValidatedBackup {
        val plain = File.createTempFile("elsewhere-import-", ".zip", context.cacheDir)
        val workingDirectory = File(context.cacheDir, "elsewhere-validate-${UUID.randomUUID()}")
        val encryptedDigest = MessageDigest.getInstance("SHA-256")
        try {
            FileOutputStream(plain).use { output ->
                DigestInputStream(input, encryptedDigest).use { measuredInput ->
                    BackupCrypto.decrypt(
                        measuredInput,
                        BoundedOutputStream(output, BackupArchiveIO.MAX_TOTAL_BYTES),
                        passphrase
                    )
                }
            }
            val validated = FileInputStream(plain).use { archiveIO.readPlainZip(it, workingDirectory) }
            validatePayload(validated.payload, validated.manifest)
            validateExtractedFiles(validated)
            return validated.copy(encryptedSha256 = encryptedDigest.digest().toHex())
        } catch (error: Exception) {
            workingDirectory.deleteRecursively()
            throw error
        } finally {
            passphrase.fill('\u0000')
            plain.delete()
        }
    }

    private suspend fun writeSafetyBackup() {
        safetyDirectory.mkdirs()
        val temporary = File(safetyDirectory, "$SAFETY_BACKUP_NAME.${UUID.randomUUID()}.tmp")
        var snapshot: BackupSnapshot? = null
        try {
            snapshot = createSnapshot(BackupSnapshotMode.EXACT_PRIVATE_RECOVERY)
            FileOutputStream(temporary).use { archiveIO.writePlainZip(snapshot, it) }
            validatePlainArchive(temporary)
            moveReplacing(temporary, safetyBackupFile)
        } finally {
            snapshot?.stagingDirectory?.deleteRecursively()
            temporary.delete()
        }
    }

    private suspend fun applySafetyBackupInternal(): BackupRestoreResult {
        val workingDirectory = File(context.cacheDir, "elsewhere-safety-${UUID.randomUUID()}")
        return try {
            val validated = FileInputStream(safetyBackupFile).use { archiveIO.readPlainZip(it, workingDirectory) }
            validatePayload(validated.payload, validated.manifest)
            validateExtractedFiles(validated)
            if (validated.manifest.coopRestoreMode != EXACT_RECOVERY_MODE) {
                throw BackupValidationException("The pre-restore safety copy is not an exact private recovery archive.")
            }
            applyValidatedBackup(validated, injectFaults = false, sanitizePortable = false)
        } finally {
            workingDirectory.deleteRecursively()
        }
    }

    private suspend fun applyValidatedBackup(
        validated: ValidatedBackup,
        injectFaults: Boolean,
        sanitizePortable: Boolean
    ): BackupRestoreResult {
        val session = UUID.randomUUID().toString()
        val createdAttachmentFiles = mutableListOf<File>()
        val profileStage = File(context.cacheDir, ".profile-images-restore-$session")
        try {
            val attachmentFiles = validated.manifest.files.filter { it.kind == BackupFileKind.MESSAGE_ATTACHMENT }
                .associateBy { it.ownerId }
            val restoredAttachments = validated.payload.messageAttachments.map { attachment ->
                val fileManifest = attachmentFiles[attachment.attachmentId]
                    ?: throw BackupValidationException("Attachment data is missing for ${attachment.attachmentId}.")
                val source = requireNotNull(validated.extractedFiles[fileManifest.entryName])
                val relativePath = if (sanitizePortable) {
                    val extension = when (attachment.mimeType) {
                        "image/png" -> "png"
                        "image/jpeg" -> "jpg"
                        "image/webp" -> "webp"
                        else -> throw BackupValidationException("Unsupported attachment media type.")
                    }
                    "message_attachments/restore-$session-${safeToken(attachment.attachmentId)}.$extension"
                } else {
                    attachment.relativePath
                }
                val destination = safeAppFile(relativePath, "message_attachments")
                destination.parentFile?.mkdirs()
                if (sanitizePortable) {
                    if (destination.exists()) throw BackupValidationException("Restore file collision detected.")
                    source.copyTo(destination, overwrite = false)
                    createdAttachmentFiles += destination
                } else if (
                    !destination.isFile || destination.length() != source.length() ||
                    BackupArchiveIO.sha256(destination) != fileManifest.sha256
                ) {
                    val replacement = File(destination.parentFile, ".${destination.name}.$session.tmp")
                    source.copyTo(replacement, overwrite = false)
                    moveReplacing(replacement, destination)
                }
                attachment.copy(relativePath = relativePath)
            }
            prepareProfileImages(validated, profileStage)
            if (injectFaults) faultInjector(RestoreStage.AFTER_FILES_STAGED)

            val restoredPayload = if (sanitizePortable) {
                validated.payload.copy(
                    messages = validated.payload.messages.map { it.copy(responsibleDeviceId = null) },
                    coopRooms = validated.payload.coopRooms.map(::sanitizeCoopRoom),
                    coopParticipants = validated.payload.coopParticipants.map(::sanitizeCoopParticipant),
                    coopEvents = validated.payload.coopEvents.map(::sanitizeCoopEvent),
                    messageAttachments = restoredAttachments
                )
            } else {
                validated.payload.copy(messageAttachments = restoredAttachments)
            }
            database.withTransaction { replaceDatabaseContents(restoredPayload) }
            if (injectFaults) faultInjector(RestoreStage.AFTER_DATABASE_REPLACED)

            replaceProfileImages(profileStage, session)
            if (injectFaults) faultInjector(RestoreStage.AFTER_PROFILE_IMAGES_REPLACED)
            appearanceRepository.replaceSettings(restoredPayload.appearance.toDomain())
            messageAttachmentRepository.reconcileOrphanFiles()
            return BackupRestoreResult(
                restoredChats = restoredPayload.chats.size,
                restoredMessages = restoredPayload.messages.size,
                restoredFiles = validated.manifest.files.size,
                safetyBackupAvailable = safetyBackupFile.isFile
            )
        } catch (error: Exception) {
            createdAttachmentFiles.forEach(File::delete)
            profileStage.deleteRecursively()
            throw error
        }
    }

    private suspend fun replaceDatabaseContents(payload: BackupPayload) {
        database.scenarioDao().clearSessions()
        database.scenarioDao().clearScenarios()
        backupDao.deleteGenerationMetadata()
        backupDao.deleteMessageAttachments()
        backupDao.deleteNpcDossierUpdates()
        backupDao.deleteNpcDossiers()
        backupDao.deleteNpcBankSettings()
        backupDao.deleteCoopMessageTombstones()
        backupDao.deleteCoopEvents()
        backupDao.deleteCoopPersonaOwnerships()
        backupDao.deleteCoopParticipants()
        backupDao.deleteCoopRooms()
        backupDao.deleteChatLorebookEntryOverlays()
        backupDao.deleteChatLorebookAttachments()
        backupDao.deleteLorebookEntries()
        backupDao.deleteLorebooks()
        backupDao.deleteConversationSummaries()
        backupDao.deleteRoleplayMemories()
        backupDao.deleteRelationships()
        backupDao.deleteMessages()
        backupDao.deleteChatSettings()
        backupDao.deleteChatParticipants()
        backupDao.deleteChatPersonaParticipants()
        backupDao.deleteChats()
        backupDao.deleteCharacters()
        backupDao.deletePersonas()

        if (payload.characters.isNotEmpty()) backupDao.insertCharacters(payload.characters)
        if (payload.personas.isNotEmpty()) backupDao.insertPersonas(payload.personas)
        if (payload.chats.isNotEmpty()) backupDao.insertChats(payload.chats)
        payload.scenarios.forEach { database.scenarioDao().save(it) }
        payload.scenarioSessions.forEach { database.scenarioDao().insertSession(it) }
        if (payload.chatParticipants.isNotEmpty()) backupDao.insertChatParticipants(payload.chatParticipants)
        if (payload.chatPersonaParticipants.isNotEmpty()) {
            backupDao.insertChatPersonaParticipants(payload.chatPersonaParticipants)
        }
        if (payload.messages.isNotEmpty()) backupDao.insertMessages(payload.messages)
        if (payload.relationships.isNotEmpty()) backupDao.insertRelationships(payload.relationships)
        if (payload.chatSettings.isNotEmpty()) backupDao.insertChatSettings(payload.chatSettings)
        if (payload.generationMetadata.isNotEmpty()) backupDao.insertGenerationMetadata(payload.generationMetadata)
        if (payload.roleplayMemories.isNotEmpty()) backupDao.insertRoleplayMemories(payload.roleplayMemories)
        if (payload.conversationSummaries.isNotEmpty()) {
            backupDao.insertConversationSummaries(payload.conversationSummaries)
        }
        if (payload.lorebooks.isNotEmpty()) backupDao.insertLorebooks(payload.lorebooks)
        if (payload.lorebookEntries.isNotEmpty()) backupDao.insertLorebookEntries(payload.lorebookEntries)
        if (payload.chatLorebookAttachments.isNotEmpty()) {
            backupDao.insertChatLorebookAttachments(payload.chatLorebookAttachments)
        }
        if (payload.chatLorebookEntryOverlays.isNotEmpty()) {
            backupDao.insertChatLorebookEntryOverlays(payload.chatLorebookEntryOverlays)
        }
        if (payload.coopRooms.isNotEmpty()) backupDao.insertCoopRooms(payload.coopRooms)
        if (payload.coopParticipants.isNotEmpty()) backupDao.insertCoopParticipants(payload.coopParticipants)
        if (payload.coopPersonaOwnerships.isNotEmpty()) {
            backupDao.insertCoopPersonaOwnerships(payload.coopPersonaOwnerships)
        }
        if (payload.coopEvents.isNotEmpty()) backupDao.insertCoopEvents(payload.coopEvents)
        if (payload.coopMessageTombstones.isNotEmpty()) {
            backupDao.insertCoopMessageTombstones(payload.coopMessageTombstones)
        }
        if (payload.messageAttachments.isNotEmpty()) backupDao.insertMessageAttachments(payload.messageAttachments)
        if (payload.npcDossiers.isNotEmpty()) backupDao.insertNpcDossiers(payload.npcDossiers)
        if (payload.npcBankSettings.isNotEmpty()) backupDao.insertNpcBankSettings(payload.npcBankSettings)
        if (payload.npcDossierUpdates.isNotEmpty()) backupDao.insertNpcDossierUpdates(payload.npcDossierUpdates)
    }

    private fun prepareProfileImages(validated: ValidatedBackup, stageRoot: File) {
        stageRoot.mkdirs()
        validated.manifest.files.filter {
            it.kind in listOf(BackupFileKind.CHARACTER_AVATAR, BackupFileKind.PERSONA_AVATAR, BackupFileKind.SCENARIO_AVATAR)
        }.forEach { fileManifest ->
            val kind = if (fileManifest.kind == BackupFileKind.SCENARIO_AVATAR) {
                ProfileImageKind.SCENARIO
            } else if (fileManifest.kind == BackupFileKind.CHARACTER_AVATAR) {
                ProfileImageKind.CHARACTER
            } else {
                ProfileImageKind.PERSONA
            }
            val source = requireNotNull(validated.extractedFiles[fileManifest.entryName])
            val directory = File(stageRoot, kind.directoryName).apply { mkdirs() }
            val destination = File(directory, "${ProfileImageRepository.storageKey(kind, fileManifest.ownerId)}.png")
            source.copyTo(destination, overwrite = false)
        }
    }

    private fun replaceProfileImages(stageRoot: File, session: String) {
        val current = File(context.filesDir, "profile_images")
        val old = File(context.filesDir, ".profile-images-before-$session")
        if (old.exists()) throw BackupValidationException("Profile-image rollback path already exists.")
        var movedCurrent = false
        try {
            if (current.exists()) {
                moveReplacing(current, old)
                movedCurrent = true
            }
            moveReplacing(stageRoot, current)
            old.deleteRecursively()
        } catch (error: Exception) {
            current.deleteRecursively()
            if (movedCurrent && old.exists()) moveReplacing(old, current)
            throw BackupValidationException("Profile images could not be restored safely.", error)
        }
    }

    private fun validatePayload(payload: BackupPayload, manifest: BackupManifest) {
        val entityCount = listOf(
            payload.scenarios.size,
            payload.scenarioSessions.size,
            payload.characters.size,
            payload.personas.size,
            payload.chats.size,
            payload.chatParticipants.size,
            payload.chatPersonaParticipants.size,
            payload.messages.size,
            payload.relationships.size,
            payload.chatSettings.size,
            payload.generationMetadata.size,
            payload.roleplayMemories.size,
            payload.conversationSummaries.size,
            payload.lorebooks.size,
            payload.lorebookEntries.size,
            payload.chatLorebookAttachments.size,
            payload.chatLorebookEntryOverlays.size,
            payload.coopRooms.size,
            payload.coopParticipants.size,
            payload.coopPersonaOwnerships.size,
            payload.coopEvents.size,
            payload.coopMessageTombstones.size,
            payload.messageAttachments.size,
            payload.npcDossiers.size,
            payload.npcBankSettings.size,
            payload.npcDossierUpdates.size
        ).sumOf(Int::toLong)
        if (entityCount > MAX_TOTAL_ENTITY_RECORDS || payload.messages.size > MAX_MESSAGE_RECORDS) {
            throw BackupValidationException("Backup contains too many data records.")
        }
        fun unique(label: String, values: List<String>): Set<String> {
            if (values.any(String::isBlank) || values.toSet().size != values.size) {
                throw BackupValidationException("Backup contains invalid or duplicate $label identifiers.")
            }
            return values.toSet()
        }
        val scenarioIds = unique("scenario", payload.scenarios.map { it.scenarioId })
        unique("scenario session", payload.scenarioSessions.map { it.chatId })
        (payload.scenarios.map { it.definitionJson } + payload.scenarioSessions.map { it.definitionJson }).forEach {
            try { com.example.data.model.ScenarioCodec.decode(it).validate() }
            catch (e: Exception) { throw BackupValidationException("Backup contains an invalid scenario.", e) }
        }
        val scenarioChatIds = payload.chats.filter { it.chatType == com.example.data.model.ChatType.SCENARIO }.map { it.chatId }.toSet()
        if (payload.scenarioSessions.map { it.chatId }.toSet() != scenarioChatIds) {
            throw BackupValidationException("Scenario playthrough configuration is missing or mismatched.")
        }
        val characterIds = unique("Character", payload.characters.map { it.characterId })
        val personaIds = unique("Persona", payload.personas.map { it.personaId })
        val chatIds = unique("chat", payload.chats.map { it.chatId })
        val messageIds = unique("message", payload.messages.map { it.messageId })
        val lorebookIds = unique("Lorebook", payload.lorebooks.map { it.lorebookId })
        val loreEntryIds = unique("Lorebook entry", payload.lorebookEntries.map { it.entryId })
        val roomIds = unique("co-op room", payload.coopRooms.map { it.roomId })
        val participantIds = unique("co-op participant", payload.coopParticipants.map { it.participantId })
        val attachmentIds = unique("attachment", payload.messageAttachments.map { it.attachmentId })
        val npcIds = unique("NPC", payload.npcDossiers.map { it.npcId })
        unique("relationship", payload.relationships.map { it.relationshipId })
        unique("memory", payload.roleplayMemories.map { it.memoryId })
        unique("co-op event", payload.coopEvents.map { it.eventId })
        unique("NPC update", payload.npcDossierUpdates.map { it.updateId })
        unique("chat settings", payload.chatSettings.map { it.chatId })
        unique("generation metadata", payload.generationMetadata.map { it.messageId })
        unique("conversation summary", payload.conversationSummaries.map { it.chatId })
        unique("NPC Bank settings", payload.npcBankSettings.map { it.chatId })
        uniqueComposite("Character chat participant", payload.chatParticipants.map { "${it.chatId}\u0000${it.characterId}" })
        uniqueComposite("Persona chat participant", payload.chatPersonaParticipants.map { "${it.chatId}\u0000${it.personaId}" })
        uniqueComposite("chat Lorebook attachment", payload.chatLorebookAttachments.map { "${it.chatId}\u0000${it.lorebookId}" })
        uniqueComposite("Lorebook overlay", payload.chatLorebookEntryOverlays.map { "${it.chatId}\u0000${it.entryId}" })
        uniqueComposite("co-op Persona ownership", payload.coopPersonaOwnerships.map { "${it.roomId}\u0000${it.personaId}" })
        uniqueComposite("co-op tombstone", payload.coopMessageTombstones.map { "${it.roomId}\u0000${it.messageId}" })
        uniqueComposite("co-op room chat", payload.coopRooms.map { it.chatId })
        uniqueComposite("co-op event sequence", payload.coopEvents.map { "${it.roomId}\u0000${it.sequence}" })
        uniqueComposite("co-op client event", payload.coopEvents.map { "${it.roomId}\u0000${it.clientEventId}" })
        uniqueComposite(
            "NPC update source",
            payload.npcDossierUpdates.map {
                "${it.sourceMessageId}\u0000${it.sourceRevision}\u0000${it.npcId}\u0000${it.fieldName}"
            }
        )
        uniqueComposite("message co-op event", payload.messages.mapNotNull { it.coOpEventId })

        requireRefs(payload.chatParticipants, "Character chat participant") {
            it.chatId in chatIds && it.characterId in characterIds
        }
        requireRefs(payload.chatPersonaParticipants, "Persona chat participant") {
            it.chatId in chatIds && it.personaId in personaIds
        }
        requireRefs(payload.messages, "message") { it.chatId in chatIds }
        requireRefs(payload.chatSettings, "chat settings") { it.chatId in chatIds }
        requireRefs(payload.generationMetadata, "generation metadata") { it.messageId in messageIds }
        requireRefs(payload.roleplayMemories, "memory") { it.chatId in chatIds && (it.characterId in characterIds || (it.chatId in scenarioChatIds && it.characterId == "narrator:${it.chatId}")) }
        requireRefs(payload.conversationSummaries, "conversation summary") { it.chatId in chatIds }
        requireRefs(payload.lorebookEntries, "Lorebook entry") { it.lorebookId in lorebookIds }
        requireRefs(payload.chatLorebookAttachments, "chat Lorebook attachment") {
            it.chatId in chatIds && it.lorebookId in lorebookIds
        }
        requireRefs(payload.chatLorebookEntryOverlays, "Lorebook overlay") {
            it.chatId in chatIds && it.entryId in loreEntryIds
        }
        requireRefs(payload.coopRooms, "co-op room") { it.chatId in chatIds }
        requireRefs(payload.coopParticipants, "co-op participant") { it.roomId in roomIds }
        requireRefs(payload.coopPersonaOwnerships, "co-op Persona ownership") {
            it.roomId in roomIds && it.personaId in personaIds && it.participantId in participantIds
        }
        requireRefs(payload.coopEvents, "co-op event") {
            it.roomId in roomIds && it.originParticipantId in participantIds
        }
        requireRefs(payload.coopMessageTombstones, "co-op tombstone") { it.roomId in roomIds }
        requireRefs(payload.messageAttachments, "message attachment") {
            it.chatId in chatIds && (it.messageId == null || it.messageId in messageIds)
        }
        requireRefs(payload.npcDossiers, "NPC dossier") { it.chatId in chatIds }
        requireRefs(payload.npcBankSettings, "NPC Bank settings") { it.chatId in chatIds }
        requireRefs(payload.npcDossierUpdates, "NPC update") {
            it.chatId in chatIds && it.npcId in npcIds && it.sourceMessageId in messageIds
        }

        val binaryAttachmentIds = manifest.files.filter { it.kind == BackupFileKind.MESSAGE_ATTACHMENT }
            .map { it.ownerId }
        if (binaryAttachmentIds.toSet() != attachmentIds || binaryAttachmentIds.size != attachmentIds.size) {
            throw BackupValidationException("Backup attachment records do not match attachment files.")
        }
        uniqueComposite("backup file owner", manifest.files.map { "${it.kind}\u0000${it.ownerId}" })
        val attachmentManifests = manifest.files.filter { it.kind == BackupFileKind.MESSAGE_ATTACHMENT }
            .associateBy { it.ownerId }
        payload.messageAttachments.forEach { attachment ->
            val file = attachmentManifests[attachment.attachmentId]
                ?: throw BackupValidationException("Backup attachment file is missing.")
            if (
                attachment.byteSize !in 1..MessageAttachmentRepository.MAX_IMAGE_BYTES ||
                attachment.mimeType !in SUPPORTED_ATTACHMENT_MIME_TYPES ||
                attachment.pixelWidth !in 1..MessageAttachmentRepository.MAX_DIMENSION ||
                attachment.pixelHeight !in 1..MessageAttachmentRepository.MAX_DIMENSION ||
                attachment.pixelWidth.toLong() * attachment.pixelHeight > MessageAttachmentRepository.MAX_PIXELS ||
                file.byteSize != attachment.byteSize ||
                file.mimeType != attachment.mimeType ||
                !file.sha256.equals(attachment.sha256, ignoreCase = true)
            ) {
                throw BackupValidationException("Backup attachment metadata is inconsistent.")
            }
        }
        manifest.files.filter { it.kind == BackupFileKind.SCENARIO_AVATAR }.forEach {
            if (it.ownerId !in scenarioIds || it.mimeType != "image/png" || it.byteSize !in 1..ProfileImageRepository.MAX_STORED_BYTES) throw BackupValidationException("Scenario artwork metadata is invalid.")
        }
        manifest.files.filter { it.kind == BackupFileKind.CHARACTER_AVATAR }.forEach {
            if (
                it.ownerId !in characterIds || it.mimeType != "image/png" ||
                it.byteSize !in 1..ProfileImageRepository.MAX_STORED_BYTES
            ) {
                throw BackupValidationException("Character avatar metadata is invalid.")
            }
        }
        manifest.files.filter { it.kind == BackupFileKind.PERSONA_AVATAR }.forEach {
            if (
                it.ownerId !in personaIds || it.mimeType != "image/png" ||
                it.byteSize !in 1..ProfileImageRepository.MAX_STORED_BYTES
            ) {
                throw BackupValidationException("Persona avatar metadata is invalid.")
            }
        }
        if (payload.appearance.fontSize !in 12f..24f) {
            throw BackupValidationException("Backup font-size setting is invalid.")
        }
        runCatching { ThemeMode.valueOf(payload.appearance.themeMode) }
            .getOrElse { throw BackupValidationException("Backup theme setting is invalid.") }
        if (ColorPalette.fromStorage(payload.appearance.colorPalette) == null) {
            throw BackupValidationException("Backup color-palette setting is invalid.")
        }
    }

    private fun validateExtractedFiles(validated: ValidatedBackup) {
        val attachments = validated.payload.messageAttachments.associateBy { it.attachmentId }
        validated.manifest.files.forEach { manifest ->
            val file = validated.extractedFiles[manifest.entryName]
                ?: throw BackupValidationException("Backup binary data is missing.")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            file.inputStream().use { BitmapFactory.decodeStream(it, null, bounds) }
            val detectedMime = normalizeImageMime(bounds.outMimeType) ?: detectImageMime(file)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || detectedMime == null) {
                throw BackupValidationException("Backup contains an unreadable image: ${manifest.entryName}")
            }
            when (manifest.kind) {
                BackupFileKind.MESSAGE_ATTACHMENT -> {
                    val attachment = attachments[manifest.ownerId]
                        ?: throw BackupValidationException("Backup attachment record is missing.")
                    if (
                        detectedMime != attachment.mimeType ||
                        bounds.outWidth != attachment.pixelWidth ||
                        bounds.outHeight != attachment.pixelHeight ||
                        bounds.outWidth > MessageAttachmentRepository.MAX_DIMENSION ||
                        bounds.outHeight > MessageAttachmentRepository.MAX_DIMENSION ||
                        bounds.outWidth.toLong() * bounds.outHeight > MessageAttachmentRepository.MAX_PIXELS
                    ) {
                        throw BackupValidationException("Backup attachment image metadata does not match its content.")
                    }
                }
                BackupFileKind.SCENARIO_AVATAR,
                BackupFileKind.CHARACTER_AVATAR,
                BackupFileKind.PERSONA_AVATAR -> {
                    if (
                        detectedMime != "image/png" ||
                        bounds.outWidth > ProfileImageRepository.MAX_STORED_DIMENSION ||
                        bounds.outHeight > ProfileImageRepository.MAX_STORED_DIMENSION
                    ) {
                        throw BackupValidationException("Backup profile image is not a supported stored PNG.")
                    }
                }
            }
        }
    }

    private fun validatePlainArchive(file: File) {
        val workingDirectory = File(context.cacheDir, "elsewhere-export-validation-${UUID.randomUUID()}")
        try {
            val validated = FileInputStream(file).use { archiveIO.readPlainZip(it, workingDirectory) }
            validatePayload(validated.payload, validated.manifest)
            validateExtractedFiles(validated)
        } finally {
            workingDirectory.deleteRecursively()
        }
    }

    private fun normalizeImageMime(value: String?): String? = when (value?.lowercase()) {
        "image/png" -> "image/png"
        "image/jpeg", "image/jpg" -> "image/jpeg"
        "image/webp" -> "image/webp"
        else -> null
    }

    private fun detectImageMime(file: File): String? {
        val header = ByteArray(12)
        val count = file.inputStream().use { it.read(header) }
        if (count >= 8 && header.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) return "image/png"
        if (
            count >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() &&
            header[2] == 0xFF.toByte()
        ) return "image/jpeg"
        if (
            count >= 12 && header.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
            header.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP"
        ) return "image/webp"
        return null
    }

    private fun <T> requireRefs(values: List<T>, label: String, predicate: (T) -> Boolean) {
        if (values.any { !predicate(it) }) throw BackupValidationException("Backup contains a broken $label reference.")
    }

    private fun uniqueComposite(label: String, values: List<String>) {
        if (values.toSet().size != values.size) {
            throw BackupValidationException("Backup contains duplicate $label records.")
        }
    }

    private fun BackupSnapshot.toPreview(): BackupPreview = manifest.previewFor(payload)

    private fun ValidatedBackup.toPreview(): BackupPreview = manifest.previewFor(payload)

    private fun BackupManifest.previewFor(payload: BackupPayload): BackupPreview = BackupPreview(
        createdAt = createdAt,
        appVersionName = appVersionName,
        databaseVersion = databaseVersion,
        characters = payload.characters.size,
        personas = payload.personas.size,
        chats = payload.chats.size,
        messages = payload.messages.size,
        memories = payload.roleplayMemories.size,
        lorebooks = payload.lorebooks.size,
        npcDossiers = payload.npcDossiers.size,
        messageAttachments = payload.messageAttachments.size,
        profileImages = files.count {
            it.kind in listOf(BackupFileKind.CHARACTER_AVATAR, BackupFileKind.PERSONA_AVATAR, BackupFileKind.SCENARIO_AVATAR)
        },
        includesDrafts = payload.chatSettings.any { !it.draftMessage.isNullOrEmpty() } ||
            payload.messageAttachments.any { it.messageId == null },
        includesCoopHistory = payload.coopRooms.isNotEmpty() || payload.coopEvents.isNotEmpty(),
        coopWillRestoreOffline = true,
        scenarios = payload.scenarios.size
    )

    private fun BackupAppearanceSettings.toDomain(): AppearanceSettings = AppearanceSettings(
        themeMode = ThemeMode.valueOf(themeMode),
        fontSize = fontSize,
        colorPalette = requireNotNull(ColorPalette.fromStorage(colorPalette)),
        legacyAccentOverride = legacyAccentOverride
    )

    private fun AppearanceSettings.toBackup(): BackupAppearanceSettings = BackupAppearanceSettings(
        themeMode = themeMode.name,
        fontSize = fontSize,
        colorPalette = colorPalette.storageId,
        legacyAccentOverride = legacyAccentOverride
    )

    private fun sanitizeCoopRoom(room: CoopRoomEntity): CoopRoomEntity = room.copy(
        hostAddress = null,
        hostPort = 0,
        hostPublicKey = "",
        hostKeyFingerprint = "",
        pairingSalt = "",
        pairingCodeHash = "",
        connectionState = CoopConnectionState.DISCONNECTED,
        activeGenerationRequestId = null
    )

    private fun sanitizeCoopParticipant(participant: CoopParticipantEntity): CoopParticipantEntity = participant.copy(
        deviceId = "",
        identityPublicKey = "",
        isConnected = false
    )

    private fun sanitizeCoopEvent(event: CoopEventEntity): CoopEventEntity {
        val sanitizedPayload = try {
            when (event.eventType) {
                CoopEventType.MESSAGE_UPSERT -> coopCodec.messageToJson(
                    coopCodec.messageFromJson(event.payloadJson).copy(responsibleDeviceId = null)
                )
                CoopEventType.MESSAGE_DELETE -> coopCodec.deleteToJson(coopCodec.deleteFromJson(event.payloadJson))
                CoopEventType.PARTICIPANT_UPSERT,
                CoopEventType.PARTICIPANT_LEFT -> coopCodec.participantToJson(
                    coopCodec.participantFromJson(event.payloadJson).copy(
                        deviceId = "",
                        identityPublicKey = "",
                        isConnected = false
                    )
                )
                CoopEventType.PERSONA_OWNERSHIP_SET ->
                    coopCodec.ownershipToJson(coopCodec.ownershipFromJson(event.payloadJson))
                CoopEventType.CHARACTER_ROSTER_SET ->
                    coopCodec.rosterToJson(coopCodec.rosterFromJson(event.payloadJson))
                CoopEventType.PAYER_POLICY_SET ->
                    coopCodec.payerToJson(coopCodec.payerFromJson(event.payloadJson))
                CoopEventType.GENERATION_REQUESTED ->
                    coopCodec.generationToJson(coopCodec.generationFromJson(event.payloadJson))
                CoopEventType.GENERATION_APPROVED ->
                    coopCodec.approvalToJson(coopCodec.approvalFromJson(event.payloadJson))
                CoopEventType.GENERATION_EXECUTING ->
                    coopCodec.executionToJson(coopCodec.executionFromJson(event.payloadJson))
                CoopEventType.GENERATION_FINISHED,
                CoopEventType.GENERATION_FAILED ->
                    coopCodec.completionToJson(coopCodec.completionFromJson(event.payloadJson))
                else -> throw BackupValidationException("Backup contains an unsupported co-op event type.")
            }
        } catch (error: BackupValidationException) {
            throw error
        } catch (error: Exception) {
            throw BackupValidationException("Backup contains a malformed co-op event payload.", error)
        }
        return event.copy(
            payloadJson = sanitizedPayload,
            originPayloadJson = "",
            originSignature = ""
        )
    }

    private fun safeAppFile(relativePath: String, requiredRoot: String): File {
        val root = context.filesDir.canonicalFile
        val allowed = File(root, requiredRoot).canonicalFile
        val destination = File(root, relativePath).canonicalFile
        if (!destination.path.startsWith(allowed.path + File.separator)) {
            throw BackupValidationException("Restore file path is unsafe.")
        }
        return destination
    }

    private fun moveReplacing(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun safeToken(value: String): String = sha256(value.toByteArray(Charsets.UTF_8)).lowercase()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

    private class BoundedOutputStream(
        private val delegate: OutputStream,
        private val maximum: Long
    ) : OutputStream() {
        private var written = 0L

        override fun write(value: Int) {
            requireCapacity(1)
            delegate.write(value)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            requireCapacity(length)
            delegate.write(buffer, offset, length)
        }

        override fun flush() = delegate.flush()

        private fun requireCapacity(increment: Int) {
            written += increment
            if (written > maximum) throw BackupValidationException("Backup exceeds the supported size limit.")
        }
    }

    companion object {
        private const val SAFETY_DIRECTORY = "restore_safety"
        private const val SAFETY_BACKUP_NAME = "before-last-restore.zip"
        private const val RESTORE_MARKER_NAME = "restore-in-progress"
        private const val COOP_RESTORE_MODE = "OFFLINE_NON_RESUMABLE_HISTORY"
        private const val EXACT_RECOVERY_MODE = "EXACT_PRIVATE_RECOVERY"
        private const val MAX_TOTAL_ENTITY_RECORDS = 500_000L
        private const val MAX_MESSAGE_RECORDS = 250_000
        private val SUPPORTED_ATTACHMENT_MIME_TYPES = setOf("image/png", "image/jpeg", "image/webp")
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        private val EXCLUDED_CATEGORIES = listOf(
            "OpenRouter API key",
            "AndroidKeyStore keys",
            "co-op device identity",
            "co-op private signing identities",
            "pairing secrets and hashes",
            "network addresses and live connection state"
        )
    }
}
