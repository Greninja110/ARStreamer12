package com.example.myapplication12.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {
    private const val TAG = "NetworkUtils"

    fun getLocalIpAddress(context: Context): String? {
        try {
            // Prioritize Wi-Fi interface
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager?
            wifiManager?.connectionInfo?.ipAddress?.let { ipInt ->
                if (ipInt != 0) {
                    val ipString = android.text.format.Formatter.formatIpAddress(ipInt)
                    // Double check it's not a loopback/invalid address
                    if (ipString != "0.0.0.0" && !ipString.startsWith("127.")) {
                        Log.d(TAG, "IP from WifiManager: $ipString")
                        return ipString
                    }
                }
            }

            // Fallback to iterating network interfaces (more reliable on some devices/Android versions)
            val interfaces: List<NetworkInterface> = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ipAddress = addr.hostAddress
                        // Check if this interface is actively connected (e.g., Wi-Fi or Ethernet)
                        if (isInterfaceConnected(context, intf.name)) {
                            Log.d(TAG, "IP from NetworkInterface (${intf.displayName}): $ipAddress")
                            return ipAddress
                        } else {
                            Log.d(TAG, "Skipping inactive interface (${intf.displayName}): $ipAddress")
                        }
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "Error getting IP address", ex)
        }
        Log.e(TAG, "Could not find a local IP address.")
        return null
    }

    private fun isInterfaceConnected(context: Context, interfaceName: String?): Boolean {
        if (interfaceName == null) return false
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networks = connectivityManager.allNetworks
        for (network in networks) {
            val linkProperties = connectivityManager.getLinkProperties(network)
            if (linkProperties?.interfaceName == interfaceName) {
                val networkCapabilities = connectivityManager.getNetworkCapabilities(network)
                return networkCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            }
        }
        return false
    }
}