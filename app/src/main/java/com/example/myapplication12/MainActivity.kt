package com.example.myapplication12

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.CameraInfo
import androidx.camera.core.MeteringPoint
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import com.example.myapplication12.ar.ARManager
import com.example.myapplication12.camera.CameraManager
import com.example.myapplication12.model.StreamMode
import com.example.myapplication12.service.StreamingService
import com.example.myapplication12.ui.composables.CameraScreen
import com.example.myapplication12.ui.theme.ARStreamerTheme
import com.example.myapplication12.ui.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"
    private val viewModel: MainViewModel by viewModels()

    // Service binding - Optional but can be useful for direct interaction
    // private var streamingService: StreamingService? = null
    // private var isBound = false
    // private val connection = object : ServiceConnection {
    //    override fun onServiceConnected(className: ComponentName, service: IBinder) {
    //        val binder = service as StreamingService.LocalBinder
    //        streamingService = binder.getService()
    //        isBound = true
    //        Log.i(TAG, "StreamingService bound.")
    //        // Now you can access service methods if needed:
    //        // streamingService?.getCurrentState()
    //    }
    //
    //    override fun onServiceDisconnected(arg0: ComponentName) {
    //        isBound = false
    //        streamingService = null
    //        Log.i(TAG, "StreamingService unbound.")
    //    }
    //}

    // --- Activity Lifecycle ---

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")

        setContent {
            ARStreamerTheme {
                CameraScreen(
                    viewModel = viewModel,
                    requestPermissions = ::requestPermissionsLaunch,
                    checkAndRequestPermissions = ::checkAndRequestPermissions,
                    startStreaming = ::startStreamingService,
                    stopStreaming = ::stopStreamingService,
                    // Camera Control Callbacks - Implemented below
                    setZoomRatio = ::setZoomRatioControl,
                    setLinearZoom = ::setLinearZoomControl,
                    triggerFocusAndMetering = ::triggerFocusAndMeteringControl,
                    setExposure = ::setExposureControl,
                    getCameraInfo = ::getCameraInfoFromService, // Gets info via service instance
                    requestArCoreInstallAction = ::requestArCoreInstall // Triggers ARCore install
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
        // Check ARCore install status again in case it was installed while paused
        // This interacts with ARManager potentially via Service, complex to handle directly here
        // Maybe trigger a check via ViewModel -> Service -> ARManager
        // If ARCore requires install, trigger check/install flow if conditions met
        // checkArCoreInstallation() // Moved logic inside ARManager/ViewModel potentially
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause")
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart")
        // Bind to service if needed
        // Intent(this, StreamingService::class.java).also { intent ->
        //     bindService(intent, connection, Context.BIND_AUTO_CREATE)
        // }
    }

    override fun onStop() {
        Log.d(TAG, "onStop")
        // Unbind from service
        // if (isBound) {
        //     unbindService(connection)
        //     isBound = false
        //     streamingService = null
        // }
        super.onStop()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        // Consider stopping the service if the activity is destroyed and not configuration change
        // if (!isChangingConfigurations) {
        //    stopStreamingService()
        // }
        super.onDestroy()
    }

    // --- Permission Handling ---

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.all { it.value }
            if (allGranted) {
                Log.i(TAG, "Permissions granted via Activity launcher.")
                // Permissions were granted, now we can potentially start the service or show dialog
                // Check if a mode was previously selected or show dialog again
                if (viewModel.selectedMode.value == null) {
                    viewModel.showStartupDialog.value = true // Show dialog if no mode selected yet
                } else {
                    // Optional: Automatically start with last mode? Risky if user just granted perms.
                    // Better to let user confirm via dialog or button press.
                }
            } else {
                Log.w(TAG, "Permissions denied via Activity launcher.")
                // Let the Composable's launcher handle rationale/snackbar messages
                // based on the permission result mapping back to the ViewModel state.
                permissions.entries.firstOrNull { !it.value }?.key?.let { firstDenied ->
                    if (!shouldShowRequestPermissionRationale(firstDenied)) {
                        // User denied permanently
                        // Composable should show snackbar guiding to settings
                    } else {
                        // Show rationale dialog via ViewModel state change
                        viewModel.requestPermissionRationale(firstDenied)
                    }
                }
            }
        }

    private fun requestPermissionsLaunch(permissions: Array<String>) {
        Log.d(TAG, "Launching permission request for: ${permissions.joinToString()}")
        requestPermissionLauncher.launch(permissions)
    }

    private fun checkPermissions(permissions: Array<String>): Boolean {
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    // Checks permissions, returns true if all granted, otherwise requests them and returns false
    private fun checkAndRequestPermissions(): Boolean {
        val permissionsToRequest = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO) // Define required permissions
        val allGranted = checkPermissions(permissionsToRequest)
        if (!allGranted) {
            Log.d(TAG, "checkAndRequestPermissions: Not all granted, requesting...")
            // Requesting permissions is handled by the composable via requestPermissionsLaunch callback now
            // requestPermissionsLaunch(permissionsToRequest.filter { !checkPermissions(arrayOf(it)) }.toTypedArray())
            return false
        }
        Log.d(TAG, "checkAndRequestPermissions: All permissions already granted.")
        return true
    }


    // --- Service Interaction ---

    private fun startStreamingService(mode: StreamMode) {
        Log.i(TAG, "Requesting service start with mode: $mode")
        val config = StreamConfig(mode = mode)
        val intent = StreamingService.createStartIntent(this, config)
        ContextCompat.startForegroundService(this, intent) // Use ContextCompat for foreground start
    }

    private fun stopStreamingService() {
        Log.i(TAG, "Requesting service stop.")
        val intent = StreamingService.createStopIntent(this)
        startService(intent) // Stop intent doesn't need foreground
    }

    // --- ARCore Installation Handling ---
    private fun requestArCoreInstall() {
        Log.i(TAG, "Attempting to request ARCore installation...")
        // This ideally should be coordinated. Check availability first.
        // Assume ARManager handles the check and updates ServiceState/ViewModel.
        // If ViewModel indicates install needed (e.g., via showArInstallDialog state):
        lifecycleScope.launch {
            try {
                // The requestInstall() method needs an Activity.
                // ARManager should ideally hold the state if install was requested.
                val availability = ArCoreApk.getInstance().checkAvailability(this@MainActivity)
                if (availability.isTransient) {
                    Log.d(TAG,"ARCore availability is transient ($availability), requesting install...")
                    when (ArCoreApk.getInstance().requestInstall(this@MainActivity, true /*userRequestedInstall*/)) {
                        ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                            Log.i(TAG, "ARCore installation requested. Resuming will check status.")
                            // The install flow runs externally. onResume will handle the result implicitly.
                        }
                        ArCoreApk.InstallStatus.INSTALLED -> {
                            Log.i(TAG, "ARCore already installed during request (or updated).")
                            // Might need to re-trigger service/AR session start if needed
                        }
                    }
                } else {
                    Log.w(TAG, "ARCore install requested, but availability is not transient ($availability).")
                    if (!availability.isSupported) {
                        viewModel.clearError() // Clear previous errors
                        viewModel.requestPermissionRationale("ARCore is not supported on this device.") // Use rationale dialog for info
                    }
                }

            } catch (e: UnavailableUserDeclinedInstallationException) {
                Log.w(TAG, "User declined ARCore installation.", e)
                viewModel.requestPermissionRationale("ARCore installation is required for AR features.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request ARCore installation", e)
                viewModel.requestPermissionRationale("Failed to check or install ARCore: ${e.message}")
            }
        }
    }


    // --- Camera Control Implementation ---
    // These functions interact with the Service/CameraManager instance.
    // Requires service binding OR finding service instance (less robust).
    // Using binding approach (if `isBound` and `streamingService` are used).
    // If not binding, these could send Intents to the service.

    // Helper to get CameraManager instance (via binding or other means)
    private fun getCameraManagerInstance(): CameraManager? {
        // If using binding:
        // return if (isBound) streamingService?.getCameraManager() else null // Assumes service exposes it

        // If NOT using binding (less safe, relies on service running):
        // This is generally discouraged. Binding is preferred.
        // Find service instance - very brittle.
        // For simplicity, let's assume the service is started and CameraManager exists.
        // We need a way to get the managers from the running service. Binding is the clean way.
        // Without binding, this example won't directly work.
        // **Fallback**: Send controls via Intents (more decoupled but slower/more complex).

        Log.e(TAG, "Camera control functions require Service Binding (not fully implemented in this example).")
        // Placeholder: return null
        return null // <-- Replace with actual retrieval via binding
    }

    private fun setZoomRatioControl(ratio: Float) {
        val camManager = getCameraManagerInstance()
        if (camManager == null) {
            Log.w(TAG, "Cannot set zoom: CameraManager instance unavailable.")
            return
        }
        Log.d(TAG, "Executing SetZoomRatio: $ratio")
        lifecycleScope.launch(Dispatchers.Main) { // Ensure on Main thread for CameraX control usually
            try {
                camManager.setZoomRatio(ratio)
                // Optionally wait for future or update ViewModel based on CameraInfo later
            } catch (e: Exception) {
                Log.e(TAG, "Error setting zoom ratio", e)
                viewModel.clearError() // Clear previous errors
                viewModel.requestPermissionRationale("Zoom failed: ${e.message}") // Show error
            }
        }
    }

    private fun setLinearZoomControl(linear: Float) {
        val camManager = getCameraManagerInstance()
        if (camManager == null) {
            Log.w(TAG, "Cannot set linear zoom: CameraManager instance unavailable.")
            return
        }
        Log.d(TAG, "Executing SetLinearZoom: $linear")
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                camManager.setLinearZoom(linear)
            } catch (e: Exception) {
                Log.e(TAG, "Error setting linear zoom", e)
                viewModel.clearError()
                viewModel.requestPermissionRationale("Zoom failed: ${e.message}")
            }
        }
    }

    private fun triggerFocusAndMeteringControl(point: MeteringPoint) {
        val camManager = getCameraManagerInstance()
        if (camManager == null) {
            Log.w(TAG, "Cannot trigger focus: CameraManager instance unavailable.")
            return
        }
        Log.d(TAG, "Executing TriggerFocusAndMetering")
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                val resultFuture = camManager.triggerFocusAndMetering(point)
                // Optional: Observe result future for success/failure
                // resultFuture?.addListener({ ... }, ContextCompat.getMainExecutor(this@MainActivity))
            } catch (e: Exception) {
                Log.e(TAG, "Error triggering focus/metering", e)
                viewModel.clearError()
                viewModel.requestPermissionRationale("Focus failed: ${e.message}")
            }
        }
    }

    private fun setExposureControl(index: Int) {
        val camManager = getCameraManagerInstance()
        if (camManager == null) {
            Log.w(TAG, "Cannot set exposure: CameraManager instance unavailable.")
            return
        }
        Log.d(TAG, "Executing SetExposure: $index")
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                camManager.setExposureCompensationIndex(index)
            } catch (e: Exception) {
                Log.e(TAG, "Error setting exposure", e)
                viewModel.clearError()
                viewModel.requestPermissionRationale("Exposure failed: ${e.message}")
            }
        }
    }

    private fun getCameraInfoFromService(): CameraInfo? {
        // Retrieve CameraInfo via bound service or other means
        val camManager = getCameraManagerInstance()
        return camManager?.getCameraInfo()
    }

}

// Simple Preview for Composable layouting
@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    ARStreamerTheme {
        // Provide a dummy ViewModel for preview
        val dummyViewModel = MainViewModel()
        // Simulate some state for preview
        dummyViewModel.showStartupDialog.value = false
        // dummyViewModel.isStreaming.value = true // Requires Flow update, harder for preview
        // dummyViewModel.serverAddress.value = "192.168.1.100:8080"

        CameraScreen(
            viewModel = dummyViewModel,
            requestPermissions = {},
            checkAndRequestPermissions = { true },
            startStreaming = {},
            stopStreaming = {},
            setZoomRatio = {},
            setLinearZoom = {},
            triggerFocusAndMetering = {},
            setExposure = {},
            getCameraInfo = { null },
            requestArCoreInstallAction = {}
        )
    }
}