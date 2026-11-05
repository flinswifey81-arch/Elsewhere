package com.example

import com.example.data.model.ChatEntity
import com.example.data.model.ChatSettingsEntity
import com.example.data.model.ChatType
import com.example.data.model.MessageAttachmentEntity
import com.example.data.model.MessageEntity
import com.example.data.model.SpeakerType
import com.example.data.model.WorldEntity
import com.example.data.model.WorldMembershipEntity
import com.example.domain.backup.BackupCrypto
import com.example.domain.repository.ProfileImageKind
import com.example.domain.world.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WorldArchiveEncryptedIOTest {
    @get:Rule val temp = TemporaryFolder()

    private val world = WorldEntity(worldId = "world-a", title = "Moonlit City")
    private val text = "A private roleplay where the moon glows violet."
    private val chat = ChatEntity(chatId = "chat-a", displayName = "Moonlit City Episode 1", chatType = ChatType.SOLO)
    private val message = MessageEntity(
        messageId = "message-a", chatId = chat.chatId,
        speakerType = SpeakerType.SYSTEM, speakerId = "scene",
        speakerDisplayNameSnapshot = "Scene", content = text, orderIndex = 0
    )
    private val imageBytes = "This is a fake PNG test fixture, not a user image".toByteArray()
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun state(withImage: Boolean = false) = WorldArchiveStateV1(
        format = WorldArchiveStateCodec.FORMAT,
        schemaVersion = WorldArchiveStateCodec.SCHEMA_VERSION,
        worldId = world.worldId,
        world = world,
        memberships = listOf(WorldMembershipEntity(world.worldId, "CHAT", chat.chatId)),
        chats = listOf(chat),
        messages = listOf(message),
        chatSettings = listOf(ChatSettingsEntity(chat.chatId)),
        messageAttachments = if (withImage) listOf(
            WorldArchiveMessageAttachmentV1(
                attachmentId = "image-a", chatId = chat.chatId, messageId = message.messageId,
                assetPath = "assets/message-attachments/image-a",
                mimeType = "image/png", byteSize = imageBytes.size.toLong(),
                pixelWidth = 1, pixelHeight = 1, sha256 = sha(imageBytes),
                sortPosition = 0, createdAt = 1L
            )
        ) else emptyList()
    )

    private fun definition() = WorldDefinitionV1(
        format = WorldDefinitionCodec.FORMAT,
        schemaVersion = WorldDefinitionCodec.SCHEMA_VERSION,
        world = WorldMetadataV1(worldId = world.worldId, title = world.title,
            settingSummary = world.settingSummary),
        resources = WorldResourcesV1(),
        links = WorldLinksV1()
    )

    private fun io() = WorldArchiveEncryptedIO(temp.newFolder("workspace-" + System.nanoTime()))

    private fun asset() = temp.newFile().apply { writeBytes(imageBytes) }

    @Test fun encryptedArchiveRoundTripsHistoryAndVerifiedImageBytesWithoutDatabaseWrites() {
        val original = state(withImage = true)
        val source = WorldArchiveEncryptedIO.AssetSource(
            original.messageAttachments.single().assetPath,
            WorldArchiveEntryKind.MESSAGE_ATTACHMENT, "image-a", asset()
        )
        val output = ByteArrayOutputStream()
        val service = io()
        val password = "sample secure passphrase".toCharArray()
        val manifest = service.exportEncrypted(
            original, WorldDefinitionCodec.encode(definition()),
            listOf(source), output, password, createdAt = 1L
        )
        assertTrue(password.all { it == '\u0000' })
        val encrypted = output.toByteArray()
        assertFalse(encrypted.toString(Charsets.ISO_8859_1).contains(text))
        assertTrue(encrypted.isNotEmpty())
        assertEquals(3, manifest.entries.size)

        val unlock = "sample secure passphrase".toCharArray()
        val inspected = service.inspectEncrypted(ByteArrayInputStream(encrypted), unlock)
        assertTrue(unlock.all { it == '\u0000' })
        assertEquals(original, inspected.state)
        assertEquals(definition(), inspected.definition)
        assertEquals(manifest, inspected.manifest)
        val extracted = inspected.assetFiles.getValue("assets/message-attachments/image-a")
        assertArrayEquals(imageBytes, extracted.readBytes())
        inspected.close()
        assertFalse(extracted.exists())
    }

    @Test fun localAttachmentIsCopiedAndVerifiedWithoutExportingTheDevicePath() {
        val original = state(withImage = true)
        val sourceFile = asset()
        val backing = MessageAttachmentEntity(
            attachmentId = "image-a", chatId = chat.chatId,
            messageId = message.messageId,
            relativePath = "message_attachments/device-private-original.png",
            mimeType = "image/png", byteSize = imageBytes.size.toLong(),
            pixelWidth = 1, pixelHeight = 1, sha256 = sha(imageBytes),
            sortPosition = 0, createdAt = 1L
        )
        val collector = WorldArchiveAssetCollector(
            attachmentFile = { row -> if (row.attachmentId == backing.attachmentId) sourceFile else null },
            profileFile = { _, _ -> null }
        )
        val stagedFile: File
        val encrypted = ByteArrayOutputStream()
        collector.stage(original, listOf(backing), temp.newFolder("staging")).use { staged ->
            assertEquals(1, staged.sources.size)
            stagedFile = staged.sources.single().file
            assertTrue(stagedFile.isFile)
            assertNotEquals(sourceFile.canonicalPath, stagedFile.canonicalPath)
            assertEquals("assets/message-attachments/image-a", staged.sources.single().path)
            assertArrayEquals(imageBytes, stagedFile.readBytes())
            val writer = io()
            writer.exportEncrypted(
                original, WorldDefinitionCodec.encode(definition()),
                staged.sources, encrypted, "secure passphrase".toCharArray(), 1L
            )
        }
        assertFalse(stagedFile.exists())
        assertTrue(sourceFile.isFile)
        val reader = io()
        reader.inspectEncrypted(
            ByteArrayInputStream(encrypted.toByteArray()), "secure passphrase".toCharArray()
        ).use { inspected ->
            assertArrayEquals(imageBytes,
                inspected.assetFiles.getValue("assets/message-attachments/image-a").readBytes())
        }
    }

    @Test fun imageStagingRejectsMissingOrChangedSourceFiles() {
        val original = state(withImage = true)
        val backing = MessageAttachmentEntity(
            attachmentId = "image-a", chatId = chat.chatId,
            messageId = message.messageId, relativePath = "message_attachments/placeholder.png",
            mimeType = "image/png", byteSize = imageBytes.size.toLong(),
            pixelWidth = 1, pixelHeight = 1, sha256 = sha(imageBytes),
            sortPosition = 0, createdAt = 1L
        )
        val collector = WorldArchiveAssetCollector(
            attachmentFile = { temp.newFile().apply { writeText("corrupted image") } },
            profileFile = { _, _ -> null }
        )
        assertThrows(IllegalArgumentException::class.java) {
            collector.stage(original, emptyList(), temp.newFolder())
        }
        assertThrows(IllegalArgumentException::class.java) {
            collector.stage(original, listOf(backing), temp.newFolder())
        }
    }

    @Test fun wrongPassphraseAndCorruptedCiphertextNeverReturnAHistoryPreview() {
        val service = io()
        val output = ByteArrayOutputStream()
        service.exportEncrypted(
            state(), WorldDefinitionCodec.encode(definition()), emptyList(), output,
            "correct passphrase".toCharArray(), 1L
        )
        val bytes = output.toByteArray()
        assertThrows(Exception::class.java) {
            service.inspectEncrypted(ByteArrayInputStream(bytes), "wrong passphrase".toCharArray())
        }
        val corrupted = bytes.copyOf().also { it[it.lastIndex - 5] = (it[it.lastIndex - 5].toInt() xor 0x7f).toByte() }
        assertThrows(Exception::class.java) {
            service.inspectEncrypted(ByteArrayInputStream(corrupted), "correct passphrase".toCharArray())
        }
    }

    @Test fun missingAndMismatchingAssetCannotBeExportedAsCompleteWorld() {
        val original = state(withImage = true)
        val service = io()
        assertThrows(IllegalArgumentException::class.java) {
            service.exportEncrypted(
                original, WorldDefinitionCodec.encode(definition()), emptyList(),
                ByteArrayOutputStream(), "good password here".toCharArray(), 1L
            )
        }
        val wrongBytes = temp.newFile().apply { writeText("different contents") }
        assertThrows(IllegalArgumentException::class.java) {
            service.exportEncrypted(
                original, WorldDefinitionCodec.encode(definition()),
                listOf(WorldArchiveEncryptedIO.AssetSource(
                    "assets/message-attachments/image-a",
                    WorldArchiveEntryKind.MESSAGE_ATTACHMENT, "image-a", wrongBytes
                )),
                ByteArrayOutputStream(), "good password here".toCharArray(), 1L
            )
        }
    }

    @Test fun unlistedZipFileAndMissingStateAreRejectedEvenInsideValidEncryption() {
        val service = io()
        val original = state()
        val definitionJson = WorldDefinitionCodec.encode(definition())
        val stateJson = WorldArchiveStateCodec.encode(original)
        val output = ByteArrayOutputStream()
        val manifest = service.exportEncrypted(
            original, definitionJson, emptyList(), output,
            "secure passphrase here".toCharArray(), 1L
        )
        fun encryptedWithEntries(extra: Boolean, skipState: Boolean): ByteArray {
            val zipped = ByteArrayOutputStream()
            ZipOutputStream(zipped).use { zip ->
                fun item(path: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(bytes)
                    zip.closeEntry()
                }
                item("manifest.json", WorldArchiveManifestCodec.encode(manifest).toByteArray())
                item(WorldArchiveManifestCodec.DEFINITION_PATH, definitionJson.toByteArray())
                if (!skipState) item(WorldArchiveManifestCodec.STATE_PATH, stateJson.toByteArray())
                if (extra) item("../secret-from-other-world.txt", "malice".toByteArray())
            }
            val encrypted = ByteArrayOutputStream()
            BackupCrypto.encrypt(
                ByteArrayInputStream(zipped.toByteArray()), encrypted,
                "secure passphrase here".toCharArray()
            )
            return encrypted.toByteArray()
        }
        assertThrows(IllegalArgumentException::class.java) {
            service.inspectEncrypted(
                ByteArrayInputStream(encryptedWithEntries(extra = true, skipState = false)),
                "secure passphrase here".toCharArray()
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            service.inspectEncrypted(
                ByteArrayInputStream(encryptedWithEntries(extra = false, skipState = true)),
                "secure passphrase here".toCharArray()
            )
        }
    }

    @Test fun definitionCannotPretendToDescribeADifferentWorld() {
        val another = definition().copy(world = definition().world.copy(worldId = "world-b"))
        val password = "another passphrase".toCharArray()
        assertThrows(IllegalArgumentException::class.java) {
            io().exportEncrypted(
                state(), WorldDefinitionCodec.encode(another), emptyList(),
                ByteArrayOutputStream(), password, 1L
            )
        }
        assertTrue(password.all { it == '\u0000' })
    }
}
