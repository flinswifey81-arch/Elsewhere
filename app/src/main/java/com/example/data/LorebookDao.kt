package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.data.model.ChatLorebookAttachmentEntity
import com.example.data.model.ChatLorebookEntryOverlayEntity
import com.example.data.model.LorebookEntity
import com.example.data.model.LorebookEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LorebookDao {
    @Query("SELECT * FROM lorebooks ORDER BY name COLLATE NOCASE, lorebookId")
    fun observeLorebooks(): Flow<List<LorebookEntity>>

    @Query("SELECT * FROM lorebooks ORDER BY name COLLATE NOCASE, lorebookId")
    suspend fun getLorebooksOnce(): List<LorebookEntity>

    @Query("SELECT * FROM lorebooks WHERE lorebookId = :lorebookId")
    suspend fun getLorebook(lorebookId: String): LorebookEntity?

    @Query("SELECT * FROM lorebook_entries WHERE lorebookId = :lorebookId ORDER BY priority DESC, title COLLATE NOCASE, entryId")
    fun observeEntries(lorebookId: String): Flow<List<LorebookEntryEntity>>

    @Query("SELECT * FROM lorebook_entries WHERE lorebookId = :lorebookId ORDER BY priority DESC, title COLLATE NOCASE, entryId")
    suspend fun getEntries(lorebookId: String): List<LorebookEntryEntity>

    @Query("SELECT * FROM lorebook_entries WHERE entryId = :entryId")
    suspend fun getEntry(entryId: String): LorebookEntryEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLorebook(lorebook: LorebookEntity)

    @Update
    suspend fun updateLorebook(lorebook: LorebookEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEntry(entry: LorebookEntryEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEntries(entries: List<LorebookEntryEntity>)

    @Update
    suspend fun updateEntry(entry: LorebookEntryEntity)

    @Query("DELETE FROM chat_lorebook_entry_overlays WHERE entryId = :entryId")
    suspend fun deleteOverlaysForEntry(entryId: String)

    @Query("DELETE FROM chat_lorebook_entry_overlays WHERE entryId IN (SELECT entryId FROM lorebook_entries WHERE lorebookId = :lorebookId)")
    suspend fun deleteOverlaysForLorebook(lorebookId: String)

    @Query("DELETE FROM lorebook_entries WHERE entryId = :entryId")
    suspend fun deleteEntryById(entryId: String)

    @Query("DELETE FROM lorebook_entries WHERE lorebookId = :lorebookId")
    suspend fun deleteEntriesForLorebook(lorebookId: String)

    @Query("DELETE FROM chat_lorebook_attachments WHERE lorebookId = :lorebookId")
    suspend fun deleteAttachmentsForLorebook(lorebookId: String)

    @Query("DELETE FROM lorebooks WHERE lorebookId = :lorebookId")
    suspend fun deleteLorebookById(lorebookId: String)

    @Transaction
    suspend fun saveDefinition(lorebook: LorebookEntity, entries: List<LorebookEntryEntity>) {
        val existingBook = getLorebook(lorebook.lorebookId)
        if (existingBook == null) insertLorebook(lorebook) else updateLorebook(lorebook)

        val existingEntries = getEntries(lorebook.lorebookId).associateBy { it.entryId }
        val desiredIds = entries.mapTo(mutableSetOf()) { it.entryId }
        entries.forEach { entry ->
            require(entry.lorebookId == lorebook.lorebookId) { "Entry belongs to a different Lorebook" }
            val existingById = getEntry(entry.entryId)
            require(existingById == null || existingById.lorebookId == lorebook.lorebookId) {
                "Entry ID already belongs to another Lorebook"
            }
            if (existingEntries.containsKey(entry.entryId)) updateEntry(entry) else insertEntry(entry)
        }
        existingEntries.keys.filterNot(desiredIds::contains).forEach { removedId ->
            deleteOverlaysForEntry(removedId)
            deleteEntryById(removedId)
        }
    }

    @Transaction
    suspend fun replaceImportedDefinition(lorebook: LorebookEntity, entries: List<LorebookEntryEntity>) {
        val replacementIds = entries.mapTo(mutableSetOf()) { it.entryId }
        getEntries(lorebook.lorebookId)
            .map { it.entryId }
            .filterNot(replacementIds::contains)
            .forEach { deleteOverlaysForEntry(it) }
        deleteEntriesForLorebook(lorebook.lorebookId)
        val existing = getLorebook(lorebook.lorebookId)
        if (existing == null) insertLorebook(lorebook) else updateLorebook(lorebook)
        insertEntries(entries)
    }

    @Transaction
    suspend fun deleteLorebook(lorebookId: String) {
        deleteOverlaysForLorebook(lorebookId)
        deleteAttachmentsForLorebook(lorebookId)
        deleteEntriesForLorebook(lorebookId)
        deleteLorebookById(lorebookId)
    }

    @Query("SELECT * FROM chat_lorebook_attachments WHERE chatId = :chatId ORDER BY sortPosition, attachedAt")
    fun observeAttachments(chatId: String): Flow<List<ChatLorebookAttachmentEntity>>

    @Query("SELECT * FROM chat_lorebook_attachments WHERE chatId = :chatId ORDER BY sortPosition, attachedAt")
    suspend fun getAttachments(chatId: String): List<ChatLorebookAttachmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAttachment(attachment: ChatLorebookAttachmentEntity)

    @Query("DELETE FROM chat_lorebook_attachments WHERE chatId = :chatId AND lorebookId = :lorebookId")
    suspend fun detachLorebook(chatId: String, lorebookId: String)

    @Query("SELECT * FROM chat_lorebook_entry_overlays WHERE chatId = :chatId")
    fun observeOverlays(chatId: String): Flow<List<ChatLorebookEntryOverlayEntity>>

    @Query("SELECT * FROM chat_lorebook_entry_overlays WHERE chatId = :chatId")
    suspend fun getOverlays(chatId: String): List<ChatLorebookEntryOverlayEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOverlay(overlay: ChatLorebookEntryOverlayEntity)

    @Query("DELETE FROM chat_lorebook_entry_overlays WHERE chatId = :chatId AND entryId = :entryId")
    suspend fun deleteOverlay(chatId: String, entryId: String)

    @Query("DELETE FROM chat_lorebook_entry_overlays WHERE chatId = :chatId")
    suspend fun deleteOverlaysForChat(chatId: String)

    @Query("DELETE FROM chat_lorebook_attachments WHERE chatId = :chatId")
    suspend fun deleteAttachmentsForChat(chatId: String)
}
