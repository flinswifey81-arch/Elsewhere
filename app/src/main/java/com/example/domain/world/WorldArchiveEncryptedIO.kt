package com.example.domain.world

import com.example.domain.backup.BackupCrypto
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Encrypted, selectively scoped World archive transport.
 *
 * This class NEVER reads/writes Room and NEVER imports or remaps identities. An inspected
 * archive is only a verified, temporary preview. An additive database importer must separately
 * plan and apply every typed AND embedded JSON reference before enabling a Restore action.
 *
 * Pass an app-private cache directory as [workingRoot]. No plaintext files are left outside
 * that root, and all temporary files are deleted on failure and after [VerifiedWorldArchive.close].
 */
class WorldArchiveEncryptedIO(private val workingRoot: File) {
    data class AssetSource(
        val path: String,
        val kind: WorldArchiveEntryKind,
        val ownerId: String,
        val file: File
    )

    class VerifiedWorldArchive internal constructor(
        val manifest: WorldArchiveManifestV1,
        val state: WorldArchiveStateV1,
        val definition: WorldDefinitionV1,
        val assetFiles: Map<String, File>,
        private val directory: File
    ) : Closeable {
        fun preview(): WorldArchiveImportPreview =
            WorldArchiveImportPreview.from(state, manifest)

        /**
         * Creates the proposed *new World* identity plan after authentication.
         * This neither writes records nor merges anyone with an existing namesake.
         * The future Room restore must recheck live destination IDs transactionally.
         */
        fun planNewWorldImport(
            namespace: String,
            reservedIds: Map<WorldArchiveIdKind, Set<String>> = emptyMap()
        ): WorldArchiveIdRemapPlan =
            WorldArchiveIdPlanner.plan(state, namespace, reservedIds)

        override fun close() {
            directory.deleteRecursively()
        }
    }

    /**
     * Packages a fully selected World from ONE immutable snapshot, building its definition
     * from that exact snapshot so cross-file identity/canon cannot drift between reads.
     */
    fun exportSelectedEncrypted(
        state: WorldArchiveStateV1,
        sources: List<AssetSource>,
        output: OutputStream,
        passphrase: CharArray,
        createdAt: Long = System.currentTimeMillis()
    ): WorldArchiveManifestV1 = exportEncrypted(
        state,
        WorldDefinitionCodec.encode(WorldArchiveDefinitionProjection.fromState(state)),
        sources, output, passphrase, createdAt
    )

