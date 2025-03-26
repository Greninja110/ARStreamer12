package com.example.myapplication12.ui.composables

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.myapplication12.R

@Composable
fun PermissionRationaleDialog(
    permissionText: String, // e.g., "Camera access is required to stream video."
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Permission Required") },
        text = { Text(permissionText) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

// Example usage within your main screen/activity:
// if (viewModel.showPermissionRationale.value != null) {
//    val permission = viewModel.showPermissionRationale.value!!
//    val text = when (permission) {
//        Manifest.permission.CAMERA -> "Camera permission is required for video and AR features."
//        Manifest.permission.RECORD_AUDIO -> "Microphone permission is required for audio streaming."
//        else -> "This permission is required for the app to function correctly."
//    }
//    PermissionRationaleDialog(
//        permissionText = text,
//        onDismiss = { viewModel.dismissPermissionRationale() },
//        onConfirm = {
//            viewModel.dismissPermissionRationale()
//            // Re-launch the permission request
//            permissionLauncher.launch(arrayOf(permission)) // Assuming permissionLauncher is defined
//        }
//    )
//}