package com.forcenetwork.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.forcenetwork.app.adapter.NetworkAdapter
import com.forcenetwork.app.databinding.ActivityMainBinding
import com.forcenetwork.app.service.NetworkMonitorService
import com.forcenetwork.app.util.PreferencesManager
import com.forcenetwork.app.util.WifiHelper

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var wifiHelper: WifiHelper
    private lateinit var networkAdapter: NetworkAdapter
    
    // Store pending network for after PIN verification
    private var pendingNetwork: WifiHelper.WifiNetwork? = null
    
    private val verifyPinLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == PinActivity.RESULT_PIN_VERIFIED) {
            pendingNetwork?.let { network ->
                if (network.isSecure) {
                    showPasswordDialog(network)
                } else {
                    savePreferredNetwork(network.ssid, null)
                }
            }
        }
        pendingNetwork = null
    }

    private val requiredPermissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.CHANGE_WIFI_STATE
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Note: ACCESS_BACKGROUND_LOCATION must be requested separately after foreground location is granted
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            // Check if we still need background location
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) 
                != PackageManager.PERMISSION_GRANTED) {
                requestBackgroundLocation()
            } else {
                scanNetworks()
            }
        } else {
            // Check if at least location is granted
            val locationGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            
            if (locationGranted) {
                // Can still scan with foreground location
                scanNetworks()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Location Permission Required")
                    .setMessage("WiFi scanning requires Location permission.\n\nPlease go to Settings → Apps → ForceNetwork → Permissions → Location and select 'Allow all the time' for best experience.")
                    .setPositiveButton("Open Settings") { _, _ ->
                        openAppSettings()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }
    
    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            AlertDialog.Builder(this)
                .setTitle("Background Location")
                .setMessage("For automatic network switching to work when the app is closed, please select 'Allow all the time' on the next screen.")
                .setPositiveButton("Continue") { _, _ ->
                    permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                }
                .setNegativeButton("Skip") { _, _ ->
                    scanNetworks()
                }
                .show()
        }
    }
    
    private fun openAppSettings() {
        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private val pinActivityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        when (result.resultCode) {
            PinActivity.RESULT_PIN_VERIFIED, PinActivity.RESULT_PIN_SET -> {
                // PIN verified or set, open settings
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }
    }

    private val serviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == NetworkMonitorService.ACTION_SERVICE_STATE_CHANGED) {
                updateStatus()
            }
        }
    }

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                binding.progressScan.visibility = View.GONE
                binding.btnScan.isEnabled = true
                updateNetworkList()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferencesManager = PreferencesManager.getInstance(this)
        wifiHelper = WifiHelper(this)

        setupUI()
        setupNetworkList()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        
        // Register for scan results
        val scanFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wifiScanReceiver, scanFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(wifiScanReceiver, scanFilter)
        }
        
        // Register for service state changes
        val serviceFilter = IntentFilter(NetworkMonitorService.ACTION_SERVICE_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceStateReceiver, serviceFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(serviceStateReceiver, serviceFilter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(wifiScanReceiver)
            unregisterReceiver(serviceStateReceiver)
        } catch (e: Exception) {
            // Receiver not registered
        }
    }

    private fun setupUI() {
        // Settings button
        binding.btnSettings.setOnClickListener {
            openSettings()
        }

        // Service toggle button
        binding.btnToggleService.setOnClickListener {
            toggleService()
        }

        // Scan button
        binding.btnScan.setOnClickListener {
            if (checkPermissions()) {
                scanNetworks()
            } else {
                requestPermissions()
            }
        }
    }

    private fun setupNetworkList() {
        networkAdapter = NetworkAdapter { network ->
            // Show option to set as preferred
            showSetPreferredDialog(network)
        }

        binding.rvNetworks.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = networkAdapter
        }
    }

    private fun updateStatus() {
        // Update current network
        val currentSsid = wifiHelper.getCurrentSsid()
        binding.tvCurrentNetwork.text = currentSsid ?: getString(R.string.not_connected)
        binding.tvCurrentNetwork.setTextColor(
            getColor(if (currentSsid != null) R.color.status_connected else R.color.status_disconnected)
        )

        // Update preferred network
        val preferredSsid = preferencesManager.getPreferredNetworkSsid()
        binding.tvPreferredNetwork.text = preferredSsid ?: getString(R.string.not_set)
        networkAdapter.setPreferredNetwork(preferredSsid)

        // Update service status
        val isServiceRunning = NetworkMonitorService.isRunning(this)
        binding.tvServiceStatus.text = if (isServiceRunning) {
            getString(R.string.running)
        } else {
            getString(R.string.stopped)
        }
        binding.tvServiceStatus.setTextColor(
            getColor(if (isServiceRunning) R.color.status_running else R.color.status_stopped)
        )
        binding.btnToggleService.text = if (isServiceRunning) {
            getString(R.string.stop_service)
        } else {
            getString(R.string.start_service)
        }
    }

    private fun checkPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            // All foreground permissions granted, check background location
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) 
                != PackageManager.PERMISSION_GRANTED) {
                requestBackgroundLocation()
            } else {
                scanNetworks()
            }
        }
    }

    private fun scanNetworks() {
        binding.progressScan.visibility = View.VISIBLE
        binding.btnScan.isEnabled = false
        binding.tvNoNetworks.visibility = View.GONE

        wifiHelper.startScan()
        
        // Timeout for scan
        binding.root.postDelayed({
            if (binding.progressScan.visibility == View.VISIBLE) {
                binding.progressScan.visibility = View.GONE
                binding.btnScan.isEnabled = true
                updateNetworkList()
            }
        }, 5000)
    }

    private fun updateNetworkList() {
        val networks = wifiHelper.getAvailableNetworks()
        networkAdapter.submitList(networks)
        
        binding.tvNoNetworks.visibility = if (networks.isEmpty()) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun showSetPreferredDialog(network: WifiHelper.WifiNetwork) {
        AlertDialog.Builder(this)
            .setTitle("Set as Preferred Network?")
            .setMessage("Do you want to set \"${network.ssid}\" as your preferred network?\n\nThe app will automatically connect to this network when available.")
            .setPositiveButton("Yes") { _, _ ->
                if (!preferencesManager.isPinSet()) {
                    // Need to set PIN first
                    Toast.makeText(
                        this,
                        "Please set a PIN first to protect your settings",
                        Toast.LENGTH_LONG
                    ).show()
                    openPinSetup()
                } else {
                    // Verify PIN then set network
                    verifyPinAndSetNetwork(network)
                }
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun verifyPinAndSetNetwork(network: WifiHelper.WifiNetwork) {
        pendingNetwork = network
        val intent = Intent(this, PinActivity::class.java).apply {
            putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_VERIFY)
        }
        verifyPinLauncher.launch(intent)
    }

    private fun showPasswordDialog(network: WifiHelper.WifiNetwork) {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or 
                       android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Network password"
        }

        AlertDialog.Builder(this)
            .setTitle("Enter WiFi Password")
            .setMessage("Enter the password for \"${network.ssid}\"")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val password = input.text.toString()
                savePreferredNetwork(network.ssid, password)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun savePreferredNetwork(ssid: String, password: String?) {
        preferencesManager.setPreferredNetworkSsid(ssid)
        password?.let { preferencesManager.setNetworkPassword(it) }
        
        Toast.makeText(
            this,
            getString(R.string.network_saved, ssid),
            Toast.LENGTH_SHORT
        ).show()
        
        updateStatus()
    }

    private fun openSettings() {
        if (!preferencesManager.isPinSet()) {
            // Need to set up PIN first
            openPinSetup()
        } else {
            // Verify PIN before opening settings
            val intent = Intent(this, PinActivity::class.java).apply {
                putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_VERIFY)
            }
            pinActivityLauncher.launch(intent)
        }
    }

    private fun openPinSetup() {
        val intent = Intent(this, PinActivity::class.java).apply {
            putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_SETUP)
        }
        pinActivityLauncher.launch(intent)
    }

    private fun toggleService() {
        if (!preferencesManager.hasPreferredNetwork()) {
            Toast.makeText(
                this,
                "Please configure a preferred network first",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val wasRunning = NetworkMonitorService.isRunning(this)
        
        if (wasRunning) {
            NetworkMonitorService.stop(this)
            Toast.makeText(this, "Service stopped", Toast.LENGTH_SHORT).show()
        } else {
            if (!checkPermissions()) {
                requestPermissions()
                return
            }
            try {
                NetworkMonitorService.start(this)
                Toast.makeText(this, "Service started", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    "Failed to start service: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        }

        // Update UI after a short delay to allow service state to change
        binding.root.postDelayed({
            updateStatus()
        }, 1000)
    }
}
