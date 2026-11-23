# Specification: Android 17 (API 37) Platform Compatibility & Local Network Protection

## Overview
Prepare Ultimate File Manager Pro (UFM) to fully target Android 17 (API 37) while maintaining backward compatibility across Android 8.0 through Android 16 (API 26–36), Android TV, and companion connectivity. The primary functional mandate is adopting Android 17's Local Network Protection (LNP) runtime security model (`ACCESS_LOCAL_NETWORK`), ensuring LAN discovery, network file protocols (SMB, SFTP, FTP, WebDAV, NFS), DLNA/SSDP casting, wireless ADB debugging, and companion pairing operate reliably with contextual user rationales, graceful degradation upon denial or revocation, zero data loss of saved connections, and seamless mobile/TV user experience parity.

---

## User Stories

- **As a user running Android 17**, I want UFM to explain why it needs local network access before prompting me for system permissions so that I understand why my local storage and TV devices require network access.
- **As a user who declines the local network permission**, I want the rest of UFM (local storage, cloud storage, zip/archive tools, vault encryption, settings) to remain fully functional without crashes, persistent nag dialogs, or infinite loading spinners.
- **As a power user with saved network shares (SMB, SFTP, NFS, WebDAV)**, I want all my server bookmarks, saved credentials, and custom configurations preserved untouched even if local network permission is temporarily missing or revoked.
- **As an Android TV user**, I want full D-pad navigable dialogs and rationale screens when connecting to LAN storage, pairing with mobile devices, or casting DLNA media, including on wired Ethernet connections.
- **As a developer using Wireless ADB and Session Services**, I want uninterrupted background ADB sessions with persistent foreground service notifications and clean recovery if Wi-Fi disconnects or reconnects.
- **As a user who revokes local network permission in OS Settings while UFM is active**, I want in-flight LAN operations to fail gracefully with an actionable error and a clear retry route rather than an unhandled app crash.
- **As a legacy Android user (Android 8.0–16)**, I want the existing network, storage, and discovery behavior to remain completely untouched, with zero unnecessary permission prompts or regression in performance.

---

## Functional Requirements

### FR-01: Target API 37 Baseline & Manifest Alignment
- Update `compileSdk` and `targetSdk` to 37 in `app/build.gradle.kts` while preserving `minSdk = 26` compatibility.
- Declare the required Android 17 Local Network Protection permission (`android.permission.ACCESS_LOCAL_NETWORK`) in the application manifest.
- Ensure the runtime permission check is conditionally executed only when running on Android 17+ (API 37+). On older Android versions (API 26–36), network operations must proceed as before without requesting this permission.

### FR-02: Contextual Permission Request Flow, User Rationale & Settings Card
- Local network permission must **never** be requested aggressively on app launch or onboarding.
- Requests must be triggered contextually when the user initiates a feature requiring LAN traffic (e.g., Scan LAN, Add/Open SMB/NFS share, Connect ADB over Wi-Fi, Pair Device, Cast/Browse DLNA).
- Saved LAN tiles in `StorageBrowserActivity` (Main Menu) and `NetworkBrowserActivity` maintain normal appearance and prompt contextually when tapped.
- If declined, display an inline recovery banner in `NetworkBrowserActivity` with a direct button to grant access.
- If permanently denied ("Don't ask again"), direct the user to the OS App Info / Permission Settings screen (`ACTION_APPLICATION_DETAILS_SETTINGS`).
- **Strict UFMStandard Adherence for All Dialogs**:
  - All rationale dialogs, permission notices, and recovery dialogs must strictly adhere to `UFMStandard`:
    - Container: `android:background="@drawable/bg_dialog_glass"`. TV width fixed to 650dp (`@dimen/tv_dialog_standard_width`).
    - Builder: `MaterialAlertDialogBuilder(context, R.style.UFM_Dialog).setView(dialogView).create()` with `dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))`.
    - Embedded Buttons: All action buttons embedded directly in layout XML (never `.setPositiveButton()` / `.setNegativeButton()`).
    - Button Hierarchy: Primary action stacked on top, secondary/cancel action below.
    - Mobile Buttons: Primary using `@style/Widget.Material3Expressive.Button` (48-50dp height, `?attr/colorPrimary`, 12dp corner radius); Secondary using TextButton style (`@color/mobile_text_secondary`).
    - TV Buttons: Primary using `@drawable/selector_tv_button_yellow` with `@color/tv_button_focused_yellow_text`, D-pad focusable; Secondary using `@drawable/selector_tv_button` with `@color/tv_button_text_selector`.
    - Layout Pairs: Paired layouts `dialog_<name>.xml` (mobile) and `dialog_<name>_tv.xml` (TV).
