package com.example.myapplication12.streaming

import android.util.Log
import com.google.gson.Gson
import com.example.myapplication12.model.ARFrameData

// Simple JSON based serializer for AR data
object DataSerializer {
    private val TAG = "DataSerializer"
    private val gson = Gson()

    fun serializeARFrameData(data: ARFrameData): String? {
        return try {
            gson.toJson(data)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize ARFrameData", e)
            null
        }
    }

    // Optional: Add deserialization if needed (likely not needed on Android side)
    fun deserializeARFrameData(json: String): ARFrameData? {
        return try {
            gson.fromJson(json, ARFrameData::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize ARFrameData", e)
            null
        }
    }

    // Add methods for other data types if necessary
}