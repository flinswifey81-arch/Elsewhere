package com.example.domain.world

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.Locale

/**
 * First, PURE validation layer for a future selective encrypted World archive.
 *
 * This is not an archive reader/writer or restore API. It never writes to Room, extracts ZIP
 * entries, reads filesystem paths, or decrypts data. Callers must independently authenticate
 * the encrypted container, verify every hash/length against bytes before extracting assets,
 * validate the payload graph, and complete a transactional import plan before any mutation.
 *
 * Unlike whole-app backup, this future format may cross Test/production app variants.
 * It must NOT weaken the whole-app backup restriction or carry provider credentials.
 */
@JsonClass(generateAdapter = true)
data class WorldArchiveManifestV1(
    val format: String,
    @Json(name = "schema_version") val schemaVersion: Int,
    @Json(name = "world_id") val worldId: String,
    @Json(name = "created_at") val createdAt: Long,
    val entries: List<WorldArchiveEntryV1>
)

enum class WorldArchiveEntryKind {
    WORLD_DEFINITION,
    WORLD_STATE,
    MESSAGE_ATTACHMENT,
    CHARACTER_AVATAR,
    PERSONA_AVATAR,
    SCENARIO_AVATAR,
    NPC_PORTRAIT
}

@JsonClass(generateAdapter = true)
data class WorldArchiveEntryV1(
    val path: String,
    val kind: WorldArchiveEntryKind,
    @Json(name = "byte_size") val byteSize: Long,
    val sha256: String,
    @Json(name = "owner_id") val ownerId: String? = null
)

/**
 * Strict *manifest* contract. Import/export implementation comes later and must revalidate
 * actual ZIP member names, the uncompressed byte sizes, hashes, and object references.
 */
object WorldArchiveManifestCodec {
    const val FORMAT = "elsewhere_world_archive"
    const val SCHEMA_VERSION = 1
    const val DEFINITION_PATH = "world/definition.json"
    const val STATE_PATH = "world/state.json"
    const val MAX_MANIFEST_CHARS = 1_000_000
    const val MAX_ENTRIES = 50_000
    const val MAX_ENTRY_BYTES = 200L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
    private val sha256Pattern = Regex("[a-f0-9]{64}")
    private val segmentPattern = Regex("[A-Za-z0-9._-]+")

    private val adapter = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
        .adapter(WorldArchiveManifestV1::class.java)
        .failOnUnknown()
        .indent("  ")

    fun parse(json: String): WorldArchiveManifestV1 {
        require(json.length in 1..MAX_MANIFEST_CHARS) { "World archive manifest is empty or oversized" }
        val parsed = requireNotNull(adapter.fromJson(json)) { "World archive manifest is empty" }
        validate(parsed)
        return parsed
    }

    fun encode(manifest: WorldArchiveManifestV1): String {
        validate(manifest)
        return adapter.toJson(manifest)
    }

    fun validate(manifest: WorldArchiveManifestV1) {
        require(manifest.format == FORMAT) { "Unsupported World archive format" }
        require(manifest.schemaVersion == SCHEMA_VERSION) { "Unsupported World archive version" }
        require(manifest.worldId.length in 1..128 && manifest.worldId == manifest.worldId.trim()) {
            "World archive ID is invalid"
        }
        require(manifest.createdAt > 0L) { "World archive creation time is invalid" }
        require(manifest.entries.size in 2..MAX_ENTRIES) { "World archive file count is invalid" }

        var cumulativeBytes = 0L
        val seenPaths = mutableSetOf<String>()
        for (entry in manifest.entries) {
            validateEntryPath(entry.path)
            require(seenPaths.add(entry.path.lowercase(Locale.ROOT))) {
                "World archive contains duplicate or case-colliding paths"
            }
            require(entry.sha256.matches(sha256Pattern)) { "World archive SHA-256 must be lowercase hex" }
            require(entry.byteSize in 1..MAX_ENTRY_BYTES) { "World archive entry size is invalid" }
            require(entry.byteSize <= MAX_TOTAL_BYTES - cumulativeBytes) {
                "World archive exceeds the supported total size"
            }
            cumulativeBytes += entry.byteSize

            when (entry.kind) {
                WorldArchiveEntryKind.WORLD_DEFINITION -> {
                    require(entry.path == DEFINITION_PATH && entry.ownerId == null) {
                        "World definition must use its reserved archive path"
                    }
                }
                WorldArchiveEntryKind.WORLD_STATE -> {
                    require(entry.path == STATE_PATH && entry.ownerId == null) {
                        "World state must use its reserved archive path"
                    }
                }
                else -> {
                    require(entry.path.startsWith("assets/") && entry.path.length > "assets/".length) {
                        "World archive assets must be inside the assets directory"
                    }
                    require(entry.ownerId?.isNotBlank() == true && entry.ownerId.length <= 128) {
                        "World archive asset owner ID is required"
                    }
                }
            }
        }
        require(manifest.entries.count { it.kind == WorldArchiveEntryKind.WORLD_DEFINITION } == 1) {
            "World archive requires one World definition"
        }
        require(manifest.entries.count { it.kind == WorldArchiveEntryKind.WORLD_STATE } == 1) {
            "World archive requires one World state payload"
        }
    }

    private fun validateEntryPath(path: String) {
        require(path.length in 1..240 && path == path.trim() && !path.startsWith("/")) {
            "Unsafe World archive entry path"
        }
        require(path.none { it == '\\' || it == ':' || it == '%' || it.code < 32 }) {
            "Unsafe World archive entry path"
        }
        val segments = path.split('/')
        require(segments.isNotEmpty() && segments.all {
            it != "." && it != ".." && it.matches(segmentPattern)
        }) { "Unsafe World archive entry path" }
    }
}
