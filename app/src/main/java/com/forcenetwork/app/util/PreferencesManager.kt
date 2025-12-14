package com.forcenetwork.app.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest

/**
 * Helper class for managing app preferences including PIN and network configuration.
 * Uses encrypted storage for sensitive data like PIN.
 */
class PreferencesManager(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        ENCRYPTED_PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val regularPrefs: SharedPreferences =
        context.getSharedPreferences(REGULAR_PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val ENCRYPTED_PREFS_NAME = "force_network_secure_prefs"
        private const val REGULAR_PREFS_NAME = "force_network_prefs"

        // Encrypted preference keys
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_NETWORK_PASSWORD = "network_password"

        // Regular preference keys
        private const val KEY_NETWORK_SSID = "network_ssid"
        private const val KEY_AUTO_CONNECT = "auto_connect"
        private const val KEY_MONITOR_CHANGES = "monitor_changes"
        private const val KEY_SERVICE_ENABLED = "service_enabled"
        private const val KEY_PIN_SET = "pin_set"

        @Volatile
        private var instance: PreferencesManager? = null

        fun getInstance(context: Context): PreferencesManager {
            return instance ?: synchronized(this) {
                instance ?: PreferencesManager(context.applicationContext).also { instance = it }
            }
        }
    }

    // ==================== PIN Management ====================

    /**
     * Check if PIN has been set
     */
    fun isPinSet(): Boolean {
        return regularPrefs.getBoolean(KEY_PIN_SET, false)
    }

    /**
     * Set the PIN (hashed for security)
     */
    fun setPin(pin: String) {
        val hashedPin = hashPin(pin)
        encryptedPrefs.edit().putString(KEY_PIN_HASH, hashedPin).apply()
        regularPrefs.edit().putBoolean(KEY_PIN_SET, true).apply()
    }

    /**
     * Verify if the provided PIN matches the stored PIN
     */
    fun verifyPin(pin: String): Boolean {
        val storedHash = encryptedPrefs.getString(KEY_PIN_HASH, null) ?: return false
        val inputHash = hashPin(pin)
        return storedHash == inputHash
    }

    /**
     * Clear the PIN
     */
    fun clearPin() {
        encryptedPrefs.edit().remove(KEY_PIN_HASH).apply()
        regularPrefs.edit().putBoolean(KEY_PIN_SET, false).apply()
    }

    /**
     * Hash the PIN using SHA-256
     */
    private fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(pin.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    // ==================== Network Configuration ====================

    /**
     * Set the preferred network SSID
     */
    fun setPreferredNetworkSsid(ssid: String) {
        regularPrefs.edit().putString(KEY_NETWORK_SSID, ssid).apply()
    }

    /**
     * Get the preferred network SSID
     */
    fun getPreferredNetworkSsid(): String? {
        return regularPrefs.getString(KEY_NETWORK_SSID, null)
    }

    /**
     * Set the network password (stored encrypted)
     */
    fun setNetworkPassword(password: String) {
        encryptedPrefs.edit().putString(KEY_NETWORK_PASSWORD, password).apply()
    }

    /**
     * Get the network password
     */
    fun getNetworkPassword(): String? {
        return encryptedPrefs.getString(KEY_NETWORK_PASSWORD, null)
    }

    /**
     * Check if a preferred network is configured
     */
    fun hasPreferredNetwork(): Boolean {
        return !getPreferredNetworkSsid().isNullOrEmpty()
    }

    // ==================== Behavior Settings ====================

    /**
     * Set auto-connect preference
     */
    fun setAutoConnect(enabled: Boolean) {
        regularPrefs.edit().putBoolean(KEY_AUTO_CONNECT, enabled).apply()
    }

    /**
     * Get auto-connect preference
     */
    fun isAutoConnectEnabled(): Boolean {
        return regularPrefs.getBoolean(KEY_AUTO_CONNECT, true)
    }

    /**
     * Set monitor network changes preference
     */
    fun setMonitorChanges(enabled: Boolean) {
        regularPrefs.edit().putBoolean(KEY_MONITOR_CHANGES, enabled).apply()
    }

    /**
     * Get monitor network changes preference
     */
    fun isMonitorChangesEnabled(): Boolean {
        return regularPrefs.getBoolean(KEY_MONITOR_CHANGES, true)
    }

    // ==================== Service State ====================

    /**
     * Set service enabled state
     */
    fun setServiceEnabled(enabled: Boolean) {
        regularPrefs.edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply()
    }

    /**
     * Get service enabled state
     */
    fun isServiceEnabled(): Boolean {
        return regularPrefs.getBoolean(KEY_SERVICE_ENABLED, false)
    }

    // ==================== Reset ====================

    /**
     * Reset all settings to default
     */
    fun resetAllSettings() {
        encryptedPrefs.edit().clear().apply()
        regularPrefs.edit().clear().apply()
    }
}
