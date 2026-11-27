package com.example.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.domain.repository.ProfileImageKind
import com.example.domain.repository.ProfileImageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
private fun rememberCover(repository: ProfileImageRepository, kind: ProfileImageKind, id: String) =
    remember(repository, kind, id) { repository.observeImage(kind, id) }
        .collectAsState(initial = repository.currentImage(kind, id)).value

/** Independent artwork shared by Home, Chats and the World/Campaign galleries. */
@Composable
internal fun CoverArtwork(
    repository: ProfileImageRepository,
    kind: ProfileImageKind,
    id: String,
    modifier: Modifier = Modifier,
    fallback: @Composable BoxScope.() -> Unit = {}
) {
    val source = rememberCover(repository, kind, id)
    val image by source.file.rememberDecodedProfileBitmap(source.revision)
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(
            MaterialTheme.colorScheme.surfaceContainerHigh,MaterialTheme.colorScheme.surface))),
        contentAlignment=Alignment.Center
    ) {
        image?.let {
            Image(it,null,modifier=Modifier.fillMaxSize().testTag("cover_art_${kind.name}_$id"),
                contentScale=ContentScale.Crop)
        } ?: fallback()
    }
}

@Composable
internal fun CoverActions(
    repository: ProfileImageRepository, kind: ProfileImageKind, id: String, label: String,
    compact: Boolean = false
) {
    val state=rememberCover(repository,kind,id)
    var expanded by remember(id) { mutableStateOf(false) }
    var busy by remember(id) { mutableStateOf(false) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var confirmRemove by remember(id) { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    fun apply(fallbackMessage: String, action: suspend () -> Unit) {
        if (busy) return
        busy=true
        error=null
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error=fallbackMessage }
            finally { busy=false }
        }
    }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if(uri!=null) apply("Couldn’t save that cover. Try choosing a different image.") {
            repository.replaceImage(kind,id,uri)
        }
    }
    Box {
        if(compact) {
            IconButton(onClick={expanded=true},enabled=!busy,
                modifier=Modifier.testTag("cover_actions_${kind.name}_$id")) {
                Icon(Icons.Filled.MoreVert,contentDescription="Cover options for $label")
            }
        } else {
            OutlinedButton(onClick={expanded=true},enabled=!busy,
                modifier=Modifier.testTag("cover_actions_${kind.name}_$id")) {
                Icon(Icons.Filled.Image,contentDescription=null,modifier=Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp));Text(if(state.file==null)"Add cover" else "Cover options")
            }
        }
        DropdownMenu(expanded,onDismissRequest={expanded=false}) {
            DropdownMenuItem(text={Text(if(state.file==null)"Choose cover image" else "Replace cover image")},
                onClick={expanded=false;picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))})
            if(state.file!=null) DropdownMenuItem(text={Text("Remove cover",color=MaterialTheme.colorScheme.error)},
                onClick={expanded=false;confirmRemove=true})
        }
    }
    if(confirmRemove) AlertDialog(onDismissRequest={confirmRemove=false},
        title={Text("Remove cover?")},text={Text("This removes only the cover image for $label.")},
        confirmButton={TextButton(onClick={
            confirmRemove=false
            apply("Couldn’t remove that cover. Try again.") { repository.removeImage(kind,id) }
        }) { Text("Remove cover",color=MaterialTheme.colorScheme.error) }},
        dismissButton={TextButton(onClick={confirmRemove=false}){ Text("Cancel") }})
    error?.let { message ->
        AlertDialog(onDismissRequest={error=null},title={Text("Cover not changed")},text={Text(message)},
            confirmButton={TextButton(onClick={error=null}){Text("OK")}})
    }
}
