package com.forcenetwork.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.forcenetwork.app.adapter.NetworkAdapter
import com.forcenetwork.app.databinding.ActivitySettingsBinding
import com.forcenetwork.app.util.PreferencesManager
import com.forcenetwork.app.util.WifiHelper

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var wifiHelper: WifiHelper
    private lateinit var networkAdapter: NetworkAdapter

    private val pinActivityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == PinActivity.RESULT_PIN_SET) {
            Toast.makeText(this, R.string.pin_set_success, Toast.LENGTH_SHORT).show()
        }
    }

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                updateNetworkList()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferencesManager = PreferencesManager.getInstance(this)
        wifiHelper = WifiHelper(this)

        setupToolbar()
        setupNetworkList()
        setupBehaviorSettings()
        setupSecuritySettings()
        loadCurrentSettings()
        
        // Start a WiFi scan to populate available networks
        wifiHelper.startScan()
    }

    override fun onResume() {
        super.onResume()
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

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupNetworkList() {
        networkAdapter = NetworkAdapter { network ->
            // Set the selected network in the text field
            binding.etNetworkSsid.setText(network.ssid)
        }

        binding.rvAvailableNetworks.apply {
            layoutManager = LinearLayoutManager(this@SettingsActivity)
            adapter = networkAdapter
        }

        // Update preferred network indicator
        val preferredSsid = preferencesManager.getPreferredNetworkSsid()
        networkAdapter.setPreferredNetwork(preferredSsid)

        // Save button
        binding.btnSaveNetwork.setOnClickListener {
            saveNetworkSettings()
        }
    }

    private fun setupBehaviorSettings() {
        binding.switchAutoConnect.setOnCheckedChangeListener { _, isChecked ->
            preferencesManager.setAutoConnect(isChecked)
        }

        binding.switchMonitorChanges.setOnCheckedChangeListener { _, isChecked ->
            preferencesManager.setMonitorChanges(isChecked)
        }
    }

    private fun setupSecuritySettings() {
        binding.btnChangePin.setOnClickListener {
            val intent = Intent(this, PinActivity::class.java).apply {
                putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_CHANGE)
            }
            pinActivityLauncher.launch(intent)
        }

        binding.btnResetSettings.setOnClickListener {
            showResetConfirmation()
        }
    }

    private fun loadCurrentSettings() {
        // Load network settings
        val preferredSsid = preferencesManager.getPreferredNetworkSsid()
        preferredSsid?.let { binding.etNetworkSsid.setText(it) }

        val password = preferencesManager.getNetworkPassword()
        password?.let { binding.etNetworkPassword.setText(it) }

        // Load behavior settings
        binding.switchAutoConnect.isChecked = preferencesManager.isAutoConnectEnabled()
        binding.switchMonitorChanges.isChecked = preferencesManager.isMonitorChangesEnabled()
    }

    private fun updateNetworkList() {
        val networks = wifiHelper.getAvailableNetworks()
        networkAdapter.submitList(networks)
    }

    private fun saveNetworkSettings() {
        val ssid = binding.etNetworkSsid.text?.toString()?.trim()
        val password = binding.etNetworkPassword.text?.toString()

        if (ssid.isNullOrEmpty()) {
            binding.etNetworkSsid.error = "Please enter a network SSID"
            return
        }

        preferencesManager.setPreferredNetworkSsid(ssid)
        if (!password.isNullOrEmpty()) {
            preferencesManager.setNetworkPassword(password)
        }

        // Add network suggestion for Android 10+
        wifiHelper.addNetworkSuggestion(ssid, password)

        Toast.makeText(
            this,
            getString(R.string.network_saved, ssid),
            Toast.LENGTH_SHORT
        ).show()

        // Update the adapter
        networkAdapter.setPreferredNetwork(ssid)
    }

    private fun showResetConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Reset Settings?")
            .setMessage("This will clear all settings including PIN, preferred network, and behavior settings. You will need to set up the app again.")
            .setPositiveButton("Reset") { _, _ ->
                resetAllSettings()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resetAllSettings() {
        preferencesManager.resetAllSettings()
        Toast.makeText(this, "Settings reset successfully", Toast.LENGTH_SHORT).show()
        
        // Go back to main activity
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
        finish()
    }
}
