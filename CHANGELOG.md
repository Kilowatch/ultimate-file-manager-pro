# Changelog

All notable changes to **Ultimate File Manager Pro (FOSS Edition)** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.7.8] — 2026-07-30

### Fixed
- Fixed a crash (NullPointerException in `PdfViewerActivity.onCreate`) when opening PDF files on Android TV devices. The TV layout has no `HorizontalScrollView` wrapper, so `findViewById(R.id.pdfHScrollView)` returned null and the non-null assignment crashed. The view is now treated as nullable and all horizontal-scroll operations are guarded, so the TV PDF viewer opens and scrolls correctly.
- Fixed an ANR (App Freeze) at `HardwareRenderer.nSetStopped` during activity stop traversals by removing explicit hardware layer types on RecyclerView containers during transitions and automatically canceling view animators on window detachment.
- Fixed an ANR (App Freeze) during view animation end callbacks (`AnimatorListener.onAnimationEnd`) by offloading adapter list updates, layout transition scheduling, and text scrolling steps to the main thread message queue.
- Fixed a false-positive ANR (App Freeze) report when the main thread is blocked on a synchronous binder call to the Android system server with no app frames on the stack (e.g. `ActivityThread.createBaseContextForActivity` → `ActivityClient.getDisplayId` during activity launch). The `AnrWatchdogThread` now treats a pure-framework stack whose top frame is `BinderProxy.transact`/`transactNative` as a system-side wait and resets its heartbeat instead of writing a report, in addition to the existing idle-looper (`nativePollOnce`) filter.

## [1.7.7] — 2026-07-29

### Added
- Added mobile-only feature to set selected image files directly as home screen or lock screen wallpaper with confirmation dialogs, supported across local, network, and cloud storage files.

### Changed
- Updated `UFM-Windows` license declaration, badge, and contribution guidelines to GPL-3.0 to align with the main FOSS repository.

### Fixed
- Fixed grid view item size inconsistency between portrait and landscape modes on mobile devices.
- Fixed folder navigation transition animations being bypassed in non-indexed local folders, network activities, and network fragments when enabled in settings.
- Prevent `IllegalStateException` crash when fragment is detached during view animation end callbacks.
- Fixed an ANR (App Freeze) on Android TV devices during SSDP discovery by implementing 10s TTL subnet caching in DlnaSsdpEngine and FileServer.
- Fixed Advanced Sync to SMB shares creating a duplicate subfolder (e.g. `/Media/Media`) instead of syncing to the selected destination. The share name was being counted twice because `NetworkBrowserActivity` encodes it as the first segment of the path and `SmbShareClient` was independently extracting it again. A `syncRemotePath` correction now strips the leading share-name segment for standard SMB shares only; all other protocols (SFTP, FTP, WebDAV, NFS, online storages) are unaffected.
- Fixed Advanced Sync to SMB subfolders (e.g. `/Media/Movies`) producing no file transfers and no notification. The resolved path did not exist on the server, so the listing returned empty and the sync silently succeeded without copying anything.
- Fixed ANR freeze when connecting to SMB servers or browsing server shares by moving blocking TCP socket connections outside session pool monitor locks and parallelizing server share accessibility probes.
- Fixed ANR (App Freeze) on Android TV (Google Chromecast) and mobile caused by MediaStore change notifications flooding the main thread looper, by offloading `ContentObserver` callbacks to a background `HandlerThread`.
- Fixed `IllegalArgumentException` crash (`Authenticator combination is unsupported on API 29: BIOMETRIC_STRONG | DEVICE_CREDENTIAL`) when initiating settings transfer authentication on Android 10 (API 29) devices.
- Fixed false-positive ANR (App Freeze) reports when waking from device deep sleep or Doze mode by switching `AnrWatchdogThread` to use `SystemClock.uptimeMillis()` and filtering idle `nativePollOnce` looper stack states.
- Fixed `NetworkOnMainThreadException` crash during SSDP discovery and UDP multicast packet transmission by offloading all `DatagramSocket.send` calls in `DlnaSsdpEngine` to a dedicated background executor.
- Fixed potential `NullPointerException` on Ethernet-only Android TV devices (such as UGOOS AM8) when initializing local network pairing by safely retrieving `WifiManager` with fallback handling when Wi-Fi hardware is unavailable.
- Fixed an ANR (App Freeze) in VaultActivity during PIN verification, PIN creation, PIN migration, and recovery code verification by offloading 260,000-iteration PBKDF2 key derivation off the main thread to background coroutines.
- Fixed WebDAV media file playback failing with a "Source Error" in both internal UFM player and external players (VLC, MPV). Fixed XML parser tag case-sensitivity for camelCase tags like `<d:getContentLength>` from Nextcloud, ownCloud, Apache, and IIS servers; added multi-stage size resolution (`HEAD` -> `PROPFIND` Depth:0 -> `GET Range: bytes=0-0`); and prevented zero-byte file size capping from causing instant EOF at offset 0.

