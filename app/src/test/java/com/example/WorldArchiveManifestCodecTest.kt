package com.example

import com.example.domain.world.WorldArchiveEntryKind
import com.example.domain.world.WorldArchiveEntryV1
import com.example.domain.world.WorldArchiveManifestCodec
import com.example.domain.world.WorldArchiveManifestV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WorldArchiveManifestCodecTest {
    private fun entry(
        path: String,
        kind: WorldArchiveEntryKind,
        ownerId: String? = null,
        size: Long = 32L
    ) = WorldArchiveEntryV1(
        path = path, kind = kind, ownerId = ownerId,
        byteSize = size, sha256 = "a".repeat(64)
    )

    private fun valid() = WorldArchiveManifestV1(
        format = WorldArchiveManifestCodec.FORMAT,
        schemaVersion = WorldArchiveManifestCodec.SCHEMA_VERSION,
        worldId = "mondstadt",
        createdAt = 1_762_000_000_000L,
        entries = listOf(
            entry(WorldArchiveManifestCodec.DEFINITION_PATH, WorldArchiveEntryKind.WORLD_DEFINITION),
            entry(WorldArchiveManifestCodec.STATE_PATH, WorldArchiveEntryKind.WORLD_STATE),
            entry("assets/images/one.png", WorldArchiveEntryKind.MESSAGE_ATTACHMENT, "message-1")
        )
    )

    private fun reject(manifest: WorldArchiveManifestV1) {
        assertThrows(IllegalArgumentException::class.java) {
            WorldArchiveManifestCodec.encode(manifest)
        }
    }

    @Test
    fun validManifestRoundTripsWithoutReadingOrRestoringAnyUserData() {
        val expected = valid()
        val json = WorldArchiveManifestCodec.encode(expected)
        assertEquals(expected, WorldArchiveManifestCodec.parse(json))
    }

    @Test
    fun formatAndFutureSchemaAreRejectedInsteadOfSilentlyDowngraded() {
        reject(valid().copy(format = "elsewhere_full_backup"))
        reject(valid().copy(schemaVersion = 2))
    }

    @Test
    fun refusesMissingOrDuplicateWorldPayloads() {
        val original = valid()
        reject(original.copy(entries = original.entries.drop(1)))
        reject(original.copy(entries = original.entries + original.entries[0]))
        reject(original.copy(entries = original.entries.map {
            if (it.kind == WorldArchiveEntryKind.WORLD_STATE) it.copy(kind = WorldArchiveEntryKind.WORLD_DEFINITION)
            else it
        }))
    }

    @Test
    fun rejectsZipSlipAbsoluteCaseCollisionsAndAmbiguousNames() {
        val original = valid()
        listOf(
            "../secrets.txt",
            "/assets/photo.png",
            "assets/../photo.png",
            "assets//photo.png",
            "assets/./photo.png",
            "assets\\photo.png",
            "assets/%2e%2e/photo.png",
            "C:/photo.png"
        ).forEach { badPath ->
            reject(original.copy(entries = original.entries.dropLast(1) +
                entry(badPath, WorldArchiveEntryKind.MESSAGE_ATTACHMENT, "message-1")))
        }
        reject(original.copy(entries = original.entries +
            entry("ASSETS/images/ONE.png", WorldArchiveEntryKind.MESSAGE_ATTACHMENT, "message-2")))
    }

    @Test
    fun assetOwnerAndPerFileHashMustBePresentAndWellFormed() {
        val original = valid()
        reject(original.copy(entries = original.entries.dropLast(1) +
            entry("assets/unowned.png", WorldArchiveEntryKind.NPC_PORTRAIT)))
        reject(original.copy(entries = original.entries.dropLast(1) +
            entry("assets/bad-hash.png", WorldArchiveEntryKind.NPC_PORTRAIT, "npc-1")
                .copy(sha256 = "not-a-sha")))
        reject(original.copy(entries = original.entries.dropLast(1) +
            entry("assets/too-large.png", WorldArchiveEntryKind.MESSAGE_ATTACHMENT, "message-1",
                size = WorldArchiveManifestCodec.MAX_ENTRY_BYTES + 1)))
    }

    @Test
    fun unknownFieldsAndOversizedManifestTextAreRejectedBeforeImport() {
        val validJson = WorldArchiveManifestCodec.encode(valid())
        assertThrows(IllegalArgumentException::class.java) {
            WorldArchiveManifestCodec.parse(
                validJson.replaceFirst("\"format\":", "\"unknown\": true, \"format\":")
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorldArchiveManifestCodec.parse(" ".repeat(WorldArchiveManifestCodec.MAX_MANIFEST_CHARS + 1))
        }
    }
}
