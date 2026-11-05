package com.example.domain.world

import android.content.Context
import androidx.room.withTransaction
import com.example.data.AppDatabase
import com.example.data.model.ChatEntity
import com.example.data.model.MessageAttachmentEntity
import com.example.domain.backup.BackupAppearanceSettings
import com.example.domain.backup.BackupPayload
import com.example.domain.repository.AppDataMaintenanceCoordinator
import com.example.domain.repository.MessageAttachmentRepository
import com.example.domain.repository.ProfileImageRepository
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Test-only UI coordinator for complete encrypted World archives.
 *
 * Unlike the existing full-app backup, this reads one coherent Room snapshot and exports
 * ONLY the selected World and its references. Image staging occurs under the same mutation
 * lease, so a concurrent edit cannot change a source image between its row and its bytes.
 *
 * File selection is a two-step operation: inspect and validate, then explicit confirmation
 * before a second independent decrypt and the additive transaction.
 */
class WorldArchiveTransferCoordinator(
    private val context: Context,
    private val database: AppDatabase,
    private val attachmentRepository: MessageAttachmentRepository,
    private val profileRepository: ProfileImageRepository,
    private val restorer: WorldArchiveAdditiveRestorer,
    private val dataCoordinator: AppDataMaintenanceCoordinator
) {
    data class ExportPreview(
        val worldId: String,
        val title: String,
        val chats: Int,
        val messages: Int,
        val alternatives: Int,
        val characters: Int,
        val personas: Int,
        val scenarios: Int,
        val npcDossiers: Int,
        val attachmentCount: Int,
        val containsPrivateMaterial: Boolean,
        val containsUnsentDrafts: Boolean,
        internal val fingerprint: String
    )

    class ImportCandidate internal constructor(
        val preview: WorldArchiveImportPreview,
        val operationNamespace: String,
        internal val encryptedFile: File,
        internal val encryptedSha256: String,
        private val directory: File
    ) : Closeable {
        override fun close() {
            directory.deleteRecursively()
        }
    }

    private data class Source(
        val state: WorldArchiveStateV1,
        val attachmentRows: List<MessageAttachmentEntity>
    )
    private data class Prepared(
        val state: WorldArchiveStateV1,
        val images: WorldArchiveAssetCollector.StagedAssets
    )

    private val tempRoot get() = File(context.cacheDir, "world-archive-transfer")
    private val archiveIO get() = WorldArchiveEncryptedIO(tempRoot)
    private val collector get() = WorldArchiveAssetCollector(
        attachmentRepository, profileRepository
    )

    /** Lists existing local chats for the sandbox World membership picker; no writes. */
    suspend fun localChatChoices(): List<ChatEntity> = withContext(Dispatchers.IO) {
        dataCoordinator.withSnapshotMaintenance {
            database.backupDao().getChats()
                .sortedWith(compareByDescending<ChatEntity> { it.updatedAt }.thenBy { it.chatId })
        }
    }

    suspend fun previewExport(worldId: String): ExportPreview = withContext(Dispatchers.IO) {
        dataCoordinator.withSnapshotMaintenance {
            val source = captureWorld(worldId)
            makeExportPreview(source.state)
        }
    }

    suspend fun exportReviewed(
        preview: ExportPreview,
        output: OutputStream,
        passphrase: CharArray,
        includePrivateMaterial: Boolean
    ): WorldArchiveManifestV1 = withContext(Dispatchers.IO) {
        try {
            // Never write an archive if the user reviewed a different chat/history revision.
            val prepared = dataCoordinator.withSnapshotMaintenance {
                val source = captureWorld(preview.worldId)
                val actual = makeExportPreview(source.state)
                require(actual.fingerprint == preview.fingerprint) {
                    "This World changed after the export preview. Review it again before saving."
                }
                if (actual.containsPrivateMaterial || actual.containsUnsentDrafts) {
                    require(includePrivateMaterial) {
                        "Private notes, NPC secrets or unsent drafts require explicit inclusion approval."
                    }
                }
                val staged = collector.stage(source.state, source.attachmentRows, tempRoot)
                Prepared(source.state, staged)
            }
            prepared.images.use { staged ->
                archiveIO.exportSelectedEncrypted(
                    prepared.state, staged.sources, output, passphrase
                )
            }
        } finally {
            passphrase.fill('\u0000')
        }
    }

    /**
     * Copies the selected encrypted document into a private, bounded location so a cloud
     * provider cannot swap the content between preview and confirmation. No Room writes.
     */
    suspend fun inspectForImport(input: InputStream, passphrase: CharArray): ImportCandidate =
        withContext(Dispatchers.IO) {
            require(tempRoot.mkdirs() || tempRoot.isDirectory) {
                "World import cache directory is unavailable"
            }
            val directory = Files.createTempDirectory(
                tempRoot.toPath(), "review-"
            ).toFile()
            try {
                val file = File(directory, "authenticated-archive.bin")
                val fingerprint = copyAndHashBounded(input, file)
                val namespace = "elsewhere-world-import-${UUID.randomUUID()}"
                val preview = archiveIO.inspectEncrypted(file.inputStream(), passphrase).use {
                    require(it.manifest.entries.none { entry ->
                        entry.kind == WorldArchiveEntryKind.NPC_PORTRAIT
                    }) { "This World has NPC portrait files not yet supported by the importer." }
                    val mapped = WorldArchiveSemanticRemapper.rewrite(
                        it.state, it.planNewWorldImport(namespace)
                    )
                    WorldArchiveStateCodec.validate(mapped.state)
                    it.preview()
                }
                ImportCandidate(preview, namespace, file, fingerprint, directory)
            } catch (failure: Exception) {
                directory.deleteRecursively()
                throw failure
            } finally {
                passphrase.fill('\u0000')
            }
        }

    /**
     * Require a second unlock against EXACTLY the same encrypted bytes that were inspected.
     * The archive is reauthenticated and revalidated, then imported as a separate World.
     */
    suspend fun importReviewed(
        candidate: ImportCandidate, passphrase: CharArray
    ): WorldArchiveAdditiveRestorer.Result = withContext(Dispatchers.IO) {
        try {
            require(candidate.encryptedFile.isFile &&
                sha256(candidate.encryptedFile) == candidate.encryptedSha256) {
                "The encrypted World file changed after review. Inspect it again."
            }
            archiveIO.inspectEncrypted(candidate.encryptedFile.inputStream(), passphrase).use {
                require(it.preview() == candidate.preview) {
                    "The World contents changed after review."
                }
                restorer.importAsNewWorld(it, candidate.operationNamespace)
            }
        } finally {
            passphrase.fill('\u0000')
        }
    }

    private suspend fun captureWorld(worldId: String): Source {
        val dao = database.backupDao()
        val payload = database.withTransaction {
            BackupPayload(
                characters = dao.getCharacters(),
                personas = dao.getPersonas(),
                scenarios = database.scenarioDao().getAll(),
                scenarioSessions = database.scenarioDao().sessions(),
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
                worlds = database.worldDao().getWorlds(),
                worldMemberships = database.worldDao().getAllMemberships(),
                worldCast = database.worldDao().getAllCast(),
                worldContinuityEvents = database.worldDao().getAllEvents(),
                // Appearance is required by the full-app snapshot DTO but is never exported
                // in a selective World. No global theme/preferences are imported.
                appearance = BackupAppearanceSettings("SYSTEM", 1.0f, "DEFAULT")
            )
        }
        val selected = WorldArchiveSnapshotSelector.select(
            payload, worldId, privateDataReviewed = true
        )
        val selectedAttachmentIds = selected.messageAttachments
            .map { it.attachmentId }.toSet()
        return Source(
            selected,
            payload.messageAttachments.filter { it.attachmentId in selectedAttachmentIds }
        )
    }

    private fun makeExportPreview(state: WorldArchiveStateV1): ExportPreview {
        val bytes = WorldArchiveStateCodec.encode(state).toByteArray(Charsets.UTF_8)
        return ExportPreview(
            worldId = state.worldId,
            title = state.world.title,
            chats = state.chats.size,
            messages = state.messages.size,
            alternatives = state.messages.count { !it.isPrimaryVariant },
            characters = state.characters.size,
            personas = state.personas.size,
            scenarios = state.scenarios.size,
            npcDossiers = state.npcDossiers.size,
            attachmentCount = state.messageAttachments.size,
            containsPrivateMaterial =
                state.characters.any { !it.authorNotesJson.isNullOrBlank() } ||
                state.personas.any { !it.privateNotesJson.isNullOrBlank() } ||
                state.npcDossiers.any { it.secrets.isNotBlank() || it.authorNotes.isNotBlank() } ||
                state.npcLibraryEntries.any { it.secrets.isNotBlank() || it.authorNotes.isNotBlank() } ||
                state.lorebookEntries.any { it.notes.isNotBlank() },
            containsUnsentDrafts = state.chatSettings.any { !it.draftMessage.isNullOrBlank() } ||
                state.messageAttachments.any { it.messageId == null },
            fingerprint = sha256(bytes)
        )
    }

    private fun copyAndHashBounded(input: InputStream, destination: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val limit = WorldArchiveManifestCodec.MAX_TOTAL_BYTES + 64L * 1024L * 1024L
        var total = 0L
        input.use { source ->
            destination.outputStream().use { target ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    total += n
                    require(total <= limit) { "Encrypted World file exceeds the supported size" }
                    digest.update(buffer, 0, n)
                    target.write(buffer, 0, n)
                }
            }
        }
        require(total >= 48) { "The selected encrypted World file is empty or incomplete" }
        return digest.digest().hex()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().hex()
    }

    private fun ByteArray.hex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
