package com.example.domain.world

import androidx.room.withTransaction
import com.example.data.AppDatabase
import com.example.data.model.MessageAttachmentEntity
import com.example.domain.repository.AppDataMaintenanceCoordinator
import com.example.domain.repository.ProfileImageKind
import com.example.domain.repository.ProfileImageRepository
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Adds a verified, remapped World beside existing data. No DELETE, REPLACE or application-wide
 * preferences are involved. Repository mutations are excluded while files and Room are committed.
 *
 * Safe before UI wiring: caller must explicitly unlock and inspect an archive and choose a unique
 * durable import namespace. The complete Room batch runs in ONE transaction. Images are promoted
 * inside that transaction, with a persistent orphan journal for process-death recovery.
 *
 * This is an additive *new World* importer only, never a merge into an existing World.
 */
class WorldArchiveAdditiveRestorer(
    private val database: AppDatabase,
    private val appFilesRoot: File,
    private val dataCoordinator: AppDataMaintenanceCoordinator = AppDataMaintenanceCoordinator.Global
) {
    enum class TestCheckpoint { AFTER_FILES_PROMOTED, AFTER_RECORDS_INSERTED }

    data class Result(
        val worldId: String,
        val chats: Int,
        val messages: Int,
        val imageFiles: Int
    )

    private data class PreparedAsset(val relativePath: String, val stage: File)

    /**
     * Caller retains ownership of [archive] and must close it after import or preview.
     * [fault] is for deterministic rollback tests and never triggers paid model requests.
     */
    suspend fun importAsNewWorld(
        archive: WorldArchiveEncryptedIO.VerifiedWorldArchive,
        namespace: String,
        fault: suspend (TestCheckpoint) -> Unit = {}
    ): Result = dataCoordinator.withDataReplacement {
        recoverInterruptedImportsLocked()

        WorldArchiveStateCodec.validateWithManifest(archive.state, archive.manifest)
        val plan = archive.planNewWorldImport(namespace)
        val remapped = WorldArchiveSemanticRemapper.rewrite(archive.state, plan)
        val targetState = remapped.state
        val worldId = targetState.worldId

        // A World identity is the commit marker for one fully atomic Room transaction.
        require(database.worldDao().getWorld(worldId) == null) {
            "This World import identity already exists; choose a fresh operation"
        }

        val stagingParent = File(appFilesRoot, STAGING_DIR)
        require(stagingParent.mkdirs() || stagingParent.isDirectory) {
            "World restore staging is not available"
        }
        val stage = Files.createTempDirectory(stagingParent.toPath(), "import-").toFile()
        val journal = journalFile(worldId)
        var journalWritten = false
        try {
            val prepared = stageAssets(archive, remapped, plan, stage)
            require(journal.parentFile?.mkdirs() == true || journal.parentFile?.isDirectory == true) {
                "World restore journal is unavailable"
            }
            require(!journal.exists()) { "An import with this identity needs recovery" }
            writeJournal(journal, worldId, prepared.map { it.relativePath })
            journalWritten = true

            database.withTransaction {
                // Check again *inside* the write transaction. SQLite ABORT protects every
                // remaining primary/composite key; Scenario has a dedicated ABORT insert.
                require(database.worldDao().getWorld(worldId) == null) {
                    "World already exists at commit time"
                }
                for (image in prepared) {
                    val destination = safeDestination(image.relativePath)
                    require(destination.parentFile?.mkdirs() == true ||
                        destination.parentFile?.isDirectory == true) {
                        "Cannot create World restore image directory"
                    }
                    require(!destination.exists()) {
                        "World restore would overwrite an existing image"
                    }
                    try {
                        Files.move(image.stage.toPath(), destination.toPath(),
                            StandardCopyOption.ATOMIC_MOVE)
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(image.stage.toPath(), destination.toPath())
                    }
                }
                fault(TestCheckpoint.AFTER_FILES_PROMOTED)
                insertAll(targetState, prepared.map { it.relativePath }.toSet())
                fault(TestCheckpoint.AFTER_RECORDS_INSERTED)
            }
            // If the process dies before this deletion, startup recovery sees the committed
            // World row and preserves the already valid files.
            journal.delete()
            Result(worldId, targetState.chats.size, targetState.messages.size, prepared.size)
        } catch (failure: Exception) {
            withContext(NonCancellable) {
                if (journalWritten && database.worldDao().getWorld(worldId) == null) {
                    cleanupUncommitted(journal, worldId)
                }
            }
            throw failure
        } finally {
            stage.deleteRecursively()
        }
    }

    /** Call on app startup before exposing World import or profile-image mutations. */
    suspend fun recoverInterruptedImports(): Int =
        dataCoordinator.withDataReplacement { recoverInterruptedImportsLocked() }

    private suspend fun recoverInterruptedImportsLocked(): Int {
        val directory = File(appFilesRoot, JOURNAL_DIR)
        var recovered = 0
        directory.listFiles()?.filter { it.isFile && it.name.endsWith(".pending") }
            ?.forEach { pending ->
                val declaredId = pending.name.removeSuffix(".pending")
                require(SAFE_UUID.matches(declaredId)) {
                    "World restore journal filename is invalid"
                }
                if (database.worldDao().getWorld(declaredId) == null) {
                    cleanupUncommitted(pending, declaredId)
                } else {
                    require(pending.delete()) { "Cannot finalize committed World restore journal" }
                }
                recovered++
            }
        val stages = File(appFilesRoot, STAGING_DIR)
        stages.listFiles()?.filter { it.isDirectory && it.name.startsWith("import-") }
            ?.forEach { it.deleteRecursively() }
        // A crash before an atomic journal rename can leave only a temporary journal.
        directory.listFiles()?.filter { it.isFile && it.name.endsWith(".writing") }
            ?.forEach { it.delete() }
        return recovered
    }

    private fun cleanupUncommitted(pending: File, expectedWorldId: String) {
        val rows = pending.readLines()
        require(rows.firstOrNull() == expectedWorldId) { "World restore journal owner mismatch" }
        val targets = rows.drop(1)
        require(targets.size <= WorldArchiveManifestCodec.MAX_ENTRIES &&
            targets.distinct().size == targets.size &&
            targets.all(::isManagedImagePath)) {
            "Unsafe World restore journal: refusing to delete any files"
        }
        targets.forEach { relative ->
            val file = safeDestination(relative)
            if (file.exists()) require(file.delete()) {
                "Could not clean interrupted World restore asset"
            }
        }
        require(pending.delete()) { "Could not remove World restore recovery journal" }
    }

    private fun writeJournal(destination: File, worldId: String, paths: List<String>) {
        require(SAFE_UUID.matches(worldId) && paths.distinct().size == paths.size &&
            paths.all(::isManagedImagePath)) {
            "World restore journal contains unsupported paths"
        }
        val writing = File(destination.parentFile, "$worldId.writing")
        require(!writing.exists()) { "World restore journal is already being written" }
        try {
            FileOutputStream(writing).use { output ->
                output.write((listOf(worldId) + paths).joinToString("\n", postfix = "\n")
                    .toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(writing.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            writing.delete()
        }
    }

    private fun stageAssets(
        archive: WorldArchiveEncryptedIO.VerifiedWorldArchive,
        rewritten: WorldArchiveSemanticRemapper.RewrittenWorld,
        plan: WorldArchiveIdRemapPlan,
        stage: File
    ): List<PreparedAsset> {
        val entries = archive.manifest.entries.filter {
            it.kind != WorldArchiveEntryKind.WORLD_DEFINITION &&
                it.kind != WorldArchiveEntryKind.WORLD_STATE
        }
        require(archive.assetFiles.keys == entries.map { it.path }.toSet()) {
            "World import has missing or unlisted image bytes"
        }
        val prepared = mutableListOf<PreparedAsset>()
        for ((index, entry) in entries.withIndex()) {
            val relative = when (entry.kind) {
                WorldArchiveEntryKind.MESSAGE_ATTACHMENT -> {
                    val newPath = requireNotNull(rewritten.attachmentFileMoves[entry.path]) {
                        "World attachment ID was not remapped"
                    }
                    val attachment = rewritten.state.messageAttachments.singleOrNull {
                        it.assetPath == newPath
                    } ?: throw IllegalArgumentException("World attachment was lost during ID rewrite")
                    "message_attachments/world-import-${attachment.attachmentId}.${extension(attachment.mimeType)}"
                }
                WorldArchiveEntryKind.CHARACTER_AVATAR,
                WorldArchiveEntryKind.PERSONA_AVATAR,
                WorldArchiveEntryKind.SCENARIO_AVATAR -> {
                    val kind = when (entry.kind) {
                        WorldArchiveEntryKind.CHARACTER_AVATAR -> ProfileImageKind.CHARACTER
                        WorldArchiveEntryKind.PERSONA_AVATAR -> ProfileImageKind.PERSONA
                        else -> ProfileImageKind.SCENARIO
                    }
                    val ownerType = when (kind) {
                        ProfileImageKind.CHARACTER -> WorldArchiveIdKind.CHARACTER
                        ProfileImageKind.PERSONA -> WorldArchiveIdKind.PERSONA
                        ProfileImageKind.SCENARIO -> WorldArchiveIdKind.SCENARIO
                    }
                    val owner = plan.get(ownerType, requireNotNull(entry.ownerId))
                    val key = ProfileImageRepository.storageKey(kind, owner)
                    "profile_images/${kind.directoryName}/$key.png"
                }
                WorldArchiveEntryKind.NPC_PORTRAIT ->
                    throw IllegalArgumentException("NPC portrait restore has no verified storage adapter")
                else -> throw IllegalArgumentException("Unsupported World asset type")
            }
            require(isManagedImagePath(relative)) { "World asset destination path is unsafe" }
            val source = requireNotNull(archive.assetFiles[entry.path]) {
                "World archive lacks its declared asset bytes"
            }
            require(source.isFile) { "World archive asset cannot be opened" }
            val copied = File(stage, "$index.bin")
            source.copyTo(copied, overwrite = false)
            require(copied.length() == entry.byteSize && sha256(copied) == entry.sha256) {
                "World archive asset changed during import staging"
            }
            require(!safeDestination(relative).exists()) {
                "World restore would overwrite an existing image"
            }
            prepared += PreparedAsset(relative, copied)
        }
        require(prepared.map { it.relativePath }.distinct().size == prepared.size) {
            "Two imported World assets resolve to the same device image path"
        }
        return prepared
    }

    private suspend fun insertAll(state: WorldArchiveStateV1, assetPaths: Set<String>) {
        val backup = database.backupDao()
        val world = database.worldDao()
        val scenario = database.scenarioDao()

        if (state.characters.isNotEmpty()) backup.insertCharacters(state.characters)
        if (state.personas.isNotEmpty()) backup.insertPersonas(state.personas)
        state.scenarios.forEach { scenario.insertForWorldImport(it) }
        if (state.chats.isNotEmpty()) backup.insertChats(state.chats)
        state.scenarioSessions.forEach { scenario.insertSession(it) }
        if (state.chatParticipants.isNotEmpty()) backup.insertChatParticipants(state.chatParticipants)
        if (state.chatPersonaParticipants.isNotEmpty()) backup.insertChatPersonaParticipants(state.chatPersonaParticipants)
        if (state.messages.isNotEmpty()) backup.insertMessages(state.messages)
        if (state.relationships.isNotEmpty()) backup.insertRelationships(state.relationships)
        if (state.chatSettings.isNotEmpty()) backup.insertChatSettings(state.chatSettings)
        if (state.generationMetadata.isNotEmpty()) backup.insertGenerationMetadata(state.generationMetadata)
        if (state.roleplayMemories.isNotEmpty()) backup.insertRoleplayMemories(state.roleplayMemories)
        if (state.conversationSummaries.isNotEmpty()) backup.insertConversationSummaries(state.conversationSummaries)
        if (state.lorebooks.isNotEmpty()) backup.insertLorebooks(state.lorebooks)
        if (state.lorebookEntries.isNotEmpty()) backup.insertLorebookEntries(state.lorebookEntries)
        if (state.chatLorebookAttachments.isNotEmpty()) backup.insertChatLorebookAttachments(state.chatLorebookAttachments)
        if (state.chatLorebookOverlays.isNotEmpty()) backup.insertChatLorebookEntryOverlays(state.chatLorebookOverlays)
        if (state.npcDossiers.isNotEmpty()) backup.insertNpcDossiers(state.npcDossiers)
        if (state.npcBankSettings.isNotEmpty()) backup.insertNpcBankSettings(state.npcBankSettings)
        if (state.npcUpdates.isNotEmpty()) backup.insertNpcDossierUpdates(state.npcUpdates)
        if (state.npcLibraryBanks.isNotEmpty()) backup.insertNpcLibraryBanks(state.npcLibraryBanks)
        if (state.npcLibraryEntries.isNotEmpty()) backup.insertNpcLibraryEntries(state.npcLibraryEntries)
        val mappedAttachments = state.messageAttachments.map { row ->
            val path = "message_attachments/world-import-${row.attachmentId}.${extension(row.mimeType)}"
            require(path in assetPaths) { "Imported attachment has no staged image file" }
            MessageAttachmentEntity(
                attachmentId = row.attachmentId, chatId = row.chatId,
                messageId = row.messageId, relativePath = path,
                mimeType = row.mimeType, byteSize = row.byteSize,
                pixelWidth = row.pixelWidth, pixelHeight = row.pixelHeight,
                sha256 = row.sha256, sortPosition = row.sortPosition, createdAt = row.createdAt
            )
        }
        if (mappedAttachments.isNotEmpty()) backup.insertMessageAttachments(mappedAttachments)
        world.insertWorlds(listOf(state.world))
        if (state.memberships.isNotEmpty()) world.insertMemberships(state.memberships)
        if (state.cast.isNotEmpty()) world.insertCast(state.cast)
        if (state.continuity.isNotEmpty()) world.insertEvents(state.continuity)
    }

    private fun journalFile(worldId: String): File {
        require(SAFE_UUID.matches(worldId)) { "World import identity must be a UUID" }
        return File(File(appFilesRoot, JOURNAL_DIR), "$worldId.pending")
    }

    private fun safeDestination(relative: String): File {
        require(isManagedImagePath(relative)) { "Unsupported World image destination" }
        val base = appFilesRoot.canonicalFile
        val target = File(base, relative).canonicalFile
        require(target.path.startsWith(base.path + File.separator)) {
            "World image escapes private app storage"
        }
        return target
    }

    private fun isManagedImagePath(relative: String): Boolean =
        ATTACHMENT_PATH.matches(relative) || PROFILE_PATH.matches(relative)

    private fun extension(mime: String): String = when (mime) {
        "image/png" -> "png"
        "image/jpeg" -> "jpg"
        "image/webp" -> "webp"
        else -> throw IllegalArgumentException("Unsupported World image content type")
    }

    private fun sha256(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(bytes)
                if (read < 0) break
                hash.update(bytes, 0, read)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
    }

    companion object {
        private const val JOURNAL_DIR = "world_import_recovery"
        private const val STAGING_DIR = "world_import_staging"
        private val SAFE_UUID = Regex("[a-f0-9-]{36}")
        private val ATTACHMENT_PATH = Regex(
            "message_attachments/world-import-[a-f0-9-]{36}\\.(png|jpg|webp)"
        )
        private val PROFILE_PATH = Regex(
            "profile_images/(characters|personas|scenarios)/[a-f0-9]{64}\\.png"
        )
    }
}
