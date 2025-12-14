package com.forcenetwork.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import com.forcenetwork.app.service.NetworkMonitorService
import com.forcenetwork.app.util.PreferencesManager
import com.forcenetwork.app.util.WifiHelper

/**
 * BroadcastReceiver that monitors network connectivity changes.
 * When a network change is detected, it triggers a check to connect
 * to the preferred network if available.
 */
class NetworkChangeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NetworkChangeReceiver"
    }

    @Suppress("DEPRECATION")
    override fun onReceive(context: Context?, intent: Intent?) {
        context ?: return
        intent ?: return

        val preferencesManager = PreferencesManager.getInstance(context)
        
        // Only process if monitoring is enabled
        if (!preferencesManager.isServiceEnabled()) {
            Log.d(TAG, "Service not enabled, ignoring network change")
            return
        }

        // Only process if monitor changes is enabled
        if (!preferencesManager.isMonitorChangesEnabled()) {
            Log.d(TAG, "Monitor changes disabled, ignoring network change")
            return
        }

        val preferredSsid = preferencesManager.getPreferredNetworkSsid()
        if (preferredSsid.isNullOrEmpty()) {
            Log.d(TAG, "No preferred network configured")
            return
        }

        when (intent.action) {
            WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                val wifiState = intent.getIntExtra(
                    WifiManager.EXTRA_WIFI_STATE,
                    WifiManager.WIFI_STATE_UNKNOWN
                )
                handleWifiStateChange(context, wifiState, preferredSsid)
            }

            WifiManager.NETWORK_STATE_CHANGED_ACTION,
            ConnectivityManager.CONNECTIVITY_ACTION -> {
                handleNetworkChange(context, preferredSsid)
            }
        }
    }

    private fun handleWifiStateChange(context: Context, wifiState: Int, preferredSsid: String) {
        when (wifiState) {
            WifiManager.WIFI_STATE_ENABLED -> {
                Log.d(TAG, "WiFi enabled, checking for preferred network")
                // Delay a bit to let WiFi fully initialize
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    NetworkMonitorService.checkNetwork(context)
                }, 2000)
            }
            WifiManager.WIFI_STATE_DISABLED -> {
                Log.d(TAG, "WiFi disabled")
            }
        }
    }

    private fun handleNetworkChange(context: Context, preferredSsid: String) {
        val wifiHelper = WifiHelper(context)
        val currentSsid = wifiHelper.getCurrentSsid()

        Log.d(TAG, "Network changed. Current: $currentSsid, Preferred: $preferredSsid")

        // If we're connected but not to the preferred network
        if (currentSsid != null && currentSsid != preferredSsid) {
            Log.d(TAG, "Connected to different network, checking if preferred is available")
            
            // Check if preferred network is available
            if (wifiHelper.isNetworkInRange(preferredSsid)) {
                Log.d(TAG, "Preferred network is in range, triggering reconnection")
                // Use the service to handle the connection
                NetworkMonitorService.checkNetwork(context)
            }
        } else if (currentSsid == null) {
            // Not connected, try to connect to preferred network
            Log.d(TAG, "Not connected, checking for preferred network")
            NetworkMonitorService.checkNetwork(context)
        }
    }

    /**
     * Check if device has active WiFi connection
     */
    private fun isWifiConnected(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
