# Changelog

All notable changes to **Ultimate File Manager Pro (FOSS Edition)** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.6.5] — 2026-07-07

### Added
- Integrated LGPL-only FFmpeg dynamic compilation fallback for ARM architectures (arm64-v8a, armeabi-v7a) to generate video thumbnails.
- Added JNI fallback frame extraction covering all video formats for local, network (SMB, FTP, SFTP, NFS, WebDAV, DLNA), and cloud (Google Drive, OneDrive, Dropbox, S3) views.
- Added detailed debug diagnostics and GoRoLog logging tracing FFmpeg initialization and thumbnail extraction.
- Configured ProGuard keep rules to prevent obfuscation or stripping of JNI class helpers and C entry symbols.
- Added case-insensitive name-based and path-based filtering rules to hide OS-specific and system-generated metadata and junk files/folders (such as `Thumbs.db`, `desktop.ini`, `$RECYCLE.BIN`, `@eaDir`, and `#recycle`) when "Show hidden files" is disabled. Applied across local and network browser lists, search results, and system file picker interactions (SAF).
- Added Robolectric and AndroidX test dependencies to the unit test suite.

### Changed
- Scoped to Mobile/Tablet layouts, moved selection toolbar actions (Copy, Cut/Move, Rename, Share, Favorite, Hide, Unhide, Protect, Unprotect, Copy Encrypt, Move Encrypt, Compress, Compress Image, Delete, Properties, and Tags) into a modern, scrolling "Tools" bottom sheet dialog triggered by a new "Tools" FAB. The standalone "Properties" and "Tag" FAB has been removed from the screen on mobile. Only "Select All" remains visible on the screen selection row (centered as a pill in twin-window mode). TV layouts remain completely unaffected.

## [1.6.4] — 2026-07-05

### Added
- Added a Settings search and filter feature on both mobile and TV layouts.
- Added a pinned settings search bar at the top of the Settings screen.
- Added an enable/disable toggle for the Settings Search Bar (placed as the first option in settings).
- Integrated Settings Search Bar preference with the backup/restore system.
- Added Tags filtering support to Advanced Sync profiles (Mobile only). Include tags and Exclude tags can be configured depending on the sync extension filter type: "All types" displays both Include and Exclude tags, "Only these" displays Include tags, and "Skip these" displays Exclude tags.
- Integrated tag filtering checks into the sync worker execution engine for both local and remote files.
- Created file tags properties dialog, checkable pills selection, custom tag editing, and multiple file tagging support for mobile devices.
- Created tag management settings dashboard with cascade tag deletion and multi-file tagging configuration toggle.
- Added tag-based file list sorting and filtering in the mobile Sort & Filter sheet.
- Added file and folder deletion protection for local, network, and online storages on both mobile and TV layouts.
- Created custom "Protected" (locked shield) and "Unprotected" (slashed shield) icons with padlock details inside.
- Integrated protection actions with the "Long Press Toolbar Icons" settings page allowing user customization.

### Changed
- Changed default NFS protocol version from NFSv3 to auto-negotiate (0) for new network shares.

### Fixed
- Fixed a bug where moving, copying, or renaming tagged files orphaned their tag mappings, causing globally deleted tags to reappear on moved local images. Implemented path migration hooks for single renames, batch renames, copy/move paste operations, and split-pane transfers.
- Fixed a bug in `FileBrowserActivity` where picker mode FABs (such as "Use This Folder" for sync and advanced sync pickers) would temporarily render and then disappear due to `updatePasteFab()` overriding visibility when the directory list finished loading.
- Enabled SMB server-mode (isServer) shares for Smart Sort by implementing dynamic share prefix stripping during list, mkdir, rename, write, delete, exists, and download operations.
- Fixed NFS connection and mount failures on servers with NFSv4.0 disabled (e.g., `-4.0 +4.1 +4.2`) by implementing an NFSv4 minor version cascade fallback mechanism (NFSv4.2 → NFSv4.1 → NFSv4.0 → NFSv3).
- Fixed NFS direct export discovery to support minor version negotiation on servers that disable NFSv4.0.
- Fixed a bug in the Add/Edit Share screen where editing an existing share would clear the reference handle, causing saved changes to write to a duplicate new share rather than updating the original configuration.
- Fixed a bug in the Add/Edit Share screen where performing a connection test on a new SSH share would prematurely write the share configuration to disk (to persist the server fingerprint) before the user explicitly clicked save.

