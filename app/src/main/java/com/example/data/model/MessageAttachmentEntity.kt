package com.example.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "message_attachments",
    indices = [
        Index(value = ["chatId"]),
        Index(value = ["messageId"])
    ]
)
data class MessageAttachmentEntity(
    @PrimaryKey val attachmentId: String = UUID.randomUUID().toString(),
    val chatId: String,
    /** Null while this is a durable per-chat draft attachment. */
    val messageId: String? = null,
    /** Path relative to Context.filesDir; never an external content URI. */
    val relativePath: String,
    val mimeType: String,
    val byteSize: Long,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val sha256: String,
    val sortPosition: Int,
    val createdAt: Long = System.currentTimeMillis()
)
