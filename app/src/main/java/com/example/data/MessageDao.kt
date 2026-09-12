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

    @Query("SELECT * FROM messages WHERE variantGroupId = :variantGroupId ORDER BY createdAt ASC")
    fun getVariants(variantGroupId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)
    
    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("UPDATE messages SET isPrimaryVariant = 0 WHERE variantGroupId = :variantGroupId")
    suspend fun clearPrimaryVariants(variantGroupId: String)
    
    @Query("UPDATE messages SET isPrimaryVariant = 1 WHERE messageId = :messageId")
    suspend fun setPrimaryVariant(messageId: String)
    
    @Delete
    suspend fun deleteMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGenerationMetadata(metadata: GenerationMetadataEntity)

    @Query("SELECT * FROM generation_metadata WHERE messageId = :messageId")
    suspend fun getGenerationMetadata(messageId: String): GenerationMetadataEntity?
}
