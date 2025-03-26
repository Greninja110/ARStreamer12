package com.example.myapplication12.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.Size
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.ArCoreApk
import com.example.myapplication12.MainActivity // Import your MainActivity
import com.example.myapplication12.R // Import R for resources
import com.example.myapplication12.ar.ARManager
import com.example.myapplication12.camera.CameraManager
import com.example.myapplication12.model.StreamConfig
import com.example.myapplication12.model.StreamMode
import com.example.myapplication12.network.NetworkDiscoveryManager
import com.example.myapplication12.network.WebServerManager
import com.example.myapplication12.streaming.StreamingSessionManager
import com.example.myapplication12.utils.NetworkUtils
import com.example.myapplication12.utils.getParcelableCompatExtra
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.net.InetAddress

class StreamingService : LifecycleService() { // Use LifecycleService for lifecycleScope

    private val TAG = "StreamingService"
    private val NOTIFICATION_ID = 101
    private val NOTIFICATION_CHANNEL_ID = "ARStreamerChannel"

    private val binder = LocalBinder()

    // Managers - Lazily initialized or created in onCreate
    private var cameraManager: CameraManager? = null
    private var arManager: ARManager? = null
    private var webServerManager: WebServerManager? = null
    private var networkDiscoveryManager: NetworkDiscoveryManager? = null
    private var streamingSessionManager: StreamingSessionManager? = null

    private var serviceJob: Job? = null
    private var streamConfig: StreamConfig? = null

    // --- Service Lifecycle ---

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        createNotificationChannel()
        // Initialization of managers requiring context can happen here
        networkDiscoveryManager = NetworkDiscoveryManager(this)

        // ARManager needs scope, provide service's lifecycleScope
        arManager = ARManager(this, lifecycleScope)

        // CameraManager needs context, lifecycle owner (service is one), ARManager, scope
        cameraManager = CameraManager(this, this, arManager, lifecycleScope)

        // WebServerManager needs callbacks for signaling
        webServerManager = WebServerManager(
            serviceScope = lifecycleScope,
            onSdpOffer = { session, sdp ->
                Log.d(TAG, "SDP Offer received by WebServer, passing to StreamingSession.")
                streamingSessionManager?.onSdpOfferReceived(sdp)
            },
            onIceCandidate = { session, sdpMid, sdpMLineIndex, candidate ->
                Log.d(TAG, "ICE Candidate received by WebServer, passing to StreamingSession.")
                streamingSessionManager?.onIceCandidateReceived(sdpMid, sdpMLineIndex, candidate)
            }
        )

        // Observe ARCore availability changes from ARManager
        lifecycleScope.launch {
            arManager?.arCoreAvailability?.collect { availability ->
                Log.i(TAG, "ARCore Availability updated: $availability")
                if (availability == ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE) {
                    ServiceState.postError("ARCore not supported on this device.")
                    // Decide how to handle this - stop AR? Degrade gracefully?
                } else if (availability == ArCoreApk.Availability.UNKNOWN_CHECKING || availability == ArCoreApk.Availability.UNKNOWN_TIMED_OUT) {
                    // Still checking or timed out
                } else if (availability.isTransient) {
                    // Needs install/update - MainActivity should handle the request flow
                    ServiceState.postError("ARCore requires installation or update.")
                } else if (availability.isSupported) {
                    // Supported and installed/updated
                    Log.i(TAG, "ARCore is supported and ready.")
                    // Try creating session if needed and not already done
                    // if (arManager?.arSession == null && streamConfig?.mode == StreamMode.VIDEO_AUDIO_AR) {
                    //      arManager?.createSession()
                    // }
                }
            }
        }