## [1.7.6] — 2026-07-27

### Added
- **Crash & ANR Detection & Reporting System**: Automatically captures uncaught crashes and main-thread ANR freezes. Presents a prompt on app restart to submit structured crash/ANR details to support (or delete locally on skip), with layout support for both Mobile and Android TV.

### Security
- **Pinned Netty to 4.1.136.Final** to patch 20+ CVEs in the transitive Netty dependency (bundled via Ktor 3.5.0). Fixes CVE-2026-50010 (High, hostname verification bypass), CVE-2026-55831 (High, SPDY heap overflow), CVE-2026-55833 (zip bomb), CVE-2026-56745 (memory exhaustion), and ~16 other CVEs. No code changes required — Gradle conflict resolution picks the pinned version.

### Fixed
- Fixed image viewer edge-to-edge layout not being applied correctly in landscape orientation, causing content to bleed under system bars on the sides. Fixed toolbar being visually cut off when rotating back from landscape to portrait.
- Fixed app crash when opening the Slideshow or UFM Media Player from a folder containing a large number of files (e.g. DCIM/Camera with 5000+ items). The crash was caused by Android's 1 MB Binder IPC limit being exceeded when serialising the full playlist into an Intent. Playlists are now passed via an in-memory cache, with a legacy fallback for smaller lists and an on-device folder re-scan as a last resort.
- Fixed app freeze when pressing the paste FAB with a large number of files on the clipboard (e.g. 5000+ items). The freeze was caused by the clipboard sheet's RecyclerView being placed inside a `NestedScrollView` with wrap-content height, which disabled view recycling and forced all items to be laid out synchronously on the main thread. Fixed by giving the RecyclerView a fixed height so recycling is restored, and by moving the clipboard entry list construction off the main thread. All files remain visible and scrollable. Applies to local, network, and online clipboard operations on both Mobile and TV.

## [1.7.5] — 2026-07-26

### Added
- **Move Files Out of Archives**: Move or delete individual files and folders directly from within ZIP and 7Z archives without full archive extraction (Mobile & TV).
- Added Help & Support section to the main menu with Report Bug, Feature Request, and General contact forms (Mobile & TV). Submissions are sent via SMTP email with optional file attachments and auto-captured device info.

### Fixed
- Fixed external subtitle files not being recognised by external players (Vimu, MX Player, VLC, etc.) when opening video files from network shares. UFM now scans the same network directory for companion subtitle files matching the video name and passes them directly to the external player via standard subtitle extras. Applies to all network share types (SMB, SFTP, NFS, FTP, WebDAV, and paired TV shares). The built-in UFM player also now detects and loads companion subtitles when playing video from a network location (Mobile & TV).

## [1.7.4] — 2026-07-24

### Added
- Added modern directional folder navigation transition animations with staggered item cascading on mobile.
- Added custom application-wide window transition styles for activity navigation.
- Added "Enable Folder Transitions" setting toggle directly on the main App Settings screen (mobile only, enabled by default, hidden on TV).

### Changed
- Added smooth layout morphing transitions when toggling view modes (List, Grid, Compact), changing list sizes (Small, Medium, Large, Extra Large), or changing grid column counts across main menu storage tiles and file browsers.

