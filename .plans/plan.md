# Implementation Plan: Android 17 (API 37) Platform Compatibility & Local Network Protection

## Goals
Target Android 17 (API 37) in `app/build.gradle.kts` while maintaining backward compatibility across Android 8.0–16 (API 26–36), Android TV, and desktop companions.
Satisfy all requirements in `.plans/spec.md` and confirmed answers in `.plans/clarifications.md`:
- Seamlessly integrate Android 17's Local Network Protection (`android.permission.ACCESS_LOCAL_NETWORK`).
- Ensure full functionality for the Wireless ADB suite (discovery, pairing, shell, push/pull, remote control, and `connectedDevice` foreground services).
- Unify LAN discovery across Wi-Fi and wired Ethernet on Android TV.
- Strictly adhere to `UFMStandard` for all dialogs, recovery banners, and Settings cards.
- Guarantee that saved shares, credentials, and offline features are never cleared or broken by permission states.

---

## Architecture Overview

```
+--------------------------------------------------------------------------------------------------+
|                                    Android 17 Runtime Layer                                      |
+--------------------------------------------------------------------------------------------------+
|                                                                                                  |
|   +------------------------------------+          +-----------------------------------------+    |
|   |   LocalNetworkPermissionManager    |          |          NetworkInterfaceUtils          |    |
|   +------------------------------------+          +-----------------------------------------+    |
|   | - isEnforced() (API >= 37)         |          | - getLocalIpv4Address()                 |    |
|   | - hasPermission(context)           |          | - getSubnetPrefix() (Wi-Fi + Ethernet)  |    |
|   | - isLoopbackAddress(host)          |          | - isLocalNetworkConnected()             |    |
|   | - checkOrRequest(activity, ...)    |          +-----------------------------------------+    |
|   | - showRationaleDialog()            |                               │                         |
|   +-----------------+------------------+                               │                         |
|                     │                                                  │                         |
|         +-----------┴-------------------------+                        │                         |
|         ▼                                     ▼                        ▼                         |
|  +--------------------+             +-------------------+   +--------------------+               |
|  |   UFMStandard      |             |  Storage / Shares |   |   Discovery & ADB  |               |
|  |  Rationale Dialog  |             |  Protocol Clients |   |  - SmbDiscovery    |               |
|  |  (Mobile & TV)     |             |  - SMB, NFS       |   |  - NfsDiscovery    |               |
|  +--------------------+             |  - SFTP, FTP      |   |  - DlnaDiscovery   |               |
|                                     |  - WebDAV, WebShr |   |  - AdbDiscovery    |               |
|                                     +-------------------+   |  - AdbManager      |               |
|                                                             |  - PairingDisc.    |               |
|                                                             +--------------------+               |
+--------------------------------------------------------------------------------------------------+
```

### Architectural Decisions:
1. **Central Permission Abstraction (`LocalNetworkPermissionManager`)**:
   - Single source of truth in `za.kilowatch.ultimatefilemanager.network`.
   - Bypassed on Android < 37 (`isEnforced() == false`) and for loopback/localhost targets (`127.0.0.1`, `localhost`).
   - Integrates with modern Android `ActivityResultLauncher<String>` or custom fallback Activity result callback.
2. **Unified Interface Helper (`NetworkInterfaceUtils`)**:
   - Replaces deprecated and Wi-Fi-only `WifiManager.connectionInfo.ipAddress`.
   - Inspects `NetworkInterface.getNetworkInterfaces()` across both `wlan*` and `eth*` (Ethernet) to safely compute IPv4 subnet prefixes.
3. **UFMStandard Frosted Glass Dialogs**:
   - Paired layouts: `dialog_local_network_rationale.xml` (mobile) and `dialog_local_network_rationale_tv.xml` (TV 650dp width).
   - Glass background `@drawable/bg_dialog_glass`, transparent window background, embedded XML buttons vertically stacked (primary on top, secondary/cancel below).
4. **Wireless ADB Guarantee**:
   - `AdbDeviceDiscovery`: Swapped to `NetworkInterfaceUtils` subnet resolver and gated with `LocalNetworkPermissionManager`.
   - `AdbPairingActivity` and `AdbPairingTvActivity`: "Scan Devices" and "Connect" buttons protected by contextual permission gate.
   - `AdbSessionForegroundService` and `AdbRemoteForegroundService`: Verified lifecycle, foreground launch checks, and clean shutdown handling.

---

## Files to Create

