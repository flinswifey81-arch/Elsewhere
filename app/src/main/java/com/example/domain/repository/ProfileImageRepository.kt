@file:android.annotation.SuppressLint("ExifInterface")

package com.example.domain.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.graphics.scale
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class ProfileImageKind(val directoryName: String) {
    PERSONA("personas"),
    CHARACTER("characters"),
    SCENARIO("scenarios")
}

data class ProfileImageState(
    val file: File?,
    val revision: Long
)

/**
 * Owns display-only Persona and Character pictures in app-private storage.
 *
 * Images deliberately have no Room row and no Character/Persona JSON field. Their stable
 * association is the hashed internal profile ID, which keeps paths private and prevents them
 * from entering exports or model context. Replacements are normalized to metadata-free PNG and
 * atomically moved over the prior image only after all validation succeeds.
 */
class ProfileImageRepository(
    private val context: Context,
    private val dataCoordinator: AppDataMaintenanceCoordinator = AppDataMaintenanceCoordinator.Global
) {
    private val storageMutex = Mutex()
    private val revision = MutableStateFlow(0L)

    fun observeImage(kind: ProfileImageKind, profileId: String): Flow<ProfileImageState> =
        revision.map { ProfileImageState(resolveImage(kind, profileId), it) }

    fun currentImage(kind: ProfileImageKind, profileId: String): ProfileImageState =
        ProfileImageState(resolveImage(kind, profileId), revision.value)

    suspend fun replaceImage(kind: ProfileImageKind, profileId: String, source: Uri): File =
        dataCoordinator.withMutation {
            storageMutex.withLock {
                withContext(Dispatchers.IO) {
                requireValidProfileId(profileId)
                val directory = imageDirectory(kind).apply { mkdirs() }
                require(directory.isDirectory) { "Profile picture storage is unavailable." }
                val key = storageKey(kind, profileId)
                val incoming = File(directory, ".$key-${UUID.randomUUID()}.incoming")
                val normalized = File(directory, ".$key-${UUID.randomUUID()}.normalized")
                val destination = File(directory, "$key.png")
                var decoded: Bitmap? = null
                var oriented: Bitmap? = null
                var scaled: Bitmap? = null
                try {
                    var byteCount = 0L
                    context.contentResolver.openInputStream(source).use { input ->
                        requireNotNull(input) { "The selected image could not be opened." }
                        FileOutputStream(incoming).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                byteCount += read
                                require(byteCount <= MAX_SOURCE_BYTES) {
                                    "Profile pictures must be ${MAX_SOURCE_BYTES / (1024 * 1024)} MB or smaller."
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    require(byteCount > 0) { "The selected image is empty." }

                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(incoming.absolutePath, bounds)
                    val mimeType = detectMimeType(incoming) ?: bounds.outMimeType?.lowercase()
                    require(mimeType in SUPPORTED_MIME_TYPES) {
                        "Only JPEG, PNG, and WebP profile pictures are supported."
                    }
                    require(bounds.outWidth > 0 && bounds.outHeight > 0) {
                        "The selected file is not a readable image."
                    }
                    require(bounds.outWidth <= MAX_SOURCE_DIMENSION && bounds.outHeight <= MAX_SOURCE_DIMENSION) {
                        "Profile pictures cannot exceed $MAX_SOURCE_DIMENSION px on either side."
                    }
                    require(bounds.outWidth.toLong() * bounds.outHeight.toLong() <= MAX_SOURCE_PIXELS) {
                        "Profile pictures cannot exceed ${MAX_SOURCE_PIXELS / 1_000_000} megapixels."
                    }

                    decoded = requireNotNull(
                        BitmapFactory.decodeFile(
                            incoming.absolutePath,
                            BitmapFactory.Options().apply {
                                inSampleSize = calculateDecodeSampleSize(bounds.outWidth, bounds.outHeight)
                                inPreferredConfig = Bitmap.Config.ARGB_8888
                            }
                        )
                    ) { "The selected image could not be decoded." }
                    oriented = applyOrientation(requireNotNull(decoded), incoming)
                    scaled = scaleForStorage(requireNotNull(oriented))
                    FileOutputStream(normalized).use { output ->
                        require(requireNotNull(scaled).compress(Bitmap.CompressFormat.PNG, 100, output)) {
                            "The profile picture could not be normalized."
                        }
                    }
                    require(normalized.length() in 1..MAX_STORED_BYTES) {
                        "The normalized profile picture is too large to store."
                    }

                    try {
                        Files.move(
                            normalized.toPath(),
                            destination.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING
                        )
                    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                        Files.move(normalized.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                    revision.value += 1
                    destination
                } finally {
                    if (scaled != null && scaled !== oriented && scaled !== decoded) scaled?.recycle()
                    if (oriented != null && oriented !== decoded) oriented?.recycle()
                    decoded?.recycle()
                    incoming.delete()
                    normalized.delete()
                }
                }
            }
        }

    suspend fun removeImage(kind: ProfileImageKind, profileId: String): Boolean =
        dataCoordinator.withMutation {
            storageMutex.withLock {
                withContext(Dispatchers.IO) {
                    requireValidProfileId(profileId)
                    val removed = resolveImage(kind, profileId)?.delete() == true
                    if (removed) revision.value += 1
                    removed
                }
            }
        }

    suspend fun copyImage(kind: ProfileImageKind, sourceProfileId: String, targetProfileId: String): Boolean =
        dataCoordinator.withMutation {
            storageMutex.withLock {
                withContext(Dispatchers.IO) {
                requireValidProfileId(sourceProfileId)
                requireValidProfileId(targetProfileId)
                val source = resolveImage(kind, sourceProfileId) ?: return@withContext false
                val directory = imageDirectory(kind).apply { mkdirs() }
                val destination = File(directory, "${storageKey(kind, targetProfileId)}.png")
                val temporary = File(directory, ".${destination.name}-${UUID.randomUUID()}.copying")
                try {
                    source.copyTo(temporary, overwrite = true)
                    try {
                        Files.move(
                            temporary.toPath(),
                            destination.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING
                        )
                    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                        Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                    revision.value += 1
                    true
                } finally {
                    temporary.delete()
                }
                }
            }
        }

    fun resolveImage(kind: ProfileImageKind, profileId: String): File? {
        if (profileId.isBlank() || profileId.length > MAX_PROFILE_ID_LENGTH) return null
        val directory = imageDirectory(kind).canonicalFile
        val file = File(directory, "${storageKey(kind, profileId)}.png").canonicalFile
        return file.takeIf {
            it.parentFile == directory && it.isFile && it.length() in 1..MAX_STORED_BYTES
        }
    }

    private fun imageDirectory(kind: ProfileImageKind): File =
        File(File(context.filesDir, PROFILE_IMAGE_DIRECTORY), kind.directoryName)

    private fun requireValidProfileId(profileId: String) {
        require(profileId.isNotBlank() && profileId.length <= MAX_PROFILE_ID_LENGTH) {
            "The profile ID is invalid."
        }
    }

    companion object {
        const val MAX_SOURCE_BYTES = 12L * 1024 * 1024
        const val MAX_STORED_BYTES = 8L * 1024 * 1024
        const val MAX_SOURCE_DIMENSION = 8192
        const val MAX_SOURCE_PIXELS = 32_000_000L
        const val MAX_DECODED_PIXELS = 4_000_000L
        const val MAX_STORED_DIMENSION = 1024
        private const val MAX_PROFILE_ID_LENGTH = 1024
        private const val PROFILE_IMAGE_DIRECTORY = "profile_images"
        private val SUPPORTED_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")

        internal fun storageKey(kind: ProfileImageKind, profileId: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest("${kind.name}\u0000$profileId".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

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

        private fun detectMimeType(file: File): String? {
            val header = ByteArray(12)
            val count = file.inputStream().use { it.read(header) }
            if (count >= 8 && header.copyOfRange(0, 8).contentEquals(
                    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
                )
            ) return "image/png"
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

        private fun scaleForStorage(bitmap: Bitmap): Bitmap {
            val longest = maxOf(bitmap.width, bitmap.height)
            if (longest <= MAX_STORED_DIMENSION) return bitmap
            val scale = MAX_STORED_DIMENSION.toFloat() / longest.toFloat()
            return bitmap.scale(
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                filter = true
            )
        }
    }
}
