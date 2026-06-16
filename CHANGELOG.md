# Changelog

All notable changes to **Ultimate File Manager Pro (FOSS Edition)** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.5.3] — 2026-06-16

### Security
- Vault PIN and recovery code now use PBKDF2-HMAC-SHA256 (260k iterations, random 16-byte salt) instead of bare SHA-256, and are stored in hardware-backed EncryptedSharedPreferences. Existing hashes are silently migrated on first successful PIN entry after upgrade.
- Vault metadata.json fields (display name, original root path, file paths) are now encrypted using AES-GCM via VaultCrypto. Existing vault entries are silently re-encrypted on first vault unlock after upgrade, with backup safety verification.
- Recovery code clipboard now auto-clears after 60 seconds. Timer resets on re-copy and is cancelled when the dialog is dismissed.
- Added per-IP brute-force protection to WebShare `/verify` endpoint — 5-second delay after 5 failures, HTTP 429 after 10 failures, with a 15-minute lockout window. Counter resets on successful PIN entry.
- WebShare server now binds to the active LAN IP instead of `0.0.0.0`, reducing exposure on untrusted networks.
- Session PIN cookie now has the `HttpOnly` flag, preventing JavaScript access.
- Removed session token from download URL query parameters. Added a short-lived (60s) single-use `/api/download-ticket` endpoint for browser-initiated downloads, preventing token leakage via browser history, logs, and referer headers.
- Replaced `mutableMapOf` with `ConcurrentHashMap` for `zipJobs` and `xapkJobs` to prevent `ConcurrentModificationException` from concurrent access.
- Fixed `isLanOrLocalhost()` to resolve hostnames to IPs via DNS before applying LAN range checks, preventing bypass via crafted `.local`/`.lan` hostnames.
- TLS certificate fingerprint log now guarded by `BuildConfig.DEBUG`.
- Fixed DLNA rate-limit bucket collision — token buckets are now keyed by both IP and endpoint type (SSDP, HTTP_BROWSE, HTTP_STREAM), preventing SSDP rate limits from being bypassed via other endpoint types.
- Replaced hardcoded backup encryption key with optional user-chosen password protection. Export now offers PBKDF2-HMAC-SHA256 (260k iterations) + AES-256-GCM encryption, or plain JSON for passwordless cross-device transfers. Old `.UFMConfig` files remain importable. Import auto-detects format and prompts for password only when needed, with a 3-attempt retry limit.

## [1.5.2] — 2026-06-16

### Added
- New features across multiple daily builds (160626-1 through 160626-7):
  - Enhanced storage browser with additional volume management capabilities
  - New vault functionality for secure file management
  - Improved network share manager with better connection handling
  - New category-based file browsing view
  - SAF (Storage Access Framework) info screen for storage permission guidance
  - Rate Us screen with integrated user feedback flow
  - Policy viewer screen for displaying terms and privacy information
  - Notification category icons for finer-grained notification control
  - Phone/device category icons for better file-type classification
  - Network share icons for SMB/FTP/WebDAV browsing
  - Additional toolbar action icons (copy encrypt, move encrypt)

### Changed
- Rebuilt Welcome screen header with new background styling
- Updated card selection and progress track drawables for better visual consistency
- Enhanced TV layout support with dedicated gradients and styling
- Improved recovery code dialog layout and presentation
- Updated vault folder picker with TV-optimized layouts
- Refreshed permission card UI for both mobile and TV

### Fixed
- Various stability improvements across the storage engine

---

## [1.5.1] — 2026-06-15

### Added
- Five daily feature updates (150626-1 through 150626-5):
  - New storage event receiver for real-time storage state tracking
  - Vault entry data model and TV-optimized vault entry layout
  - Enhanced permission item model for the onboarding flow
  - Storage bar view component for visual storage usage display
  - TV-focused list item selectors and button theming

### Changed
- Updated UI components for better Android TV compatibility:
  - Vault browser now has a dedicated TV layout
  - TV button selectors, color states, and stroke styling
  - TV list item highlight selectors
  - Status badge icons (required/accent variants)
  - Accent icon circle drawables

---

## [1.5.0] — 2026-06-14

### Added
- Three feature updates (140626, 140626-1, 140626-2):
  - Folder icon drawable for directory representation
  - File icon drawable for generic file representation
  - Back arrow navigation icon

### Changed
- Reorganized project structure with initial icon asset system

---

## [1.4.0] — 2026-06-12

### Added
- Four feature updates (120626 through 120626-3):
  - Lock screen/encryption icon
  - Remote management icon
  - Security-focused UI assets
  - Additional iconography for file operations

---

## [1.3.0] — 2026-06-11

### Added
- Storage manager icons for internal, SD card, and USB storage types
- Media category icons (music, photo/video)
- Installer/APK icon
- Delete and close action icons
- Notification icon
- New badge indicator for recently added features

---

## [1.2.0] — 2026-06-10

### Added
- FOSS Tip Jar screen with GitHub Sponsors integration (replaces Google Play Billing)
- Updated README with comprehensive build instructions and FOSS edition differences

### Changed
- Removed all proprietary binaries and closed-source libraries
- Stripped Firebase Analytics, Crashlytics, and GMS trackers
- Disabled Google Play Billing and in-app reviews
- Removed proprietary cloud storage integrations (Google Drive, OneDrive, Dropbox)
- Configured build variants for FOSS distribution channel

---

## [1.1.0] — 2026-06-10

### Added
- Initial open-source release of Ultimate File Manager Pro under GPL v3
- Dual-pane file manager for Android and Android TV
- Core file operations (copy, move, rename, delete, share, paste)
- Search functionality across storage volumes
- Storage browser with browsable volume list
- File analyzer tool
- Theme customization engine
- Icon customization with built-in icon pack
- Toolbar icon personalization
- Settings management with full backup/restore (export/import)
- Dual layout system (mobile + TV) for all activities
- Edge-to-edge display support
- Multi-language support via `attachBaseContext` locale management

---

## [1.0.0] — 2026-06-10

### Added
- Initial commit of UFM Pro FOSS Edition to GitHub
- Core application framework with Gradle build system (Kotlin)
- Firebase integration for messaging (FOSS-safe subset)
- Primary navigation structure with `StorageBrowserActivity`

---

[1.5.2]: https://github.com/Kilowatch/UltimateFileManagerPro/releases/tag/v1.5.2
[1.5.1]: https://github.com/Kilowatch/UltimateFileManagerPro/releases/tag/v1.5.1
[1.5.0]: https://github.com/Kilowatch/UltimateFileManagerPro/releases/tag/v1.5.0
[1.4.0]: https://github.com/Kilowatch/UltimateFileManagerPro/releases/tag/v1.4.0
[1.3.0]: https://github.com/Kilowatch/UltimateFileManagerPro/releases/tag/v1.3.0
[1.2.0]: https://github.com/Kilowatch/UltimateFileManagerPro/releases/tag/v1.2.0
[1.1.0]: https://github.com/Kilowatch/UltimateFileManagerPro/releases/tag/v1.1.0
[1.0.0]: https://github.com/Kilowatch/UltimateFileManagerPro/releases/tag/v1.0.0
