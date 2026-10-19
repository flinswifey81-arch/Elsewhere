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