### Fixed
- Fixed NFS server connection timeouts on servers using dynamic or non-20048 mountd ports (such as HaneWin NFS) by allowing standard Portmapper lookup on port 111 with fallback for firewalled setups.
- Optimized NFS auto-negotiation to attempt NFSv3 first before falling back to NFSv4 cascades.
- Fixed NFS permission denied errors (ret=-13 / EACCES) by using native UID/GID binding with automatic retry fallback for servers requiring explicit AUTH_SYS headers.

## [1.7.3] — 2026-07-22

### Added
- Added "Record Screen" option below "Take Screenshot" for paired TV devices on mobile featuring audio mic toggle, max duration selector (1m/3m/5m/10m), countdown clock, and auto-export to Movies/Recordings.

### Fixed
- Fixed an `ExceptionInInitializerError` when opening `.xls` spreadsheets in release builds by preserving Apache POI HSSF record classes and `sid` fields in R8 ProGuard rules.
- Fixed an `UninitializedPropertyAccessException` (`lateinit property prefs has not been initialized`) in `HiddenFilesManager` by implementing safe lazy auto-initialization and fallback guards for `prefs` and `dao`.
- Fixed an `UninitializedPropertyAccessException` (`Exception jr3`) crash in `NetworkBrowserFragment` and `FileBrowserFragment` by making view bindings null-safe across mobile, TV, and compact twin-window layout variants.
- Fixed `ClassNotFoundException` (`HlsMediaSource$Factory`) crash in `UFMPlaybackService` when streaming or playing HLS (`.m3u8`), DASH, or RTSP media files by adding Media3 format extension dependencies and ProGuard keep rules.
- Fixed missing "Tools" FAB and converted "Select All" into a standalone `ExtendedFloatingActionButton` matching the exact size and style of "Tools". Aligned both FABs in twin window mode (stacked vertically in center for vertical split, side-by-side at bottom center for horizontal split).
- Fixed application crash when opening pane 1, pane 2, or standard browser window for non-existent local directory paths, removed SD cards/USB drives, or deleted network shares by falling back safely to Internal Storage root.
- Fixed main-thread inflation lag and ANRs during file list scrolling by using lightweight `AppCompatCheckBox` in RecyclerView item layouts.
- Fixed an `OutOfMemoryError` crash during 7-Zip (`.7z`) archive viewing and extraction by applying dynamic memory limits via `SevenZFileOptions`, catching memory errors safely with user-friendly notices, and enabling `largeHeap` in the application manifest.

## [1.7.2] — 2026-07-21

### Fixed
- Fixed an issue where creating a new file on Mobile or TV (local, network, or cloud) opened the text editor in a non-editable state, requiring closing and reopening the file to edit.
- Fixed UFM Player and Slideshow next/previous navigation and auto-advance to strictly respect the set sort mode (Name, Size, Date, Type) and sort order (Ascending, Descending) of local and network folders on both mobile and TV.
- Fixed SMB connection reliability, infinite loading spinners, and batch delete stalls by adding a 2-second proactive idle session refresh, validating `diskShare.isConnected` upfront, invalidating stale pool entries on attempt 1, and tuning command timeouts.
- Fixed an issue where folder-specific custom sort and filtering was not applied when navigating SMB folders, by ensuring folder preferences are loaded before sorting and rendering directory file listings.
- Fixed an issue in server-mode SMB root listing where returning to discovered shares spammed background folder count requests with empty remote paths.

### Added
- Added Targeted Folder Large Files Finder feature for individual folders on Mobile (Tools section) and TV (Toolbar button) when storage is indexed, launching a dedicated edge-to-edge activity to find files > 10 MB ordered descending by size with 2-state location badges (In Folder vs Subfolder).
- Added Targeted Duplicate Finder feature for individual folders on Mobile (Tools FAB) and TV (Toolbar button) when storage is indexed, launching a dedicated edge-to-edge activity to scan the selected folder and its subfolder tree.
- Added automatic network transition listener (`ConnectivityManager.NetworkCallback`) in `UfmApplication` to instantly purge pooled SMB sessions when switching Wi-Fi networks or reconnecting, preventing stale socket handles.