### 1. `app/src/main/java/za/kilowatch/ultimatefilemanager/network/LocalNetworkPermissionManager.kt`
- **What**: Central manager handling Android 17 Local Network Protection checks, rationales, request launcher bindings, and permanent denial routing.
- **Key details**:
  - `const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"`
  - `fun isEnforced(): Boolean` (returns `Build.VERSION.SDK_INT >= 37`)
  - `fun hasPermission(context: Context): Boolean`
  - `fun isLoopbackAddress(host: String?): Boolean`
  - `fun checkOrRequest(activity: ComponentActivity, launcher: ActivityResultLauncher<String>?, onGranted: () -> Unit, onDenied: () -> Unit)`
  - `fun showRationaleDialog(activity: Activity, launcher: ActivityResultLauncher<String>?, onGranted: () -> Unit, onDenied: () -> Unit)`
  - `fun openAppPermissionSettings(activity: Activity)`

### 2. `app/src/main/java/za/kilowatch/ultimatefilemanager/network/NetworkInterfaceUtils.kt`
- **What**: Unified network interface detection helper supporting Wi-Fi and wired Ethernet without relying on deprecated `WifiManager` APIs.
- **Key details**:
  - `fun getActiveLocalIpv4(context: Context): Inet4Address?`
  - `fun getSubnetPrefix(context: Context): String?`
  - `fun isLocalNetworkConnected(context: Context): Boolean`

### 3. `app/src/main/res/layout/dialog_local_network_rationale.xml`
- **What**: Mobile rationale dialog strictly conforming to `UFMStandard`.
- **Key details**:
  - Background `@drawable/bg_dialog_glass`, 56dp frosted squircle hero badge with `@drawable/ic_cloud` / `@drawable/ic_lightning`.
  - Title: 20sp `sans-serif-black`, Subtitle: 13sp.
  - Frosted card (`@color/mobile_glass_card`, `@color/mobile_glass_stroke`).
  - Vertically stacked buttons: Primary filled `MaterialButton` (`Widget.Material3Expressive.Button`) on top, TextButton secondary below.

### 4. `app/src/main/res/layout/dialog_local_network_rationale_tv.xml`
- **What**: Android TV rationale dialog strictly conforming to `UFMStandard`.
- **Key details**:
  - Width fixed to 650dp (`@dimen/tv_dialog_standard_width`), background `@drawable/bg_dialog_glass`.
  - D-pad focusable primary button (`@drawable/selector_tv_button_yellow`) and secondary button (`@drawable/selector_tv_button`).

### 5. `app/src/main/res/layout/item_banner_local_network_permission.xml`
- **What**: Inline recovery banner for `NetworkBrowserActivity` and `NetworkBrowserFragment`.
- **Key details**: Frosted glass card with message and "Grant Permission" action button.

---

## Files to Modify

### 1. `app/build.gradle.kts`
- **What**: Update `compileSdk` and `targetSdk` to 37.
- **Changes**:
  - Change `compileSdk { version = release(36) { minorApiLevel = 1 } }` to `compileSdk { version = release(37) }` (or `compileSdk = 37`).
  - Change `targetSdk { version = release(36) }` to `targetSdk { version = release(37) }` (or `targetSdk = 37`).

### 2. `app/src/main/AndroidManifest.xml`
- **What**: Declare the Android 17 permission.
- **Changes**:
  - Add `<uses-permission android:name="android.permission.ACCESS_LOCAL_NETWORK" />`.

### 3. `app/src/main/java/za/kilowatch/ultimatefilemanager/network/AdbDeviceDiscovery.kt`
- **What**: Replace deprecated `WifiManager` subnet logic with `NetworkInterfaceUtils` and gate with `LocalNetworkPermissionManager`.
- **Changes**:
  - Check `LocalNetworkPermissionManager.hasPermission(context)`. If false, log and return empty list.
  - Use `NetworkInterfaceUtils.getSubnetPrefix(context)` for subnet IP construction.

### 4. `app/src/main/java/za/kilowatch/ultimatefilemanager/ui/AdbPairingActivity.kt` & `AdbPairingTvActivity.kt`
- **What**: Gate discovery and manual socket connections with `LocalNetworkPermissionManager`.
- **Changes**:
  - Register `ActivityResultLauncher<String>` for local network permission.
  - Wrap `btnScan.setOnClickListener`, `btnConnectNoPIN`, and `btnConnectWithPin` in permission checks before launching network operations.

### 5. `app/src/main/java/za/kilowatch/ultimatefilemanager/network/SmbDiscovery.kt` & `NfsDiscovery.kt`
- **What**: Migrate subnet discovery to `NetworkInterfaceUtils` and gate probes with `LocalNetworkPermissionManager`.
- **Changes**:
  - Replace `WifiManager` IP parsing with `NetworkInterfaceUtils.getSubnetPrefix(context)`.
  - Pre-flight permission check before launching port probes.

### 6. `app/src/main/java/za/kilowatch/ultimatefilemanager/network/NetworkBrowserActivity.kt` & `NetworkBrowserFragment.kt`
- **What**: Implement contextual tap gating and inline recovery banner on denial.
- **Changes**:
  - Register `permissionLauncher` for `LocalNetworkPermissionManager.PERMISSION`.
  - Wrap share connection entry points (`loadShares`, `openShare`) to check permission (unless loopback).
  - Add and toggle visibility of inline recovery banner if user declines permission.

