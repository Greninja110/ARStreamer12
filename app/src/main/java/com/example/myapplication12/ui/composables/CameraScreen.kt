package com.example.myapplication12.ui.composables

import android.Manifest
import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraInfo
import androidx.camera.core.MeteringPoint
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.* // Import necessary icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView // If needed for TextureView/SurfaceView preview
import com.google.ar.core.ArCoreApk
import com.google.ar.core.TrackingState
import com.example.myapplication12.BuildConfig
import com.example.myapplication12.model.StreamMode
import com.example.myapplication12.service.StreamingService
import com.example.myapplication12.ui.theme.* // Import your theme colors
import com.example.myapplication12.ui.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private const val TAG_CAM_SCREEN = "CameraScreen"

@Composable
fun CameraScreen(
    viewModel: MainViewModel,
    // Callbacks to Activity/Fragment to interact with Service/Managers
    requestPermissions: (Array<String>) -> Unit,
    checkAndRequestPermissions: () -> Boolean, // Returns true if permissions granted
    startStreaming: (StreamMode) -> Unit,
    stopStreaming: () -> Unit,
    setZoomRatio: (Float) -> Unit,
    setLinearZoom: (Float) -> Unit,
    triggerFocusAndMetering: (MeteringPoint) -> Unit,
    setExposure: (Int) -> Unit,
    getCameraInfo: () -> CameraInfo?, // Function to get current CameraInfo
    requestArCoreInstallAction: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Collect state from ViewModel
    val showDialog by viewModel.showStartupDialog
    val isStreaming by viewModel.isStreaming.collectAsState()
    val serverAddress by viewModel.serverAddress.collectAsState()
    val arTrackingState by viewModel.arTrackingState.collectAsState()
    val connectedClients by viewModel.connectedClients.collectAsState()
    val selectedMode by viewModel.selectedMode.collectAsState()
    val videoFps by viewModel.videoFps.collectAsState()
    val arFps by viewModel.arFps.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val showRationale by viewModel.showPermissionRationale
    val showArInstall by viewModel.showArInstallDialog

    // Camera Control State from ViewModel
    val zoomRatio by viewModel.zoomRatio
    val linearZoom by viewModel.linearZoom
    val minZoomRatio by viewModel.minZoomRatio
    val maxZoomRatio by viewModel.maxZoomRatio

    val exposureIndex by viewModel.exposureIndex
    val minExposureIndex by viewModel.minExposureIndex
    val maxExposureIndex by viewModel.maxExposureIndex
    val exposureStep by viewModel.exposureStep

    // Focus indicator state
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var showFocusIndicator by remember { mutableStateOf(false) }


    // --- Permission Handling ---
    val permissionsToRequest = remember {
        mutableListOf(Manifest.permission.CAMERA).apply {
            // Add mic permission conditionally based on potential modes later? Or always request?
            add(Manifest.permission.RECORD_AUDIO)
            // Network permissions are normal, no runtime request needed usually
        }.toTypedArray()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            Log.i(TAG_CAM_SCREEN, "All permissions granted.")
            // Permissions granted, proceed (e.g., show startup dialog or start service)
            viewModel.showStartupDialog.value = true
        } else {
            Log.w(TAG_CAM_SCREEN, "Not all permissions granted: $permissions")
            // Check if rationale should be shown
            val shouldShowRationale = permissions.any { (perm, granted) ->
                !granted && !(context as Activity).shouldShowRequestPermissionRationale(perm)
            }
            if (shouldShowRationale) {
                // User denied permanently (or first time deny with "Don't ask again")
                // Show a message guiding them to settings
                scope.launch {
                    snackbarHostState.showSnackbar("Permissions denied. Please enable them in App Settings.", duration = SnackbarDuration.Long)
                }
            } else {
                // User denied, but can ask again. Show rationale.
                // Find the first denied permission to show rationale for
                val firstDenied = permissions.entries.firstOrNull { !it.value }?.key
                firstDenied?.let { viewModel.requestPermissionRationale(it) }
                    ?: scope.launch { // Fallback message if permission key not found somehow
                        snackbarHostState.showSnackbar("Permissions required to function.", duration = SnackbarDuration.Short)
                    }
            }
        }
    }

    // --- Effects ---

    // Check permissions on launch
    LaunchedEffect(Unit) {
        if (!checkAndRequestPermissions()) {
            Log.d(TAG_CAM_SCREEN, "Requesting permissions on launch.")
            requestPermissions(permissionsToRequest) // Use callback to Activity
        } else {
            Log.d(TAG_CAM_SCREEN, "Permissions already granted on launch.")
            // If permissions are already granted, directly show the startup dialog
            viewModel.showStartupDialog.value = true
        }
    }

    // Effect to handle ARCore Installation request
    LaunchedEffect(showArInstall) {
        if (showArInstall) {
            Log.d(TAG_CAM_SCREEN, "ARCore installation requested by ViewModel.")
            requestArCoreInstallAction() // Trigger Activity's AR install flow
            viewModel.arCoreInstallRequested() // Reset flag in ViewModel
        }
    }

    // Effect to show error messages
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            Log.e(TAG_CAM_SCREEN, "Error received: $it")
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Long)
            viewModel.clearError() // Clear error after showing
        }
    }

    // Effect to update ViewModel with CameraInfo periodically (or on change)
    LaunchedEffect(isStreaming) { // Re-check when streaming starts/stops
        while (true) {
            if (isStreaming) {
                getCameraInfo()?.let { info ->
                    viewModel.updateCameraState(info)
                }
            }
            delay(1000) // Update camera state info every second (adjust interval as needed)
        }
    }

    // Effect to manage focus indicator visibility
    LaunchedEffect(showFocusIndicator) {
        if (showFocusIndicator) {
            delay(1000) // Show indicator for 1 second
            showFocusIndicator = false
            focusPoint = null
        }
    }


    // --- UI ---

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // --- Camera Preview Area ---
            // Placeholder for Camera Preview. Replace with actual preview if needed.
            // Using AndroidView for TextureView/SurfaceView is an option,
            // or drawing frames onto a Canvas if processing raw data.
            // For ARCore + CameraX, often no direct preview is rendered in the *service*,
            // but if the Activity needs a preview:
            /*
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        // Get SurfaceProvider from this view and link to CameraX Preview UseCase
                        // This needs careful setup with CameraManager
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            */
            // Simple background placeholder:
            Box(modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.8f))
                // --- Tap to Focus ---
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { offset ->
                            Log.d(TAG_CAM_SCREEN, "Tap detected at: $offset")
                            val cameraInfo = getCameraInfo()
                            if (cameraInfo != null && cameraInfo.isFocusMeteringSupported(FocusMeteringAction.FLAG_AF)) {
                                focusPoint = offset // Store tap location
                                showFocusIndicator = true // Show indicator
                                val factory = SurfaceOrientedMeteringPointFactory(size.width.toFloat(), size.height.toFloat())
                                val meteringPoint = factory.createPoint(offset.x, offset.y)
                                Log.d(TAG_CAM_SCREEN, "Triggering focus/metering via callback")
                                triggerFocusAndMetering(meteringPoint) // Use callback to Activity/Service
                            } else {
                                Log.w(TAG_CAM_SCREEN, "Focus/Metering not supported or CameraInfo unavailable.")
                                scope.launch { snackbarHostState.showSnackbar("Tap to focus not supported", duration = SnackbarDuration.Short) }
                            }
                        }
                    )
                }
            ) {
                // Draw focus indicator
                if (showFocusIndicator && focusPoint != null) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawCircle(
                            color = Color.White,
                            radius = 40f,
                            center = focusPoint!!,
                            style = Stroke(width = 2f)
                        )
                    }
                }
            }


            // --- UI Overlay ---
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Info Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Status Text (IP Address, Connection Status)
                    Column {
                        Text(
                            text = if (isStreaming) "Streaming" else "Idle",
                            color = if (isStreaming) ColorTracking else MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = serverAddress ?: "Starting...",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Clients: $connectedClients",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // AR Status & FPS
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selectedMode == StreamMode.VIDEO_AUDIO_AR) {
                            ARStatusIndicator(arTrackingState)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "AR: ${arFps}fps",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        if (selectedMode == StreamMode.VIDEO_ONLY || selectedMode == StreamMode.VIDEO_AUDIO_AR) {
                            Text(
                                text = "Vid: ${videoFps}fps",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Bottom Controls Row
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Camera Controls (Only show if streaming video)
                    if (isStreaming && (selectedMode == StreamMode.VIDEO_ONLY || selectedMode == StreamMode.VIDEO_AUDIO_AR)) {
                        CameraControls(
                            zoomRatio = zoomRatio,
                            linearZoom = linearZoom,
                            minZoomRatio = minZoomRatio,
                            maxZoomRatio = maxZoomRatio,
                            exposureIndex = exposureIndex,
                            minExposureIndex = minExposureIndex,
                            maxExposureIndex = maxExposureIndex,
                            exposureStep = exposureStep,
                            onZoomRatioChanged = { setZoomRatio(it) }, // Use callback
                            onLinearZoomChanged = { setLinearZoom(it) }, // Use callback
                            onExposureChanged = { setExposure(it) } // Use callback
                        )
                        Spacer(Modifier.height(16.dp))
                    }

                    // Start/Stop Button
                    Button(
                        onClick = {
                            if (isStreaming) {
                                stopStreaming() // Use callback
                            } else {
                                // If stopped, likely need to show startup dialog again or have a default mode
                                // For simplicity, assume we restart with last selected mode if available
                                selectedMode?.let { mode ->
                                    if (checkAndRequestPermissions()) { // Re-check perms before starting
                                        startStreaming(mode) // Use callback
                                    } else {
                                        requestPermissions(permissionsToRequest) // Use callback
                                    }
                                } ?: run {
                                    // If no mode selected (shouldn't happen if dialog was shown), show dialog
                                    viewModel.showStartupDialog.value = true
                                }
                            }
                        },
                        modifier = Modifier.width(120.dp)
                    ) {
                        Icon(
                            if (isStreaming) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (isStreaming) "Stop Streaming" else "Start Streaming"
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (isStreaming) "Stop" else "Start")
                    }
                    // Debug info
                    if (BuildConfig.DEBUG) {
                        Text(
                            "Mode: ${selectedMode?.name ?: "N/A"}",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            // --- Dialogs ---
            if (showDialog) {
                StartupOptionsDialog(
                    onDismissRequest = {
                        // Optional: Handle dismiss action if needed, e.g., close app or show message
                        // For now, just keep dialog open until a choice is made or explicitly cancelled
                        // viewModel.showStartupDialog.value = false // Example: dismiss closes dialog
                    },
                    onModeSelected = { mode ->
                        viewModel.showStartupDialog.value = false // Hide dialog
                        if (checkAndRequestPermissions()) {
                            startStreaming(mode) // Use callback
                        } else {
                            requestPermissions(permissionsToRequest) // Use callback
                        }
                    }
                )
            }

            if (showRationale != null) {
                val permission = showRationale!!
                val text = getPermissionRationaleText(permission)
                PermissionRationaleDialog(
                    permissionText = text,
                    onDismiss = { viewModel.dismissPermissionRationale() },
                    onConfirm = {
                        viewModel.dismissPermissionRationale()
                        // Re-launch the permission request via Activity
                        requestPermissions(arrayOf(permission))
                    }
                )
            }
        }
    }
}

@Composable
fun ARStatusIndicator(state: TrackingState) {
    val (icon, color, text) = when (state) {
        TrackingState.TRACKING -> Triple(Icons.Default.CheckCircle, ColorTracking, "Tracking")
        TrackingState.PAUSED -> Triple(Icons.Default.PauseCircle, ColorTrackingPaused, "Paused")
        TrackingState.STOPPED -> Triple(Icons.Default.Error, ColorTrackingStopped, "Stopped")
        // Add limited tracking state?
        // TrackingState.LIMITED_EXCESSIVE_MOTION -> ...
        // TrackingState.LIMITED_INSUFFICIENT_FEATURES -> ...
        else -> Triple(Icons.Default.HelpOutline, MaterialTheme.colorScheme.outline, state.name) // Handle other states
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = "AR Status: $text",
            tint = color,
            modifier = Modifier.size(16.dp)
        )
        // Optional: Show text description
        // Spacer(Modifier.width(4.dp))
        // Text(text, fontSize = 12.sp, color = color)
    }
}

@Composable
fun CameraControls(
    zoomRatio: Float,
    linearZoom: Float,
    minZoomRatio: Float,
    maxZoomRatio: Float,
    exposureIndex: Int,
    minExposureIndex: Int,
    maxExposureIndex: Int,
    exposureStep: Float,
    onZoomRatioChanged: (Float) -> Unit,
    onLinearZoomChanged: (Float) -> Unit,
    onExposureChanged: (Int) -> Unit
) {
    // Use remember for slider positions to avoid instant jump on recomposition from state update
    var currentLinearZoom by remember(linearZoom) { mutableFloatStateOf(linearZoom) }
    var currentExposure by remember(exposureIndex) { mutableIntStateOf(exposureIndex) }

    val showZoom = maxZoomRatio > minZoomRatio
    val showExposure = minExposureIndex < maxExposureIndex

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), // Semi-transparent background
                MaterialTheme.shapes.medium
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Zoom Control
        if (showZoom) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ZoomOut, contentDescription = "Zoom Out", modifier = Modifier.size(18.dp))
                Slider(
                    value = currentLinearZoom,
                    onValueChange = { newValue ->
                        currentLinearZoom = newValue
                        onLinearZoomChanged(newValue) // Use callback
                    },
                    valueRange = 0f..1f,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                Icon(Icons.Default.ZoomIn, contentDescription = "Zoom In", modifier = Modifier.size(18.dp))
                Text(
                    "${(zoomRatio * 10).roundToInt() / 10.0}x", // Format zoom ratio
                    fontSize = 12.sp,
                    modifier = Modifier.width(40.dp), // Fixed width for text
                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                )
            }
        }

        // Exposure Control
        if (showExposure) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.BrightnessMedium, contentDescription = "Exposure", modifier = Modifier.size(18.dp)) // Or BrightnessLow/High
                Slider(
                    value = currentExposure.toFloat(),
                    onValueChange = { newValue ->
                        currentExposure = newValue.roundToInt()
                        onExposureChanged(currentExposure) // Use callback
                    },
                    valueRange = minExposureIndex.toFloat()..maxExposureIndex.toFloat(),
                    steps = (maxExposureIndex - minExposureIndex - 1).coerceAtLeast(0), // Steps between values
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                val evValue = exposureIndex * exposureStep
                Text(
                    "${if (evValue > 0) "+" else ""}${(evValue * 10).roundToInt() / 10.0} EV", // Format EV value
                    fontSize = 12.sp,
                    modifier = Modifier.width(40.dp), // Fixed width
                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                )
            }
        }
    }
}


fun getPermissionRationaleText(permission: String): String {
    return when (permission) {
        Manifest.permission.CAMERA -> "Camera permission is required for video streaming and AR features."
        Manifest.permission.RECORD_AUDIO -> "Microphone permission is required for audio streaming."
        else -> "This permission is required for the app to function correctly."
    }
}