        // Observe image frames from CameraManager
        lifecycleScope.launch {
            cameraManager?.imageProxyFlow?.collectLatest { imageProxy ->
                // Pass frame to StreamingSessionManager if it exists and needs it
                //Log.v(TAG, "ImageProxy received in Service, passing to session.") // Very verbose
                streamingSessionManager?.onFrameAvailable(imageProxy)
                    ?: imageProxy.close() // Ensure closed if no consumer
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId) // Important for LifecycleService
        Log.i(TAG, "onStartCommand - Action: ${intent?.action}")

        when (intent?.action) {
            Actions.START.name -> {
                val config = intent.getParcelableCompatExtra<StreamConfig>(Constants.EXTRA_STREAM_CONFIG)
                if (config == null) {
                    Log.e(TAG, "Start command received without valid StreamConfig.")
                    stopSelf() // Stop if configuration is missing
                    return START_NOT_STICKY
                }
                streamConfig = config
                ServiceState.setStreamMode(config.mode) // Update global state
                Log.i(TAG, "Starting service with mode: ${config.mode}")
                startStreamingComponents(config)
                startForeground(NOTIFICATION_ID, createNotification("Starting..."))
            }
            Actions.STOP.name -> {
                Log.i(TAG, "Stop action received.")
                stopStreamingComponents()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf() // Stop the service itself
            }
            else -> {
                Log.w(TAG, "Received unknown or null action.")
                // Decide if service should continue or stop if started unexpectedly
                if (ServiceState.isStreaming.value) {
                    Log.d(TAG, "Service already running, ignoring unknown action.")
                    // Ensure foreground notification is present if running
                    startForeground(NOTIFICATION_ID, createNotification("Streaming..."))
                } else {
                    Log.w(TAG, "Service not streaming, stopping due to unknown action.")
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }

        // If the service is killed, restart it but don't redeliver the last intent.
        return START_STICKY
    }

    private fun startStreamingComponents(config: StreamConfig) {
        // Cancel any previous job
        serviceJob?.cancel()
        ServiceState.resetAll() // Reset state before starting
        ServiceState.setStreamMode(config.mode)

        // Launch main service logic in its own coroutine
        serviceJob = lifecycleScope.launch(Dispatchers.IO) { // Use IO for network/file ops
            var componentsStarted = false
            try {
                // 1. Get Network IP
                val ipAddress = NetworkUtils.getLocalIpAddress(this@StreamingService)
                if (ipAddress == null || !isActive) {
                    Log.e(TAG, "Failed to get local IP address.")
                    ServiceState.postError("Network unavailable. Check Wi-Fi.")
                    return@launch // Exit coroutine
                }
                val hostAddress = InetAddress.getByName(ipAddress) // Ensure valid InetAddress

                // Update state with IP early
                withContext(Dispatchers.Main.immediate) { ServiceState.setServerAddress(ipAddress) }


                // 2. Initialize StreamingSessionManager (needs mode)
                Log.d(TAG, "Initializing StreamingSessionManager...")
                streamingSessionManager = StreamingSessionManager(
                    context = this@StreamingService,
                    webServerManager = webServerManager!!, // Assume not null after onCreate
                    arManager = if (config.mode == StreamMode.VIDEO_AUDIO_AR) arManager else null,
                    serviceScope = this, // Pass the serviceJob's scope or lifecycleScope
                    initialStreamMode = config.mode
                )
                Log.i(TAG, "StreamingSessionManager initialized.")


                // 3. Start Camera (if video needed)
                if (config.mode == StreamMode.VIDEO_ONLY || config.mode == StreamMode.VIDEO_AUDIO_AR) {
                    Log.d(TAG, "Starting Camera...")
                    // Needs to run on main thread or designated executor used by CameraX internally
                    withContext(Dispatchers.Main) {
                        cameraManager?.startCamera(targetResolution = Size(640, 480)) // TODO: Make resolution configurable
                    }
                    Log.i(TAG, "Camera start requested.")
                    // Wait briefly for camera to potentially initialize? Or handle async better.
                    // delay(500) // Avoid if possible
                }

                // 4. Start ARCore (if needed)
                if (config.mode == StreamMode.VIDEO_AUDIO_AR) {
                    Log.d(TAG, "Starting ARCore...")
                    arManager?.checkARCoreAvailability() // Check first
                    // Wait for availability check? Should be fast.
                    // If ARCore needs install/update, MainActivity handles it. Assume supported for now.
                    if (arManager?.createSession() == true) {
                        withContext(Dispatchers.Main) { // Resume needs main thread if interacting with GL potentially
                            arManager?.resumeSession()
                        }
                        Log.i(TAG, "ARCore session creation/resume requested.")
                    } else {
                        Log.e(TAG, "ARCore session could not be created/resumed.")
                        // Error state should be set by ARManager
                    }
                }

                // 5. Start Web Server (Ktor)
                Log.d(TAG, "Starting Web Server...")
                webServerManager?.startServer(ipAddress)
                Log.i(TAG, "Web Server start requested.")


                // 6. Start Network Discovery (NSD)
                Log.d(TAG, "Starting Network Discovery...")
                networkDiscoveryManager?.registerService(webServerManager?.PORT ?: 8080, hostAddress)
                Log.i(TAG, "Network Discovery registration requested.")

                componentsStarted = true
                withContext(Dispatchers.Main.immediate) {
                    // ServiceState.setStreaming(true) // Already set in createPeerConnection maybe? Set definitively here.
                    updateNotification("Streaming on $ipAddress:${webServerManager?.PORT ?: 8080}")
                }
                Log.i(TAG, "*** Streaming Service Components Started ***")

                // Keep the job running while components are active
                // WebServerManager's job and collect flows keep this coroutine alive
                // Or use a channel/flow to wait for a stop signal
                awaitCancellation() // Keeps the coroutine running until cancelled

            } catch (e: CancellationException) {
                Log.i(TAG, "Service job cancelled.")
            } catch (e: Exception) {
                Log.e(TAG, "Error during service startup or operation", e)
                withContext(Dispatchers.Main.immediate) { ServiceState.postError("Service Error: ${e.message}") }
            } finally {
                Log.i(TAG, "Service job finishing. Cleaning up components...")
                withContext(NonCancellable) { // Ensure cleanup runs even if cancelled
                    stopStreamingComponents()
                    if (componentsStarted) {
                        updateNotification("Service stopped.")
                    }
                }
            }
        }
    }

    private fun stopStreamingComponents() {
        Log.i(TAG, "Stopping all streaming components...")

        serviceJob?.cancel() // Cancel the main service job first
        serviceJob = null

        // Stop in reverse order of start, handling potential nulls
        networkDiscoveryManager?.unregisterService()
        Log.d(TAG, "NSD unregistered.")

        webServerManager?.stopServer()
        Log.d(TAG, "Web Server stopped.")

        if (streamConfig?.mode == StreamMode.VIDEO_AUDIO_AR) {
            // Needs main thread? Check ARManager implementation details
            // GlobalScope.launch(Dispatchers.Main) { arManager?.pauseSession() } // Avoid GlobalScope
            lifecycleScope.launch(Dispatchers.Main.immediate) { // Use immediate for faster cleanup attempt
                try {
                    arManager?.pauseSession() // Pause first
                    arManager?.closeSession() // Then close fully
                    Log.d(TAG, "ARCore session stopped.")
                } catch (e: Exception) {
                    Log.e(TAG,"Error stopping ARCore", e)
                }
            }
        }

        if (streamConfig?.mode == StreamMode.VIDEO_ONLY || streamConfig?.mode == StreamMode.VIDEO_AUDIO_AR) {
            // Needs main thread? Check CameraManager implementation
            lifecycleScope.launch(Dispatchers.Main.immediate) {
                try {
                    cameraManager?.stopCamera()
                    Log.d(TAG, "Camera stopped.")
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping Camera", e)
                }
            }
        }

        // Stop audio capture if applicable (handled within StreamingSessionManager.close)

        streamingSessionManager?.close() // This handles WebRTC cleanup
        streamingSessionManager = null
        Log.d(TAG, "StreamingSessionManager closed.")

        // Reset state
        ServiceState.resetAll()

        Log.i(TAG, "*** Streaming Service Components Stopped ***")
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        // Ensure everything is cleaned up
        serviceJob?.cancel() // Cancel job if still running
        stopStreamingComponents() // Call cleanup method

        // Release managers created in onCreate
        cameraManager?.close() // CameraManager manages its own executor
        arManager?.close()
        webServerManager?.close()
        networkDiscoveryManager?.close()

        ServiceState.resetAll() // Final state reset

        super.onDestroy()
        Log.i(TAG, "Service destroyed.")
    }

    // --- Binder ---

    inner class LocalBinder : Binder() {
        // Return this instance of LocalService so clients can call public methods
        fun getService(): StreamingService = this@StreamingService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent) // Important for LifecycleService
        Log.i(TAG, "onBind")
        return binder
        // Clients will bind, but primary control is via startService with Actions
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "onUnbind")
        return super.onUnbind(intent)
    }

