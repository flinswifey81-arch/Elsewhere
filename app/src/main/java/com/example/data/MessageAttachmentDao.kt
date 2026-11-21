package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.data.model.MessageAttachmentEntity
import com.example.data.model.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageAttachmentDao {
    @Query("SELECT * FROM message_attachments WHERE chatId = :chatId ORDER BY createdAt, sortPosition")
    fun observeForChat(chatId: String): Flow<List<MessageAttachmentEntity>>


    /**
     * Chat display only. Limit attachment metadata to complete logical message
     * groups in the same newest-first window as the chat UI. Drafts are always
     * included, even when the current chat has more than 10k story messages.
     *
     * Keep all variants in each selected group: a reader can switch versions
     * of a bubble without a second archive-wide attachment subscription.
     * Full archival APIs remain unchanged for backup, export and generation.
     */
    @Query("""
        SELECT * FROM message_attachments
        WHERE chatId = :chatId
          AND (
              messageId IS NULL OR messageId IN (
                  SELECT m.messageId
                  FROM messages m
                  WHERE m.chatId = :chatId
                    AND COALESCE(m.variantGroupId, m.messageId) IN (
                        SELECT COALESCE(variantGroupId, messageId) AS groupKey
                        FROM messages
                        WHERE chatId = :chatId
                        GROUP BY groupKey
                        ORDER BY COALESCE(
                            MAX(CASE WHEN isPrimaryVariant = 1 THEN orderIndex END),
                            MIN(orderIndex)
                        ) DESC, MIN(rowid) DESC
                        LIMIT :visibleGroupLimit
                    )
              )
          )
        ORDER BY createdAt ASC, sortPosition ASC
    """)
    fun observeVisibleForChat(
        chatId: String,
        visibleGroupLimit: Int
    ): Flow<List<MessageAttachmentEntity>>

    @Query("SELECT * FROM message_attachments WHERE chatId = :chatId ORDER BY createdAt, sortPosition")
    suspend fun getForChat(chatId: String): List<MessageAttachmentEntity>

    @Query("SELECT relativePath FROM message_attachments")
    suspend fun getAllRelativePaths(): List<String>

    @Query("SELECT * FROM message_attachments")
    suspend fun getAll(): List<MessageAttachmentEntity>

    @Query("SELECT * FROM message_attachments WHERE chatId = :chatId AND messageId IS NULL ORDER BY sortPosition")
    suspend fun getDrafts(chatId: String): List<MessageAttachmentEntity>

    @Query("SELECT * FROM message_attachments WHERE messageId = :messageId ORDER BY sortPosition")
    suspend fun getForMessage(messageId: String): List<MessageAttachmentEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(attachment: MessageAttachmentEntity)

    @Query("DELETE FROM message_attachments WHERE attachmentId = :attachmentId")
    suspend fun deleteById(attachmentId: String)

    @Query("UPDATE message_attachments SET messageId = :messageId WHERE chatId = :chatId AND messageId IS NULL")
    suspend fun attachDrafts(chatId: String, messageId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMessage(message: MessageEntity)

    @Transaction
    suspend fun insertMessageAndAttachDrafts(message: MessageEntity) {
        insertMessage(message)
        attachDrafts(message.chatId, message.messageId)
    }
}
