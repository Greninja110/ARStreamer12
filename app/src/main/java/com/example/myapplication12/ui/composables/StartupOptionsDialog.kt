package com.example.myapplication12.ui.composables

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myapplication12.model.StreamMode

@Composable
fun StartupOptionsDialog(
    onDismissRequest: () -> Unit, // Usually called if user cancels or taps outside
    onModeSelected: (StreamMode) -> Unit
) {
    var selectedMode by remember { mutableStateOf(StreamMode.default()) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("Select Streaming Mode") },
        text = {
            Column {
                StreamMode.values().forEach { mode ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = (mode == selectedMode),
                                onClick = { selectedMode = mode }
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (mode == selectedMode),
                            onClick = null // Recommended way: null onClick for RadioButton when Row is selectable
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = mode.description,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onModeSelected(selectedMode) }) {
                Text("Start")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Cancel")
            }
        }
    )
}