## [1.7.1] — 2026-07-19

### Added
- Added a list-view density toggle icon (`ic_list_view_custom`) to the Settings header bar on mobile and TV, allowing users to switch settings row size between Small, Medium (default), and Large. Adjusts card row padding, icon circle dimensions, and text sizes dynamically.

### Changed
- Duplicate detector upgraded to a two-phase content-hashing pipeline. Phase 1 groups files by the existing 64 KB quick-hash (DB query, no I/O). Phase 2 computes a full-file MD5 for each candidate group at analysis time, confirming true content identity regardless of filename — eliminating false positives and detecting copies that differ only in name. Files larger than 500 MB skip the full-hash and are shown in the Duplicates tab with an amber "⚠ Quick match only" badge on both mobile and TV layouts. A "Verifying content…" progress indicator appears at the bottom of the Duplicates tab while the verification pass is running.

### Fixed
- Fixed folder-scoped and global duplicate detection to accurately capture duplicate files located directly inside root directories as well as nested subfolders by normalizing trailing slashes and case-insensitive path comparisons.
- Local video playback no longer shows a buffering spinner or experiences playback delay on initial open, repeat loop transitions, and seeks across the full-screen player, side-by-side player, and slideshow on mobile and TV
- Android 13+ themed icons (Adopt system colors) now render the UFM logo stencil correctly instead of showing a solid colored block. A proper monochrome vector drawable was traced exactly from the official logo reference image and is now referenced by the adaptive icon's `<monochrome>` layer.
- "Keep Both" when pasting a file into the same directory it was copied from (local internal storage, SD card, USB OTG) now correctly creates the renamed duplicate (e.g. `photo (1).jpg`) instead of silently doing nothing. The self-copy safety guard in `TransferConflictHelper` was firing before the unique-name resolution, blocking the operation entirely; it now runs after the new name is generated so it cannot interfere with `KEEP_BOTH`.
- Advanced Sync — Instant Sync no longer silently drops files that arrive in the source folder while a sync is already running. `InstantSyncWatcher` now tracks a pending-trigger flag per profile: if a file-system event fires during an active run, the flag is set instead of enqueuing a duplicate request. When `AdvancedSyncWorker` completes successfully, it calls back into `InstantSyncWatcher.onSyncCompleted()`, which fires exactly one follow-up sync run if the flag is set. Instant sync enqueuing also switched from an unmanaged `enqueue()` to `enqueueUniqueWork(KEEP)` to prevent multiple requests stacking up.

## [1.7.0] — 2026-07-15

### Added
- Created 5 custom vector drawables (`ic_loyalty_ristretto`, `ic_loyalty_espresso`, `ic_loyalty_cappuccino`, `ic_loyalty_latte`, `ic_loyalty_coffee_bag`) for the supporter loyalty buttons.
- Created custom raised fist icon drawable (`ic_fist.xml`) for the header blurb.
- Added support for 5 new Google Play billing SKUs: `tip_ristretto_shot` ($1), `tip_quick_espresso` ($3), `tip_cappuccino` ($5), `tip_full_latte` ($10), and `tip_coffee_bean_bag` ($25).
- Dynamic connection mode selector dialog in Remote Manage screen allowing users to choose between the Windows App Companion and HTTPS Web Server formats.
- Windows App Companion mode UI that hides CA certificate details and displays a clean, un-prefixed Device IP address.
- Windows Desktop Companion download section on the main product website featuring screenshots, feature descriptions, and MSI/EXE/Portable ZIP installer placeholders.