### 7. `app/src/main/java/za/kilowatch/ultimatefilemanager/network/WebShareServer.kt` & `PairingServer.kt`
- **What**: Ensure clean localized failure/toast if user initiates server without local network permission.
- **Changes**:
  - Check `LocalNetworkPermissionManager.hasPermission(context)`.
  - If denied on API 37+, show localized notice and cancel server startup safely.

### 8. `app/src/main/java/za/kilowatch/ultimatefilemanager/settings/SettingsActivity.kt` & `activity_settings.xml` / `activity_settings_tv.xml`
- **What**: Add dedicated Local Network Permission card in Network Settings section.
- **Changes**:
  - Add `cardLocalNetworkPermission` card view with icon, title, subtitle, and action button in both mobile and TV layouts.
  - In `SettingsActivity.kt`, update card subtitle to show "Granted" or "Not Granted / Required" and handle click to trigger permission request / open settings.

### 9. `app/src/main/res/values/strings.xml`
- **What**: Add all localized strings for rationales, banner, and settings cards.
- **Changes**:
  - `local_network_rationale_title`, `local_network_rationale_subtitle`, `local_network_rationale_message`
  - `local_network_btn_continue`, `local_network_btn_settings`, `local_network_btn_not_now`
  - `local_network_banner_text`, `local_network_banner_grant`
  - `settings_local_network_title`, `settings_local_network_subtitle`, `settings_local_network_status_granted`, `settings_local_network_status_denied`
  - `toast_local_network_permission_required`

### 10. `app/src/main/res/values/strings_policy.xml`
- **What**: Update in-app legal and privacy policy tables to disclose the Android 17 Local Network Protection permission.
- **Changes**:
  - `pp_network_perm_labels` & `pp_network_perm_details`: Add `ACCESS_LOCAL_NETWORK` and explanation.
  - `tc_permission_labels` & `tc_permission_details`: Add `Local Network Access (Android 17+)` and explanation.

### 11. `WEBSITE/UFMPrivacyPolicy.html` & `WEBSITE/UFMTerms.html`
- **What**: Keep external website legal policies synchronized with Android 17 permission disclosures.
- **Changes**:
  - `UFMPrivacyPolicy.html`: Add `ACCESS_LOCAL_NETWORK` row under the Network Permissions table.
  - `UFMTerms.html`: Add Local Network Access disclosure row in Section 05 Permissions table.

---

## Data Model
No database schema or SharedPreferences schema changes required. Local network permission status is queried live from the OS (`ContextCompat.checkSelfPermission`), with a boolean flag in SharedPreferences (`KEY_LOCAL_NETWORK_RATIONALE_SHOWN`) to track whether the user has seen the explanation previously.

---

## Dependencies
- Android SDK Platform `android-37` (already installed locally in SDK directory).
- Android Gradle Plugin `9.2.1` (already in project).

---

## Test Plan

### Automated Build Verification:
```bash
./gradlew assembleMobileGoogleDebug
```

### Manual Verification Matrix:
1. **Android 17+ (API 37)**:
   - Fresh launch: Verify no aggressive permission prompt appears on Welcome screen or Main Menu.
   - Tap SMB share: Rationale dialog appears adhering to `UFMStandard`.
   - Click "Continue": OS permission prompt appears.
   - On Grant: SMB share opens and lists contents immediately.
   - On Denial: Return to browser; inline recovery banner is visible; clicking "Grant" re-prompts.
   - Revocation in OS Settings: While browsing, revoke permission in Settings; app reports recoverable error without crashing.
   - Wireless ADB: Open ADB pairing; test subnet scan, 6-digit PIN pairing, shell terminal, and `AdbSessionForegroundService` background notification.
   - Android TV: Test on TV layout with D-pad navigation over wired Ethernet.
2. **Android 8.0–16 (API 26–36)**:
   - Verify all LAN features (SMB, NFS, ADB, DLNA) execute directly without showing any Android 17 permission dialogs or crashing.

---

## Risks & Mitigations
- **Risk**: Android 17 platform pre-release SDK behavior changes.
  - **Mitigation**: Guard permission constants safely with `Build.VERSION.SDK_INT >= 37` runtime check.
- **Risk**: Users confusing Local Network Permission with Internet access.
  - **Mitigation**: Rationale dialog explicitly explains that access is strictly limited to discovering and transferring files with local devices on the home network.
- **Risk**: TV devices on Ethernet failing Wi-Fi checks.
  - **Mitigation**: `NetworkInterfaceUtils` enumerates all active non-loopback IPv4 interfaces, treating Ethernet and Wi-Fi as first-class peers.
