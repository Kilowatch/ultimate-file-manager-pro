# Task List: Android 17 (API 37) Platform Compatibility & Local Network Protection

## Phase 1: Setup & Manifest Alignment
- [x] [T001] [P] Update `compileSdk` and `targetSdk` to 37 in `app/build.gradle.kts`.
- [x] [T002] [P] Declare `android.permission.ACCESS_LOCAL_NETWORK` in `app/src/main/AndroidManifest.xml`.
- [x] [T003] [P] Define English string resources in `app/src/main/res/values/strings.xml` for rationale dialogs, recovery banners, settings cards, and permission notices.

## Phase 2: Foundation & Architecture
- [x] [T004] [P] Create `NetworkInterfaceUtils.kt` in `za.kilowatch.ultimatefilemanager.network`:
  - Enumerate active IPv4 non-loopback network interfaces across Wi-Fi and wired Ethernet.
  - Compute 24-bit subnet prefix without relying on deprecated `WifiManager` APIs.
  - Provide network connectivity state checks.
- [x] [T005] [P] Create `LocalNetworkPermissionManager.kt` in `za.kilowatch.ultimatefilemanager.network`:
  - Gate runtime checks with `Build.VERSION.SDK_INT >= 37` (bypassed on Android < 37).
  - Provide loopback/localhost exemption (`127.0.0.1`, `localhost`, `::1`).
  - Provide `checkOrRequest(...)` with lifecycle-aware Activity Result launcher binding.
  - Handle permanent denial ("Don't ask again") with direct navigation to `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`.

## Phase 3: UFMStandard Dialogs & UI Components
- [x] [T006] [P] Create `dialog_local_network_rationale.xml` (mobile) conforming strictly to `UFMStandard`:
  - Background `@drawable/bg_dialog_glass` with transparent window.
  - 56dp squircle hero badge (`@drawable/bg_btn_icon_frosted`) with `@drawable/ic_cloud` / `@drawable/ic_lightning`.
  - Explanation card with `@color/mobile_glass_card` and `@color/mobile_glass_stroke`.
  - Vertically stacked buttons: Primary filled `MaterialButton` (`Widget.Material3Expressive.Button`, 50dp, 12dp radius) on top, secondary TextButton below.
- [x] [T007] [P] Create `dialog_local_network_rationale_tv.xml` (TV) conforming strictly to `UFMStandard`:
  - Fixed 650dp width (`@dimen/tv_dialog_standard_width`), background `@drawable/bg_dialog_glass`.
  - D-pad focusable yellow primary button (`@drawable/selector_tv_button_yellow`) and secondary button (`@drawable/selector_tv_button`).
- [x] [T008] Create `item_banner_local_network_permission.xml` for inline recovery banner in network browser views.

## Phase 4: Wireless ADB Suite Integration
- [x] [T009] [P] Update `AdbDeviceDiscovery.kt` in `za.kilowatch.ultimatefilemanager.network`:
  - Replace `WifiManager` IP parsing with `NetworkInterfaceUtils.getSubnetPrefix`.
  - Gate network scan execution with `LocalNetworkPermissionManager.hasPermission`.
- [x] [T010] [P] Update `AdbPairingActivity.kt` and `AdbPairingTvActivity.kt` in `za.kilowatch.ultimatefilemanager.ui`:
  - Register `ActivityResultLauncher<String>` for local network permission.
  - Gate "Scan Devices", "Connect", and 6-digit PIN pairing actions with `LocalNetworkPermissionManager.checkOrRequest`.
- [x] [T011] Verify and safeguard `AdbSessionForegroundService.kt` and `AdbRemoteForegroundService.kt`:
  - Validate foreground process start checks before service promotion.
  - Verify ongoing notification with instant one-tap "Disconnect" action.
  - Ensure clean teardown on task dismissal from Recents or permission revocation.

## Phase 5: Storage Protocols & Discovery Integration
- [x] [T012] [P] Update `SmbDiscovery.kt` and `NfsDiscovery.kt`:
  - Replace `WifiManager` subnet discovery with `NetworkInterfaceUtils.getSubnetPrefix`.
  - Gate TCP probe loops with `LocalNetworkPermissionManager.hasPermission`.
- [x] [T013] [P] Update `NetworkBrowserActivity.kt` and `NetworkBrowserFragment.kt`:
  - Register `ActivityResultLauncher<String>` for local network permission.
  - Protect share opening and discovery contextually with `LocalNetworkPermissionManager.checkOrRequest`.
  - Show and hide inline recovery banner based on permission denial/grant state.
- [x] [T014] Update `WebShareServer.kt` and `PairingServer.kt`:
  - Verify local network permission before binding network server sockets.
  - Display localized error dialog/toast if initiated without permission.

## Phase 6: Settings & Legal Policy Integration
- [x] [T015] Add `cardLocalNetworkPermission` to `activity_settings.xml` and `activity_settings_tv.xml` in the Network Settings section matching existing card layouts.
- [x] [T016] Wire `cardLocalNetworkPermission` in `SettingsActivity.kt`:
  - Display live status badge ("Granted" / "Not Granted / Required").
  - Handle card and button click to trigger permission request or navigate to OS App Info settings.
- [x] [T017] [P] Update in-app policy strings in `app/src/main/res/values/strings_policy.xml`:
  - Add `ACCESS_LOCAL_NETWORK` to `pp_network_perm_labels` and its description to `pp_network_perm_details`.
  - Add `Local Network Access (Android 17+)` to `tc_permission_labels` and its description to `tc_permission_details`.
- [x] [T018] [P] Update website legal documentation:
  - Update `F:\Share\UFM\WEBSITE\UFMPrivacyPolicy.html`: Add `ACCESS_LOCAL_NETWORK` entry under Network Permissions table.
  - Update `F:\Share\UFM\WEBSITE\UFMTerms.html`: Add Local Network Access disclosure in Section 05 Permissions table.

## Phase 7: Verification, Translations & Build
- [x] [T019] [P] Compile release/debug APKs with `./gradlew assembleMobileGoogleDebug` to verify clean compilation with API 37 SDK.
- [x] [T020] Run `python -u translate_strings.py verify` to sync all new English strings across all locale translation files.
