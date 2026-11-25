package com.example.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.PrimaryKey

/**
 * Local-only message text index. The content is owned by MessageEntity;
 * Room maintains FTS sync for insert, edit, regeneration and deletion.
 *
 * Do not route search results into generation or Story Memory eligibility.
 */
@Entity(tableName = "messages_search_fts")
@Fts4(contentEntity = MessageEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
data class MessageSearchFtsEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long,
    val content: String
)