    /**
     * Writes an AES-GCM encrypted container using the existing full-app backup crypto
     * primitive; the ZIP schema is independent from the full-app backup schema and has NO
     * application-ID restriction. This must not relax the full-app restore policy.
     *
     * [sources] must point at a caller-staged, stable snapshot of each attachment and portrait.
     * The exporter hashes them, checks the final ZIP bytes, then encrypts to a temporary file
     * before copying to [output]. No partial archive is written if validation fails.
     */
    fun exportEncrypted(
        state: WorldArchiveStateV1,
        definitionJson: String,
        sources: List<AssetSource>,
        output: OutputStream,
        passphrase: CharArray,
        createdAt: Long = System.currentTimeMillis()
    ): WorldArchiveManifestV1 {
        val scratch = newDirectory()
        try {
            BackupCrypto.requirePassphrase(passphrase)
            WorldArchiveStateCodec.validate(state)
            val definition = WorldDefinitionCodec.parse(definitionJson)
            require(definition.world.worldId == state.worldId &&
                definition.world.title == state.world.title) {
                "World definition and history disagree about identity or title"
            }
            require(definition.resources.worldCast.map { it.worldCharacterId }.toSet() ==
                state.cast.map { it.worldCharacterId }.toSet()) {
                "World definition would omit or misidentify cast members"
            }
            val acceptedIds = state.continuity.filter { it.status == "ACCEPTED" }
                .map { it.eventId }.toSet()
            require(definition.continuity.map { it.eventId }.toSet() == acceptedIds) {
                "World definition would omit accepted continuity"
            }
            val definitionBytes = WorldDefinitionCodec.encode(definition).toByteArray(Charsets.UTF_8)
            val stateBytes = WorldArchiveStateCodec.encode(state).toByteArray(Charsets.UTF_8)
            require(definitionBytes.size.toLong() in 1..WorldArchiveManifestCodec.MAX_ENTRY_BYTES &&
                stateBytes.size.toLong() in 1..WorldArchiveManifestCodec.MAX_ENTRY_BYTES) {
                "World JSON payload exceeds the per-entry archive limit"
            }
            val entries = mutableListOf(
                entry(WorldArchiveManifestCodec.DEFINITION_PATH,
                    WorldArchiveEntryKind.WORLD_DEFINITION, null, definitionBytes),
                entry(WorldArchiveManifestCodec.STATE_PATH,
                    WorldArchiveEntryKind.WORLD_STATE, null, stateBytes)
            )
            for (asset in sources) {
                require(asset.kind != WorldArchiveEntryKind.WORLD_DEFINITION &&
                    asset.kind != WorldArchiveEntryKind.WORLD_STATE) {
                    "World JSON payloads must come from validated state"
                }
                require(asset.file.isFile) { "World archive asset is missing" }
                val size = asset.file.length()
                require(size in 1..WorldArchiveManifestCodec.MAX_ENTRY_BYTES) {
                    "World archive asset exceeds its size limit"
                }
                entries += WorldArchiveEntryV1(
                    asset.path, asset.kind, size, sha256(asset.file), asset.ownerId
                )
            }
            val manifest = WorldArchiveManifestV1(
                WorldArchiveManifestCodec.FORMAT,
                WorldArchiveManifestCodec.SCHEMA_VERSION,
                state.worldId, createdAt, entries
            )
            WorldArchiveStateCodec.validateWithManifest(state, manifest)
            val manifestBytes = WorldArchiveManifestCodec.encode(manifest)
                .toByteArray(Charsets.UTF_8)

            val plain = File(scratch, "staged-world.zip")
            FileOutputStream(plain).use { fileOutput ->
                ZipOutputStream(fileOutput.buffered()).use { zip ->
                    zip.addBytes(MANIFEST_PATH, manifestBytes)
                    zip.addBytes(WorldArchiveManifestCodec.DEFINITION_PATH, definitionBytes)
                    zip.addBytes(WorldArchiveManifestCodec.STATE_PATH, stateBytes)
                    for (asset in sources) {
                        zip.putNextEntry(ZipEntry(asset.path).apply { time = 0L })
                        FileInputStream(asset.file).use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }

            // Hash and graph validation operate on the bytes ACTUALLY emitted by ZIP, not
            // on a previous read of a file which may have changed during snapshot staging.
            val verified = verifyPlainZip(plain, newDirectory(scratch))
            verified.close()

            val encrypted = File(scratch, "staged-world.encrypted")
            FileInputStream(plain).use { input ->
                FileOutputStream(encrypted).use { encryptedOutput ->
                    BackupCrypto.encrypt(input, encryptedOutput, passphrase)
                }
            }
            FileInputStream(encrypted).use { it.copyTo(output) }
            output.flush()
            return manifest
        } finally {
            passphrase.fill('\u0000')
            scratch.deleteRecursively()
        }
    }

    /**
     * Decrypt and verify every member, then return a read-only temporary snapshot.
     * The caller MUST close the result to delete the decrypted files. No database changes.
     */
    fun inspectEncrypted(input: InputStream, passphrase: CharArray): VerifiedWorldArchive {
        val scratch = newDirectory()
        try {
            val plain = File(scratch, "decrypted-world.zip")
            FileOutputStream(plain).use { output ->
                BackupCrypto.decrypt(input, output, passphrase)
            }
            val inspection = verifyPlainZip(plain, newDirectory(scratch))
            // Keep only the inspected data, not a second copy of the decrypted archive.
            plain.delete()
            return VerifiedWorldArchive(
                inspection.manifest, inspection.state, inspection.definition,
                inspection.assetFiles, scratch
            )
        } catch (failure: Exception) {
            scratch.deleteRecursively()
            throw failure
        } finally {
            passphrase.fill('\u0000')
        }
    }

    private fun verifyPlainZip(file: File, destination: File): VerifiedWorldArchive {
        try {
            var manifest: WorldArchiveManifestV1? = null
            val found = mutableSetOf<String>()
            var total = 0L
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            ZipInputStream(FileInputStream(file).buffered()).use { zip ->
                val first = zip.nextEntry
                require(first != null && first.name == MANIFEST_PATH && !first.isDirectory) {
                    "World archive must begin with manifest.json"
                }
                val firstBytes = readBounded(zip, WorldArchiveManifestCodec.MAX_MANIFEST_CHARS.toLong())
                manifest = WorldArchiveManifestCodec.parse(firstBytes.toString(Charsets.UTF_8))
                zip.closeEntry()
                val entries = requireNotNull(manifest).entries.associateBy { it.path }
                var count = 0
                while (true) {
                    val next = zip.nextEntry ?: break
                    count++
                    require(count <= WorldArchiveManifestCodec.MAX_ENTRIES && !next.isDirectory) {
                        "World archive contains an invalid number or type of entries"
                    }
                    val expected = requireNotNull(entries[next.name]) {
                        "World archive contains an unexpected file"
                    }
                    require(found.add(next.name)) { "Duplicate World archive file" }
                    val outputFile = safeDestination(destination, next.name)
                    require(outputFile.parentFile?.mkdirs() == true ||
                        outputFile.parentFile?.isDirectory == true) {
                        "Unable to stage World archive member"
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    FileOutputStream(outputFile).use { output ->
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            size += read
                            total += read
                            require(size <= expected.byteSize &&
                                total <= WorldArchiveManifestCodec.MAX_TOTAL_BYTES) {
                                "World archive exceeds the declared size or total limit"
                            }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                    }
                    require(size == expected.byteSize && digest.digest().hex() == expected.sha256) {
                        "World archive file checksum or byte length is invalid"
                    }
                    zip.closeEntry()
                }
                require(found == entries.keys) { "World archive is missing a required file" }
            }
            val actual = requireNotNull(manifest)
            val definitionFile = safeDestination(destination, WorldArchiveManifestCodec.DEFINITION_PATH)
            val stateFile = safeDestination(destination, WorldArchiveManifestCodec.STATE_PATH)
            val definition = WorldDefinitionCodec.parse(definitionFile.readText(Charsets.UTF_8))
            val state = WorldArchiveStateCodec.parse(stateFile.readText(Charsets.UTF_8))
            require(definition.world.worldId == state.worldId &&
                definition.world.title == state.world.title) {
                "World definition and history identity do not match"
            }
            require(definition.resources.worldCast.map { it.worldCharacterId }.toSet() ==
                state.cast.map { it.worldCharacterId }.toSet()) {
                "World definition cast does not match archived history"
            }
            require(definition.continuity.map { it.eventId }.toSet() ==
                state.continuity.filter { it.status == "ACCEPTED" }.map { it.eventId }.toSet()) {
                "World definition continuity does not match archived history"
            }
            WorldArchiveStateCodec.validateWithManifest(state, actual)
            val files = actual.entries.filter {
                it.kind != WorldArchiveEntryKind.WORLD_DEFINITION &&
                    it.kind != WorldArchiveEntryKind.WORLD_STATE
            }.associate { it.path to safeDestination(destination, it.path) }
            return VerifiedWorldArchive(actual, state, definition, files, destination)
        } catch (failure: Exception) {
            destination.deleteRecursively()
            throw failure
        }
    }

    private fun newDirectory(parent: File = workingRoot): File {
        require(parent.mkdirs() || parent.isDirectory) { "World archive workspace is unavailable" }
        return Files.createTempDirectory(parent.toPath(), "elsewhere-world-").toFile()
    }

    private fun safeDestination(root: File, path: String): File {
        val base = root.canonicalFile
        val target = File(base, path).canonicalFile
        require(target.path.startsWith(base.path + File.separator)) {
            "World archive member escapes the working directory"
        }
        return target
    }

    private fun ZipOutputStream.addBytes(path: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(path).apply { time = 0L })
        write(bytes)
        closeEntry()
    }

    private fun readBounded(input: InputStream, limit: Long): ByteArray {
        val stream = java.io.ByteArrayOutputStream()
        val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(bytes)
            if (read < 0) break
            total += read
            require(total <= limit) { "World archive manifest is oversized" }
            stream.write(bytes, 0, read)
        }
        return stream.toByteArray()
    }

    private fun entry(path: String, kind: WorldArchiveEntryKind, owner: String?, bytes: ByteArray) =
        WorldArchiveEntryV1(path, kind, bytes.size.toLong(),
            MessageDigest.getInstance("SHA-256").digest(bytes).hex(), owner)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { stream ->
            val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val n = stream.read(bytes)
                if (n < 0) break
                digest.update(bytes, 0, n)
            }
        }
        return digest.digest().hex()
    }

    private fun ByteArray.hex(): String =
        joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }

    companion object {
        private const val MANIFEST_PATH = "manifest.json"
    }
}