### Changed
- Redesigned the "Fuel the Developer" screen layout for Mobile and TV to match the premium list-based mockup (integrating the first-coffee blurb, community progress bar, vertical 5-tier product list with custom prices, and footer info card).
- Updated BillingManager and SupporterLoyaltyActivity (Google flavor) to support all 5 new billing tiers on Google Mobile & TV while preserving Amazon Appstore compatibility.
- Updated TipCelebrationHelper to support celebration palettes and custom titles for all 5 billing tiers.
- Added FOSS-specific terms &amp; conditions and privacy policy agreement notice on the welcome language screen.
- Automatically accept terms &amp; conditions and privacy policy on language confirmation in FOSS builds (setting both acceptance timestamps in shared preferences), skipping the policy welcome screen and navigating directly to the permissions screen on both mobile and TV layouts.
- Updated README.md and index.html to include Amazon Downloader code `1581139` instructions for easy installation of the official Amazon TV edition.


## [1.6.9] — 2026-07-13

### Added
- Added per-folder **Folder Scope Mode** selector (supporting **Only Root Folder** and **Recursive (Subfolders)** options) to the Sort & Filter sheets on both Mobile and TV, allowing users to automatically propagate sort, filter, and view overrides down nested directory structures.
- Added recursive parent lookup resolvers across Local and Network directory browsers to apply inherited parent overrides and color-highlight sort badges.

### Changed
- Updated in-app Prominent Disclosure and Privacy Policy Section 13b to accurately describe installed application data use across App Manager, Debloater, and Remote File Server features, removing the incorrect "never uploaded" claim previously flagged by Google Play
- Added a one-time Remote File Server-specific disclosure dialog that appears before the server transmits installed app data over the local network, with the `/api/apps` endpoint returning an empty response until the user accepts

### Added
- Added per-folder Sort & Filter scope selector to the Sort & Filter bottom sheet on both Mobile and TV. Users can now choose **Global** (applies to all folders) or **This Folder** (applies only to the current folder). Scope is available across Local, Network (SMB, FTP, SFTP, NFS, WebDAV, DLNA), and Online (OneDrive, Google Drive, Dropbox, S3) storage.
- Per-folder sort settings are stored encrypted (AES-256-GCM via Android Keystore) and keyed by a SHA-256 hash of the folder path, ensuring no path data is exposed in plaintext.
- Added a coloured sort icon badge (accent tint) to the sort button in all browser views when the current folder has an active custom sort override.
- Added **Folder Sort Overrides** management screen (accessible from Settings) on both Mobile and TV, listing all folders with custom sort settings. Users can delete individual overrides or clear all overrides at once.
- Added **Folder Sort Overrides** card to Settings (Mobile and TV layouts).
- Added folder-specific **View Mode** options directly inside the Sort & Filter dialog (visible when the scope is set to "This Folder") on both Mobile and TV layouts, allowing users to save distinct layout styles (List/Grid sizes) per folder.
- Integrated folder-specific View Mode layout loading and saving across Local, Network, and Cloud browser fragments/activities, syncing toolbar view toggle controls to the active folder override.
- Redesigned the Android TV Sort & Filter dialog using a `NestedScrollView` content body with sticky header title/scope selector and sticky footer Apply button to support vertical scrolling for the new options.

### Fixed
- Fixed network video playback length showing 0:00 and failing to load or play in UFM Media Player by correctly initializing the file size in the custom Media3 DataSource (`UfmMedia3DataSource`).

## [1.6.7] — 2026-07-09

### Added
- Added ability to Pin/Unpin files and folders on both Mobile and TV layouts across Local, Network, and Online storage (pinned items float to the top alphabetically case-insensitive).
- Added paperclip status badges to list, compact, and grid layout item views.
- Integrated Pin/Unpin icons in Toolbar Customization settings, Backup/Restore preferences, and Icon Pack Export categories.

### Fixed
- Fixed false "Network share not found" notification in Advanced Sync firing even when the share is configured and the connection is stable. The issue was caused by a transient repository load failure (process restart or Direct Boot) that made `getById()` return null. The worker now silently retries up to 3 times with WorkManager exponential backoff before giving up, instead of immediately showing an error notification.

### Security
- Fixed unsafe WebView SSL error handler in Box OAuth flow to comply with Google Play Device and Network Abuse policy

## [1.6.6] — 2026-07-09

