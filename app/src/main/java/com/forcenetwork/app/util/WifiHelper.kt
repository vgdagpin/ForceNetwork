package com.forcenetwork.app.util

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.util.Log

/**
 * Helper class for WiFi operations including scanning and connecting to networks.
 */
class WifiHelper(private val context: Context) {

    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val connectivityManager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    companion object {
        private const val TAG = "WifiHelper"
    }

    /**
     * Check if WiFi is enabled
     */
    fun isWifiEnabled(): Boolean {
        return wifiManager.isWifiEnabled
    }

    /**
     * Enable WiFi
     */
    @SuppressLint("MissingPermission")
    fun enableWifi(): Boolean {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            wifiManager.setWifiEnabled(true)
        } else {
            // On Android 10+, apps cannot directly enable WiFi
            // User needs to enable it manually
            false
        }
    }

    /**
     * Get the currently connected WiFi SSID
     */
    @SuppressLint("MissingPermission")
    fun getCurrentSsid(): String? {
        val wifiInfo = wifiManager.connectionInfo
        var ssid = wifiInfo?.ssid
        
        // Remove quotes from SSID if present
        if (ssid != null && ssid.startsWith("\"") && ssid.endsWith("\"")) {
            ssid = ssid.substring(1, ssid.length - 1)
        }
        
        // Return null if SSID is unknown or empty
        if (ssid == "<unknown ssid>" || ssid.isNullOrEmpty()) {
            return null
        }
        
        return ssid
    }

    /**
     * Get list of available WiFi networks
     */
    @SuppressLint("MissingPermission")
    fun getAvailableNetworks(): List<WifiNetwork> {
        val scanResults = wifiManager.scanResults
        val networks = mutableListOf<WifiNetwork>()
        val seenSsids = mutableSetOf<String>()

        for (result in scanResults) {
            val ssid = result.SSID
            if (ssid.isNotEmpty() && ssid !in seenSsids) {
                seenSsids.add(ssid)
                networks.add(
                    WifiNetwork(
                        ssid = ssid,
                        bssid = result.BSSID,
                        signalStrength = WifiManager.calculateSignalLevel(result.level, 5),
                        isSecure = result.capabilities.contains("WPA") ||
                                result.capabilities.contains("WEP") ||
                                result.capabilities.contains("PSK"),
                        capabilities = result.capabilities
                    )
                )
            }
        }

        // Sort by signal strength (highest first)
        return networks.sortedByDescending { it.signalStrength }
    }

    /**
     * Start a WiFi scan
     */
    @SuppressLint("MissingPermission")
    fun startScan(): Boolean {
        return wifiManager.startScan()
    }

    /**
     * Check if the preferred network is in range
     */
    fun isNetworkInRange(ssid: String): Boolean {
        return getAvailableNetworks().any { it.ssid == ssid }
    }

    /**
     * Check if currently connected to the specified network
     */
    fun isConnectedTo(ssid: String): Boolean {
        return getCurrentSsid() == ssid
    }

    /**
     * Connect to a WiFi network
     */
    @SuppressLint("MissingPermission")
    fun connectToNetwork(ssid: String, password: String?, callback: ConnectionCallback) {
        Log.d(TAG, "Attempting to connect to: $ssid")
        
        // Check if already connected to this network
        if (isConnectedTo(ssid)) {
            Log.d(TAG, "Already connected to $ssid")
            callback.onSuccess(ssid)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectUsingNetworkRequest(ssid, password, callback)
        } else {
            connectUsingWifiConfiguration(ssid, password, callback)
        }
    }

    /**
     * Connect using NetworkRequest (Android 10+)
     */
    @SuppressLint("MissingPermission")
    private fun connectUsingNetworkRequest(ssid: String, password: String?, callback: ConnectionCallback) {
        // First, disconnect any existing network callback
        disconnectNetworkCallback()

        val specifierBuilder = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)

        if (!password.isNullOrEmpty()) {
            specifierBuilder.setWpa2Passphrase(password)
        }

        val networkSpecifier = specifierBuilder.build()

        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(networkSpecifier)
            .build()

        var hasCalledBack = false

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                if (!hasCalledBack) {
                    hasCalledBack = true
                    Log.d(TAG, "Network available: $ssid")
                    connectivityManager.bindProcessToNetwork(network)
                    callback.onSuccess(ssid)
                }
            }

            override fun onUnavailable() {
                super.onUnavailable()
                if (!hasCalledBack) {
                    hasCalledBack = true
                    Log.d(TAG, "Network unavailable: $ssid")
                    callback.onFailure("Network unavailable")
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                Log.d(TAG, "Network lost: $ssid")
                // Only call onDisconnected if we successfully connected before
                // and check if we're actually disconnected from this network
                if (hasCalledBack && !isConnectedTo(ssid)) {
                    callback.onDisconnected()
                }
            }
        }

        try {
            connectivityManager.requestNetwork(networkRequest, networkCallback!!)
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting network", e)
            callback.onFailure(e.message ?: "Unknown error")
        }
    }

    /**
     * Connect using WifiConfiguration (Android 9 and below)
     */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun connectUsingWifiConfiguration(ssid: String, password: String?, callback: ConnectionCallback) {
        try {
            val config = WifiConfiguration().apply {
                SSID = "\"$ssid\""
                if (!password.isNullOrEmpty()) {
                    preSharedKey = "\"$password\""
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                } else {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                }
            }

            // Check if network already exists
            val existingConfig = wifiManager.configuredNetworks?.find { 
                it.SSID == "\"$ssid\"" 
            }

            val networkId = if (existingConfig != null) {
                existingConfig.networkId
            } else {
                wifiManager.addNetwork(config)
            }

            if (networkId == -1) {
                callback.onFailure("Failed to add network configuration")
                return
            }

            wifiManager.disconnect()
            val success = wifiManager.enableNetwork(networkId, true)
            wifiManager.reconnect()

            if (success) {
                callback.onSuccess(ssid)
            } else {
                callback.onFailure("Failed to enable network")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to network", e)
            callback.onFailure(e.message ?: "Unknown error")
        }
    }

    /**
     * Add network suggestion (Android 10+)
     * This allows the system to automatically connect to the network
     */
    @SuppressLint("MissingPermission")
    fun addNetworkSuggestion(ssid: String, password: String?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false
        }

        val suggestionBuilder = WifiNetworkSuggestion.Builder()
            .setSsid(ssid)
            .setIsAppInteractionRequired(true)

        if (!password.isNullOrEmpty()) {
            suggestionBuilder.setWpa2Passphrase(password)
        }

        val suggestion = suggestionBuilder.build()
        val suggestions = listOf(suggestion)

        // Remove existing suggestions first
        wifiManager.removeNetworkSuggestions(suggestions)

        val status = wifiManager.addNetworkSuggestions(suggestions)
        return status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS
    }

    /**
     * Disconnect network callback
     */
    fun disconnectNetworkCallback() {
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering network callback", e)
            }
            networkCallback = null
        }
    }

    /**
     * Get WiFi signal strength for current connection
     */
    @SuppressLint("MissingPermission")
    fun getCurrentSignalStrength(): Int {
        val wifiInfo = wifiManager.connectionInfo
        return WifiManager.calculateSignalLevel(wifiInfo?.rssi ?: -100, 5)
    }

    /**
     * Callback interface for connection results
     */
    interface ConnectionCallback {
        fun onSuccess(ssid: String)
        fun onFailure(reason: String)
        fun onDisconnected()
    }

    /**
     * Data class representing a WiFi network
     */
    data class WifiNetwork(
        val ssid: String,
        val bssid: String,
        val signalStrength: Int, // 0-4
        val isSecure: Boolean,
        val capabilities: String
    )
}
