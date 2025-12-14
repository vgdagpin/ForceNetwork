package com.forcenetwork.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.forcenetwork.app.MainActivity
import com.forcenetwork.app.R
import com.forcenetwork.app.util.PreferencesManager
import com.forcenetwork.app.util.WifiHelper

/**
 * Background service that monitors WiFi networks and automatically connects
 * to the preferred network when available.
 */
class NetworkMonitorService : Service() {

    private lateinit var preferencesManager: PreferencesManager
    private lateinit var wifiHelper: WifiHelper
    private lateinit var handler: Handler
    
    private var wakeLock: PowerManager.WakeLock? = null
    private var isMonitoring = false
    private var isConnecting = false
    private var lastConnectedSsid: String? = null

    private val scanInterval = 30_000L // 30 seconds
    private val reconnectDelay = 5_000L // 5 seconds

    companion object {
        private const val TAG = "NetworkMonitorService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "network_monitor_channel"

        const val ACTION_START = "com.forcenetwork.app.ACTION_START"
        const val ACTION_STOP = "com.forcenetwork.app.ACTION_STOP"
        const val ACTION_CHECK_NETWORK = "com.forcenetwork.app.ACTION_CHECK_NETWORK"
        const val ACTION_SERVICE_STATE_CHANGED = "com.forcenetwork.app.SERVICE_STATE_CHANGED"

        fun start(context: Context) {
            val intent = Intent(context, NetworkMonitorService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, NetworkMonitorService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun checkNetwork(context: Context) {
            val intent = Intent(context, NetworkMonitorService::class.java).apply {
                action = ACTION_CHECK_NETWORK
            }
            context.startService(intent)
        }

        fun isRunning(context: Context): Boolean {
            return PreferencesManager.getInstance(context).isServiceEnabled()
        }
    }

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                if (success) {
                    checkAndConnectToPreferredNetwork()
                }
            }
        }
    }

    private val networkCheckRunnable = object : Runnable {
        override fun run() {
            if (isMonitoring) {
                checkAndConnectToPreferredNetwork()
                wifiHelper.startScan()
                handler.postDelayed(this, scanInterval)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        preferencesManager = PreferencesManager.getInstance(this)
        wifiHelper = WifiHelper(this)
        handler = Handler(Looper.getMainLooper())

        createNotificationChannel()
        
        // Acquire wake lock to keep the service running
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startMonitoring()
            ACTION_STOP -> stopMonitoring()
            ACTION_CHECK_NETWORK -> checkAndConnectToPreferredNetwork()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopMonitoring()
        releaseWakeLock()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun startMonitoring() {
        if (isMonitoring) return

        Log.d(TAG, "Starting network monitoring")
        isMonitoring = true
        preferencesManager.setServiceEnabled(true)

        // Start foreground service with notification
        try {
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
            isMonitoring = false
            preferencesManager.setServiceEnabled(false)
            stopSelf()
            return
        }

        // Register for scan results (with RECEIVER_NOT_EXPORTED for Android 13+)
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wifiScanReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(wifiScanReceiver, filter)
        }

        // Broadcast service state change
        sendBroadcast(Intent(ACTION_SERVICE_STATE_CHANGED))

        // Start periodic scanning
        handler.post(networkCheckRunnable)

        // Initial scan
        wifiHelper.startScan()
    }

    private fun stopMonitoring() {
        if (!isMonitoring) {
            stopSelf()
            return
        }

        Log.d(TAG, "Stopping network monitoring")
        isMonitoring = false
        preferencesManager.setServiceEnabled(false)

        handler.removeCallbacks(networkCheckRunnable)

        try {
            unregisterReceiver(wifiScanReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }

        // Broadcast service state change
        sendBroadcast(Intent(ACTION_SERVICE_STATE_CHANGED))

        wifiHelper.disconnectNetworkCallback()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun checkAndConnectToPreferredNetwork() {
        val preferredSsid = preferencesManager.getPreferredNetworkSsid()
        
        if (preferredSsid.isNullOrEmpty()) {
            Log.d(TAG, "No preferred network configured")
            return
        }

        // Skip if already connecting
        if (isConnecting) {
            Log.d(TAG, "Already connecting, skipping check")
            return
        }

        val currentSsid = wifiHelper.getCurrentSsid()
        Log.d(TAG, "Current SSID: $currentSsid, Preferred: $preferredSsid")

        // Already connected to preferred network
        if (currentSsid == preferredSsid) {
            Log.d(TAG, "Already connected to preferred network")
            if (lastConnectedSsid != preferredSsid) {
                lastConnectedSsid = preferredSsid
                updateNotification("Connected to $preferredSsid")
            }
            return
        }

        // Check if auto-connect is enabled
        if (!preferencesManager.isAutoConnectEnabled()) {
            Log.d(TAG, "Auto-connect is disabled")
            return
        }

        // Check if preferred network is in range
        if (wifiHelper.isNetworkInRange(preferredSsid)) {
            Log.d(TAG, "Preferred network in range, connecting...")
            updateNotification("Connecting to $preferredSsid...")
            connectToPreferredNetwork(preferredSsid)
        } else {
            Log.d(TAG, "Preferred network not in range")
            updateNotification("Waiting for $preferredSsid...")
            lastConnectedSsid = null
        }
    }

    private fun connectToPreferredNetwork(ssid: String) {
        if (isConnecting) {
            Log.d(TAG, "Already connecting, ignoring request")
            return
        }
        
        isConnecting = true
        val password = preferencesManager.getNetworkPassword()
        
        // Use network suggestion for persistent connection (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val suggestionAdded = wifiHelper.addNetworkSuggestion(ssid, password)
            Log.d(TAG, "Network suggestion added: $suggestionAdded")
        }
        
        wifiHelper.connectToNetwork(ssid, password, object : WifiHelper.ConnectionCallback {
            override fun onSuccess(ssid: String) {
                Log.d(TAG, "Successfully connected to $ssid")
                isConnecting = false
                lastConnectedSsid = ssid
                updateNotification("Connected to $ssid")
            }

            override fun onFailure(reason: String) {
                Log.e(TAG, "Failed to connect: $reason")
                isConnecting = false
                updateNotification("Connection failed: $reason")
                
                // Retry after delay (but only once)
                handler.postDelayed({
                    if (isMonitoring && !isConnecting) {
                        val currentSsid = wifiHelper.getCurrentSsid()
                        if (currentSsid != ssid) {
                            checkAndConnectToPreferredNetwork()
                        }
                    }
                }, reconnectDelay)
            }

            override fun onDisconnected() {
                Log.d(TAG, "Disconnected from network")
                isConnecting = false
                
                // Only try to reconnect if we were previously connected to the preferred network
                // and are now disconnected (not just because of callback noise)
                val currentSsid = wifiHelper.getCurrentSsid()
                val preferredSsid = preferencesManager.getPreferredNetworkSsid()
                
                if (currentSsid != preferredSsid && lastConnectedSsid == preferredSsid) {
                    lastConnectedSsid = null
                    updateNotification("Disconnected, will reconnect...")
                    
                    // Don't immediately reconnect - let the periodic check handle it
                    // This prevents the reconnection loop
                }
            }
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(message: String? = null): Notification {
        val preferredSsid = preferencesManager.getPreferredNetworkSsid() ?: "Not configured"
        val contentText = message ?: getString(R.string.service_notification_text, preferredSsid)

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, NetworkMonitorService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_wifi)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(message: String) {
        val notification = createNotification(message)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ForceNetwork::NetworkMonitorWakeLock"
        )
        wakeLock?.acquire()
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }
}
