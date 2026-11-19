# Task List: Remote TV Download Manager (Mobile, Windows & Linux Companions)

## Setup & String Resources
- [x] [T001] Add string resources in `app/src/main/res/values/strings.xml` for Remote Download Manager (e.g., `tv_download_manager_subtitle`, `tv_storage_location`, `change_tv_storage_location`, `clear_completed_transfers`, `add_direct_download`, `add_magnet_link`, `add_torrent_file`, `torrent_subfile_selection`, `remove_from_list_only`, `delete_files_and_remove`, `reconnecting_to_tv`).

## Phase 1: Android TV Backend API (`PairingServer.kt`)
- [x] [T002] Implement `/tv/download-manager/*` routing in `PairingServer.kt` with LAN containment check (`SEC-CRIT-1`) and token authentication check.
- [x] [T003] Implement `GET /tv/download-manager/status` in `PairingServer.kt` to serialize `DownloadPreferencesManager` settings (directory, free/total space, network policy, legal accepted status) and `DownloadTransferManager.getAllTransfers()` into JSON.
- [x] [T004] Implement `POST /tv/download-manager/settings` in `PairingServer.kt` to update download location directory and network policy (Wi-Fi only vs all networks).
- [x] [T005] Implement `POST /tv/download-manager/action` in `PairingServer.kt` to handle `pause`, `resume`, `retry`, and `delete` (supporting `deleteFiles = false` and `deleteFiles = true` via `DownloadTransferManager.cancelTransfer()`).
- [x] [T006] Implement `POST /tv/download-manager/add-url`, `POST /tv/download-manager/add-magnet`, and `POST /tv/download-manager/add-torrent` (Base64 decode, temporary cache file write, and sub-file `selectedIndices` support) in `PairingServer.kt`.

## Phase 2: Mobile Network Client Layer (`TvShareClient.kt`)
- [x] [T007] Define data classes in `TvShareClient.kt`: `RemoteDownloadSettings`, `RemoteTransferItem`, and `RemoteDownloadStatus`.
- [x] [T008] Implement client network methods in `TvShareClient.kt`: `getDownloadManagerStatus()`, `updateDownloadSettings()`, `executeTransferAction()`, `addDirectDownload()`, `addMagnetDownload()`, and `addTorrentDownload()`.

## Phase 3: Mobile Root Action Entry (`NetworkBrowserActivity.kt`)
- [x] [T009] Define `DOWNLOAD_MANAGER_PATH = "__download_manager__"` in `NetworkBrowserActivity.kt`.
- [x] [T010] In `NetworkBrowserActivity.loadDirectory()`, append the Download Manager action item when browsing a paired TV root (`isTvRoot`).
- [x] [T011] In `NetworkBrowserActivity` item click handler, intercept `DOWNLOAD_MANAGER_PATH` and launch `RemoteDownloadManagerActivity` passing device ID, host, port, and TV name.

## Phase 4: Mobile Remote Activity & Layouts
- [x] [T012] Create layout `app/src/main/res/layout/activity_remote_download_manager.xml` with TV header card, storage directory card, transfers RecyclerView, and empty state with quick action buttons.
- [x] [T013] Create layout `app/src/main/res/layout/item_remote_download_transfer.xml` for transfer cards (title, type badge, status badge, progress bar, metrics, seeds/peers, and action icons).
- [x] [T014] Create layout `app/src/main/res/layout/dialog_remote_tv_folder_picker.xml` for exploring TV storage mounts and subdirectories.
- [x] [T015] Implement `RemoteDownloadManagerActivity.kt` with:
  - 1.5s coroutine polling loop during `Lifecycle.State.RESUMED`.
  - Storage location card and interactive TV Storage Explorer dialog (browsing TV partitions and creating folders via `TvShareClient`).
  - Transfers `RecyclerView` with DiffUtil.
  - Per-item controls: Pause, Resume, Retry, and Delete (with dialog options: *Remove from list only* vs *Delete files & remove*).
  - Add Download dialogs: Direct URL, Magnet link, and .Torrent file picker (with local `TorrentInfo` inspection and sub-file checklist).
  - Top toolbar menu: "Clear Completed", "Refresh", and "Wi-Fi Only Toggle".
- [x] [T016] Register `RemoteDownloadManagerActivity` in `app/src/main/AndroidManifest.xml`.

## Phase 5: Desktop Companions (`UFM-Windows` & `UFM-Linux`)
- [x] [T017] Create `DownloadManagerModal.tsx` in `UFM-Windows/src/components/` and `UFM-Linux/src/components/` with:
  - 1.5s polling loop calling `ufm_api_request("GET", "/tv/download-manager/status", ...)`.
  - TV storage location card and remote folder picker.
  - Live transfer cards with progress bars, speeds, ETAs, and peer/seed counts.
  - Transfer actions: Pause, Resume, Retry, and Delete (*Remove from list only* vs *Delete files & remove*).
  - Add Direct URL, Magnet link, and .Torrent file picker (via Tauri file dialog, base64 payload dispatch).
- [x] [T018] Mount `DownloadManagerModal` in `UFM-Windows/src/App.tsx` and `UFM-Linux/src/App.tsx` with a `DownloadCloud` button in the top navigation bar.
- [x] [T019] Add localization keys to `UFM-Windows/src/hooks/translations.ts` and `UFM-Linux/src/hooks/translations.ts`.

## Phase 6: Build Verification & Testing
- [x] [T020] Run compilation and unit tests on Android: `./gradlew assembleDebug testDebugUnitTest`.
- [x] [T021] Run compilation check on desktop companions: `DownloadManagerModal.tsx` and `App.tsx` wired cleanly in `UFM-Windows` and `UFM-Linux`.
- [x] [T022] Verify all edge cases (TV standby/wake lock, storage preflight, unmounted USB, duplicate transfers, deletion semantics).
