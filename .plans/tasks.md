# Task List: Unified Download Manager (Torrent, Magnet & HTTP/HTTPS)

## Phase 1: Build Configuration, Dependencies & Keep Rules
- [x] [T001] [gradle/libs.versions.toml] Add `libtorrent4j = "2.1.0-39"` and library declarations for `libtorrent4j-core`, `libtorrent4j-arm`, `libtorrent4j-arm64`, `libtorrent4j-x86`, and `libtorrent4j-x86_64`.
- [x] [T002] [app/build.gradle.kts] Add `libtorrent4j` dependencies and configure `licensee` plugin to allow BSD-3-Clause and MIT licenses without build failures.
- [x] [T003] [app/proguard-rules.pro] Add minimal, non-blanket R8 Proguard rules for `org.libtorrent4j.swig.libtorrent_jni` and public constructors (`<init>`).

## Phase 2: Legal, Terms, Privacy & Policy Documentation
- [x] [T004] [P] [WEBSITE/UFMPrivacyPolicy.html] Update badge date to `November 2026` and add BitTorrent P2P swarm communications section (public IP visibility, decentralized transfers, instant socket closure on completion, local file sandboxing).
- [x] [T005] [P] [WEBSITE/UFMTerms.html] Update badge date to `November 2026` and add Download Manager Acceptable Use & Non-Piracy Policy section (strict prohibition of copyright infringement, personal responsibility, open-source/legal use mandate).
- [x] [T006] [app/src/main/res/values/strings_policy.xml] Update `policy_last_updated` to November 2026, add Download Manager entry to `tc_features`, add anti-piracy obligation clause to `tc_s4`, and add `libtorrent4j` attribution to `tc_opensource`.
- [x] [T007] [Cleanup] Delete modified keys (`policy_last_updated`, `tc_features`, `tc_s4`, `tc_opensource`) from all 17 translation directories (`app/src/main/res/values-*/strings_policy.xml`) per project translation cleanup policy.
- [x] [T008] [P] [app/src/main/java/za/kilowatch/ultimatefilemanager/ui/policy/LicensesActivity.kt] Register `libtorrent4j` (v2.1.0-39, BSD-3-Clause / MIT) in both `loadLibraries()` and `staticFallback()` registries.

## Phase 3: Main Menu Tile Integration ("Utilities" Category)
- [x] [T009] [app/src/main/res/values/strings.xml] Add string resources: `download_manager_title` ("Download Manager"), `download_manager_subtitle` ("Torrent, Magnet & HTTP downloads"), `download_manager_desc`, and related UI labels.
- [x] [T010] [app/src/main/java/za/kilowatch/ultimatefilemanager/storage/StorageItem.kt] Add `val isDownloadsTile: Boolean = false` property and update copy/equality methods.
- [x] [T011] [app/src/main/java/za/kilowatch/ultimatefilemanager/storage/StorageBrowserActivity.kt] Register `downloads_manager_tile` in `loadStorageVolumes()`, `buildAllKnownTiles()`, and handle click navigation in `onStorageTileClicked()` to launch `DownloadManagerActivity`.
- [x] [T012] [app/src/main/java/za/kilowatch/ultimatefilemanager/storage/StorageAdapter.kt] Map `item.isDownloadsTile` to `MainMenuViewModeManager.CATEGORY_UTILITIES` in `categoryForTile()`.
- [x] [T013] [app/src/main/java/za/kilowatch/ultimatefilemanager/storage/TileOrderManager.kt] Register `item.isDownloadsTile` in `isFeatureTile()` to preserve tile ordering and persistence.

## Phase 4: Canonical FAB Folder Picker Flow
- [x] [T014] [app/src/main/java/za/kilowatch/ultimatefilemanager/storage/FileBrowserActivity.kt] Define `EXTRA_TORRENT_DOWNLOAD_LOCATION_PICKER = "extra_torrent_download_location_picker"`, handle mode in `onResume()` / `applyPickerFabState` to configure FAB icon (`R.drawable.ic_folder`), label (`R.string.torrent_location_picker_fab_text`), and implement `showConfirmDownloadLocationDialog()` returning selected local directory path via `RESULT_OK`.
- [x] [T015] [app/src/main/java/za/kilowatch/ultimatefilemanager/storage/StorageBrowserActivity.kt] Forward `EXTRA_TORRENT_DOWNLOAD_LOCATION_PICKER` intent extra when opening storage volumes from the picker entry point.

## Phase 5: Storage Pre-Flight Validation & Security Sandboxing
- [x] [T016] [app/src/main/java/za/kilowatch/ultimatefilemanager/download/StoragePreflightValidator.kt] Implement filesystem inspection (`/proc/mounts`, `StorageVolume`) for FAT32 / VFAT detection, single-file 4 GB limit verification (`4_294_967_295L`), free usable space calculation with 100 MB safety margin, and path traversal / sensitive directory blacklisting (`/vault`, private app dirs, `/system`).
- [x] [T017] [app/src/test/java/za/kilowatch/ultimatefilemanager/download/StoragePreflightValidatorTest.kt] Unit tests for FAT32 4 GB limit detection, usable space calculation, and path traversal rejection.

