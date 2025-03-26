package com.example.myapplication12.utils

import android.content.Intent
import android.os.Build
import android.os.Parcelable

// Helper to deal with deprecation of getParcelableExtra
inline fun <reified T : Parcelable> Intent.getParcelableCompatExtra(key: String): T? = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> getParcelableExtra(key, T::class.java)
    else -> @Suppress("DEPRECATION") getParcelableExtra(key) as? T
}