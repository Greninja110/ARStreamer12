package com.example.myapplication12.ar

import android.content.Context
import android.util.Log
import android.media.Image
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import com.example.myapplication12.model.ARFrameData
import com.example.myapplication12.model.StreamMode
import com.example.myapplication12.service.ServiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

// Logger for application-wide logging
object Logger {
    private var logFile: File? = null
    private var fileWriter: FileWriter? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun initialize(context: Context) {
        try {
            // Create logs directory if it doesn't exist
            val logsDir = File(context.getExternalFilesDir(null), "logs")
            if (!logsDir.exists()) {
                logsDir.mkdirs()
            }

            // Create log file with timestamp
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            logFile = File(logsDir, "ar_app_log_$timestamp.txt")
            fileWriter = FileWriter(logFile, true)

            log("Logger", "Log initialized at ${logFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e("Logger", "Failed to initialize logger", e)
        }
    }

    fun log(tag: String, message: String, throwable: Throwable? = null) {
        // Log to Android logcat
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.i(tag, message)
        }

        // Log to file
        try {
            fileWriter?.apply {
                val timestamp = dateFormat.format(Date())
                append("$timestamp [$tag] $message\n")
                if (throwable != null) {
                    append("$timestamp [$tag] Exception: ${throwable.message}\n")
                    throwable.stackTrace.forEach { element ->
                        append("$timestamp [$tag] \tat $element\n")
                    }
                }
                flush()
            }
        } catch (e: Exception) {
            Log.e("Logger", "Failed to write to log file", e)
        }
    }

    fun close() {
        try {
            fileWriter?.close()
        } catch (e: Exception) {
            Log.e("Logger", "Error closing log file", e)
        }
    }
}

