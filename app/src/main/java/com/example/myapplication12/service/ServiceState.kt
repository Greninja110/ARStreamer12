package com.example.myapplication12.service

import com.google.ar.core.TrackingState
import com.example.myapplication12.model.StreamMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

// Singleton object to hold and expose the service's state reactively
object ServiceState {

    private const val DEFAULT_PORT = 8080

    // --- Configuration ---
    private val _selectedStreamMode = MutableStateFlow<StreamMode?>(null)
    val selectedStreamMode = _selectedStreamMode.asStateFlow()

    // --- Status ---
    private val _isStreaming = MutableStateFlow(false)
    val isStreaming = _isStreaming.asStateFlow()

    private val _serverAddress = MutableStateFlow<String?>(null) // e.g., "192.168.1.5:8080"
    val serverAddress = _serverAddress.asStateFlow()

    private val _arTrackingState = MutableStateFlow(TrackingState.PAUSED) // ARCore Tracking State
    val arTrackingState = _arTrackingState.asStateFlow()

    private val _connectedClients = MutableStateFlow(0) // Number of connected WebRTC clients
    val connectedClients = _connectedClients.asStateFlow()

    private val _errorMessages = MutableStateFlow<String?>(null) // For showing errors in UI
    val errorMessages = _errorMessages.asStateFlow()

    // --- Statistics ---
    val videoFpsCalculator = FrameRateCalculator()
    val arFpsCalculator = FrameRateCalculator()

    // --- Internal Methods (called by StreamingService) ---

    internal fun setStreamMode(mode: StreamMode?) {
        _selectedStreamMode.value = mode
    }

    internal fun setStreaming(streaming: Boolean) {
        _isStreaming.value = streaming
    }

    internal fun setServerAddress(ip: String?, port: Int = DEFAULT_PORT) {
        _serverAddress.value = ip?.let { "$it:$port" }
    }

    internal fun setArTrackingState(state: TrackingState) {
        _arTrackingState.value = state
        if (state != TrackingState.TRACKING) {
            arFpsCalculator.reset() // Reset AR FPS if not tracking
        }
    }

    internal fun incrementConnectedClients() {
        _connectedClients.value += 1
    }

    internal fun decrementConnectedClients() {
        _connectedClients.value = (_connectedClients.value - 1).coerceAtLeast(0)
    }

    internal fun postError(message: String?) {
        _errorMessages.value = message
    }

    internal fun clearError() {
        _errorMessages.value = null
    }

    internal fun resetAll() {
        setStreamMode(null)
        setStreaming(false)
        setServerAddress(null)
        setArTrackingState(TrackingState.PAUSED)
        _connectedClients.value = 0
        clearError()
        videoFpsCalculator.reset()
        arFpsCalculator.reset()
    }
}