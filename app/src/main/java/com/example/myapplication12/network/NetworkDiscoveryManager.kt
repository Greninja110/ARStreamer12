package com.example.myapplication12.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.example.myapplication12.service.ServiceState
import java.io.Closeable
import java.net.InetAddress

class NetworkDiscoveryManager(private val context: Context) : Closeable {

    private val TAG = "NetworkDiscovery"
    private val SERVICE_TYPE = "_arstreamer._tcp." // Standard mDNS service type format
    private val SERVICE_NAME = "AR Streamer Service" // User-friendly name

    private val nsdManager: NsdManager? = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var registeredServiceName: String? = null

    fun registerService(port: Int, hostAddress: InetAddress) {
        if (nsdManager == null) {
            Log.e(TAG, "NSD Manager not available.")
            ServiceState.postError("Network Discovery (NSD) not supported on this device.")
            return
        }
        if (registrationListener != null) {
            Log.w(TAG, "Service already registered or registration in progress.")
            return // Avoid multiple registrations
        }

        Log.i(TAG, "Attempting to register NSD service '$SERVICE_NAME' on port $port")

        // Create the NsdServiceInfo object
        val serviceInfo = NsdServiceInfo().apply {
            // Service name should be unique on the network. Using device model might help.
            // Avoid changing it frequently if clients rely on the name.
            serviceName = SERVICE_NAME // Or use a more unique name like "ARStreamer-${Build.MODEL}"
            serviceType = SERVICE_TYPE
            setPort(port)
            // Optionally add attributes (key-value pairs)
            // setAttribute("version", "1.0")
            // setAttribute("path", "/") // e.g., path for web interface

            // Set host explicitly (required on some Android versions/devices)
            try {
                setHost(hostAddress)
                Log.i(TAG, "NSD Host set to: ${hostAddress.hostAddress}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set NSD host address", e)
                // Fallback or error handling
            }
        }

        // Define the registration listener
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                // Save the service name after registration succeeds
                registeredServiceName = serviceInfo.serviceName
                Log.i(TAG, "NSD Service registered successfully: ${serviceInfo.serviceName}")
                // Update UI or state if needed
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD Service registration failed. Error code: $errorCode, Service: ${serviceInfo.serviceName}")
                // Handle errors (e.g., NSD_FAILURE_ALREADY_ACTIVE, NSD_FAILURE_INTERNAL_ERROR)
                ServiceState.postError("Network Discovery failed (Error $errorCode)")
                registrationListener = null // Allow retry
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "NSD Service unregistered: ${serviceInfo.serviceName}")
                registeredServiceName = null
                registrationListener = null // Reset listener after unregistration
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD Service unregistration failed. Error code: $errorCode, Service: ${serviceInfo.serviceName}")
                // This might indicate a deeper issue, but usually can be ignored if trying to stop
                registrationListener = null // Reset listener anyway
            }
        }

        // Register the service
        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
            Log.d(TAG, "NSD registration request submitted.")
        } catch (e: Exception) {
            Log.e(TAG, "Exception during nsdManager.registerService call", e)
            ServiceState.postError("Failed to initiate Network Discovery.")
            registrationListener = null // Reset if call itself failed
        }
    }

    fun unregisterService() {
        if (nsdManager == null) {
            Log.w(TAG, "NSD Manager not available, cannot unregister.")
            return
        }
        if (registrationListener != null) {
            Log.i(TAG, "Unregistering NSD service...")
            try {
                nsdManager.unregisterService(registrationListener)
                // Listener's onServiceUnregistered or onUnregistrationFailed will be called
            } catch (e: Exception) {
                // This can happen if the listener is already invalid or unregistered
                Log.e(TAG, "Exception during nsdManager.unregisterService call: ${e.message}")
                // Force reset state as registration is likely gone
                registeredServiceName = null
                registrationListener = null
            }
        } else {
            Log.w(TAG, "No active NSD registration listener to unregister.")
        }
    }

    override fun close() {
        Log.d(TAG, "Closing NetworkDiscoveryManager.")
        unregisterService()
        // No other resources managed directly by this class
    }
}