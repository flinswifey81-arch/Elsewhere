package com.example.ui.screens

import com.example.ui.components.ResourceTextField
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.CampaignChapterEntity
import com.example.data.model.CampaignContinuityItemEntity
import com.example.data.model.CampaignPlaythroughField
import com.example.data.model.campaignPlaythroughStateAt
import com.example.data.model.PLAYTHROUGH_NARRATOR_APPROVED

@Composable
internal fun CampaignPlaythroughStateSection(
    campaignId: String,
    chapters: List<CampaignChapterEntity>,
    items: List<CampaignContinuityItemEntity>,
    busy: Boolean,
    onSave: (String, CampaignPlaythroughField, String, String, Boolean, () -> Unit) -> Unit
) = key(campaignId) {
    val episodes = chapters.filter { it.campaignId == campaignId }.sortedBy { it.ordinal }
    var selected by remember { mutableStateOf<String?>(null) }
    val episode = episodes.firstOrNull { it.chapterId == selected } ?: episodes.lastOrNull()
    var pickingEpisode by remember { mutableStateOf(false) }
    var pickingField by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var field by remember { mutableStateOf(CampaignPlaythroughField.LOCATION) }
    var subject by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var existing by remember { mutableStateOf(false) }
    var shareWithNarrator by remember { mutableStateOf(false) }

    Text("Playthrough state", style = MaterialTheme.typography.titleSmall)
    Text(
        "Record what is true in this episode. Changes carry into later episodes in this Campaign, not into other Campaigns or shared World canon. Entries stay local unless you make them available to Scenario narration.",
        style = MaterialTheme.typography.bodySmall
    )
    if (episode == null) {
        Text("Add an episode to start tracking its state.", style = MaterialTheme.typography.bodySmall)
    } else {
        val current = campaignPlaythroughStateAt(episode, episodes, items)
        Box {
            OutlinedButton(onClick = { pickingEpisode = true }, enabled = !busy) {
                Text("Episode ${episode.ordinal}: ${episode.title}")
            }
            DropdownMenu(expanded = pickingEpisode, onDismissRequest = { pickingEpisode = false }) {
                episodes.forEach { item ->
                    DropdownMenuItem(
                        text = { Text("Episode ${item.ordinal}: ${item.title}") },
                        onClick = { selected = item.chapterId; pickingEpisode = false }
                    )
                }
            }
        }
        current.forEach { row ->
            val rowField = requireNotNull(CampaignPlaythroughField.fromItemType(row.itemType))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("${rowField.label} · ${row.title}", style = MaterialTheme.typography.labelLarge)
                    Text(row.details, style = MaterialTheme.typography.bodySmall,
                        maxLines = if (com.example.BuildConfig.BUILD_TYPE == "phoneIntegration") 3 else Int.MAX_VALUE,
                        overflow = TextOverflow.Ellipsis)
                }
                TextButton(enabled = !busy, onClick = {
                    field = rowField
                    subject = row.title
                    value = row.details
                    existing = true
                    shareWithNarrator = row.resolution == PLAYTHROUGH_NARRATOR_APPROVED
                    editing = true
                }) { Text("Change") }
            }
        }
        TextButton(
            modifier = Modifier.testTag("campaign_new_playthrough_state"),
            enabled = !busy,
            onClick = {
                field = CampaignPlaythroughField.LOCATION
                subject = ""
                value = ""
                existing = false
                shareWithNarrator = false
                editing = true
            }
        ) { Text("Record state") }

        if (editing) AlertDialog(
            onDismissRequest = { if (!busy) editing = false },
            title = { Text("State in Episode ${episode.ordinal}") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box {
                        OutlinedButton(enabled = !existing, onClick = { pickingField = true }) {
                            Text(field.label)
                        }
                        DropdownMenu(expanded = pickingField, onDismissRequest = { pickingField = false }) {
                            CampaignPlaythroughField.entries.forEach { option ->
                                DropdownMenuItem(text = { Text(option.label) }, onClick = {
                                    field = option
                                    pickingField = false
                                })
                            }
                        }
                    }
                    OutlinedTextField(
                        value = subject, onValueChange = { subject = it.take(200) },
                        enabled = !existing, singleLine = true,
                        label = { Text("Person, place, or thread") },
                        modifier = Modifier.testTag("campaign_playthrough_subject")
                    )
                    ResourceTextField(
                        value = value, onValueChange = { value = it.take(12_000) },
                        label = "What is true now?",
                        supportingText = if (existing) {
                            { Text("Leave blank to clear this value from this episode onward.") }
                        } else null,
                        minLines = 2,
                        modifier = Modifier.testTag("campaign_playthrough_value"),
                        maxLength = 12_000
                    )
                    Row {
                        Checkbox(
                            checked = shareWithNarrator,
                            onCheckedChange = { shareWithNarrator = it },
                            enabled = !busy && value.isNotBlank()
                        )
                        Text("Use in Scenario narration. The full entry is used when it fits the selected model. Chat shows a notice if entries do not fit. This does not give characters that knowledge.")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("save_campaign_playthrough_state"),
                    enabled = !busy && subject.isNotBlank() && (existing || value.isNotBlank()),
                    onClick = {
                        onSave(episode.chapterId, field, subject, value, shareWithNarrator) { editing = false }
                    }
                ) { Text(if (value.isBlank()) "Clear value" else "Save") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } }
        )
    }
}