### Added
- Added "Left-handed FAB mode" setting in Mobile Settings to position the Tools and Paste floating action buttons on the bottom-left instead of the default bottom-right.

### Fixed
- Fixed a NullPointerException crash in SortFilterSheet on Android TV due to missing tag views
- Fixed a NetworkOnMainThreadException crash in UfmDlnaServer's periodic SSDP keep-alive loop by running network calls on a background thread
- Fixed a lateinit property prefs has not been initialized crash in HiddenFilesManager by moving HiddenFilesManager and RecycleBinManager initialization from the background startup thread to the main thread in UfmApplication

## [1.6.5] — 2026-07-07

### Added
- Integrated LGPL-only FFmpeg dynamic compilation fallback for ARM architectures (arm64-v8a, armeabi-v7a) to generate video thumbnails.
- Added JNI fallback frame extraction covering all video formats for local, network (SMB, FTP, SFTP, NFS, WebDAV, DLNA), and cloud (Google Drive, OneDrive, Dropbox, S3) views.
- Added detailed debug diagnostics and GoRoLog logging tracing FFmpeg initialization and thumbnail extraction.
- Configured ProGuard keep rules to prevent obfuscation or stripping of JNI class helpers and C entry symbols.
- Added case-insensitive name-based and path-based filtering rules to hide OS-specific and system-generated metadata and junk files/folders (such as `Thumbs.db`, `desktop.ini`, `$RECYCLE.BIN`, `@eaDir`, and `#recycle`) when "Show hidden files" is disabled. Applied across local and network browser lists, search results, and system file picker interactions (SAF).
- Added Robolectric and AndroidX test dependencies to the unit test suite.
- Added "Show controls on video repeat" option in Settings (on mobile and TV) to enable or disable displaying video player controls when a looping video restarts in Twin Window mode (disabled by default).
- Added a "Retrigger Thumbnails" option for local, network, and cloud storage files. Selecting one or more video files, or any folders, allows users to clear their thumbnail cache and trigger a fresh thumbnail generation (prioritizing FFmpeg extraction first). This is available under "Tools" on mobile and in the selection action bar on TV.
- Added "Retrigger Thumbnails" configuration switch to the "Long Press toolbar icons" Settings list on both mobile and TV.
- Added smart black frame detection and automatic fallback frame extraction (at alternative 15%, 20%, 5%, and 30% time offsets) in `FFmpegThumbnailHelper` to prevent blank or solid black video thumbnails, accompanied by detailed diagnostic logging and FFmpeg internal logging redirection to android logcat.

### Changed
- Scoped to Mobile/Tablet layouts, moved selection toolbar actions (Copy, Cut/Move, Rename, Share, Favorite, Hide, Unhide, Protect, Unprotect, Copy Encrypt, Move Encrypt, Compress, Compress Image, Delete, Properties, and Tags) into a modern, scrolling "Tools" bottom sheet dialog triggered by a new "Tools" FAB. The standalone "Properties" and "Tag" FAB has been removed from the screen on mobile. Only "Select All" remains visible on the screen selection row (centered as a pill in twin-window mode). TV layouts remain completely unaffected.
- Integrated and formatted the "Prominent Disclosure — Installed Application Information" as a dedicated Section 13b in the Privacy Policy screen.
- Simplified the QUERY_ALL_PACKAGES Prominent Disclosure popup dialog (Mobile/TV) into a concise, policy-compliant description that references Section 13b.
- Removed duplicate QUERY_ALL_PACKAGES paragraph from Section 13 (Google Play Policy Compliance) of the Privacy Policy.
- Synced the new Section 13b, Section 13, and dialog strings across all 13 supported languages.
- Updated the default "Video Thumbnail Time" settings percentage to 10% on both mobile and TV (was 0%).

### Fixed
- Fixed folder item totals incorrectly including hidden/junk files in local, network, and online storage lists for both mobile and TV layouts.
- Fixed UFM Media Player continuing to play in the background when exiting via the system back button or TV D-pad back button; it now stops playback entirely.

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
