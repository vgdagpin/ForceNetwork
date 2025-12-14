# Development Notes

This document tracks issues encountered and fixes applied during development.

## Development Session: December 15, 2025

### Initial Setup
- Created complete Android project structure manually
- Set up Gradle with Kotlin DSL
- Configured Material Design 3 theming

---

## Issues Fixed

### 1. Unresolved reference: bindingAdapterPosition
**File**: `NetworkAdapter.kt`  
**Error**: `Unresolved reference: bindingAdapterPosition`  
**Fix**: Changed `bindingAdapterPosition` to `adapterPosition` for RecyclerView compatibility

---

### 2. JDK/jlink compatibility error
**Error**: `JdkImageTransform` error with Android 34  
**Fix**: 
- Changed Java compatibility from 17 to 11
- Added `coreLibraryDesugaring`
- Cleared Gradle transforms cache

---

### 3. registerForActivityResult crash
**File**: `MainActivity.kt`  
**Error**: `IllegalStateException: LifecycleOwner is attempting to register while current state is RESUMED`  
**Cause**: `registerForActivityResult()` was called inside `verifyPinAndSetNetwork()` while activity was already running  
**Fix**: 
- Moved `registerForActivityResult()` to class member (`verifyPinLauncher`)
- Added `pendingNetwork` variable to store network being configured

---

### 4. Service not starting / status not updating
**File**: `NetworkMonitorService.kt`, `AndroidManifest.xml`  
**Issues**:
- Missing `POST_NOTIFICATIONS` permission for Android 13+
- Wrong foreground service type (`connectedDevice` instead of `location`)
- Receiver registration not using `RECEIVER_NOT_EXPORTED` on Android 13+

**Fix**:
- Added `POST_NOTIFICATIONS` permission
- Changed `FOREGROUND_SERVICE_CONNECTED_DEVICE` to `FOREGROUND_SERVICE_LOCATION`
- Changed service `foregroundServiceType` to `location`
- Added `RECEIVER_NOT_EXPORTED` flag for broadcast receivers on Android 13+
- Added service state broadcast for UI updates

---

### 5. Disconnect/Reconnect loop
**Files**: `NetworkMonitorService.kt`, `WifiHelper.kt`  
**Issue**: After connecting to preferred network, it kept disconnecting and reconnecting in a loop  
**Cause**: 
- `WifiNetworkSpecifier` creates temporary app-bound connections
- `onLost()` callback triggered reconnection even when still connected
- No protection against multiple simultaneous connection attempts

**Fix**:
- Added `isConnecting` flag to prevent concurrent connection attempts
- Added `lastConnectedSsid` to track connection state
- Added `hasCalledBack` flag in `NetworkCallback` to prevent multiple callbacks
- `onLost()` now checks if actually disconnected before triggering reconnection
- `onDisconnected()` no longer immediately retries - waits for periodic check
- Added early return in `connectToNetwork()` if already connected
- Added `WifiNetworkSuggestion` for more persistent connections on Android 10+

---

## Version Updates (LTS)

Updated to long-term support / stable versions:

| Component | Old | New |
|-----------|-----|-----|
| Java | 11 | 17 (LTS) |
| Kotlin | 1.9.21 | 2.0.21 |
| Android Gradle Plugin | 8.2.0 | 8.7.3 |
| Gradle | 8.2 | 8.11.1 |
| compileSdk/targetSdk | 34 | 35 |
| desugar_jdk_libs | 2.0.4 | 2.1.4 |
| core-ktx | 1.12.0 | 1.15.0 |
| appcompat | 1.6.1 | 1.7.0 |
| material | 1.11.0 | 1.12.0 |
| constraintlayout | 2.1.4 | 2.2.0 |
| lifecycle | 2.7.0 | 2.8.7 |

---

## Current State

### Working Features
- ✅ WiFi network scanning
- ✅ Set preferred network with PIN protection
- ✅ Background monitoring service with foreground notification
- ✅ Auto-connect to preferred network when in range
- ✅ Service status display and toggle
- ✅ Permissions handling with dialogs

### Known Issues / TODO
- [ ] Test boot receiver functionality
- [ ] Test on different Android versions (10, 11, 12, 13, 14, 15)
- [ ] Add network signal strength indicator in list
- [ ] Add option to configure scan interval
- [ ] Consider using WorkManager for more reliable background operation
- [ ] Test on manufacturer-specific Android (Xiaomi MIUI, Samsung OneUI, etc.)

---

## Architecture Notes

### WiFi Connection on Android 10+
Android 10 introduced significant changes to WiFi APIs:
- Apps can no longer directly enable/disable WiFi
- `WifiConfiguration` is deprecated
- `WifiNetworkSpecifier` creates temporary, app-bound connections
- `WifiNetworkSuggestion` allows system-managed persistent connections

The app uses both approaches:
1. `WifiNetworkSpecifier` for immediate connection
2. `WifiNetworkSuggestion` for system to remember the network

### Background Service
Uses foreground service with `location` type because:
- WiFi scanning requires location permission
- Foreground service ensures the app isn't killed
- Notification keeps user informed of status

### Security
- PIN stored as SHA-256 hash
- Network password stored in EncryptedSharedPreferences
- Uses AndroidX Security library for encryption
