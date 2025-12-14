# Force Network - Android WiFi Auto-Connect App

An Android application that automatically connects to your preferred WiFi network when in range and monitors network changes to ensure you stay connected to your preferred network.

## Features

### 1. Auto-Connect to Preferred Network
- When the app detects your configured preferred network is in range, it automatically connects to it
- Works even when the device is connected to a different network

### 2. Network Change Monitoring
- Monitors network connectivity changes in real-time
- If someone manually changes the network, the app checks if your preferred network is available
- Automatically switches back to the preferred network if found

### 3. PIN Protection
- All settings are protected by a 4-digit PIN
- PIN is securely stored using encrypted SharedPreferences
- Change PIN option available in settings

### 4. Background Service
- Runs as a foreground service for reliable operation
- Survives app restarts and device reboots
- Shows notification with current monitoring status

## Permissions Required

The app requires the following permissions:

| Permission | Purpose |
|------------|---------|
| `ACCESS_WIFI_STATE` | Read WiFi network information |
| `CHANGE_WIFI_STATE` | Connect to WiFi networks |
| `ACCESS_FINE_LOCATION` | Required for WiFi scanning (Android 8.1+) |
| `ACCESS_COARSE_LOCATION` | Required for WiFi scanning |
| `ACCESS_BACKGROUND_LOCATION` | Monitor networks when app is in background |
| `NEARBY_WIFI_DEVICES` | Required for WiFi on Android 13+ |
| `FOREGROUND_SERVICE` | Run background monitoring service |
| `RECEIVE_BOOT_COMPLETED` | Start service on device boot |

## How to Use

### Initial Setup
1. Open the app
2. Grant all required permissions when prompted
3. Tap the **Settings** button (gear icon)
4. Set up a 4-digit PIN when prompted
5. Configure your preferred network:
   - Enter the SSID manually, or
   - Tap "Scan" and select from available networks
6. Enter the network password (if secured)
7. Tap **Save**

### Starting the Service
1. On the main screen, tap **Start Service**
2. The app will begin monitoring for your preferred network
3. A notification will appear showing the monitoring status

### Changing Settings
1. Tap the **Settings** button
2. Enter your PIN
3. Modify settings as needed:
   - Change preferred network
   - Toggle auto-connect
   - Toggle network change monitoring
   - Change PIN
   - Reset all settings

## Technical Details

### Architecture
- **MainActivity**: Main UI showing current network status and controls
- **PinActivity**: Handles PIN setup and verification
- **SettingsActivity**: Configuration interface
- **NetworkMonitorService**: Foreground service for continuous monitoring
- **NetworkChangeReceiver**: BroadcastReceiver for network change events
- **BootReceiver**: Starts service on device boot

### Data Storage
- **PreferencesManager**: Manages all app preferences
  - Regular preferences for non-sensitive data
  - EncryptedSharedPreferences for PIN and network password

### Network Connection
- **WifiHelper**: Utility class for WiFi operations
  - Uses `WifiNetworkSpecifier` on Android 10+
  - Uses `WifiConfiguration` on Android 9 and below
  - Supports network suggestions for better auto-connect

## Building the App

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- Android SDK 34

### Build Steps
1. Open the project in Android Studio
2. Sync Gradle files
3. Build > Make Project
4. Run > Run 'app'

### Build from Command Line
```bash
./gradlew assembleDebug
```

The APK will be located at `app/build/outputs/apk/debug/app-debug.apk`

## Limitations

### Android 10+ (API 29+)
- Apps cannot directly enable/disable WiFi - user must do it manually
- Network connections made via `WifiNetworkSpecifier` are temporary and only last while the app is active
- Consider using `WifiNetworkSuggestion` for persistent connections

### Android 13+ (API 33+)
- Requires `NEARBY_WIFI_DEVICES` permission for WiFi scanning
- Notification permission required for foreground service notification

## Troubleshooting

### Network not connecting
1. Ensure all permissions are granted
2. Verify the SSID is spelled correctly
3. Check if the password is correct
4. Make sure the network is in range

### Service stops unexpectedly
1. Disable battery optimization for the app
2. Enable "Allow background activity" in app settings
3. Some manufacturers have aggressive battery management - check device-specific settings

### WiFi scanning not working
1. Ensure Location services are enabled
2. Grant background location permission
3. On Android 13+, ensure NEARBY_WIFI_DEVICES permission is granted

## License

This project is provided as-is for educational purposes.

## Version History

- **1.0** - Initial release
  - Auto-connect to preferred network
  - Network change monitoring
  - PIN protection
  - Background service with notification