class ARManager(
    private val context: Context,
    private val serviceScope: CoroutineScope
) : Closeable {
    private val TAG = "ARManager"

    private var arSession: Session? = null
    private var installRequested = AtomicBoolean(false)
    private var config: Config? = null

    // Executor for frame updates
    private val frameExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var frameUpdateJob: Job? = null
    private var frameScheduledFuture: java.util.concurrent.ScheduledFuture<*>? = null
    private var isFrameProcessingActive = AtomicBoolean(false)

    private val _arCoreAvailability = MutableSharedFlow<ArCoreApk.Availability>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val arCoreAvailability = _arCoreAvailability.asSharedFlow()

    private val _arFrameDataFlow = MutableSharedFlow<ARFrameData>(replay = 0, extraBufferCapacity = 2, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val arFrameDataFlow = _arFrameDataFlow.asSharedFlow()

    private var processingJob: Job? = null

    // --- Public API ---

    fun checkARCoreAvailability() {
        serviceScope.launch(Dispatchers.IO) {
            val availability = ArCoreApk.getInstance().checkAvailability(context)
            _arCoreAvailability.emit(availability)
            if (availability == ArCoreApk.Availability.UNKNOWN_ERROR) {
                Logger.log(TAG, "ARCore check returned UNKNOWN_ERROR")
                ServiceState.postError("ARCore availability check failed.")
            }
            Logger.log(TAG, "ARCore Availability: $availability")
        }
    }

    fun requestARCoreInstall(activity: android.app.Activity) {
        Logger.log(TAG, "Requesting ARCore installation.")
        try {
            // Use compareAndSet for atomic operation
            if (installRequested.compareAndSet(false, true)) {
                // Request install, requesting user confirmation (true)
                val installStatus = ArCoreApk.getInstance().requestInstall(activity, true)
                when (installStatus) {
                    ArCoreApk.InstallStatus.INSTALLED -> {
                        Logger.log(TAG, "ARCore already installed during install request.")
                        // Should not happen if checkAvailability was done first, but handle anyway
                        installRequested.set(false)
                        // Attempt to create session now that we know it's installed
                        serviceScope.launch { createSession() }
                    }
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        Logger.log(TAG, "ARCore installation requested. Waiting for Activity result.")
                        // Install flow continues in the Activity's onResume after user interaction
                    }
                    // Handle null or other statuses if necessary, though INSTALLED/INSTALL_REQUESTED are primary
                    else -> {
                        Logger.log(TAG, "ARCore installation request returned unexpected status: $installStatus")
                        installRequested.set(false) // Reset flag if request didn't proceed as expected
                    }
                }
            } else {
                Logger.log(TAG, "ARCore installation already requested.")
            }
        } catch (e: UnavailableException) {
            Logger.log(TAG, "ARCore installation request failed (UnavailableException)", e)
            ServiceState.postError("ARCore installation failed: ${e.message}")
            installRequested.set(false) // Reset install request flag on failure
        } catch (e: Exception) {
            Logger.log(TAG, "ARCore installation request failed (Exception)", e)
            ServiceState.postError("ARCore installation failed: ${e.message}")
            installRequested.set(false)
        }
    }

    fun createSession(): Boolean {
        if (arSession != null) {
            Logger.log(TAG, "Session already exists.")
            return true // Already created and presumably configured
        }

        Logger.log(TAG, "Attempting to create ARCore Session...")
        return try {
            // Reset install requested flag if we are successfully creating a session now
            installRequested.set(false)

            // Create the ARCore Session
            arSession = Session(context) // This can throw various Unavailable* exceptions
            Logger.log(TAG, "ARCore Session created successfully.")

            // Configure the session
            config = Config(arSession)

            // Light Estimation (ENVIRONMENTAL_HDR provides more realistic lighting)
            config?.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
            Logger.log(TAG, "Light Estimation Mode: ${config?.lightEstimationMode}")

            // Plane Finding (Enable if needed for anchoring or visualization)
            config?.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            Logger.log(TAG, "Plane Finding Mode: ${config?.planeFindingMode}")

            // Update Mode (LATEST_CAMERA_IMAGE is typical for AR)
            config?.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            Logger.log(TAG, "Update Mode: ${config?.updateMode}")

            // Depth API Configuration
            if (arSession?.isDepthModeSupported(Config.DepthMode.AUTOMATIC) == true) {
                config?.depthMode = Config.DepthMode.AUTOMATIC
                Logger.log(TAG, "Depth Mode set to AUTOMATIC.")
            } else {
                config?.depthMode = Config.DepthMode.DISABLED
                Logger.log(TAG, "Depth Mode DISABLED (AUTOMATIC not supported).")
                // Optionally post a non-fatal warning:
                // ServiceState.postWarning("Depth sensing not available on this device.")
            }

            // Apply the configuration to the session
            arSession?.configure(config)
            Logger.log(TAG, "ARCore Session configured successfully.")
            true // Session created and configured

        } catch (e: UnavailableArcoreNotInstalledException) {
            Logger.log(TAG, "ARCore not installed.", e)
            _arCoreAvailability.tryEmit(ArCoreApk.Availability.UNKNOWN_CHECKING) // Trigger install flow if activity is available
            ServiceState.postError("ARCore is not installed. Please install ARCore.")
            arSession = null // Ensure session is null on failure
            false
        } catch (e: UnavailableApkTooOldException) {
            Logger.log(TAG, "ARCore Apk too old.", e)
            _arCoreAvailability.tryEmit(ArCoreApk.Availability.UNKNOWN_CHECKING) // Trigger update flow
            ServiceState.postError("Your ARCore app is too old. Please update ARCore.")
            arSession = null
            false
        } catch (e: UnavailableSdkTooOldException) {
            Logger.log(TAG, "ARCore SDK too old (App issue).", e)
            _arCoreAvailability.tryEmit(ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE) // App needs update
            ServiceState.postError("This app requires a newer version of ARCore SDK.")
            arSession = null
            false
        } catch (e: UnavailableDeviceNotCompatibleException) {
            Logger.log(TAG, "ARCore device not compatible.", e)
            _arCoreAvailability.tryEmit(ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE)
            ServiceState.postError("This device is not compatible with ARCore.")
            arSession = null
            false
        } catch (e: CameraNotAvailableException) {
            Logger.log(TAG, "Camera not available for ARCore.", e)
            ServiceState.postError("Camera is in use or unavailable. Cannot start AR.")
            arSession = null
            false
        } catch (e: SecurityException) {
            Logger.log(TAG, "Camera permission not granted for ARCore.", e)
            ServiceState.postError("Camera permission required for AR. Please grant permission.")
            arSession = null
            false
        } catch (e: Exception) { // Catch any other unexpected exceptions during setup
            Logger.log(TAG, "Failed to create ARCore session (Unknown Exception)", e)
            ServiceState.postError("ARCore initialization failed: ${e.message}")
            arSession = null
            false
        }
    }

    fun resumeSession() {
        if (arSession == null) {
            Logger.log(TAG, "Cannot resume null session. Attempting creation...")
            if (!createSession()) {
                Logger.log(TAG, "Failed to create session during resume attempt.")
                return // Exit if session creation failed
            }
            // If createSession succeeded, arSession is now non-null
        }

        // Now arSession should not be null, but use safe call just in case
        arSession?.let { session ->
            try {
                Logger.log(TAG, "Resuming ARCore Session...")
                session.resume()

                // Start frame processing thread
                startFrameProcessing()

                Logger.log(TAG, "ARCore Session resumed and frame processing started.")
                ServiceState.setArTrackingState(TrackingState.PAUSED) // Initial state is paused until tracking starts
            } catch (e: CameraNotAvailableException) {
                Logger.log(TAG, "Failed to resume ARCore session, camera not available.", e)
                ServiceState.postError("Cannot resume AR: Camera unavailable.")
                closeSession() // Clean up session if resume fails critically
            } catch (e: SecurityException) {
                Logger.log(TAG, "Camera permission not granted when resuming ARCore.", e)
                ServiceState.postError("Camera permission required for AR. Cannot resume.")
                closeSession()
            } catch (e: Exception) { // Catch other potential resume errors
                Logger.log(TAG, "Failed to resume ARCore session", e)
                ServiceState.postError("Failed to resume AR: ${e.message}")
                closeSession()
            }
        } ?: Logger.log(TAG, "Session was unexpectedly null even after creation attempt in resumeSession.")
    }

    fun pauseSession() {
        if (arSession == null) {
            Logger.log(TAG, "Attempting to pause a null session.")
            return
        }
        Logger.log(TAG, "Pausing ARCore Session...")

        // Stop frame processing first
        stopFrameProcessing()

        arSession?.pause()
        Logger.log(TAG, "ARCore Session paused.")
        ServiceState.setArTrackingState(TrackingState.PAUSED)
        // Cancel any ongoing frame processing when pausing
        processingJob?.cancel()
        processingJob = null
    }

    fun closeSession() {
        if (arSession == null) {
            Logger.log(TAG, "Attempting to close a null session.")
            return
        }
        Logger.log(TAG, "Closing ARCore Session...")
        pauseSession() // Ensure it's paused and frame processing is stopped
        arSession?.close() // Release ARCore resources
        arSession = null   // Set reference to null
        config = null      // Clear config reference
        Logger.log(TAG, "ARCore Session closed.")
        ServiceState.setArTrackingState(TrackingState.PAUSED) // Ensure state reflects closed session
    }

    // Called by CameraManager or equivalent GL thread setup
    fun setCameraTextureName(textureId: Int) {
        // Ensure session exists before setting texture name
        arSession?.setCameraTextureName(textureId)
            ?: Logger.log(TAG, "Attempted to set camera texture name on a null AR session.")
    }

    // Start frame processing in a scheduled thread
    private fun startFrameProcessing() {
        if (isFrameProcessingActive.compareAndSet(false, true)) {
            Logger.log(TAG, "Starting frame processing")

            // Schedule frame updates at 30fps (approximately)
            frameScheduledFuture = frameExecutor.scheduleAtFixedRate({
                try {
                    updateFrame()
                } catch (e: Exception) {
                    Logger.log(TAG, "Error in frame processing thread", e)
                }
            }, 0, 33, TimeUnit.MILLISECONDS) // ~30 fps
        }
    }

    // Stop frame processing
    private fun stopFrameProcessing() {
        if (isFrameProcessingActive.compareAndSet(true, false)) {
            Logger.log(TAG, "Stopping frame processing")

            // Cancel the scheduled future if it exists
            frameScheduledFuture?.cancel(false)
            frameScheduledFuture = null

            // Cancel any active processing job
            frameUpdateJob?.cancel()
            frameUpdateJob = null
        }
    }

    // Update frame - called by the scheduled executor
    private fun updateFrame() {
        if (!isFrameProcessingActive.get() || arSession == null) {
            return
        }

        try {
            // Get the current frame via update()
            val frame = arSession?.update()

            if (frame != null) {
                // Process the frame on a coroutine
                if (frameUpdateJob?.isActive != true) {
                    frameUpdateJob = serviceScope.launch(Dispatchers.Default) {
                        try {
                            onFrameUpdate(frame)
                        } catch (t: Throwable) {
                            Logger.log(TAG, "Error processing frame", t)
                        }
                    }
                }
            }
        } catch (e: CameraNotAvailableException) {
            Logger.log(TAG, "Camera not available during frame update", e)
            // Handle camera not available - might need to pause session
            ServiceState.postError("Camera unavailable during AR processing.")
            pauseSession()
        } catch (e: Exception) {
            Logger.log(TAG, "Error updating ARCore frame", e)
        }
    }

    // Process a received frame
    private fun onFrameUpdate(frame: Frame) {
        // Short-circuit if frame is null or if AR processing is disabled
        if (!shouldProcessARData()) {
            // If not processing AR but session is running, still update tracking state
            if (frame.camera != null) {
                val currentTrackingState = frame.camera.trackingState
                if (ServiceState.arTrackingState.value != currentTrackingState) {
                    serviceScope.launch(Dispatchers.Main.immediate) {
                        ServiceState.setArTrackingState(currentTrackingState)
                    }
                }
            }
            processingJob?.cancel() // Ensure any lingering job is cancelled
            processingJob = null
            return
        }

        // Basic check to prevent queuing multiple processing jobs if one is already running
        if (processingJob?.isActive != true) {
            processingJob = serviceScope.launch(Dispatchers.Default) {
                try {
                    processFrame(frame) // Process the valid frame
                } catch (t: Throwable) {
                    Logger.log(TAG, "Unhandled error processing AR frame", t)
                }
            }
        }
    }

    // Runs on a background thread (Dispatchers.Default)
    private suspend fun processFrame(frame: Frame) {
        // Getting the camera - no unnecessary null checks needed as Frame.camera is non-null
        val camera = frame.camera

        val currentTrackingState = camera.trackingState
        val timestamp = frame.timestamp // Nanoseconds

        // Update tracking state in UI (on Main thread)
        // Only update if the state actually changed to avoid unnecessary UI updates
        if (ServiceState.arTrackingState.value != currentTrackingState) {
            withContext(Dispatchers.Main.immediate) {
                ServiceState.setArTrackingState(currentTrackingState)
            }
        }

        // Only extract and send data if ARCore is actively tracking
        if (currentTrackingState == TrackingState.TRACKING) {
            ServiceState.arFpsCalculator.frameReceived() // Update FPS counter

            // Extract Camera Pose (camera's transformation relative to world origin)
            val poseMatrix = FloatArray(16)
            camera.displayOrientedPose.toMatrix(poseMatrix, 0) // Use displayOrientedPose for consistency with rendering

            // --- Depth Data Extraction ---
            var depthImage: Image? = null
            var depthWidth: Int? = null
            var depthHeight: Int? = null
            var depthTimestamp: Long? = null // Depth might have its own timestamp

            // Check if depth mode is actually enabled in the current config
            if (config?.depthMode != Config.DepthMode.DISABLED) {
                try {
                    // Choose one: acquireDepthImage16Bits (recommended for precision) or acquireDepthImage (8-bit)
                    depthImage = frame.acquireDepthImage16Bits()
                    // depthImage = frame.acquireDepthImage()

                    if (depthImage != null) {
                        depthWidth = depthImage.width
                        depthHeight = depthImage.height
                        depthTimestamp = depthImage.timestamp // Get depth specific timestamp if needed
                    }
                } catch (e: NotYetAvailableException) {
                    // This is expected, especially at the start or if the device is moving quickly.
                    // Logger.log(TAG, "Depth image not yet available for frame $timestamp")
                } catch (e: SecurityException) {
                    Logger.log(TAG, "SecurityException acquiring depth image. Check permissions.", e)
                    // Handle missing permissions appropriately
                } catch (e: IllegalStateException) {
                    Logger.log(TAG,"IllegalStateException acquiring depth image. Session state issue?",e)
                } catch (e: Exception) { // Catch other potential errors
                    Logger.log(TAG, "Error acquiring depth image", e)
                } finally {
                    // CRITICAL: Always close the Image object to release underlying resources.
                    depthImage?.close()
                }
            }

            // Create ARFrameData object
            val arData = ARFrameData(
                timestamp = timestamp, // Use ARCore frame timestamp for general sync
                cameraPose = poseMatrix,
                trackingState = currentTrackingState.name, // Send state name as String
                depthWidth = depthWidth,   // Nullable Int
                depthHeight = depthHeight, // Nullable Int
                // depthTimestamp = depthTimestamp // Optionally include depth timestamp if needed
            )

            // Emit the data object to the flow
            // Use tryEmit for non-suspending emission from the background thread
            val emitted = _arFrameDataFlow.tryEmit(arData)
            if (!emitted) {
                // This indicates the downstream collector is too slow or the buffer is full.
                Logger.log(TAG, "AR data buffer overflow. Dropping frame data for timestamp $timestamp.")
            }

        } else {
            // Optional: Log when not tracking, but can be noisy
            // Logger.log(TAG, "ARCore not tracking. Current state: $currentTrackingState")
        }
    }

    // --- Closeable ---

    override fun close() {
        Logger.log(TAG, "Closing ARManager instance.")
        closeSession() // Ensure all ARCore resources are released

        // Shutdown the executor service
        try {
            frameExecutor.shutdown()
            if (!frameExecutor.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                frameExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            frameExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    // --- Helper ---
    // Determines if AR data should be processed based on global state
    fun shouldProcessARData(): Boolean {
        // Check if session exists AND the app is configured to stream AR data
        return arSession != null && ServiceState.selectedStreamMode.value == StreamMode.VIDEO_AUDIO_AR
    }
}