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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            scanNetworks()
        } else {
            Toast.makeText(
                this,
                "Permissions required to scan WiFi networks",
                Toast.LENGTH_LONG
            ).show()
        }
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
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        registerReceiver(wifiScanReceiver, filter)
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(wifiScanReceiver)
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
        // Check for background location separately on Android 10+
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            // Request foreground permissions first
            val foregroundPermissions = permissionsToRequest.filter {
                it != Manifest.permission.ACCESS_BACKGROUND_LOCATION
            }
            
            if (foregroundPermissions.isNotEmpty()) {
                permissionLauncher.launch(foregroundPermissions.toTypedArray())
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Request background location separately
                permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
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
        val intent = Intent(this, PinActivity::class.java).apply {
            putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_VERIFY)
        }
        
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == PinActivity.RESULT_PIN_VERIFIED) {
                // Show password dialog if network is secured
                if (network.isSecure) {
                    showPasswordDialog(network)
                } else {
                    savePreferredNetwork(network.ssid, null)
                }
            }
        }.launch(intent)
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

        if (NetworkMonitorService.isRunning(this)) {
            NetworkMonitorService.stop(this)
        } else {
            if (!checkPermissions()) {
                requestPermissions()
                return
            }
            NetworkMonitorService.start(this)
        }

        // Update UI after a short delay
        binding.root.postDelayed({
            updateStatus()
        }, 500)
    }
}
