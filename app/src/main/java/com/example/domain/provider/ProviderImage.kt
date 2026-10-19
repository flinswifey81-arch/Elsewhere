package com.example.domain.provider

/** In-memory request material. Image bytes are never persisted in Room or logs. */
data class ProviderImage(
    val mimeType: String,
    val bytes: ByteArray,
    val sha256: String
)

object ProviderImageLimits {
    const val MAX_IMAGES = 4
    const val MAX_IMAGE_BYTES = 8L * 1024L * 1024L
    const val MAX_TOTAL_BYTES = 12L * 1024L * 1024L

    fun validate(images: List<ProviderImage>) {
        require(images.size <= MAX_IMAGES) { "A request can contain at most $MAX_IMAGES images." }
        var total = 0L
        images.forEach { image ->
            require(image.bytes.size.toLong() in 1..MAX_IMAGE_BYTES) { "An image is empty or too large." }
            total += image.bytes.size
            require(total <= MAX_TOTAL_BYTES) { "Images in one request exceed the safe aggregate size." }
        }
    }
}

class UnsupportedImageModelException(modelId: String) :
    IllegalStateException("The selected model ($modelId) does not advertise image input support. Choose a vision-capable model or remove the images.")

class UnconfirmedImageCapabilityException(modelId: String) :
    IllegalStateException("Image support has not been confirmed for the selected model ($modelId). Refresh the model catalog before sending images.")
