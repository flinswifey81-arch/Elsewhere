package com.example.domain.repository

import com.example.data.LorebookDao
import com.example.data.model.ChatLorebookAttachmentEntity
import com.example.data.model.ChatLorebookEntryOverlayEntity
import com.example.data.model.ChatLorebookState
import com.example.data.model.LorebookEntity
import com.example.data.model.LorebookEntryEntity
import com.example.data.model.LorebookWithEntries
import com.example.data.model.MessageEntity
import com.example.domain.lore.ActiveLoreEntry
import com.example.domain.lore.LorebookActivationEngine
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.DateTimeException
import java.time.Instant
import java.util.UUID

enum class LorebookImportMode { REJECT_DUPLICATE, IMPORT_AS_NEW_COPY, REPLACE_EXISTING }

data class LorebookImportPreview(
    val lorebook: LorebookEntity,
    val entries: List<LorebookEntryEntity>,
    val duplicateLorebookId: Boolean,
    val conflictingEntryIds: List<String>
)

class LorebookValidationException(message: String) : IllegalArgumentException(message)
class LorebookDuplicateIdException(val duplicateId: String) :
    IllegalStateException("Lorebook ID already exists: $duplicateId")

class LorebookRepository(
    private val lorebookDao: LorebookDao,
    private val moshi: Moshi
) {
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )

    fun observeLorebooks(): Flow<List<LorebookEntity>> = lorebookDao.observeLorebooks()
    fun observeEntries(lorebookId: String): Flow<List<LorebookEntryEntity>> = lorebookDao.observeEntries(lorebookId)

    suspend fun getLorebook(lorebookId: String): LorebookWithEntries? = withContext(Dispatchers.IO) {
        lorebookDao.getLorebook(lorebookId)?.let { LorebookWithEntries(it, lorebookDao.getEntries(lorebookId)) }
    }

    suspend fun saveDefinition(lorebook: LorebookEntity, entries: List<LorebookEntryEntity>) =
        withContext(Dispatchers.IO) {
            validateDefinition(lorebook, entries)
            lorebookDao.saveDefinition(lorebook, entries)
        }

    suspend fun deleteLorebook(lorebookId: String) = withContext(Dispatchers.IO) {
        lorebookDao.deleteLorebook(lorebookId)
    }

    fun newLorebook(name: String = "New Lorebook"): LorebookWithEntries {
        val now = System.currentTimeMillis()
        return LorebookWithEntries(
            lorebook = LorebookEntity(name = name, createdAt = now, updatedAt = now),
            entries = emptyList()
        )
    }

    fun newEntry(lorebookId: String): LorebookEntryEntity {
        val now = System.currentTimeMillis()
        return LorebookEntryEntity(
            lorebookId = lorebookId,
            title = "New Entry",
            createdAt = now,
            updatedAt = now
        )
    }

    suspend fun previewImport(json: String): LorebookImportPreview = withContext(Dispatchers.IO) {
        val parsed = parseLorebookJson(json)
        LorebookImportPreview(
            lorebook = parsed.lorebook,
            entries = parsed.entries,
            duplicateLorebookId = lorebookDao.getLorebook(parsed.lorebook.lorebookId) != null,
            conflictingEntryIds = findForeignEntryIdConflicts(parsed)
        )
    }

    suspend fun importJson(json: String, mode: LorebookImportMode): String = withContext(Dispatchers.IO) {
        val parsed = parseLorebookJson(json)
        val existing = lorebookDao.getLorebook(parsed.lorebook.lorebookId)
        val conflictingEntryIds = if (mode == LorebookImportMode.IMPORT_AS_NEW_COPY) {
            emptyList()
        } else {
            findForeignEntryIdConflicts(parsed)
        }
        if (conflictingEntryIds.isNotEmpty()) {
            throw LorebookValidationException(
                "Entry IDs already belong to another Lorebook: ${conflictingEntryIds.joinToString()}"
            )
        }
        when (mode) {
            LorebookImportMode.REJECT_DUPLICATE -> {
                if (existing != null) throw LorebookDuplicateIdException(parsed.lorebook.lorebookId)
                lorebookDao.saveDefinition(parsed.lorebook, parsed.entries)
                parsed.lorebook.lorebookId
            }
            LorebookImportMode.IMPORT_AS_NEW_COPY -> {
                val newBookId = UUID.randomUUID().toString()
                val copiedBook = parsed.lorebook.copy(
                    lorebookId = newBookId,
                    name = copyName(parsed.lorebook.name),
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                val copiedEntries = parsed.entries.map { entry ->
                    entry.copy(
                        entryId = UUID.randomUUID().toString(),
                        lorebookId = newBookId,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                }
                lorebookDao.saveDefinition(copiedBook, copiedEntries)
                newBookId
            }
            LorebookImportMode.REPLACE_EXISTING -> {
                lorebookDao.replaceImportedDefinition(parsed.lorebook, parsed.entries)
                parsed.lorebook.lorebookId
            }
        }
    }

    private suspend fun findForeignEntryIdConflicts(parsed: LorebookWithEntries): List<String> =
        parsed.entries.mapNotNull { entry ->
            lorebookDao.getEntry(entry.entryId)
                ?.takeIf { it.lorebookId != parsed.lorebook.lorebookId }
                ?.let { entry.entryId }
        }.sorted()

    suspend fun exportJson(lorebookId: String): String = withContext(Dispatchers.IO) {
        val book = lorebookDao.getLorebook(lorebookId) ?: throw IllegalArgumentException("Lorebook not found")
        val entries = lorebookDao.getEntries(lorebookId)
        val root = linkedMapOf<String, Any?>(
            "schema_version" to book.schemaVersion,
            "type" to TYPE,
            "id" to book.lorebookId,
            "name" to book.name,
            "description" to book.description,
            "enabled" to book.enabled,
            "scope" to linkedMapOf(
                "type" to book.scopeType,
                "story_id" to book.storyId
            ),
            "entries" to entries.map { entry ->
                linkedMapOf<String, Any?>(
                    "id" to entry.entryId,
                    "title" to entry.title,
                    "enabled" to entry.enabled,
                    "activation" to linkedMapOf(
                        "mode" to entry.activationMode,
                        "keys" to entry.keys,
                        "secondary_keys" to entry.secondaryKeys,
                        "match" to entry.matchMode,
                        "case_sensitive" to entry.caseSensitive,
                        "whole_words" to entry.wholeWords
                    ),
                    "canon" to linkedMapOf(
                        "content" to entry.canonContent,
                        "locked" to entry.canonLocked
                    ),
                    "current" to linkedMapOf("content" to entry.currentContent),
                    "injection" to linkedMapOf(
                        "priority" to entry.priority,
                        "position" to entry.position
                    ),
                    "tags" to entry.tags,
                    "notes" to entry.notes,
                    "created_at" to Instant.ofEpochMilli(entry.createdAt).toString(),
                    "updated_at" to Instant.ofEpochMilli(entry.updatedAt).toString()
                )
            }
        )
        mapAdapter.indent("  ").toJson(root)
    }

    fun parseLorebookJson(json: String): LorebookWithEntries {
        if (json.toByteArray(Charsets.UTF_8).size > MAX_JSON_BYTES) {
            throw LorebookValidationException("Lorebook JSON exceeds the 2 MB limit")
        }
        val root = try {
            mapAdapter.fromJson(json)
        } catch (error: JsonDataException) {
            throw LorebookValidationException("Malformed Lorebook JSON: ${error.message}")
        } catch (error: java.io.IOException) {
            throw LorebookValidationException("Malformed Lorebook JSON: ${error.message}")
        } ?: throw LorebookValidationException("Lorebook JSON must contain one object")

        root.rejectUnknown(ROOT_FIELDS, "root")
        val schemaVersion = root.requireInt("schema_version", "root")
        if (schemaVersion != SCHEMA_VERSION) throw LorebookValidationException("Unsupported schema_version: $schemaVersion")
        val type = root.requireString("type", "root")
        if (type != TYPE) throw LorebookValidationException("type must be '$TYPE'")
        val lorebookId = root.requireId("id", "root")
        val name = root.requireString("name", "root", MAX_NAME_LENGTH)
        val description = root.optionalString("description", "root", MAX_DESCRIPTION_LENGTH) ?: ""
        val enabled = root.optionalBoolean("enabled", "root") ?: true
        val scope = if (root.containsKey("scope")) root["scope"].asObject("scope") else null
        scope?.rejectUnknown(SCOPE_FIELDS, "scope")
        val scopeType = scope?.optionalString("type", "scope", MAX_ID_LENGTH) ?: "story"
        if (scopeType != "story") throw LorebookValidationException("Unsupported scope.type '$scopeType'; Lorebook v1 supports only 'story'")
        val storyId = scope?.optionalNullableString("story_id", "scope", MAX_ID_LENGTH)

        val rawEntries = root["entries"] as? List<*>
            ?: throw LorebookValidationException("root.entries must be an array")
        if (rawEntries.size > MAX_ENTRIES) throw LorebookValidationException("A Lorebook may contain at most $MAX_ENTRIES entries")
        val now = System.currentTimeMillis()
        val entryIds = mutableSetOf<String>()
        val entries = rawEntries.mapIndexed { index, raw ->
            val path = "entries[$index]"
            val item = raw.asObject(path)
            item.rejectUnknown(ENTRY_FIELDS, path)
            val entryId = item.requireId("id", path)
            if (!entryIds.add(entryId)) throw LorebookValidationException("Duplicate entry ID: $entryId")
            val activation = item["activation"].asObject("$path.activation")
            activation.rejectUnknown(ACTIVATION_FIELDS, "$path.activation")
            val mode = activation.requireString("mode", "$path.activation")
            if (mode !in ACTIVATION_MODES) throw LorebookValidationException("$path.activation.mode must be keyword, always, or manual")
            val matchMode = activation.optionalString("match", "$path.activation", 16) ?: "any"
            if (matchMode !in MATCH_MODES) throw LorebookValidationException("$path.activation.match must be any or all")
            val canon = item["canon"].asObject("$path.canon")
            canon.rejectUnknown(CANON_FIELDS, "$path.canon")
            val current = item["current"].asObject("$path.current")
            current.rejectUnknown(CURRENT_FIELDS, "$path.current")
            val injection = item["injection"].asObject("$path.injection")
            injection.rejectUnknown(INJECTION_FIELDS, "$path.injection")
            val position = injection.optionalString("position", "$path.injection", 32) ?: "lore"
            if (position != "lore") throw LorebookValidationException("Unsupported injection.position '$position'; Lorebook v1 supports only 'lore'")
            LorebookEntryEntity(
                entryId = entryId,
                lorebookId = lorebookId,
                title = item.requireString("title", path, MAX_TITLE_LENGTH),
                enabled = item.optionalBoolean("enabled", path) ?: true,
                activationMode = mode,
                keys = activation.optionalStringList("keys", "$path.activation") ?: emptyList(),
                secondaryKeys = activation.optionalStringList("secondary_keys", "$path.activation") ?: emptyList(),
                matchMode = matchMode,
                caseSensitive = activation.optionalBoolean("case_sensitive", "$path.activation") ?: false,
                wholeWords = activation.optionalBoolean("whole_words", "$path.activation") ?: true,
                canonContent = canon.requireStringAllowEmpty("content", "$path.canon", MAX_CONTENT_LENGTH),
                canonLocked = canon.optionalBoolean("locked", "$path.canon") ?: true,
                currentContent = current.requireStringAllowEmpty("content", "$path.current", MAX_CONTENT_LENGTH),
                priority = injection.optionalInt("priority", "$path.injection") ?: 50,
                position = position,
                tags = item.optionalStringList("tags", path) ?: emptyList(),
                notes = item.optionalString("notes", path, MAX_NOTES_LENGTH) ?: "",
                createdAt = item.optionalTimestamp("created_at", path) ?: now,
                updatedAt = item.optionalTimestamp("updated_at", path) ?: now
            )
        }
        val book = LorebookEntity(
            lorebookId = lorebookId,
            schemaVersion = schemaVersion,
            name = name,
            description = description,
            enabled = enabled,
            scopeType = scopeType,
            storyId = storyId,
            createdAt = entries.minOfOrNull { it.createdAt } ?: now,
            updatedAt = entries.maxOfOrNull { it.updatedAt } ?: now
        )
        validateDefinition(book, entries)
        return LorebookWithEntries(book, entries)
    }

    suspend fun getChatLorebookStates(chatId: String): List<ChatLorebookState> = withContext(Dispatchers.IO) {
        val books = lorebookDao.getLorebooksOnce()
        val attachments = lorebookDao.getAttachments(chatId).associateBy { it.lorebookId }
        val overlays = lorebookDao.getOverlays(chatId).associateBy { it.entryId }
        books.map { book ->
            val entries = lorebookDao.getEntries(book.lorebookId)
            ChatLorebookState(
                lorebook = book,
                entries = entries,
                attachment = attachments[book.lorebookId],
                overlays = entries.mapNotNull { entry -> overlays[entry.entryId]?.let { entry.entryId to it } }.toMap()
            )
        }
    }

    suspend fun attachLorebook(chatId: String, lorebookId: String) = withContext(Dispatchers.IO) {
        requireNotNull(lorebookDao.getLorebook(lorebookId)) { "Lorebook not found" }
        val existing = lorebookDao.getAttachments(chatId)
        val current = existing.firstOrNull { it.lorebookId == lorebookId }
        lorebookDao.upsertAttachment(
            current ?: ChatLorebookAttachmentEntity(
                chatId = chatId,
                lorebookId = lorebookId,
                sortPosition = (existing.maxOfOrNull { it.sortPosition } ?: -1) + 1
            )
        )
    }

    suspend fun detachLorebook(chatId: String, lorebookId: String) = withContext(Dispatchers.IO) {
        lorebookDao.detachLorebook(chatId, lorebookId)
    }

    suspend fun setAttachmentEnabled(chatId: String, lorebookId: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val existing = lorebookDao.getAttachments(chatId).firstOrNull { it.lorebookId == lorebookId }
            ?: throw IllegalArgumentException("Lorebook is not attached to this chat")
        lorebookDao.upsertAttachment(existing.copy(isEnabled = enabled))
    }

    suspend fun setManualActive(chatId: String, entryId: String, active: Boolean) = withContext(Dispatchers.IO) {
        val existing = lorebookDao.getOverlays(chatId).firstOrNull { it.entryId == entryId }
        lorebookDao.upsertOverlay(
            (existing ?: ChatLorebookEntryOverlayEntity(chatId = chatId, entryId = entryId))
                .copy(manualActive = active, updatedAt = System.currentTimeMillis())
        )
    }

    suspend fun setChatCurrentOverride(chatId: String, entryId: String, currentContent: String) = withContext(Dispatchers.IO) {
        if (currentContent.length > MAX_CONTENT_LENGTH) throw LorebookValidationException("Current content is too long")
        val existing = lorebookDao.getOverlays(chatId).firstOrNull { it.entryId == entryId }
        lorebookDao.upsertOverlay(
            (existing ?: ChatLorebookEntryOverlayEntity(chatId = chatId, entryId = entryId)).copy(
                hasCurrentOverride = true,
                currentContent = currentContent,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun clearChatCurrentOverride(chatId: String, entryId: String) = withContext(Dispatchers.IO) {
        val existing = lorebookDao.getOverlays(chatId).firstOrNull { it.entryId == entryId } ?: return@withContext
        if (existing.manualActive) {
            lorebookDao.upsertOverlay(existing.copy(hasCurrentOverride = false, currentContent = "", updatedAt = System.currentTimeMillis()))
        } else {
            lorebookDao.deleteOverlay(chatId, entryId)
        }
    }

    suspend fun resolveActiveLore(
        chatId: String,
        chatHistory: List<MessageEntity>,
        currentMessage: MessageEntity,
        recentMessageLimit: Int = 24
    ): List<ActiveLoreEntry> = withContext(Dispatchers.IO) {
        val scanText = LorebookActivationEngine.conversationScanText(chatHistory, currentMessage, recentMessageLimit)
        val attachments = lorebookDao.getAttachments(chatId).filter { it.isEnabled }
        val overlays = lorebookDao.getOverlays(chatId).associateBy { it.entryId }
        buildList {
            attachments.forEach { attachment ->
                val book = lorebookDao.getLorebook(attachment.lorebookId) ?: return@forEach
                if (!book.enabled) return@forEach
                lorebookDao.getEntries(book.lorebookId).forEach { entry ->
                    if (!entry.enabled) return@forEach
                    val overlay = overlays[entry.entryId]
                    val active = when (entry.activationMode) {
                        "always" -> true
                        "manual" -> overlay?.manualActive == true
                        "keyword" -> LorebookActivationEngine.keywordMatches(entry, scanText)
                        else -> false
                    }
                    if (active) {
                        add(
                            ActiveLoreEntry(
                                title = entry.title,
                                currentContent = if (overlay?.hasCurrentOverride == true) overlay.currentContent else entry.currentContent,
                                priority = entry.priority
                            )
                        )
                    }
                }
            }
        }.sortedWith(compareByDescending<ActiveLoreEntry> { it.priority }.thenBy { it.title.lowercase() })
    }

    private fun validateDefinition(lorebook: LorebookEntity, entries: List<LorebookEntryEntity>) {
        if (lorebook.schemaVersion != SCHEMA_VERSION) throw LorebookValidationException("Unsupported schema version")
        validateId(lorebook.lorebookId, "Lorebook ID")
        if (lorebook.name.isBlank() || lorebook.name.length > MAX_NAME_LENGTH) throw LorebookValidationException("Lorebook name is required and must be at most $MAX_NAME_LENGTH characters")
        if (lorebook.description.length > MAX_DESCRIPTION_LENGTH) throw LorebookValidationException("Lorebook description is too long")
        if (lorebook.scopeType != "story") throw LorebookValidationException("Lorebook v1 supports only story scope")
        if (lorebook.storyId != null && (lorebook.storyId.isBlank() || lorebook.storyId.length > MAX_ID_LENGTH)) {
            throw LorebookValidationException("Story ID must be null or a nonblank value of at most $MAX_ID_LENGTH characters")
        }
        if (entries.size > MAX_ENTRIES) throw LorebookValidationException("Too many Lorebook entries")
        if (entries.map { it.entryId }.toSet().size != entries.size) throw LorebookValidationException("Entry IDs must be unique")
        entries.forEach { entry ->
            validateId(entry.entryId, "Entry ID")
            if (entry.lorebookId != lorebook.lorebookId) throw LorebookValidationException("Entry belongs to a different Lorebook")
            if (entry.title.isBlank() || entry.title.length > MAX_TITLE_LENGTH) throw LorebookValidationException("Entry title is required and must be at most $MAX_TITLE_LENGTH characters")
            if (entry.activationMode !in ACTIVATION_MODES) throw LorebookValidationException("Unsupported activation mode")
            if (entry.matchMode !in MATCH_MODES) throw LorebookValidationException("Unsupported keyword match mode")
            if (entry.position != "lore") throw LorebookValidationException("Lorebook v1 supports only the lore injection position")
            if (entry.canonContent.length > MAX_CONTENT_LENGTH || entry.currentContent.length > MAX_CONTENT_LENGTH) throw LorebookValidationException("Lore entry content is too long")
            if (entry.notes.length > MAX_NOTES_LENGTH) throw LorebookValidationException("Lore entry notes are too long")
            validateStringList(entry.keys, "activation keys")
            validateStringList(entry.secondaryKeys, "secondary activation keys")
            validateStringList(entry.tags, "tags")
        }
    }

    private fun validateStringList(values: List<String>, label: String) {
        if (values.size > MAX_LIST_ITEMS) throw LorebookValidationException("Too many $label")
        if (values.any { it.isBlank() || it.length > MAX_KEY_LENGTH }) throw LorebookValidationException("Each $label value must be nonblank and at most $MAX_KEY_LENGTH characters")
    }

    private fun copyName(name: String): String {
        val suffix = " (Copy)"
        return name.take(MAX_NAME_LENGTH - suffix.length).trimEnd() + suffix
    }

    private fun validateId(value: String, label: String) {
        if (!ID_PATTERN.matches(value)) throw LorebookValidationException("$label must use 1-$MAX_ID_LENGTH letters, numbers, dots, colons, underscores, or hyphens")
    }

    private fun Map<String, Any?>.rejectUnknown(allowed: Set<String>, path: String) {
        val unknown = keys - allowed
        if (unknown.isNotEmpty()) throw LorebookValidationException("Unsupported field at $path: ${unknown.sorted().joinToString()}")
    }

    private fun Any?.asObject(path: String): Map<String, Any?> {
        val raw = this as? Map<*, *> ?: throw LorebookValidationException("$path must be an object")
        if (raw.keys.any { it !is String }) throw LorebookValidationException("$path contains a non-string field name")
        @Suppress("UNCHECKED_CAST")
        return raw as Map<String, Any?>
    }

    private fun Map<String, Any?>.requireString(key: String, path: String, max: Int = MAX_ID_LENGTH): String {
        val value = this[key] as? String ?: throw LorebookValidationException("Missing or invalid $path.$key")
        if (value.isBlank()) throw LorebookValidationException("$path.$key must not be blank")
        if (value.length > max) throw LorebookValidationException("$path.$key exceeds $max characters")
        return value
    }

    private fun Map<String, Any?>.requireStringAllowEmpty(key: String, path: String, max: Int): String {
        val value = this[key] as? String ?: throw LorebookValidationException("Missing or invalid $path.$key")
        if (value.length > max) throw LorebookValidationException("$path.$key exceeds $max characters")
        return value
    }

    private fun Map<String, Any?>.optionalString(key: String, path: String, max: Int): String? {
        if (!containsKey(key)) return null
        val value = this[key] as? String ?: throw LorebookValidationException("$path.$key must be a string")
        if (value.length > max) throw LorebookValidationException("$path.$key exceeds $max characters")
        return value
    }

    private fun Map<String, Any?>.optionalNullableString(key: String, path: String, max: Int): String? {
        if (!containsKey(key) || this[key] == null) return null
        return optionalString(key, path, max)?.also {
            if (it.isBlank()) throw LorebookValidationException("$path.$key must be null or nonblank")
        }
    }

    private fun Map<String, Any?>.requireId(key: String, path: String): String =
        requireString(key, path, MAX_ID_LENGTH).also { validateId(it, "$path.$key") }

    private fun Map<String, Any?>.optionalBoolean(key: String, path: String): Boolean? {
        if (!containsKey(key)) return null
        return this[key] as? Boolean ?: throw LorebookValidationException("$path.$key must be true or false")
    }

    private fun Map<String, Any?>.requireInt(key: String, path: String): Int =
        optionalInt(key, path) ?: throw LorebookValidationException("Missing $path.$key")

    private fun Map<String, Any?>.optionalInt(key: String, path: String): Int? {
        if (!containsKey(key)) return null
        val number = this[key] as? Number ?: throw LorebookValidationException("$path.$key must be an integer")
        val double = number.toDouble()
        if (!double.isFinite() || double % 1.0 != 0.0 || double < Int.MIN_VALUE || double > Int.MAX_VALUE) {
            throw LorebookValidationException("$path.$key must be a 32-bit integer")
        }
        return double.toInt()
    }

    private fun Map<String, Any?>.optionalTimestamp(key: String, path: String): Long? {
        if (!containsKey(key)) return null
        val value = this[key]
        if (value is String) {
            if (value.isBlank()) throw LorebookValidationException("$path.$key must be an ISO-8601 timestamp")
            return try {
                Instant.parse(value).toEpochMilli().also { epochMillis ->
                    if (epochMillis < 0) throw LorebookValidationException("$path.$key must not be before 1970-01-01T00:00:00Z")
                }
            } catch (error: DateTimeException) {
                throw LorebookValidationException("$path.$key must be an ISO-8601 UTC timestamp such as 2026-10-04T00:00:00Z")
            } catch (error: ArithmeticException) {
                throw LorebookValidationException("$path.$key is outside the supported timestamp range")
            }
        }
        val number = value as? Number
            ?: throw LorebookValidationException("$path.$key must be an ISO-8601 timestamp or legacy epoch-millisecond integer")
        val double = number.toDouble()
        if (!double.isFinite() || double % 1.0 != 0.0 || double < 0 || double > Long.MAX_VALUE.toDouble()) {
            throw LorebookValidationException("$path.$key must be a non-negative epoch-millisecond integer")
        }
        return double.toLong()
    }

    private fun Map<String, Any?>.optionalStringList(key: String, path: String): List<String>? {
        if (!containsKey(key)) return null
        val raw = this[key] as? List<*> ?: throw LorebookValidationException("$path.$key must be an array")
        if (raw.size > MAX_LIST_ITEMS) throw LorebookValidationException("$path.$key contains too many values")
        return raw.mapIndexed { index, item ->
            val value = item as? String ?: throw LorebookValidationException("$path.$key[$index] must be a string")
            if (value.isBlank() || value.length > MAX_KEY_LENGTH) throw LorebookValidationException("$path.$key[$index] must be nonblank and at most $MAX_KEY_LENGTH characters")
            value
        }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val TYPE = "elsewhere_lorebook"
        const val MAX_JSON_BYTES = 2_000_000
        const val MAX_ENTRIES = 500
        const val MAX_CONTENT_LENGTH = 200_000
        const val MAX_NOTES_LENGTH = 100_000
        const val MAX_NAME_LENGTH = 200
        const val MAX_TITLE_LENGTH = 300
        const val MAX_DESCRIPTION_LENGTH = 10_000
        const val MAX_ID_LENGTH = 200
        const val MAX_LIST_ITEMS = 100
        const val MAX_KEY_LENGTH = 200

        private val ID_PATTERN = Regex("[A-Za-z0-9._:-]{1,$MAX_ID_LENGTH}")
        private val ACTIVATION_MODES = setOf("keyword", "always", "manual")
        private val MATCH_MODES = setOf("any", "all")
        private val ROOT_FIELDS = setOf("schema_version", "type", "id", "name", "description", "enabled", "scope", "entries")
        private val SCOPE_FIELDS = setOf("type", "story_id")
        private val ENTRY_FIELDS = setOf("id", "title", "enabled", "activation", "canon", "current", "injection", "tags", "notes", "created_at", "updated_at")
        private val ACTIVATION_FIELDS = setOf("mode", "keys", "secondary_keys", "match", "case_sensitive", "whole_words")
        private val CANON_FIELDS = setOf("content", "locked")
        private val CURRENT_FIELDS = setOf("content")
        private val INJECTION_FIELDS = setOf("priority", "position")
    }
}
