package com.example.myapplication12.model

// <<< Make sure this package matches your `namespace` in build.gradle.kts and AndroidManifest.xml

enum class StreamMode(val description: String) {
    IMAGE_ONLY("Image Only (Single Capture)"),
    AUDIO_ONLY("Audio Only"),
    VIDEO_ONLY("Video Only"),
    VIDEO_AUDIO_AR("Video + Audio + AR Data");

    companion object {
        fun default(): StreamMode = VIDEO_AUDIO_AR // Default selection
    }
}