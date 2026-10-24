package com.example.domain.backup

import com.example.data.APP_DATABASE_VERSION
import com.squareup.moshi.Moshi
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal class BackupArchiveIO(
    moshi: Moshi,
    private val acceptedApplicationIds: Set<String>
) {
    constructor(moshi: Moshi, expectedApplicationId: String) : this(moshi, setOf(expectedApplicationId))

    init {
        require(acceptedApplicationIds.isNotEmpty() && acceptedApplicationIds.none(String::isBlank)) {
            "At least one non-blank backup application ID is required."
        }
    }

    private val manifestAdapter = moshi.adapter(BackupManifest::class.java).indent("  ")
    private val payloadAdapter = moshi.adapter(BackupPayload::class.java).indent("  ")

    fun payloadToJson(payload: BackupPayload): String = payloadAdapter.toJson(payload)

    fun writePlainZip(snapshot: BackupSnapshot, output: OutputStream) {
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putTextEntry(MANIFEST_ENTRY, manifestAdapter.toJson(snapshot.manifest))
            zip.putTextEntry(PAYLOAD_ENTRY, snapshot.payloadJson)
            snapshot.binaries.forEach { source ->
                validateEntryName(source.manifest.entryName)
                zip.putNextEntry(ZipEntry(source.manifest.entryName).apply { time = 0L })
                FileInputStream(source.file).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun readPlainZip(input: InputStream, workingDirectory: File): ValidatedBackup {
        require(workingDirectory.mkdirs() || workingDirectory.isDirectory) {
            "Backup validation workspace is unavailable."
        }
        val seen = mutableSetOf<String>()
        var totalBytes = 0L
        var entryCount = 0
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount += 1
                if (entryCount > MAX_ENTRY_COUNT) throw BackupValidationException("Backup contains too many files.")
                val name = entry.name
                validateEntryName(name)
                if (!seen.add(name)) throw BackupValidationException("Backup contains a duplicate entry: $name")
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                val destination = safeDestination(workingDirectory, name)
                destination.parentFile?.mkdirs()
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var entryBytes = 0L
                    val entryLimit = when (name) {
                        MANIFEST_ENTRY -> MAX_MANIFEST_BYTES
                        PAYLOAD_ENTRY -> MAX_PAYLOAD_BYTES
                        else -> MAX_SINGLE_ENTRY_BYTES
                    }
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        entryBytes += read
                        totalBytes += read
                        if (entryBytes > entryLimit || totalBytes > MAX_TOTAL_BYTES) {
                            throw BackupValidationException("Backup exceeds the supported size limit.")
                        }
                        output.write(buffer, 0, read)
                    }
                }
                zip.closeEntry()
            }
        }

        val manifestFile = File(workingDirectory, MANIFEST_ENTRY)
        val payloadFile = File(workingDirectory, PAYLOAD_ENTRY)
        if (!manifestFile.isFile || !payloadFile.isFile) {
            throw BackupValidationException("Backup is missing its manifest or data payload.")
        }
        val manifest = runCatching { manifestAdapter.fromJson(manifestFile.readText()) }
            .getOrElse { throw BackupValidationException("Backup manifest is malformed.", it) }
            ?: throw BackupValidationException("Backup manifest is empty.")
        validateManifest(manifest)
        if (sha256(payloadFile) != manifest.payloadSha256) {
            throw BackupValidationException("Backup data checksum does not match the manifest.")
        }
        val payload = runCatching { payloadAdapter.fromJson(payloadFile.readText()) }
            .getOrElse { throw BackupValidationException("Backup data is malformed.", it) }
            ?: throw BackupValidationException("Backup data is empty.")

        val expectedBinaryNames = manifest.files.map { it.entryName }.toSet()
        if (expectedBinaryNames.size != manifest.files.size) {
            throw BackupValidationException("Backup manifest contains duplicate file entries.")
        }
        val actualBinaryNames = seen - setOf(MANIFEST_ENTRY, PAYLOAD_ENTRY)
        if (actualBinaryNames != expectedBinaryNames) {
            throw BackupValidationException("Backup file entries do not match the manifest.")
        }
        val extracted = manifest.files.associate { fileManifest ->
            validateEntryName(fileManifest.entryName)
            val file = safeDestination(workingDirectory, fileManifest.entryName)
            if (!file.isFile || file.length() != fileManifest.byteSize || sha256(file) != fileManifest.sha256) {
                throw BackupValidationException("Backup file is missing or corrupted: ${fileManifest.entryName}")
            }
            fileManifest.entryName to file
        }
        return ValidatedBackup(manifest, payload, extracted, workingDirectory)
    }

    fun validateManifest(manifest: BackupManifest) {
        if (manifest.formatVersion != FORMAT_VERSION) {
            throw BackupValidationException("Unsupported backup format version: ${manifest.formatVersion}")
        }
        if (manifest.applicationId !in acceptedApplicationIds) {
            throw BackupValidationException("This backup belongs to a different application.")
        }
        if (manifest.databaseVersion > APP_DATABASE_VERSION) {
            throw BackupValidationException(
                "This backup was created by a newer database version (${manifest.databaseVersion})."
            )
        }
        if (manifest.files.size > MAX_ENTRY_COUNT - 2) {
            throw BackupValidationException("Backup manifest contains too many file entries.")
        }
        manifest.files.forEach { file ->
            validateEntryName(file.entryName)
            if (!file.entryName.startsWith("files/")) {
                throw BackupValidationException("Backup binary entry is outside the files directory.")
            }
            if (file.ownerId.isBlank() || file.byteSize !in 1..MAX_SINGLE_ENTRY_BYTES) {
                throw BackupValidationException("Backup file metadata is invalid: ${file.entryName}")
            }
            if (!file.sha256.matches(Regex("[0-9A-F]{64}"))) {
                throw BackupValidationException("Backup file checksum is invalid: ${file.entryName}")
            }
        }
    }

    companion object {
        const val FORMAT_VERSION = 1
        const val MANIFEST_ENTRY = "manifest.json"
        const val PAYLOAD_ENTRY = "data.json"
        const val MAX_ENTRY_COUNT = 10_000
        const val MAX_MANIFEST_BYTES = 512L * 1024L
        const val MAX_PAYLOAD_BYTES = 32L * 1024L * 1024L
        const val MAX_SINGLE_ENTRY_BYTES = 8L * 1024L * 1024L
        const val MAX_TOTAL_BYTES = 512L * 1024L * 1024L

        fun sha256(file: File): String = file.inputStream().use(::sha256)

        fun sha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            return digest.digest().joinToString("") { "%02X".format(it) }
        }

        private fun validateEntryName(name: String) {
            if (
                name.isBlank() || name.length > 512 || name.startsWith("/") || name.startsWith("\\") ||
                name.contains('\\') || name.split('/').any { it.isBlank() || it == "." || it == ".." }
            ) {
                throw BackupValidationException("Backup contains an unsafe entry name.")
            }
        }

        private fun safeDestination(root: File, entryName: String): File {
            val canonicalRoot = root.canonicalFile
            val destination = File(canonicalRoot, entryName).canonicalFile
            if (!destination.path.startsWith(canonicalRoot.path + File.separator)) {
                throw BackupValidationException("Backup entry escapes the validation directory.")
            }
            return destination
        }

        private fun ZipOutputStream.putTextEntry(name: String, value: String) {
            putNextEntry(ZipEntry(name).apply { time = 0L })
            write(value.toByteArray(Charsets.UTF_8))
            closeEntry()
        }
    }
}
