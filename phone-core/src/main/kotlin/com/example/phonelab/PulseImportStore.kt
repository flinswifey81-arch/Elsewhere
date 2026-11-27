package com.example.phonelab

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

/**
 * Private on-device storage. On Android pass a subdirectory of Context.getNoBackupFilesDir().
 * One atomic snapshot per World keeps the template and approved campaign starter consistent.
 */
class PulseImportStore(private val directory: Path, private val membership: Membership) {
    /** Resolve scope from local records, never from the imported pack. */
    interface Membership {
        fun worldExists(worldId: String): Boolean
        fun campaignBelongsToWorld(worldId: String, campaignId: String): Boolean
    }

    class Preview internal constructor(
        internal val owner: PulseImportStore,
        internal val pack: PulseFeedPack,
        val worldId: String,
        val campaignId: String?,
    ) {
        val name get() = pack.name
        val platforms get() = pack.platforms
        val accounts get() = pack.accounts
        val publicEvents get() = pack.publicEvents
        val posts get() = pack.posts
        val replies get() = pack.replies
        /** Local review only. Do not feed arbitrary template fields to a model automatically. */
        fun templateForReview(): String = pack.templateJson
        /** Local review only. Confirm that this material is public in-world before importing it. */
        fun starterForReview(): String? = pack.starterJson
    }

    data class Receipt(val addedTemplate: Boolean, val addedStarter: Boolean, val templateId: String, val starterId: String?)
    data class Starter(val templateId: String, val stateJson: String)

    fun preview(content: ByteArray, worldId: String, campaignId: String? = null): Preview {
        checkScope(worldId, campaignId)
        return Preview(this, PulseFeedPack.parse(content), worldId, campaignId)
    }

    /** Invoke after local review. Using the starter requires separate approval and a playthrough. */
    fun apply(preview: Preview, reviewedUseStarter: Boolean = false): Receipt {
        require(preview.owner === this) { "Preview belongs to another import store." }
        val (world, campaign, pack) = Triple(preview.worldId, preview.campaignId, preview.pack)
        require(!reviewedUseStarter || (campaign != null && pack.starterId != null)) {
            "Select a playthrough and a pack with starter content."
        }
        return access(world, campaign, create = true) { path, contents ->
            val changedTemplate = section(contents, "templates").putIfAbsent(pack.templateId, pack.templateJson) == null
            val changedStarter = if (reviewedUseStarter) {
                val starters = section(section(contents, "campaigns"), requireNotNull(campaign))
                starters.putIfAbsent(requireNotNull(pack.starterId),
                    mapOf("templateId" to pack.templateId, "stateJson" to pack.starterJson)) == null
            } else false
            if (changedTemplate || changedStarter) write(path, contents)
            Receipt(changedTemplate, changedStarter, pack.templateId, pack.starterId.takeIf { reviewedUseStarter })
        }
    }

    fun templates(worldId: String): Map<String, String> = access(worldId, null) { _, contents ->
        section(contents, "templates").mapValues { (_, value) ->
            value as? String ?: throw IOException("Stored feed has an invalid template.")
        }
    }

    /** Returns only campaign-local approved starter data; this class has no read path to private texts. */
    fun campaignStarters(worldId: String, campaignId: String): Map<String, Starter> =
        access(worldId, campaignId) { _, contents ->
            val templates = section(contents, "templates")
            val rows = section(section(contents, "campaigns"), campaignId)
            rows.keys.associateWith { id ->
                val row = section(rows, id)
                val template = row["templateId"] as? String
                val state = row["stateJson"] as? String
                if (template == null || templates[template] !is String || state == null)
                    throw IOException("Stored campaign starter has no matching World template.")
                Starter(template, state)
            }
        }

    /** Remove only this campaign's seeds before erasing its membership. */
    fun deleteCampaignStarters(worldId: String, campaignId: String): Boolean =
        access(worldId, campaignId) { path, contents ->
            if (Files.exists(path) && section(contents, "campaigns").remove(campaignId) != null) {
                write(path, contents)
                true
            } else false
        }

    private fun checkScope(world: String, campaign: String?) {
        require(usable(world) && membership.worldExists(world)) { "Home World not found." }
        require(campaign == null || (usable(campaign) && membership.campaignBelongsToWorld(world, campaign))) {
            "Campaign not found in the selected World."
        }
    }

    private fun usable(id: String): Boolean = id.isNotBlank() && id.length <= 200 &&
        '\u0000' !in id && Charsets.UTF_8.newEncoder().canEncode(id)

    private fun <T> access(
        world: String,
        campaign: String?,
        create: Boolean = false,
        operation: (Path, MutableMap<String, Any?>) -> T,
    ): T = synchronized(IN_PROCESS) {
        checkScope(world, campaign)
        val stem = pulseDigest(world)
        val path = directory.resolve("$stem.json")
        if (!create && !Files.exists(path)) return@synchronized operation(path, linkedMapOf())
        Files.createDirectories(directory)
        FileChannel.open(directory.resolve("$stem.lock"), CREATE, WRITE).use { channel ->
            channel.lock().use {
                // Recheck after a preview, including if a campaign was removed while waiting.
                checkScope(world, campaign)
                operation(path, read(path))
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun read(path: Path): MutableMap<String, Any?> {
        if (!Files.exists(path)) return linkedMapOf()
        if (Files.size(path) > MAX_WORLD_BYTES) throw IOException("Stored feed exceeds its local size limit.")
        return try {
            PulseJson.parse(String(Files.readAllBytes(path), Charsets.UTF_8)) as? MutableMap<String, Any?>
                ?: throw IOException("Expected stored object.")
        } catch (e: IllegalArgumentException) {
            throw IOException("Stored feed could not be read.", e)
        }
    }

    private fun write(path: Path, contents: Map<String, Any?>) {
        val bytes = PulseJson.canonical(contents).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_WORLD_BYTES) throw IOException("This World is at the local feed storage limit.")
        val temp = Files.createTempFile(path.parent, ".pulse-", ".tmp")
        try {
            FileChannel.open(temp, WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temp, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun section(contents: MutableMap<String, Any?>, name: String): MutableMap<String, Any?> {
        if (!contents.containsKey(name)) contents[name] = linkedMapOf<String, Any?>()
        return contents[name] as? MutableMap<String, Any?> ?: throw IOException("Stored feed has an invalid section.")
    }

    private companion object {
        val IN_PROCESS = Any()
        const val MAX_WORLD_BYTES = 16 * 1024 * 1024
    }
}
