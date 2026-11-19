package com.example.data

import androidx.room.*
import com.example.data.model.GenerationMetadataEntity
import com.example.data.model.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    // Only fetch primary variants for normal chat view
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY orderIndex ASC")
    fun getMessagesForChat(chatId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY orderIndex ASC")
    suspend fun getMessagesForChatOnce(chatId: String): List<MessageEntity>

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
