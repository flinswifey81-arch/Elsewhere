package com.example.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "lorebooks")
data class LorebookEntity(
    @PrimaryKey val lorebookId: String = UUID.randomUUID().toString(),
    val schemaVersion: Int = 1,
    val name: String,
    val description: String = "",
    val enabled: Boolean = true,
    val scopeType: String = "story",
    val storyId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "lorebook_entries",
    indices = [Index("lorebookId")]
)
data class LorebookEntryEntity(
    @PrimaryKey val entryId: String = UUID.randomUUID().toString(),
    val lorebookId: String,
    val title: String,
    val enabled: Boolean = true,
    val activationMode: String = "keyword",
    val keys: List<String> = emptyList(),
    val secondaryKeys: List<String> = emptyList(),
    val matchMode: String = "any",
    val caseSensitive: Boolean = false,
    val wholeWords: Boolean = true,
    val canonContent: String = "",
    val canonLocked: Boolean = true,
    val currentContent: String = "",
    val priority: Int = 50,
    val position: String = "lore",
    val tags: List<String> = emptyList(),
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "chat_lorebook_attachments",
    primaryKeys = ["chatId", "lorebookId"],
    indices = [Index("lorebookId")]
)
data class ChatLorebookAttachmentEntity(
    val chatId: String,
    val lorebookId: String,
    val isEnabled: Boolean = true,
    val sortPosition: Int = 0,
    val attachedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "chat_lorebook_entry_overlays",
    primaryKeys = ["chatId", "entryId"],
    indices = [Index("entryId")]
)
data class ChatLorebookEntryOverlayEntity(
    val chatId: String,
    val entryId: String,
    val hasCurrentOverride: Boolean = false,
    val currentContent: String = "",
    val manualActive: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

data class LorebookWithEntries(
    val lorebook: LorebookEntity,
    val entries: List<LorebookEntryEntity>
)

data class ChatLorebookState(
    val lorebook: LorebookEntity,
    val entries: List<LorebookEntryEntity>,
    val attachment: ChatLorebookAttachmentEntity?,
    val overlays: Map<String, ChatLorebookEntryOverlayEntity>
)
