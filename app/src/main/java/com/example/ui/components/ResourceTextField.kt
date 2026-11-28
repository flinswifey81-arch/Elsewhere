package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.BuildConfig

/**
 * Compact parent field in Redesign Lab. Only Done changes the parent draft; the parent still
 * owns Save and validation. A preview is never used as editor input or serialized as text.
 */
@Composable
internal fun ResourceTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    minLines: Int = 2,
    maxLines: Int = 8,
    maxLength: Int? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    supportingText: (@Composable () -> Unit)? = null
) {
    if (BuildConfig.BUILD_TYPE != "phoneIntegration") {
        OutlinedTextField(value, onValueChange, label = { Text(label) }, modifier = modifier,
            minLines = minLines, maxLines = maxLines, enabled = enabled, readOnly = readOnly,
            supportingText = supportingText, shape = MaterialTheme.shapes.medium, colors = elsewhereTextFieldColors())
        return
    }
    var isOpen by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        OutlinedCard(onClick = { isOpen = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()
            .testTag("open_long_text_$label"), border = elsewhereCardBorder()) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(if (value.isBlank()) "No text yet" else value.take(420),
                    style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text("${value.length} characters · ${if (readOnly) "Read full text" else "Open editor"}",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        supportingText?.invoke()
    }
    if (isOpen) {
        FocusedResourceEditor(label, value, readOnly, maxLength, onClose = { isOpen = false },
            onDone = { onValueChange(it); isOpen = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FocusedResourceEditor(
    label: String,
    original: String,
    readOnly: Boolean,
    maxLength: Int?,
    onClose: () -> Unit,
    onDone: (String) -> Unit
) {
    // Do not put pasted documents in the Activity state Bundle. The underlying parent stays
    // composed, and an ordinary Back/cancel never modifies that parent's draft.
    var field by remember { mutableStateOf(TextFieldValue(original)) }
    var showFind by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var findStatus by remember { mutableStateOf("") }
    var fontSize by remember { mutableIntStateOf(16) }
    var confirming by remember { mutableStateOf(false) }
    val bodyFocus = remember { FocusRequester() }
    val overLimit = maxLength != null && field.text.length > maxLength
    fun close() {
        if (field.text == original || readOnly) onClose() else confirming = true
    }
    fun navigate(range: TextRange) {
        bodyFocus.requestFocus()
        field = field.copy(selection = range, composition = null)
    }
    fun findNext() {
        if (query.isEmpty()) return
        val first = field.text.indexOf(query, field.selection.max, ignoreCase = true)
        val match = if (first >= 0) first else field.text.indexOf(query, ignoreCase = true)
        if (match >= 0) {
            navigate(TextRange(match, match + query.length))
            findStatus = "Match at character ${match + 1}"
        } else findStatus = "No matches"
    }

    Dialog(
        onDismissRequest = ::close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        BackHandler(enabled = !confirming, onBack = ::close)
        Scaffold(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().testTag("focused_resource_editor"),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = ::close) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to form")
                        }
                    },
                    actions = {
                        TextButton(onClick = { if (readOnly) onClose() else onDone(field.text) },
                            enabled = !overLimit || readOnly, modifier = Modifier.testTag("long_text_done")) {
                            Text("Done")
                        }
                    },
                    colors = elsewhereTopAppBarColors()
                )
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    IconButton(onClick = { showFind = !showFind; findStatus = "" }) {
                        Icon(Icons.Default.Search, contentDescription = "Find in text")
                    }
                    IconButton(onClick = { navigate(TextRange.Zero) }) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Jump to start")
                    }
                    IconButton(onClick = { navigate(TextRange(field.text.length)) }) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Jump to end")
                    }
                    IconButton(onClick = { fontSize -= 2 }, enabled = fontSize > 12) {
                        Icon(Icons.Default.Remove, contentDescription = "Decrease editor text size")
                    }
                    IconButton(onClick = { fontSize += 2 }, enabled = fontSize < 28) {
                        Icon(Icons.Default.Add, contentDescription = "Increase editor text size")
                    }
                }
                if (showFind) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(query, { query = it; findStatus = "" },
                            label = { Text("Find text") }, singleLine = true, modifier = Modifier.weight(1f).testTag("long_text_query"),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { findNext() }))
                        TextButton(onClick = ::findNext, enabled = query.isNotEmpty()) { Text("Next match") }
                    }
                    if (findStatus.isNotEmpty()) Text(findStatus,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.labelMedium)
                }
                val count = if (maxLength == null) "${field.text.length} characters"
                    else "${field.text.length} / $maxLength characters"
                Text(if (overLimit && !readOnly) "$count. Shorten the text before choosing Done." else count,
                    color = if (overLimit && !readOnly) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = field, onValueChange = { field = it; findStatus = "" }, readOnly = readOnly,
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 8.dp)
                        .focusRequester(bodyFocus).testTag("long_text_body"),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = fontSize.sp, lineHeight = (fontSize + 7).sp
                    ),
                    label = { Text("Full text") }, colors = elsewhereTextFieldColors()
                )
            }
        }
        if (confirming) {
            AlertDialog(
                onDismissRequest = { confirming = false },
                title = { Text("Keep these field edits?") },
                text = { Text("Keep changes returns them to the form. Save the resource separately.") },
                confirmButton = {
                    TextButton(onClick = { onDone(field.text) }, enabled = !overLimit) { Text("Keep changes") }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { confirming = false }) { Text("Keep editing") }
                        TextButton(onClick = onClose) { Text("Discard edits") }
                    }
                }
            )
        }
    }
}