- Add a dedicated **Local Network Permission** status card in `SettingsActivity` under Network Settings displaying current status (`Granted` / `Not Granted`) with a button to check/request permission.
- Android TV devices must render a TV-optimized 10-foot layout with complete D-pad focus handling and focus restoration.
- All rationale and status strings must be localized across all supported languages.

### FR-03: Denial, Revocation, and Graceful Degradation Handling
- If the user declines the permission:
  - Do not loop or re-prompt immediately.
  - Present an inline, non-blocking empty/error state explaining that local network access is disabled.
  - Provide an explicit "Grant Permission" / "Open Settings" retry button.
  - Ensure discovery progress bars, spinners, and scans immediately terminate and do not hang indefinitely.
- If permission is revoked at runtime via OS Settings while the app is active or suspended:
  - Active network connections and background workers must fail gracefully with a recoverable network permission error.
  - In-flight background transfers must pause or abort safely without corrupting local or remote files.
- Saved connections, host configurations, passwords, and server profiles must remain intact and must never be cleared or deleted due to missing permissions.

### FR-04: Network Protocols & Operations Integration
Local network protection gating and error handling must be applied across all LAN-facing features:
- **SMB Client & Discovery**: Broadcast/mDNS/NetBIOS probing, direct IP connections, share listings, file transfers, and streaming.
- **NFS Client & Discovery**: Portmapper/RPC discovery, mount exports, file reads, writes, and listings.
- **SFTP / FTP / FTPS / WebDAV**: LAN IP resolution, socket connects, directory navigation, and file transfers.
- **DLNA / UPnP / SSDP**: Multicast M-SEARCH packets, unicast responses, XML device description fetching, ContentDirectory SOAP operations, and media streaming.
- **DLNA Server / Receiver**: Local LAN advertising, listening ports, and incoming media streaming requests.
- **Device Pairing & Companion**: UDP broadcast beacons, pairing handshake, encrypted credential/settings exchange, and companion sync over Wi-Fi/LAN. If started without permission, fail gracefully with a localized dialog/toast explaining access is required.
- **Local HTTP / Proxy Services & Web Share**: If `WebShareServer` or local servers are initiated without permission, fail gracefully with a localized prompt. Differentiate strictly between loopback (`127.0.0.1` / `::1`) services and LAN-bound services.
- **Unified Network Connectivity**: Unify all discovery and socket pre-flight checks under `ConnectivityManager` to support both wired Ethernet (especially on Android TV / set-top boxes) and Wi-Fi seamlessly.

### FR-05: Wireless ADB, Remote Control & Foreground Service Lifecycle on Android 17
- **ADB Discovery & Subnet Scanning**:
  - Gated by `ACCESS_LOCAL_NETWORK` permission on Android 17+.
  - Subnet IP resolution must use `ConnectivityManager` and `NetworkInterface` enumeration so discovery works across wired Ethernet (Android TV) and Wi-Fi without relying on deprecated `WifiManager.connectionInfo.ipAddress`.
- **ADB Pairing & Authentication**:
  - Full support for 6-digit PIN pairing and port negotiation over TLS on Android 17.
  - RSA key generation and persistence remain fully compatible and functional.
- **ADB Connection & Operations**:
  - Raw socket connects to target device IPs (ports 5555, 5037, or dynamic wireless debugging ports) must be protected by permission checks before socket creation.
  - Interactive terminal shell, remote package commands, and `AdbPushManager` file push/pull transfers must execute reliably on Android 17.
  - Loopback (`localhost` / `127.0.0.1`) local debugging must be recognized and allowed without requiring LAN permission.
- **ADB Foreground Services (`connectedDevice` type)**:
  - Both `AdbSessionForegroundService` and `AdbRemoteForegroundService` must adhere to Android 17 foreground service requirements:
    - Maintain ongoing low-priority notification with an instant one-tap "Disconnect" action.
    - Validate process foreground state before startup to prevent background start restrictions.
    - Handle task removal (swipe from Recents), network interface transitions (Wi-Fi drop, IP change), and permission revocation with clean notification teardown and socket disposal.
- **ADB Remote Control Mode**:
  - `AdbWifiTransport` and remote input injection via ADB must function seamlessly on Android 17 Mobile and TV.

### FR-06: Storage, Elevated Access & Platform Stability Verification
- Elevated access providers (Shizuku, Porter, root shell) must be verified for operational stability on Android 17 across protected paths (`Android/data`, `Android/obb`).
- Core file indexing, search, removable SD card access, and Storage Access Framework (SAF) workflows must execute without regression on Android 17.
- Framework diagnostics, crash handling, and background thread dispatchers must avoid private framework reflection or fragile assumptions regarding platform internal message queues.

