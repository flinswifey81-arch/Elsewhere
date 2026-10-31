package com.example.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/** Compact entry point so library actions do not permanently cover profile cards. */
@Composable
internal fun ProfileLibraryActions(
    profileType: String,
    onNew: () -> Unit,
    onImport: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val tagPrefix = profileType.lowercase()

    Box {
        FloatingActionButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.testTag("${tagPrefix}_actions")
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add $profileType")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("New $profileType") },
                onClick = { expanded = false; onNew() },
                modifier = Modifier.testTag("new_${tagPrefix}_fab")
            )
            DropdownMenuItem(
                text = { Text("Import JSON") },
                onClick = { expanded = false; onImport() },
                modifier = Modifier.testTag("import_${tagPrefix}_fab")
            )
        }
    }
}
