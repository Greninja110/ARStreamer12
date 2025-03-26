package com.example.myapplication12.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.camera.core.CameraInfo
import androidx.camera.core.FocusMeteringResult
import androidx.camera.core.MeteringPoint
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ar.core.ArCoreApk
import com.google.ar.core.TrackingState
import com.google.common.util.concurrent.ListenableFuture
import com.example.myapplication12.ar.ARManager
import com.example.myapplication12.camera.CameraManager
import com.example.myapplication12.model.StreamConfig
import com.example.myapplication12.model.StreamMode
import com.example.myapplication12.service.ServiceState
import com.example.myapplication12.service.StreamingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {

    private val TAG = "MainViewModel"

    // --- UI State ---
    val showStartupDialog = mutableStateOf(true) // Initially show the dialog
    val showPermissionRationale = mutableStateOf<String?>(null) // Holds permission type if rationale needed
    val showArInstallDialog = mutableStateOf(false) // Trigger ARCore install prompt

    // Expose relevant state from ServiceState
    val selectedMode: StateFlow<StreamMode?> = ServiceState.selectedStreamMode
    val isStreaming: StateFlow<Boolean> = ServiceState.isStreaming
    val serverAddress: StateFlow<String?> = ServiceState.serverAddress
    val arTrackingState: StateFlow<TrackingState> = ServiceState.arTrackingState
    val connectedClients: StateFlow<Int> = ServiceState.connectedClients
    val errorMessage: StateFlow<String?> = ServiceState.errorMessages

    // Frame rates
    val videoFps: StateFlow<Int> = ServiceState.videoFpsCalculator.fpsStateFlow
    val arFps: StateFlow<Int> = ServiceState.arFpsCalculator.fpsStateFlow

    // Camera Controls State
    // Expose CameraInfo/State directly or derive specific values
    // Using simple mutable state for control values, update based on CameraInfo feedback
    val zoomRatio = mutableStateOf(1.0f)
    val linearZoom = mutableStateOf(0.0f) // 0.0 to 1.0
    val minZoomRatio = mutableStateOf(1.0f)
    val maxZoomRatio = mutableStateOf(1.0f)

    val exposureIndex = mutableStateOf(0)
    val minExposureIndex = mutableStateOf(0)
    val maxExposureIndex = mutableStateOf(0)
    val exposureStep = mutableStateOf(0.0f) // EV step


    // --- Actions ---

    fun onStreamModeSelected(context: Context, mode: StreamMode) {
        Log.i(TAG, "Stream mode selected: $mode")
        showStartupDialog.value = false // Hide dialog
        val config = StreamConfig(mode = mode)
        // Start the service with the selected configuration
        val intent = StreamingService.createStartIntent(context, config)
        context.startForegroundService(intent)
    }

    fun stopStreaming(context: Context) {
        Log.i(TAG, "Requesting streaming stop.")
        // Send stop command to the service
        val intent = StreamingService.createStopIntent(context)
        context.startService(intent) // Service will handle cleanup and stopForeground/stopSelf
    }

    fun dismissPermissionRationale() {
        showPermissionRationale.value = null
    }

    fun requestPermissionRationale(permission: String) {
        showPermissionRationale.value = permission
    }

    fun clearError() {
        ServiceState.clearError()
    }

    fun requestArCoreInstall() {
        // This signals MainActivity to trigger ARCore install request
        showArInstallDialog.value = true
    }
    fun arCoreInstallRequested() {
        // Called by Activity after triggering install request
        showArInstallDialog.value = false
    }


    // --- Camera Control Actions ---
    // These need access to the CameraManager instance, which lives in the Service.
    // This is awkward. Options:
    // 1. Bind to service (complex in ViewModel)
    // 2. Send actions via Intents/Broadcasts (less direct)
    // 3. Expose CameraManager via Service Binder (possible, but couples ViewModel to Service structure)
    // 4. **Let UI trigger actions, Activity receives them and interacts with bound Service/CameraManager.** (Chosen Approach)

    // Placeholder functions - Actual implementation will be in Activity/Fragment interacting with Service/CameraManager
    fun setZoomRatioAction(ratio: Float) {
        Log.d(TAG, "UI Action: Set Zoom Ratio $ratio (Needs implementation via Service)")
        // Update local state optimistically/based on feedback
        zoomRatio.value = ratio
        // TODO: Trigger actual camera control via Service/Activity
    }

    fun setLinearZoomAction(linear: Float) {
        Log.d(TAG, "UI Action: Set Linear Zoom $linear (Needs implementation via Service)")
        linearZoom.value = linear
        // TODO: Trigger actual camera control via Service/Activity
    }

    fun triggerFocusAction(point: MeteringPoint) {
        Log.d(TAG, "UI Action: Trigger Focus at (${point.x}, ${point.y}) (Needs implementation via Service)")
        // TODO: Trigger actual camera control via Service/Activity
    }

    fun setExposureAction(index: Int) {
        Log.d(TAG, "UI Action: Set Exposure Index $index (Needs implementation via Service)")
        exposureIndex.value = index
        // TODO: Trigger actual camera control via Service/Activity
    }

    // Function called by Activity/Fragment to update ViewModel with actual Camera state
    fun updateCameraState(cameraInfo: CameraInfo?) {
        cameraInfo?.let { info ->
            info.zoomState.value?.let { state ->
                // Update only if significantly different to avoid jitter?
                if (kotlin.math.abs(zoomRatio.value - state.zoomRatio) > 0.01f) {
                    zoomRatio.value = state.zoomRatio
                }
                if (kotlin.math.abs(linearZoom.value - state.linearZoom) > 0.01f) {
                    linearZoom.value = state.linearZoom
                }
                minZoomRatio.value = state.minZoomRatio
                maxZoomRatio.value = state.maxZoomRatio
            }
            info.exposureState.let { state ->
                exposureIndex.value = state.exposureCompensationIndex
                minExposureIndex.value = state.exposureCompensationRange.lower
                maxExposureIndex.value = state.exposureCompensationRange.upper
                exposureStep.value = state.exposureCompensationStep.toFloat()
            }
        }
    }

    // Example of how Activity might call the actual CameraManager function
    // This function *should not* live in the ViewModel directly if CameraManager is in Service
    /*
    fun performSetZoomRatio(cameraManager: CameraManager?, ratio: Float) {
        viewModelScope.launch { // Use viewModelScope for UI-related async tasks
            try {
                cameraManager?.setZoomRatio(ratio)?.await() // Using await() if ListenableFuture extension used
                // Update state based on result or let updateCameraState handle it
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set zoom ratio", e)
                ServiceState.postError("Zoom failed: ${e.message}")
            }
        }
    }
    */

    init {
        Log.i(TAG, "ViewModel Initialized")
        // Optionally clear state on init if needed
        // ServiceState.resetAll() // Resetting here might conflict with service lifecycle
    }

    override fun onCleared() {
        Log.i(TAG, "ViewModel Cleared")
        super.onCleared()
    }
}

// Helper function to convert ListenableFuture to suspend function (optional)
//suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { continuation ->
//    addListener({
//        try {
//            continuation.resume(get()) {}
//        } catch (e: Exception) {
//            continuation.resumeWithException(e)
//        }
//    }, Dispatchers.IO) // Execute listener on IO thread or appropriate executor
//
//    continuation.invokeOnCancellation {
//        cancel(false)
//    }
//}