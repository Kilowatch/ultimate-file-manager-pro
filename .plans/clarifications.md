# Clarifications: Android 17 (API 37) Platform Compatibility & Local Network Protection

## Status
Answers received — ready for `/tasks`

## Questions and Answers

### UI and Layout
**Q1. How should the permission request flow be triggered when accessing a LAN share on Android 17+?**  
**A:** Contextual prompt on tap (UFM glass rationale dialog followed by OS prompt), plus an inline recovery banner in `NetworkBrowserActivity` if declined so the user has an immediate recovery button.

**Q1b. What UI/UX standard must all dialogs, banners, and buttons follow?**  
**A:** Strict adherence to `UFMStandard`:
- Custom glass background (`@drawable/bg_dialog_glass`), transparent window background (`ColorDrawable(Color.TRANSPARENT)`), styled via `R.style.UFM_Dialog`.
- Embedded buttons inside XML layouts (never `.setPositiveButton()` / `.setNegativeButton()`).
- Vertical button stacking: primary action on top, secondary/cancel below.
- Mobile: Primary `MaterialButton` (`Widget.Material3Expressive.Button`, 48-50dp, 12dp corner radius); Secondary TextButton.
- TV: Paired `dialog_*_tv.xml` layouts (fixed 650dp width) with `@drawable/selector_tv_button_yellow` (primary) and `@drawable/selector_tv_button` (secondary), full D-pad focusability.

**Q2. If the user permanently denies the permission ('Don't ask again'), should the dialog link directly to App Info Settings?**  
**A:** Yes, direct the user directly to the OS App Info / Permission Settings screen (`ACTION_APPLICATION_DETAILS_SETTINGS`) with clear guidance.

**Q3. In `StorageBrowserActivity` (Main Menu) and `NetworkBrowserActivity`, how should saved LAN tiles appear when permission is not granted?**  
**A:** Normal appearance — prompt contextually when tapped (no cluttering warning badges on idle tiles).

### Settings and Navigation
**Q4. Should a status card for Local Network Permission be added to Settings under Network Settings?**  
**A:** Yes, add a dedicated Local Network Permission status card in `SettingsActivity` under Network Settings displaying current status (`Granted` / `Not Granted`) with a button to check/request permission.

### Network Interfaces & Connectivity
**Q5. Should discovery and socket pre-flight checks support both wired Ethernet and Wi-Fi via ConnectivityManager?**  
**A:** Yes, unify pre-flight checks under `ConnectivityManager` to ensure seamless discovery and socket operations on both wired Ethernet (especially on Android TV / set-top boxes) and Wi-Fi.

**Q5b. What specific functionality must be verified and guaranteed on Android 17?**  
**A:** All existing features must remain 100% operational on Android 17, with special emphasis on:
- **Wireless ADB Suite**:
  - ADB Subnet Discovery (Ethernet and Wi-Fi) with `ACCESS_LOCAL_NETWORK` gating.
  - ADB 6-digit PIN Pairing and TLS handshake.
  - ADB TCP socket connection for terminal shell and remote commands.
  - `AdbPushManager` file push and pull transfers.
  - `AdbRemoteControlActivity` and `AdbWifiTransport` input injection.
  - `AdbSessionForegroundService` and `AdbRemoteForegroundService` (`connectedDevice` type) lifecycle stability, notifications, and background task retention.
  - Local loopback (`localhost` / `127.0.0.1`) wireless debugging preserved without requiring LAN permission.
- **Network Storage Protocols**: SMB2/3, NFS, SFTP, FTP/FTPS, and WebDAV discovery, navigation, and streaming.
- **DLNA & Casting**: SSDP multicast discovery, XML descriptions, SOAP commands, and media streaming.
- **Companion & Pairing**: UDP beacons, secure credential exchange, and WebShareServer.
- **Elevated Access**: Shizuku and Porter integration for protected storage (`Android/data`, `Android/obb`).

**Q6. If local network permission is denied when starting `WebShareServer` or `PairingServer`, how should they behave?**  
**A:** Fail gracefully with a localized dialog/toast explaining that local network access is required, without crashing or hanging.

### SDK & Build Configuration
**Q7. What SDK & build rollout approach should be followed?**  
**A:** Immediate: bump `compileSdk` and `targetSdk` to 37 directly in `app/build.gradle.kts` as part of the implementation.

---

## Spec / Plan Impact
- **`spec.md` updated**:
  - FR-01: Confirmed immediate bump of `compileSdk` and `targetSdk` to 37 in `app/build.gradle.kts`.
  - FR-02: Added dedicated Local Network Permission status card in `SettingsActivity` under Network Settings.
  - FR-03 & FR-04: Explicit requirement that `WebShareServer` and `PairingServer` show a clear localized dialog/toast if started without local network permission.
  - NFR-04: Confirmed unified `ConnectivityManager` interface abstraction supporting both wired Ethernet and Wi-Fi for discovery.

---

## Confirmed — Ready for /tasks
All questions have been answered and recorded. The specification in `.plans/spec.md` and clarifications in `.plans/clarifications.md` form the authoritative baseline. Ready to proceed to `/tasks`.
