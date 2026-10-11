package com.example.domain.repository

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.example.data.MessageAttachmentDao
import com.example.data.AppDatabase
import androidx.room.withTransaction
import com.example.data.model.MessageAttachmentEntity
import com.example.data.model.MessageEntity
import com.example.domain.provider.ProviderImage
import com.example.domain.provider.ProviderImageLimits
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MessageAttachmentRepository(
    private val context: Context,
    private val database: AppDatabase
) {
    private val dao: MessageAttachmentDao get() = database.messageAttachmentDao()
    private val storageMutex = Mutex()
    fun observeForChat(chatId: String): Flow<List<MessageAttachmentEntity>> = dao.observeForChat(chatId)

    suspend fun importDraftImage(chatId: String, uri: Uri): MessageAttachmentEntity = storageMutex.withLock {
        withContext(Dispatchers.IO) {
            val existingDrafts = dao.getDrafts(chatId)
            require(existingDrafts.size < MAX_IMAGES_PER_MESSAGE) {
                "A message can contain at most $MAX_IMAGES_PER_MESSAGE images."
            }
            val directory = File(context.filesDir, ATTACHMENT_DIRECTORY).apply { mkdirs() }
            require(directory.isDirectory) { "Attachment storage is unavailable." }
            val id = UUID.randomUUID().toString()
            val temporary = File(directory, "$id.tmp")
            var byteCount = 0L
            try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "The selected image could not be opened." }
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        byteCount += read
                        require(byteCount <= MAX_IMAGE_BYTES) {
                            "Images must be ${MAX_IMAGE_BYTES / (1024 * 1024)} MB or smaller."
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            require(byteCount > 0) { "The selected image is empty." }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.absolutePath, bounds)
            val mimeType = detectMimeType(temporary) ?: bounds.outMimeType?.lowercase()
            requireNotNull(mimeType) {
                "The selected file is not a readable image."
            }
            require(mimeType in SUPPORTED_MIME_TYPES) {
                "Only JPEG, PNG, and WebP images are supported."
            }
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The selected file is not a readable image." }
            require(bounds.outWidth <= MAX_DIMENSION && bounds.outHeight <= MAX_DIMENSION) {
                "Images cannot exceed ${MAX_DIMENSION} px on either side."
            }
            require(bounds.outWidth.toLong() * bounds.outHeight.toLong() <= MAX_PIXELS) {
                "Images cannot exceed ${MAX_PIXELS / 1_000_000} megapixels."
            }
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = calculateDecodeSampleSize(bounds.outWidth, bounds.outHeight)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = requireNotNull(BitmapFactory.decodeFile(temporary.absolutePath, decodeOptions)) {
                "The selected image could not be decoded."
            }
            require(decoded.width.toLong() * decoded.height.toLong() <= MAX_DECODED_PIXELS) {
                "The selected image could not be decoded within the safe pixel budget."
            }
            val normalized = applyOrientation(decoded, temporary)
            val normalizedTemporary = File(directory, "$id.normalized")
            val normalizedMime: String
            val normalizedWidth: Int
            val normalizedHeight: Int
            try {
                normalizedMime = if (mimeType == "image/png" || normalized.hasAlpha()) "image/png" else "image/jpeg"
                FileOutputStream(normalizedTemporary).use { output ->
                    val format = if (normalizedMime == "image/png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                    require(normalized.compress(format, if (format == Bitmap.CompressFormat.JPEG) 92 else 100, output)) {
                        "The image could not be normalized."
                    }
                }
                normalizedWidth = normalized.width
                normalizedHeight = normalized.height
            } finally {
                if (normalized !== decoded) normalized.recycle()
                decoded.recycle()
            }
            val extension = if (normalizedMime == "image/png") "png" else "jpg"
            temporary.delete()
            byteCount = normalizedTemporary.length()
            require(byteCount in 1..MAX_IMAGE_BYTES) {
                "The normalized image must be ${MAX_IMAGE_BYTES / (1024 * 1024)} MB or smaller."
            }
            require(existingDrafts.sumOf { it.byteSize } + byteCount <= MAX_TOTAL_IMAGE_BYTES) {
                "Images on one message must total ${MAX_TOTAL_IMAGE_BYTES / (1024 * 1024)} MB or less."
            }
            val digest = MessageDigest.getInstance("SHA-256")
            normalizedTemporary.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val destination = File(directory, "$id.$extension")
            require(normalizedTemporary.renameTo(destination)) { "The image could not be saved." }
            val relativePath = "$ATTACHMENT_DIRECTORY/${destination.name}"
            val entity = MessageAttachmentEntity(
                attachmentId = id,
                chatId = chatId,
                relativePath = relativePath,
                mimeType = normalizedMime,
                byteSize = byteCount,
                pixelWidth = normalizedWidth,
                pixelHeight = normalizedHeight,
                sha256 = digest.digest().joinToString("") { "%02x".format(it) },
                sortPosition = dao.getDrafts(chatId).size
            )
            try {
                dao.insert(entity)
            } catch (error: Throwable) {
                destination.delete()
                throw error
            }
            entity
            } catch (error: Throwable) {
                temporary.delete()
                File(directory, "$id.normalized").delete()
                throw error
            }
        }
    }

    suspend fun persistMessageWithDrafts(message: MessageEntity) = withContext(Dispatchers.IO) {
        dao.insertMessageAndAttachDrafts(message)
    }

    suspend fun getDrafts(chatId: String): List<MessageAttachmentEntity> = withContext(Dispatchers.IO) {
        dao.getDrafts(chatId)
    }

    suspend fun getForChat(chatId: String): List<MessageAttachmentEntity> = withContext(Dispatchers.IO) {
        dao.getForChat(chatId)
    }

    suspend fun getForMessage(messageId: String): List<MessageAttachmentEntity> = withContext(Dispatchers.IO) {
        dao.getForMessage(messageId)
    }

    suspend fun removeDraft(attachment: MessageAttachmentEntity) = storageMutex.withLock {
        withContext(Dispatchers.IO) {
            require(attachment.messageId == null) { "Only draft attachments can be removed from the composer." }
            dao.deleteById(attachment.attachmentId)
            resolveFile(attachment)?.delete()
        }
    }

    suspend fun deleteFiles(attachments: List<MessageAttachmentEntity>) = storageMutex.withLock {
        withContext(Dispatchers.IO) { deleteFilesLocked(attachments) }
    }

    suspend fun deleteFilesAndReconcile(attachments: List<MessageAttachmentEntity>) = storageMutex.withLock {
        withContext(Dispatchers.IO) {
            deleteFilesLocked(attachments)
            reconcileOrphanFilesLocked()
        }
    }

    suspend fun deleteMessageAndOwnedFiles(message: MessageEntity) = storageMutex.withLock {
        withContext(Dispatchers.IO) {
            val attachments = dao.getForMessage(message.messageId)
            database.withTransaction {
                database.npcDao().deleteUpdatesForMessage(message.messageId)
                database.messageDao().deleteMessageAndRepair(message)
                if (message.isPrimaryVariant) {
                    database.messageDao().getVariantsOnce(message.variantGroupId)
                        .firstOrNull { it.isPrimaryVariant }
                        ?.let { database.npcDao().setMessageUpdatesApplied(it.messageId, true) }
                }
            }
            deleteFilesLocked(attachments)
            reconcileOrphanFilesLocked()
        }
    }

    suspend fun deleteChatAndOwnedFiles(chatId: String) = storageMutex.withLock {
        withContext(Dispatchers.IO) {
            val attachments = dao.getForChat(chatId)
            database.chatDao().deleteChatAndOwnedData(chatId)
            deleteFilesLocked(attachments)
            reconcileOrphanFilesLocked()
        }
    }

    suspend fun reconcileOrphanFiles() = storageMutex.withLock {
        withContext(Dispatchers.IO) { reconcileOrphanFilesLocked() }
    }

    private fun deleteFilesLocked(attachments: List<MessageAttachmentEntity>) {
        attachments.forEach { resolveFile(it)?.delete() }
    }

    private suspend fun reconcileOrphanFilesLocked() {
        val attachments = dao.getAll()
        attachments.filter { resolveFile(it) == null }.forEach { missing ->
            dao.deleteById(missing.attachmentId)
        }
        val referenced = dao.getAllRelativePaths().toHashSet()
        val directory = File(context.filesDir, ATTACHMENT_DIRECTORY)
        directory.listFiles()?.forEach { file ->
            val relative = "$ATTACHMENT_DIRECTORY/${file.name}"
            if (relative !in referenced) file.delete()
        }
    }

    suspend fun providerImagesForMessage(messageId: String): List<ProviderImage> = withContext(Dispatchers.IO) {
        val attachments = dao.getForMessage(messageId)
        validateRequestBudget(attachments)
        attachments.map { attachment ->
            val file = requireNotNull(resolveFile(attachment)) { "An image attachment is no longer available." }
            val bytes = file.readBytes()
            require(bytes.size.toLong() == attachment.byteSize && bytes.size.toLong() <= MAX_IMAGE_BYTES) {
                "An image attachment changed or is too large."
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            require(digest == attachment.sha256) { "An image attachment failed its integrity check." }
            ProviderImage(attachment.mimeType, bytes, attachment.sha256)
        }
    }

    fun resolveFile(attachment: MessageAttachmentEntity): File? {
        val root = context.filesDir.canonicalFile
        val file = File(root, attachment.relativePath).canonicalFile
        val allowed = File(root, ATTACHMENT_DIRECTORY).canonicalFile
        return file.takeIf { it.path.startsWith(allowed.path + File.separator) && it.isFile }
    }

    companion object {
        const val MAX_IMAGES_PER_MESSAGE = ProviderImageLimits.MAX_IMAGES
        const val MAX_IMAGE_BYTES = ProviderImageLimits.MAX_IMAGE_BYTES
        const val MAX_TOTAL_IMAGE_BYTES = ProviderImageLimits.MAX_TOTAL_BYTES
        const val MAX_DIMENSION = 8192
        const val MAX_PIXELS = 16_000_000L
        const val MAX_DECODED_PIXELS = 4_000_000L
        private const val ATTACHMENT_DIRECTORY = "message_attachments"
        private val SUPPORTED_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")

        internal fun calculateDecodeSampleSize(width: Int, height: Int): Int {
            require(width > 0 && height > 0) { "Image dimensions must be positive." }
            var sampleSize = 1
            while (
                ((width + sampleSize - 1L) / sampleSize) *
                    ((height + sampleSize - 1L) / sampleSize) > MAX_DECODED_PIXELS
            ) {
                sampleSize *= 2
            }
            return sampleSize
        }

        internal fun validateRequestBudget(attachments: List<MessageAttachmentEntity>) {
            require(attachments.size <= MAX_IMAGES_PER_MESSAGE) {
                "A message can contain at most $MAX_IMAGES_PER_MESSAGE images."
            }
            var totalBytes = 0L
            attachments.forEach { attachment ->
                require(attachment.byteSize in 1..MAX_IMAGE_BYTES) {
                    "Each image must be ${MAX_IMAGE_BYTES / (1024 * 1024)} MB or smaller."
                }
                totalBytes += attachment.byteSize
                require(totalBytes <= MAX_TOTAL_IMAGE_BYTES) {
                    "Images on one message must total ${MAX_TOTAL_IMAGE_BYTES / (1024 * 1024)} MB or less."
                }
            }
        }

        private fun detectMimeType(file: File): String? {
            val header = ByteArray(12)
            val count = file.inputStream().use { it.read(header) }
            if (count >= 8 && header.copyOfRange(0, 8).contentEquals(
                    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
                )) return "image/png"
            if (count >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()) {
                return "image/jpeg"
            }
            if (count >= 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(header, 8, 4, Charsets.US_ASCII) == "WEBP"
            ) return "image/webp"
            return null
        }

        @Suppress("DEPRECATION")
        private fun applyOrientation(bitmap: Bitmap, source: File): Bitmap {
            val orientation = runCatching {
                ExifInterface(source.absolutePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
                else -> return bitmap
            }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
    }
}