### Changed
- Updated Privacy Policy and Terms &amp; Conditions disclosure for the `QUERY_ALL_PACKAGES` permission (installed application information) to comply with the Google Play User Data policy. The disclosure now explicitly states what data is read (app name, package ID, version, APK size, install/update timestamps, system vs. user-app flag), that it is processed entirely on-device, and that no installed application data is ever transmitted to KiloWatch servers or any third party. Updated in `strings_policy.xml`, `UFMPrivacyPolicy.html`, and `UFMTerms.html`.

---

## [1.6.3] — 2026-07-03

### Added
- Full background media playback — audio and video continue playing when the app is minimised, with a media notification showing play/pause, next, previous controls, and live time display
- Picture-in-Picture mode for video — double-tap the PiP window to return to full screen, with previous, play/pause, and next controls built into the PiP window
- Auto-play next preview showing the upcoming file 5 seconds before the current one ends, with skip and cancel options
- Mini-player bar at the bottom of the file browser when media is playing — shows the current track and lets you control playback without leaving the browser
- Queue drawer in the player with drag-to-reorder, swipe-to-remove, and tap-to-jump to any track
- Audio now-playing screen with album art, title, artist, and album metadata
- Background video mode setting to choose between Picture-in-Picture and audio-only background playback
- New "Include file names containing" filter option in Advanced Sync profile filtering — works as the inverse of the existing "Skip" filter, with Include running first then Skip. Supports comma-separated words, case-insensitive matching, and composes with Skip for fine-grained control.
- Local destination support for Advanced Sync profiles — select internal storage, USB, or SD card as the sync destination alongside existing network shares. All direction modes (Upload, Download, Two-way) work with local destinations, including filtering, move files, and sync deletions.

### Changed
- All audio and video files now open in the UFM Media Player by default with playlist support, replacing the basic viewer
- ExoPlayer upgraded from 1.4.1 to 1.10.1 with the new media3-session module
- Audio focus handling now pauses for calls, ducks for alerts, and auto-resumes after interruptions
- Notification and media playback policy disclosures updated in Privacy Policy and Terms and Conditions
- Settings backup now includes UFM Player preferences

### Fixed
- Threading crash when extracting audio metadata from background thread (player access violation)
- Missing FOREGROUND_SERVICE_MEDIA_PLAYBACK permission causing crash on Android 15
- Notification play/pause icon not updating when playback state changed
- Notification progress bar not rendering on some Android versions (MediaStyle template incompatibility)
- Top-left back button in the player now stops playback entirely; system back continues background playback
- SFTP/SCP folder navigation in NetworkBrowserActivity using relative paths — now uses absolute paths, fixing SSH_FX_NO_SUCH_FILE errors on servers where the session working directory is not /
- File-type filters in NetworkBrowserFragment checking File.isDirectory on the local filesystem instead of NetworkFile.isDirectory, fixing directory visibility on remote shares
- Swallowed CancellationException in NetworkBrowserFragment coroutines that could cause spurious error snackbars on cancelled loads
- Race condition in NetworkBrowserActivity where rapid folder taps could produce stale directory listings from orphaned coroutines
- Available Shares button showing for non-SMB protocols in the Add Share screen (mobile and TV)
- NFS Version selector remaining visible when switching to DLNA in the Add Share screen
- SMB not being explicitly selected as the default protocol on opening Add Share

## [1.6.1] — 2026-06-29

### Added
- Range selection in edit mode: long press a file to set an anchor, then long press another file to select everything between them (local, network, and online storage; mobile and TV)
- Premiumize.me cloud storage support via RClone (API key auth, mobile + TV)
- Box cloud storage support via RClone (OAuth 2.0) — mobile & TV, Google Play & Amazon builds only. Users authenticate through their browser (mobile) or device code (TV).
- RClone provider selector: new scrollable list view with 3 visible items, custom scrollbar, and "Scroll for more providers" hint
- RCloneProviderViewModel for rotation state preservation