    // --- Notifications ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "AR Streamer Service Channel",
                NotificationManager.IMPORTANCE_LOW // Low importance for foreground service notification
            ).apply {
                description = "Notification channel for AR Streamer foreground service"
                // Configure channel settings if needed (sound, vibration etc.) - keep it silent
                setSound(null, null)
                enableVibration(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created.")
        }
    }

    private fun createNotification(contentText: String): Notification {
        Log.d(TAG, "Creating notification: $contentText")
        // Intent to open MainActivity when notification is tapped
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        // Intent for the Stop action
        val stopSelfIntent = Intent(this, StreamingService::class.java).apply {
            action = Actions.STOP.name
        }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopSelfIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("AR Streamer Active")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // CHANGE TO A REAL ICON (e.g., streaming icon)
            .setContentIntent(pendingIntent) // Tap action
            .addAction(R.drawable.ic_stop, "Stop", stopPendingIntent) // Stop action button (CHANGE ICON)
            .setOngoing(true) // Makes the notification non-dismissible
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE) // Show immediately
            .setSilent(true) // No sound
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            // Check if service is actually running in foreground before updating
            // This requires querying service state, which is complex. Assume it is if this method is called.
            manager.notify(NOTIFICATION_ID, notification)
            Log.d(TAG, "Notification updated: $contentText")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating notification", e)
            // This can happen if the service is stopping or in a weird state
        }
    }


    // --- Public Methods for potential Binder interaction (Optional) ---
    fun getCurrentState(): ServiceState = ServiceState // Expose state object
    fun getCurrentConfig(): StreamConfig? = streamConfig

    // --- Constants and Actions ---
    companion object {
        object Constants {
            const val EXTRA_STREAM_CONFIG = "com.yourapp.arstreamer.STREAM_CONFIG"
        }
        enum class Actions { START, STOP }

        // Helper to create a start intent
        fun createStartIntent(context: Context, config: StreamConfig): Intent {
            return Intent(context, StreamingService::class.java).apply {
                action = Actions.START.name
                putExtra(Constants.EXTRA_STREAM_CONFIG, config)
            }
        }

        // Helper to create a stop intent
        fun createStopIntent(context: Context): Intent {
            return Intent(context, StreamingService::class.java).apply {
                action = Actions.STOP.name
            }
        }
    }
}