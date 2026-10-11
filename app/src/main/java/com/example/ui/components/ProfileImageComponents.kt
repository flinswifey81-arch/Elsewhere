package com.example.ui.components

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.domain.repository.ProfileImageKind
import com.example.domain.repository.ProfileImageRepository
import com.example.domain.repository.ProfileImageState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ProfileImageAvatar(
    repository: ProfileImageRepository?,
    kind: ProfileImageKind?,
    profileId: String?,
    displayName: String,
    size: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null
) {
    val state = if (repository != null && kind != null && !profileId.isNullOrBlank()) {
        repository.observeImage(kind, profileId).collectAsState(
            initial = repository.currentImage(kind, profileId)
        ).value
    } else {
        remember { ProfileImageState(file = null, revision = 0L) }
    }
    val bitmap by state.file.rememberDecodedProfileBitmap(state.revision)
    val shape = RoundedCornerShape((size.value * 0.24f).dp.coerceAtLeast(8.dp))
    val fallbackContainer = when (kind) {
        ProfileImageKind.PERSONA -> MaterialTheme.colorScheme.secondaryContainer
        ProfileImageKind.CHARACTER -> MaterialTheme.colorScheme.primaryContainer
        null -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val fallbackContent = when (kind) {
        ProfileImageKind.PERSONA -> MaterialTheme.colorScheme.onSecondaryContainer
        ProfileImageKind.CHARACTER -> MaterialTheme.colorScheme.onPrimaryContainer
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        shape = shape,
        color = fallbackContainer,
        modifier = modifier.size(size).clip(shape)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = requireNotNull(bitmap),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val initials = profileInitials(displayName)
                if (initials.isNotEmpty()) {
                    Text(
                        text = initials,
                        style = if (size >= 72.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge,
                        color = fallbackContent
                    )
                } else {
                    Icon(
                        imageVector = if (kind == ProfileImageKind.PERSONA) Icons.Default.Face else Icons.Default.Person,
                        contentDescription = contentDescription,
                        tint = fallbackContent,
                        modifier = Modifier.padding(size * 0.24f)
                    )
                }
            }
        }
    }
}

@Composable
fun ProfileImageEditor(
    repository: ProfileImageRepository?,
    kind: ProfileImageKind,
    profileId: String?,
    displayName: String,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val highFontScale = LocalDensity.current.fontScale > 1.15f
    // Work is intentionally not saveable: if a configuration change cancels the importing
    // coroutine, the rebuilt editor must not remain stuck behind a stale progress state.
    var isWorking by remember(profileId) { mutableStateOf(false) }
    var error by rememberSaveable(profileId) { mutableStateOf<String?>(null) }
    val imageState = if (repository != null && !profileId.isNullOrBlank()) {
        repository.observeImage(kind, profileId).collectAsState(
            initial = repository.currentImage(kind, profileId)
        ).value
    } else {
        remember { ProfileImageState(file = null, revision = 0L) }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && repository != null && !profileId.isNullOrBlank()) {
            isWorking = true
            error = null
            coroutineScope.launch {
                runCatching { repository.replaceImage(kind, profileId, uri) }
                    .onFailure { failure ->
                        error = failure.message ?: "The profile picture could not be saved."
                    }
                isWorking = false
            }
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        border = elsewhereCardBorder(),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(contentAlignment = Alignment.Center) {
                ProfileImageAvatar(
                    repository = repository,
                    kind = kind,
                    profileId = profileId,
                    displayName = displayName,
                    size = 88.dp,
                    contentDescription = "Profile picture for $displayName",
                    modifier = Modifier.testTag("profile_image_preview")
                )
                if (isWorking) {
                    Surface(
                        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.58f),
                        shape = RoundedCornerShape(21.dp),
                        modifier = Modifier.size(88.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Profile picture", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (profileId == null) {
                        "Save this profile first, then reopen it to add a picture."
                    } else {
                        "Stored only on this device. Display-only; never added to model context or JSON exports."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val chooseButton: @Composable (Modifier) -> Unit = { buttonModifier ->
                    Button(
                        onClick = {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        enabled = repository != null && profileId != null && !isWorking,
                        modifier = buttonModifier.testTag("replace_profile_image")
                    ) {
                        Text(if (imageState.file == null) "Choose image" else "Replace")
                    }
                }
                val removeButton: @Composable (Modifier) -> Unit = { buttonModifier ->
                    if (imageState.file != null) {
                        OutlinedButton(
                            onClick = {
                                if (repository != null && profileId != null) {
                                    isWorking = true
                                    error = null
                                    coroutineScope.launch {
                                        runCatching { repository.removeImage(kind, profileId) }
                                            .onFailure { failure ->
                                                error = failure.message ?: "The profile picture could not be removed."
                                            }
                                        isWorking = false
                                    }
                                }
                            },
                            enabled = !isWorking,
                            modifier = buttonModifier.testTag("remove_profile_image")
                        ) {
                            Text("Remove", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                if (highFontScale) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        chooseButton(Modifier.fillMaxWidth())
                        removeButton(Modifier.fillMaxWidth())
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        chooseButton(Modifier)
                        removeButton(Modifier)
                    }
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun File?.rememberDecodedProfileBitmap(revision: Long): androidx.compose.runtime.State<ImageBitmap?> =
    produceState<ImageBitmap?>(initialValue = null, this?.absolutePath, revision) {
        val file = this@rememberDecodedProfileBitmap
        value = if (file == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull()
            }
        }
    }

private fun profileInitials(displayName: String): String = displayName
    .trim()
    .split(Regex("\\s+"))
    .filter { it.isNotEmpty() }
    .take(2)
    .mapNotNull { it.firstOrNull()?.uppercaseChar() }
    .joinToString("")
