package com.example.myapplication12.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

// Basic configuration holder, can be expanded
@Parcelize
data class StreamConfig(
    val mode: StreamMode = StreamMode.default(),
    // Add quality settings later (resolution, bitrate, etc.)
    // val quality: StreamQuality = StreamQuality.default()
) : Parcelable