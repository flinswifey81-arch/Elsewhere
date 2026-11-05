package com.example.domain.world

import com.example.data.model.MessageAttachmentEntity
import com.example.domain.repository.MessageAttachmentRepository
import com.example.domain.repository.ProfileImageKind
import com.example.domain.repository.ProfileImageRepository
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * Stages the actual locally-owned images of one validated World snapshot.
 *
 * The original device-relative attachment paths are never serialized in the World state.
 * Binary sources are independently validated and copied into a private, short-lived directory
 * before a ZIP writer can consume them. This class never changes original user image files.
 */
class WorldArchiveAssetCollector(
    private val attachmentFile: (MessageAttachmentEntity) -> File?,
    private val profileFile: (ProfileImageKind, String) -> File?
) {
    constructor(
        messageAttachments: MessageAttachmentRepository,
        profileImages: ProfileImageRepository
    ) : this(messageAttachments::resolveFile, profileImages::resolveImage)

    class StagedAssets internal constructor(
        val sources: List<WorldArchiveEncryptedIO.AssetSource>,
        private val directory: File
    ) : Closeable {
        override fun close() {
            directory.deleteRecursively()
        }
    }

    /**
     * [backingAttachments] are the *source* Room rows for the already selected World state.
     * Reject even one omitted/foreign row; never drop a missing image to make an archive pass.
     */
    fun stage(
        state: WorldArchiveStateV1,
        backingAttachments: List<MessageAttachmentEntity>,
        privateDirectory: File
    ): StagedAssets {
        WorldArchiveStateCodec.validate(state)
        require(privateDirectory.mkdirs() || privateDirectory.isDirectory) {
            "World archive image staging directory is unavailable"
        }
        val stage = Files.createTempDirectory(
            privateDirectory.toPath(), "elsewhere-world-assets-"
        ).toFile()
        try {
            val originalById = backingAttachments.associateBy { it.attachmentId }
            require(originalById.size == backingAttachments.size &&
                originalById.keys == state.messageAttachments.map { it.attachmentId }.toSet()) {
                "World archive backing attachments do not match its selected history"
            }
            val sources = mutableListOf<WorldArchiveEncryptedIO.AssetSource>()

            fun snapshot(
                archivePath: String, kind: WorldArchiveEntryKind, ownerId: String, file: File
            ) {
                require(file.isFile && file.length() in 1..WorldArchiveManifestCodec.MAX_ENTRY_BYTES) {
                    "World archive source image is missing, empty or too large"
                }
                val staged = File(stage, "${UUID.randomUUID()}.bin")
                file.copyTo(staged, overwrite = false)
                require(staged.length() == file.length()) { "World archive image changed while staging" }
                sources += WorldArchiveEncryptedIO.AssetSource(
                    archivePath, kind, ownerId, staged
                )
            }

            for (image in state.messageAttachments) {
                val source = requireNotNull(originalById[image.attachmentId]) {
                    "Missing original attachment metadata"
                }
                require(source.chatId == image.chatId && source.messageId == image.messageId &&
                    source.mimeType == image.mimeType && source.byteSize == image.byteSize &&
                    source.sha256 == image.sha256) {
                    "Attachment metadata changed since the World snapshot was selected"
                }
                val file = requireNotNull(attachmentFile(source)) {
                    "One of the World images is missing from device storage"
                }
                snapshot(image.assetPath, WorldArchiveEntryKind.MESSAGE_ATTACHMENT, image.attachmentId, file)
                require(sha256(sources.last().file) == image.sha256 &&
                    sources.last().file.length() == image.byteSize) {
                    "World image failed its integrity check while staging"
                }
            }

            fun profileImages(kind: ProfileImageKind, owners: List<String>, archiveKind: WorldArchiveEntryKind) {
                for (id in owners) {
                    val source = profileFile(kind, id) ?: continue
                    val path = "assets/avatars/${kind.name.lowercase(Locale.ROOT)}/${sha256Text(kind.name + "\u0000" + id)}.png"
                    snapshot(path, archiveKind, id, source)
                }
            }
            profileImages(ProfileImageKind.CHARACTER, state.characters.map { it.characterId },
                WorldArchiveEntryKind.CHARACTER_AVATAR)
            profileImages(ProfileImageKind.PERSONA, state.personas.map { it.personaId },
                WorldArchiveEntryKind.PERSONA_AVATAR)
            profileImages(ProfileImageKind.SCENARIO, state.scenarios.map { it.scenarioId },
                WorldArchiveEntryKind.SCENARIO_AVATAR)
            return StagedAssets(sources, stage)
        } catch (error: Exception) {
            stage.deleteRecursively()
            throw error
        }
    }

    private fun sha256Text(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(bytes)
                if (read < 0) break
                digest.update(bytes, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