### Changed
- RClone provider selector: Test Connection and Save buttons stacked full-width (both filled)
- RClone provider selector: clearing storage name and all fields on provider switch (also applies to TV)
- Network Shares → Add Share: clearing all connection fields when switching share types (applies to mobile and TV)

### Fixed
- Fixed RClone storages failing to load from the Main Menu after a force close by ensuring the clean-up and remote registration sequence matches the Online Storage browser initialization.
- Fixed Box RClone storage creation overwriting the user-defined storage name with the account email address upon authentication completion.
- NFS mounts now default to AUTH_SYS authentication, fixing the "seal broken" / RPCSEC_GSS auth rejection that prevented mounting against standard NFS servers
- RPC AUTH_ERROR/MSG_DENIED replies are now handled as immediate terminal failures (previously surfaced as ~60-second socket timeout)
- Removed the EMC nfs-client-java fallback (libnfs is now the sole NFS backend), eliminating the suspected GSS credential source

### Added
- 5 new differentiated NFS error messages (auth rejection, connection failure, path not found, service unavailable, version mismatch)
- Network diagnostics pre-check (DNS resolution + TCP port 2049 + port 111) runs automatically during connection test
- NFS version selector (Auto / NFSv3 / NFSv4) on the share edit form
- Exportable debug log with human-readable summary for failed mount attempts
- Structured debug logging with ring buffer (last 20 mount attempts recorded)

## [1.5.9] — 2026-06-25

