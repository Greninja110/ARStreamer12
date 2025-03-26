package com.example.myapplication12.model

// Placeholder for quality settings - Define specific resolutions, bitrates etc. later
enum class StreamQuality {
    LOW, MEDIUM, HIGH;

    companion object {
        fun default(): StreamQuality = MEDIUM
    }
    // Or use a data class:
    // data class StreamQuality(val resolution: Size, val videoBitrate: Int, val audioBitrate: Int)
}

// Example AR Data structure to be serialized
data class ARFrameData(
    val timestamp: Long, // System.nanoTime() or Frame timestamp
    val cameraPose: FloatArray?, // 16 floats, column-major matrix
    val trackingState: String, // e.g., "TRACKING", "PAUSED", "STOPPED"
    // Depth data - sending raw depth can be heavy. Send metadata or downsampled.
    val depthWidth: Int?,
    val depthHeight: Int?,
    // val depthConfidenceWidth: Int?,
    // val depthConfidenceHeight: Int?,
    // Add intrinsics if needed by the client
)