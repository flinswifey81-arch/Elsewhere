package com.example.data

import androidx.room.*
import com.example.data.model.GenerationMetadataEntity
import com.example.data.model.MessageEntity
import kotlinx.coroutines.flow.Flow

/** Bounded selected generation context fetched from one consistent Room snapshot. */
data class SelectedGroupGenerationWindow(
    val messages: List<MessageEntity>,
    val latestPersona: MessageEntity?
)

@Dao
interface MessageDao {
    // Only fetch primary variants for normal chat view
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY orderIndex ASC")
    fun getMessagesForChat(chatId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY orderIndex ASC")
    suspend fun getMessagesForChatOnce(chatId: String): List<MessageEntity>

    /**
     * UI-only window of complete logical message groups (all regeneration
     * variants included), newest [groupLimitPlusOne] groups. The extra group
     * lets the ViewModel know if older history exists without a second query.
     *
     * SQLite groups rows before limiting, so an older selected variant is
     * never detached from the message bubble it belongs to. Full-history
     * repository APIs remain available for export, co-op and maintenance.
     */
    @Query("""
        SELECT * FROM messages
        WHERE chatId = :chatId
          AND COALESCE(variantGroupId, messageId) IN (
              SELECT COALESCE(variantGroupId, messageId) AS groupKey
              FROM messages
              WHERE chatId = :chatId
              GROUP BY groupKey
              ORDER BY COALESCE(
                  MAX(CASE WHEN isPrimaryVariant = 1 THEN orderIndex END),
                  MIN(orderIndex)
              ) DESC, MIN(rowid) DESC
              LIMIT :groupLimitPlusOne
          )
        ORDER BY orderIndex ASC, rowid ASC
    """)
    fun observeRecentMessageGroups(
        chatId: String,
        groupLimitPlusOne: Int
    ): Flow<List<MessageEntity>>

    /**
     * Latest selected turns for a bounded Group generation window. Reversing
     * the result restores the same oldest-to-newest story chronology. ORDER
     * BY rowid is the deterministic insertion-order tie break for messages
     * whose orderIndex is equal (common on imported/co-op histories).
     */
    @Query("""
        SELECT * FROM messages
        WHERE chatId = :chatId AND isPrimaryVariant = 1
        ORDER BY orderIndex DESC, rowid DESC
        LIMIT :limit
    """)
    suspend fun getLatestPrimaryWindow(chatId: String, limit: Int): List<MessageEntity>

    /** Preserve Group's most recently used Persona even when more than 24
     * Character turns have passed. A Wack must prohibit pre-reset identity
     * inference from old Persona messages.
     */
    @Query("""
        SELECT * FROM messages
        WHERE chatId = :chatId AND isPrimaryVariant = 1
          AND speakerType = 'PERSONA'
          AND (:resetBoundary IS NULL OR orderIndex > :resetBoundary)
        ORDER BY orderIndex DESC, rowid DESC
        LIMIT 1
    """)
    suspend fun getLatestPrimaryPersona(
        chatId: String,
        resetBoundary: Long?
    ): MessageEntity?

    @Transaction
    suspend fun getSelectedGroupGenerationWindow(
        chatId: String,
        resetBoundary: Long?,
        limit: Int
    ): SelectedGroupGenerationWindow = SelectedGroupGenerationWindow(
        messages = getLatestPrimaryWindow(chatId, limit).asReversed(),
        latestPersona = getLatestPrimaryPersona(chatId, resetBoundary)
    )

    /**
     * Read selected story messages newest first, optionally scoped after Wack.
     * Fetching older pages on demand supports exact rolling-summary suffixes
     * without allocating the entire local archive.
     */
    @Query("""
        SELECT * FROM messages
        WHERE chatId = :chatId AND isPrimaryVariant = 1
          AND (:resetBoundary IS NULL OR orderIndex > :resetBoundary)
        ORDER BY orderIndex DESC, rowid DESC
        LIMIT :pageSize OFFSET :offset
    """)
    suspend fun getSelectedHistoryReversePage(
        chatId: String,
        resetBoundary: Long?,
        pageSize: Int,
        offset: Int
    ): List<MessageEntity>

    /**
     * Manual deterministic memory extraction must preserve original
     * oldest-first provenance and duplicate winner behavior.
     */
    @Query("""
        SELECT * FROM messages
        WHERE chatId = :chatId AND isPrimaryVariant = 1
          AND (:resetBoundary IS NULL OR orderIndex > :resetBoundary)
        ORDER BY orderIndex ASC, rowid ASC
        LIMIT :pageSize OFFSET :offset
    """)
    suspend fun getSelectedHistoryForwardPage(
        chatId: String,
        resetBoundary: Long?,
        pageSize: Int,
        offset: Int
    ): List<MessageEntity>

    /**
     * Provenance-only lookup for saved memory sources. Query is bounded by
     * referenced message IDs, not by total transcript length.
     */
    @Query("SELECT * FROM messages WHERE chatId = :chatId AND messageId IN (:messageIds)")
    suspend fun getMemorySourceMessages(chatId: String, messageIds: List<String>): List<MessageEntity>

    /**
     * Fetch every variant in the referenced groups to detect ambiguous
     * primary selections without scanning unrelated historical messages.
     */
    @Query("SELECT * FROM messages WHERE chatId = :chatId AND variantGroupId IN (:variantGroupIds)")
    suspend fun getMemorySourceVariants(chatId: String, variantGroupIds: List<String>): List<MessageEntity>

    /**
     * A source and its selected alternatives must be checked against the SAME
     * Room snapshot. A regenerate/variant switch between the source and
     * variant queries must never authorize stale character knowledge.
     */
    @Transaction
    suspend fun readMemoryProvenanceSnapshot(
        chatId: String,
        sourceMessageIds: List<String>,
        explicitVariantGroupIds: List<String>
    ): List<MessageEntity> {
        val sources = sourceMessageIds.asSequence().filter(String::isNotBlank)
            .distinct().toList().chunked(400).flatMap { getMemorySourceMessages(chatId, it) }
        val groups = (explicitVariantGroupIds.asSequence() + sources.asSequence().map { it.variantGroupId })
            .filter(String::isNotBlank).distinct().toList()
        val variants = groups.chunked(400).flatMap { getMemorySourceVariants(chatId, it) }
        return (sources + variants).distinctBy { it.messageId }
    }

    @Query("SELECT * FROM messages WHERE messageId = :messageId LIMIT 1")
    suspend fun getMessageById(messageId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE variantGroupId = :variantGroupId ORDER BY createdAt ASC")
    fun getVariants(variantGroupId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE variantGroupId = :variantGroupId ORDER BY createdAt ASC, messageId ASC")
    suspend fun getVariantsOnce(variantGroupId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessageIfAbsent(message: MessageEntity): Long
    
    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("UPDATE messages SET isPrimaryVariant = 0 WHERE variantGroupId = :variantGroupId")
    suspend fun clearPrimaryVariants(variantGroupId: String)
    
    @Query("UPDATE messages SET isPrimaryVariant = 1 WHERE messageId = :messageId")
    suspend fun setPrimaryVariant(messageId: String)
    
    @Delete
    suspend fun deleteMessage(message: MessageEntity)

    @Query("DELETE FROM generation_metadata WHERE messageId = :messageId")
    suspend fun deleteGenerationMetadata(messageId: String)

    @Query("DELETE FROM messages WHERE messageId = :messageId")
    suspend fun deleteMessageById(messageId: String)

    @Query("DELETE FROM message_attachments WHERE messageId = :messageId")
    suspend fun deleteAttachmentsForMessage(messageId: String)

    @Transaction
    suspend fun insertVariantAndSelect(message: MessageEntity) {
        insertMessage(message)
        clearPrimaryVariants(message.variantGroupId)
        setPrimaryVariant(message.messageId)
    }

    @Transaction
    suspend fun deleteMessageAndRepair(message: MessageEntity) {
        deleteGenerationMetadata(message.messageId)
        deleteAttachmentsForMessage(message.messageId)
        deleteMessage(message)
        if (message.isPrimaryVariant) {
            getVariantsOnce(message.variantGroupId).firstOrNull()?.let { survivor ->
                setPrimaryVariant(survivor.messageId)
            }
        }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGenerationMetadata(metadata: GenerationMetadataEntity)

    @Query("SELECT * FROM generation_metadata WHERE messageId = :messageId")
    suspend fun getGenerationMetadata(messageId: String): GenerationMetadataEntity?
}
