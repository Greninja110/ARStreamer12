package com.example.myapplication12.service

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FrameRateCalculator(private val updateIntervalMs: Long = 1000) {

    private var frameCount = 0
    private var lastTimestamp = 0L

    private val _fpsStateFlow = MutableStateFlow(0)
    val fpsStateFlow = _fpsStateFlow.asStateFlow()

    fun frameReceived() {
        frameCount++
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastTimestamp

        if (lastTimestamp == 0L) {
            // First frame
            lastTimestamp = now
            return
        }

        if (elapsed >= updateIntervalMs) {
            val fps = (frameCount * 1000.0 / elapsed).toInt()
            _fpsStateFlow.value = fps
            frameCount = 0
            lastTimestamp = now
        }
    }

    fun reset() {
        frameCount = 0
        lastTimestamp = 0L
        _fpsStateFlow.value = 0
    }
}