## Phase 6: Preferences & Settings Integration
- [x] [T018] [app/src/main/java/za/kilowatch/ultimatefilemanager/download/DownloadPreferencesManager.kt] Create manager for download directory path (default `/storage/emulated/0/Download`), network policy (`WIFI_ONLY` vs `ALL`), and legal gate acceptance timestamp.
- [x] [T019] [app/src/main/java/za/kilowatch/ultimatefilemanager/settings/SettingsActivity.kt] Add `cardDownloadManager` to Settings UI displaying current network policy and legal disclaimer review dialog.

## Phase 7: Download Engines & Service Layer
- [x] [T020] [app/src/main/java/za/kilowatch/ultimatefilemanager/download/TorrentEngine.kt] Create TorrentEngine wrapping `libtorrent4j.SessionManager` configured for strict zero-seeding (`active_seeds=0`, `upload_rate_limit=1`, `seeding_outgoing_connections=false`, forced encryption), alert listener handling `torrent_finished_alert` with immediate pause, `event=stopped` dispatch, DHT announcement stoppage, and `MediaScannerConnection.scanFile()`.
- [x] [T021] [app/src/main/java/za/kilowatch/ultimatefilemanager/download/HttpDownloadEngine.kt] Create OkHttp streaming engine with chunked disk writing, `Range` header resume support, speed and progress calculation.
- [x] [T022] [app/src/main/java/za/kilowatch/ultimatefilemanager/download/DownloadTransferManager.kt] Create transfer orchestrator managing active and completed download tasks, fast-resume state persistence, `ConnectivityManager.NetworkCallback` for auto-resumption when Wi-Fi connects, and pre-flight validation dispatch.
- [x] [T023] [app/src/main/java/za/kilowatch/ultimatefilemanager/download/DownloadService.kt] Create foreground service with `foregroundServiceType="dataSync"`, partial wake lock and Wi-Fi lock, ongoing notification with progress, speed, ETA, and Pause/Cancel actions.

## Phase 8: UI Drawables, Layouts & Dialogs
- [x] [T024] [app/src/main/res/drawable/] Create missing drawables (`ic_link.xml`, `ic_magnet.xml`, progress bar background drawables, status pill drawables).
- [x] [T025] [app/src/main/res/layout/] Create Mobile and TV layouts for Download Manager: `activity_download_manager.xml` and `activity_download_manager_tv.xml` matching `images/layout.jpeg` (Direct Downloads card, Torrent Downloads card, Transfers list, and Download location row).
- [x] [T026] [app/src/main/res/layout/] Create transfer item layouts: `item_download_transfer.xml` and `item_download_transfer_tv.xml` (file icon, file name, size/speed/ETA details, percent pill, green progress bar, pause/resume/cancel controls).
- [x] [T027] [app/src/main/res/layout/] Create dialog layouts: `dialog_download_legal_gate.xml` (UFMStandard glass dialog with mandatory "I Agree" checkbox gating Continue button), `dialog_torrent_inspect.xml` (torrent metadata / file selection tree), and `dialog_direct_download_input.xml` (URL input with clipboard paste).

## Phase 9: Activity Implementation, Legal Gate & Manifest
- [x] [T028] [app/src/main/java/za/kilowatch/ultimatefilemanager/download/DownloadLegalGateHelper.kt] Implement legal gate check and interactive UFMStandard dialog gating access until explicitly accepted.
- [x] [T029] [app/src/main/java/za/kilowatch/ultimatefilemanager/download/DownloadTransferAdapter.kt] RecyclerView adapter for transfer items with progress updates, status pill styling, and D-pad TV focus support.
- [x] [T030] [app/src/main/java/za/kilowatch/ultimatefilemanager/download/DownloadManagerActivity.kt] Main activity implementing `attachBaseContext`, `EdgeToEdge.enable()`, TV D-pad handling, layout switching (Mobile vs TV), FAB folder picker launcher, URL and torrent file import, cellular alert dialog, and transfer list monitoring.
- [x] [T031] [app/src/main/AndroidManifest.xml] Declare `DownloadManagerActivity` with intent filters for `.torrent` files, `application/x-bittorrent`, and `magnet:` links, and declare `DownloadService` with `foregroundServiceType="dataSync"`.

## Phase 10: Verification, Testing & Polish
- [x] [T032] [Unit Tests] Run `./gradlew testMobileGoogleDebugUnitTest` for `StoragePreflightValidatorTest` and `DownloadPreferencesManagerTest`.
- [x] [T033] [Build Verification] Compile debug builds for both flavors: `./gradlew assembleMobileGoogleDebug assembleTvGoogleDebug`.
- [x] [T034] [Translations Sync] Verified all resource files are valid and free of obsolete keys across all 17 translation directories per project policy.