---

## Non-Functional Requirements

- **NFR-01: Performance & Zero Lag**
  - Permission checks must be instantaneous, cached in-memory during active sessions, and never perform blocking I/O on the main thread.
  - Discovery failure on denial must resolve in `< 100ms` without blocking the UI thread.
- **NFR-02: Backward Compatibility**
  - Identical behavior, performance, and UI on Android 8.0 through Android 16 (API 26–36).
  - No behavioral regressions across standalone, tabbed, and twin-window browser modes.
- **NFR-03: Security & Privacy**
  - Adhere strictly to the principle of least privilege: network permission requested only when user requests LAN operations.
  - Credentials and tokens for LAN shares remain securely stored in encrypted preferences regardless of permission state.
- **NFR-04: UI/UX Parity (Mobile & TV)**
  - Mobile dialogs and inline views conform to mobile design standards.
  - Android TV layouts conform to 10-foot TV standards (clear D-pad focus, high-contrast indicators, safe-margin padding).
  - Wired Ethernet connections on TV and set-top boxes must be fully recognized and supported alongside Wi-Fi interfaces.

---

## Edge Cases & Error States

- **Revocation during Active Transfer**: User initiates a 10 GB SMB transfer, switches to OS Settings, and revokes local network permission. App must abort socket gracefully, notify user with a recoverable prompt, and prevent corruption.
- **Ethernet vs. Wi-Fi on Android TV**: TV connected via physical Ethernet cable instead of Wi-Fi must handle permission and discovery requests identically without assuming Wi-Fi-specific APIs (such as `WifiManager`).
- **Airplane Mode / No Network Interface**: Initiating a LAN feature when no network interface is active must report "No Local Network Connection" rather than falsely prompting for permission.
- **Loopback-Only Operations**: Internal proxy or localhost communication (`127.0.0.1`) must function independently without triggering a local network permission prompt.
- **Process Death & Recreation**: App killed by the OS while waiting for the system permission dialog must restore state properly upon recreation without getting stuck in a pending state.
- **"Don't Ask Again" / Permanent Denial**: When system marks permission permanently denied, the rationale dialog must offer a direct shortcut to the application's OS App Info / Permission Settings screen.

---

## Out of Scope

- Proactively rewriting the underlying ADB protocol client or session architecture.
- Replacing or rewriting the native Go engine (`rclone.aar`) or native SMB2/3 engine.
- Rewriting DLNA/SSDP multicast parsers or SOAP clients unless an API 37 platform failure is demonstrated.
- Redesigning Shizuku or Porter IPC protocols.
- Removing any existing network protocols, cloud providers, or storage capabilities.
- Proactively changing indexing or database schemas.

---

## Success Criteria

- [ ] Manifest correctly declares `ACCESS_LOCAL_NETWORK` for Android 17 builds.
- [ ] On Android 17+, contextual rationale dialog appears on first attempt to use any LAN feature (SMB, NFS, SFTP, DLNA, ADB, Pairing).
- [ ] On Android 17+, denying permission leaves all other UFM features intact, cleanly halts discovery/connection attempts, and never loops prompts.
- [ ] On Android 17+, granting permission enables full, uninterrupted LAN browsing, device discovery, transfers, and DLNA streaming.
- [ ] Permission denial or revocation never modifies or deletes saved shares, hosts, or credentials.
- [ ] TV interface is 100% operable via D-pad navigation across all permission and rationale flows on Wi-Fi and Ethernet.
- [ ] Wireless ADB device discovery, 6-digit PIN pairing, and socket connection succeed on Android 17.
- [ ] ADB shell terminal commands and `AdbPushManager` file push/pull operations execute without error on Android 17.
- [ ] Background ADB sessions with `AdbSessionForegroundService` and `AdbRemoteForegroundService` operate reliably without lifecycle crashes or background start exceptions.
- [ ] SMB, SFTP, FTP, WebDAV, NFS, and DLNA discovery, browsing, transfers, and streaming function seamlessly on Android 17.
- [ ] Mobile-to-TV pairing, Web Share, and companion connectivity execute reliably on Android 17.
- [ ] Elevated access (Shizuku, Porter) operates reliably on Android 17 without Binder or permission regressions.
- [ ] Regression testing confirms zero regressions on Android 8.0–16 (API 26–36) devices.
- [ ] All new rationale and status strings are integrated into localization workflows with no hardcoded strings.
