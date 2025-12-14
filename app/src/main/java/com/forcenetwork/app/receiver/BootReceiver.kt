package com.forcenetwork.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.forcenetwork.app.service.NetworkMonitorService
import com.forcenetwork.app.util.PreferencesManager

/**
 * BroadcastReceiver that starts the NetworkMonitorService when the device boots up,
 * if the service was previously enabled.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        context ?: return
        
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == "android.intent.action.QUICKBOOT_POWERON") {
            
            Log.d(TAG, "Boot completed, checking if service should start")
            
            val preferencesManager = PreferencesManager.getInstance(context)
            
            // Start service if it was previously enabled
            if (preferencesManager.isServiceEnabled() && preferencesManager.hasPreferredNetwork()) {
                Log.d(TAG, "Starting NetworkMonitorService on boot")
                NetworkMonitorService.start(context)
            } else {
                Log.d(TAG, "Service not enabled or no preferred network configured")
            }
        }
    }
}