### Fixed
- SMB Server-Mode — all features now correctly populate the share name when establishing connections. Fixes browse navigation, media playback, file operations (compress, scanner, share receiver, backup, batch rename), and SAF picker for server-mode shares.
- Share-name duplication — folder navigation and rename dialogs no longer produce `\\server\ShareName\ShareName\` paths in either the browser or twin window.
- Twin window pane restoration — after closing a video, panes (local, share-mode, server-mode) now restore to their correct folder instead of `/storage/emulated/0`.
- Back navigation in server-mode SMB — pressing back from a subfolder now correctly navigates to the share root before the share list.
- Sync and Smart Sort now gracefully reject server-mode shares with a logged warning instead of crashing.
- Recycle Bin safe-fail for server-mode shares — no longer crashes when attempting trash operations.

### Security
- Added `splitSharePath` guard against empty basePath — throws `IllegalArgumentException` instead of silently passing empty share names, preventing future `connectShare("")` crashes.

## [1.5.7] — 2026-06-21

### Added
- WebDAV random-access file support (`IRandomAccessFile`) — video seeking is now on par with SMB/FTP/cloud shares

### Fixed
- WebDAV video playback via external players (VLC, MX Player) no longer returns HTTP 500 errors
- WebDAV videos can now be played through the built-in UFMPlayer (ExoPlayer) with seeking support
- Custom tile create/edit dialog on TV: the "Show in folder/file pickers" toggle switch now responds to OK/Enter on the remote — D-pad focus reaches the row and pressing toggles the switch.
- Custom tile create/edit dialog on TV: the icon preview now responds to OK/Enter on the remote — pressing opens the built-in icon picker with D-pad navigable grid items.

## [1.5.8] — 2026-06-23

### Added
- New Advanced Sync system — fully independent sync engine alongside existing Folder Sync, with support for upload, download, and two-way bidirectional sync across all storage types (SMB, NFS, FTP, SFTP/SCP, WebDAV, S3, Google Drive, Dropbox, OneDrive)
- Instant sync trigger using FileObserver with configurable per-profile toggle, 5-second debounce, and battery-aware skipping below 15%
- Conflict resolution for two-way sync with four strategies: skip, use newest, keep local, keep remote — conflicts are logged per profile
- Sync deletions with SHA-256 hashed tracking — files deleted from source are removed from destination on next sync, with no plain-text file names written to disk
- RClone cloud storage integration — "RClone" chip in Online Shares > Add Storage, with data-driven provider setup Activity (mobile + TV)
- First RClone provider: Filen (Email, Password, API Key fields) with Test Connection and password-obscured save via rclone RC
- RCloneConfig.kt with config builder functions for all 112 supported RClone storage providers
- RCloneAdd.md reference guide covering .aar rebuild, provider addition, and troubleshooting
- Full RClone cloud storage browsing — list files, create folders, delete, rename, copy, move, upload, and download with the same UI as S3/WebDAV via `RCloneShareClient`
- Move files (cut) option for upload and download directions — source files are deleted after successful transfer, mutually exclusive with sync deletions
- Download subfolders toggle for download direction — recursively fetches files from all subdirectories preserving folder structure
- WiFi-only constraint per profile using WorkManager NetworkType.UNMETERED
- Download subfolders toggle for download direction — recursively fetches files from all subdirectories preserving folder structure
- Schedule intervals as low as 5 and 10 minutes
- File filtering system with three extension modes (all types, only these, skip these), name pattern exclude, file size limits (MB/GB with unit toggle), and file age limits in days — all filters work together in sequence
- Advanced Sync tile in main menu with full icon customization support
- Backup and restore support for Advanced Sync profiles in Settings

### Changed
- Direction selector redesigned as premium toggle chips — Upload and Download in one row, Two-way below
- Schedule type selector redesigned as toggle chips with Manual on its own row
- Conflict resolution redesigned as toggle chips — Skip and Use newest in first row, Keep local and Keep remote in second row
- Source and destination folder picker cards redesigned — horizontal layout with smaller padding and premium styling
- Filter section header changed from "Sync direction" to "Sync filtering"
- All filter fields use consistent label-above-input pattern with MB/GB unit toggle chips for size
- Sync deletions summary text updates dynamically based on selected direction
- Source and destination labels swap dynamically when Download direction is selected

### Fixed
- StorageAdapter isSpecialTile check missing for Advanced Sync tile — now renders as feature tile instead of showing "0 B free of 0 B"
- Toggle switch in profile list not reflecting enabled state — loadProfiles now re-reads from repository after toggle
- Hash tracking for sync deletions not persisted across sync runs — syncedFileHashes now saved to repository after each sync
- Wrong FTP protocol fallback for SFTP/SCP operations — dispatch methods now throw for unsupported protocol types instead of falling through to FTP
- Hardcoded English interval strings in loadProfile replaced with string resources
- Notification permission launcher moved to a field to prevent re-registration on every onCreate
- Package name typo for DeviceUtils corrected from utils to util
- FileObserver recursion not available on compile SDK — uses non-recursive watcher with documented limitation

## [1.5.6] — 2026-06-21

### Added
- Custom tiles: new "Show in folder/file pickers" toggle in the tile edit screen (mobile + TV) that lets users choose which custom tiles appear alongside storage drives when selecting a folder/file destination. Existing custom tiles default to hidden in pickers, preserving the clean all-drives view.

### Fixed
- Feature tiles (Twin Window, Notepad, Document Scanner, Remote, Settings, Apps, etc.) no longer leak into folder/file picker views — all picker modes now show only storage drives, consistent with Auto Backup's Select Folder behavior.
- Custom tile icons now display correctly in folder/file picker mode — TileIconManager overrides are loaded before early-return picker paths submit the tile list.
- Custom tiles in picker mode now propagate picker extras (EXTRA_* flags) through CustomTileActivity to FileBrowserActivity/NetworkBrowserActivity, so the picker FAB appears when navigating into a folder from a custom tile.
- Custom tile child tiles no longer leak into the main picker grid — children are removed from the main list before early-return picker paths submit the tile list.

## [1.5.5] — 2026-06-19

### Added
- Media Player Controls Auto-Hide Duration — new Settings screen (mobile: SeekBar, TV: step cards + Save button) letting users choose how long media player controls stay visible before fading (1–10 seconds, default 3 s). Applies to UFMPlayer, MediaPlayer, TwinWindow player, and Slideshow video playback. New `ic_controls_timeout` vector icon registered in icon customization, backup/restore, and transfer systems.
- Text Viewer: Added text selection and copy-to-clipboard in view mode on mobile, without needing to enter edit mode. Cut/Paste are hidden from the view-mode context menu since the text is read-only.

### Fixed
- "Show hidden files" setting now filters out dot-prefixed files/folders (e.g. `.UFM_Recyclebin`, `.test.txt`) in both local and network/online storage browsers, consistent with standard file manager behavior.
- Auto Backup: Fixed misleading "Selected folder no longer exists" message when no custom folder had been selected yet — now shows a prompt to select a folder instead.
- Auto Backup: Fixed custom backup location being silently cleared when leaving and re-entering the settings page.

### Security
- Upgraded BouncyCastle from 1.83 to 1.84 to fix CVE-2026-5588 (HIGH — `CompositeVerifier` in `bcpkix` accepted an empty signature sequence as valid, which could allow a crafted certificate to bypass signature chain validation in the custom TLS cert import flow).
- Upgraded Ktor server from 3.4.2 to 3.5.0 to pick up Netty ≥ 4.1.135.Final, which addresses CVE-2026-50010 (TLS/SSL verification issue in the embedded Netty engine).
- Upgraded OkHttp from 5.3.2 to 5.4.0 (latest stable).
- Added `FLAG_SECURE` to `VaultActivity` and `VaultBrowserActivity` to prevent the vault PIN entry screen and decrypted file browser from appearing in recent-apps thumbnails and screen capture output.
- ADB Terminal now shows a security warning on each new shell connection reminding the user that the session grants full device access and to disconnect when not in use.

## [1.5.4] — 2026-06-17

### Changed
- Custom tile contents on TV now enter edit mode on long-press (matching main menu behavior), with hide/gear buttons, color picker, and a second long-press showing a reorder option.

### Added
- In-document search for Text Viewer and Spreadsheet Viewer: search bar with case-insensitive substring matching, match highlighting (yellow/light blue), up/down navigation with wrapping, match count display, and search icon indicator. Supports both mobile and TV with full D-pad focus on TV.
- Auto Backup system — new settings screen with enable/disable toggle, selection of what to back up (Settings config and/or Icon Theme), schedule picker (daily/weekly/monthly), and optional password protection. Backups are saved to `Documents/UFM/` and survive uninstall. On fresh install, the app detects existing backup files and offers to restore them (theme first, then settings). Tip jar loyalty data is now included in both manual and auto backups.
- Custom Backup Location — users can now choose a custom save destination for auto-backups (local folder, SD card, USB drive, or network share via SMB/FTP/SFTP/NFS/WebDAV). The folder picker reuses the existing StorageBrowserActivity → FileBrowserActivity flow with a dedicated FAB and confirmation dialog. Falls back gracefully to `Documents/UFM/` when the custom location is unavailable (network down, storage removed).

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
- Replaced hardcoded theme pack encryption key with the same PBKDF2 + AES-256-GCM password protection pattern. Export prompts for a password or allows unencrypted export. Old `.UFMTheme` files remain importable. Import auto-detects format (V1/V2) and prompts for password only when needed, with a 3-attempt retry limit. Both mobile and TV dialogs follow the premium dialog conventions.
- ADB pairing code log statement now guarded by `BuildConfig.DEBUG`.
- Replaced `AcceptAllServerKeyVerifier` with Trust-On-First-Use (TOFU) host key verification for all SSH/SFTP/SCP connections. On first connect, the server's public key SHA-256 fingerprint is captured and stored in the share. Subsequent connections verify the key matches — a mismatch rejects the connection with a clear error. Fingerprints can be cleared per share if the server legitimately changes keys. Auto-clears on host/port edit. Stripped on backup/restore to force re-TOFU on new devices.
- Added canonical path validation to 7z archive extraction. Entries that resolve outside the destination directory (Zip Slip attacks) are now detected and skipped with a warning log, matching the existing protection already present in ZIP and other archive viewers.
- Added canonical path validation to XAPK extraction in the pairing server (same Zip Slip protection as 7z).
- WebShare file-sharing server now uses TLS encryption with a self-signed ECDSA certificate. File transfers and the access PIN are encrypted on the LAN. The certificate fingerprint is displayed on the PIN page for manual verification. Falls back to HTTP if TLS setup fails.
- UDP pairing discovery metadata (device name, UUID) documented as non-sensitive accepted risk — all secrets travel exclusively over the pinned TLS handshake.
- `GoRoLog.d()` and `GoRoLog.i()` now guarded by `BuildConfig.DEBUG`. All debug and info log output across the app is suppressed in release builds. Error and warning logs remain active for crash diagnostics.

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
