# Specification: Remote TV Download Manager (Mobile, Windows & Linux Companions)

## Overview
Ultimate File Manager Pro (UFM) currently supports standalone Direct HTTP/HTTPS downloads and BitTorrent / Magnet transfers on both mobile and Android TV platforms via `DownloadManagerActivity` and `DownloadTransferManager`. When a mobile device or desktop companion (Windows / Linux) pairs with an Android TV via "Paired TVs" or the desktop companion console, the client application connects over an authenticated TLS 1.2/1.3 encrypted LAN channel (`PairingServer` / `TvShareClient` on Android, Rust `ufm_api_request` on desktop).

This specification defines the complete requirements for the **Remote TV Download Manager** feature across:
1. **Android Mobile**: A new "Download Manager" section integrated into the paired TV root screen in `NetworkBrowserActivity`, opening a dedicated `RemoteDownloadManagerActivity`.
2. **Desktop Companions (`UFM-Windows` & `UFM-Linux`)**: A dedicated "Download Manager" console / modal view in the desktop Tauri + React application.

From either client (Mobile, Windows, or Linux), users can remotely inspect, monitor, configure, and fully manage the TV's Download Manager. Clients can monitor live download progress (%, speeds, ETA, peer/seed stats), pause/resume/cancel transfers, choose between "Remove from list only" and "Delete files & remove", switch the TV download storage location to any internal or external TV mount, toggle TV network policies (such as Wi-Fi only), and dispatch new downloads directly to the TV (Direct HTTP/HTTPS URLs, Magnet links, and `.torrent` files with selective file inclusion).

---

## User Stories
- **US-01 (Remote Discovery & Navigation - Mobile):** As a mobile user connected to a paired TV, I want to see a "Download Manager" entry in the TV root actions list so that I can access the TV's download interface with a single tap.
- **US-02 (Remote Download Monitoring):** As a mobile or desktop user, I want to see the TV's active, paused, queued, and completed downloads with real-time progress percentages, speeds, ETAs, and peer/seed counts so that I know exactly how downloads are progressing without turning on the TV display.
- **US-03 (Remote Transfer Control):** As a mobile or desktop user, I want to pause, resume, retry, or delete transfers on the TV, choosing between removing the item from the list only or deleting the associated files from the TV storage.
- **US-04 (Remote Storage Location Configuration):** As a mobile or desktop user, I want to view the TV's current download directory and available storage space, and change the download directory to any TV storage mount (Internal Storage or connected USB/external drive) using a remote TV folder picker.
- **US-05 (Direct HTTP/HTTPS Download Dispatch):** As a mobile or desktop user, I want to paste or enter a direct download URL on my phone or PC and have the TV download engine fetch it directly to the TV's chosen storage folder.
- **US-06 (Magnet Link Dispatch):** As a mobile or desktop user, I want to paste a BitTorrent Magnet URI on my phone or PC and send it to the TV to start downloading immediately via the TV's BitTorrent engine.
- **US-07 (Torrent File Upload & Sub-file Selection):** As a mobile or desktop user, I want to pick a `.torrent` file stored locally (on mobile or PC), inspect its internal files, select which files to download, and dispatch the transfer payload to the TV.
- **US-08 (Remote Policy & Legal Gate Verification):** As a mobile or desktop user, I want the remote interface to verify that the TV's download manager settings (network policy and legal disclaimer gate) are properly configured and operational.
- **US-09 (Windows Companion Integration):** As a Windows Companion user connected to a paired TV or phone, I want a dedicated Download Manager button in the top navigation to view and manage remote downloads directly from Windows.
- **US-10 (Linux Companion Integration):** As a Linux Companion user (Wayland / X11), I want the same dedicated Download Manager view and capabilities to monitor and dispatch downloads to my paired TV.

---

## Functional Requirements

### 1. Mobile Entry Point (`NetworkBrowserActivity`)
- **FR-01 (TV Root Action Item):**
  - When browsing a paired TV share (`share.type == ShareType.TV` and `currentPath.isEmpty()` on mobile), a new item named **Download Manager** (`DOWNLOAD_MANAGER_PATH = "__download_manager__"`) must be appended to the TV root actions list.
  - The item must display the icon `ic_download`, a title `getString(R.string.download_manager)`, and a descriptive subtitle (e.g., `getString(R.string.tv_download_manager_subtitle)`: "Manage TV downloads, active transfers, and storage location").
  - Tapping this item must launch `RemoteDownloadManagerActivity`, passing `EXTRA_PAIRED_DEVICE_ID`, `EXTRA_SHARE_HOST`, and `EXTRA_SHARE_PORT`.

### 2. Remote Download Manager Activity (`RemoteDownloadManagerActivity` - Mobile)
- **FR-02 (Activity Layout & Visual Hierarchy):**
  - Designed with Material 3 Expressive and glassmorphic card styling matching the UFM Pro design system.
  - **Header Card:** Displays paired TV name, IP address, connection status indicator (Online / Offline), and active network policy (Wi-Fi Only vs All Networks).
  - **Storage Card:** Displays current TV download folder path (e.g., `/storage/emulated/0/Download` or USB path), available free disk space on that mount, and a "Change Location" button.
  - **Transfers Recycler View:** Displays list of active, paused, queued, completed, and failed transfers on the TV.
  - **Empty State View:** Shown when no transfers exist, featuring quick-action buttons for "Add Direct Download", "Add Magnet Link", and "Add .Torrent File".
  - **Action FAB / Menu:** Floating Action Button (or top toolbar icon) to add a new transfer.
- **FR-03 (Real-Time State Polling & Synchronization):**
  - The activity must poll the TV's status endpoint (`/tv/download-manager/status`) periodically (every 1.5–2.0 seconds while the activity is in the foreground / `RESUMED` lifecycle state).
  - Smooth DiffUtil or in-place adapter updates must prevent scroll jumping, screen flickering, or button focus loss during live updates.
  - Polling must pause immediately when the activity moves to `STOPPED` or `PAUSED`.

### 3. Remote Transfer Control (Mobile & Desktop)
- **FR-04 (Transfer Item Actions):**
  - Each item card in the transfer list must display:
    - Transfer title / filename
    - Badge indicator for transfer type (HTTP, TORRENT, MAGNET)
    - State badge (Downloading, Paused, Queued Wi-Fi, Completed, Error)
    - Progress bar with percentage
    - Metrics: downloaded size / total size, download speed (KB/s or MB/s), ETA (remaining time)
    - Peer and seed count indicators for torrent / magnet transfers
    - Error message banner if the transfer is in an ERROR state
  - Action buttons per item:
    - **Clear All / Clear Completed:** A top toolbar or menu option to clear all completed transfers from the list with a single tap (defaulting to keeping files intact on the TV disk).
    - **Pause:** Sends a pause request for the transfer ID to the TV.
    - **Resume:** Sends a resume request for the transfer ID to the TV.
    - **Retry:** Re-triggers an error transfer.
    - **Delete / Cancel:** Displays a confirmation dialog (matching `dialog_transfer_delete_confirm`) giving the user two clear deletion options:
      1. **Remove from List Only (`deleteFiles = false`):** Cancels/removes the transfer from the TV Download Manager queue, but leaves any partial or completed files intact on the TV disk.
      2. **Delete Files & Remove (`deleteFiles = true`):** Cancels/removes the transfer from the TV Download Manager and permanently deletes the associated file or directory from the TV's storage.
      3. **Cancel:** Dismisses the dialog without making changes.

### 4. TV Storage Location Picker & Management
- **FR-05 (TV Storage Exploration & Selection):**
  - Tapping "Change Location" opens an interactive TV directory picker dialog or bottom sheet.
  - The picker queries the TV's root drives via `TvShareClient.listFiles(share, "")` (or desktop `ufm_api_request`) and subfolders.
  - The user can navigate into folders, create a new folder on the TV, and confirm the selection.
  - Once selected, the client dispatches `POST /tv/download-manager/settings` with the new target directory path.
  - The TV validates that the target path exists, is writable, and updates `DownloadPreferencesManager.setDownloadDir()`.

### 5. Adding New Downloads to TV
- **FR-06 (Direct Download Dispatch):**
  - User taps "Add Direct Download" → opens input dialog for HTTP/HTTPS URL, optional custom filename, and optional HTTP headers (User-Agent, Referer, Cookie).
  - Client validates URL format (must begin with `http://` or `https://`).
  - Client dispatches `POST /tv/download-manager/add-url` to the TV.
  - TV preflights storage space and triggers `DownloadTransferManager.startHttpDownload()`.
- **FR-07 (Magnet Link Dispatch):**
  - User taps "Add Magnet Link" → opens input dialog with automatic clipboard detection for `magnet:?xt=urn:btih:`.
  - Client dispatches `POST /tv/download-manager/add-magnet` with the magnet URI and optional target folder.
  - TV initiates metadata resolution and transfers via `DownloadTransferManager.startTorrentDownload()`.
- **FR-08 (Torrent File Upload & Sub-File Selection):**
  - User taps "Add .Torrent File" → opens local file picker (mobile storage or PC desktop file dialog via Tauri).
  - On mobile: parses `.torrent` with `TorrentInfo`, lets user toggle files in `TorrentFileSelectionAdapter`, sends Base64 payload + selected indices.
  - On desktop (Windows/Linux): reads `.torrent` bytes, parses metadata/files, allows file selection, and dispatches Base64 payload + selected indices via `POST /tv/download-manager/add-torrent`.
  - TV writes the `.torrent` payload to a secure cache directory, configures file priorities, and registers the torrent with `DownloadTransferManager`.

### 6. Desktop Companion Integration (`UFM-Windows` & `UFM-Linux`)
- **FR-09 (Desktop Navigation & Action Bar):**
  - When connected to an active paired device, the top navigation header renders a new **Download Manager** action button with the `DownloadCloud` / `Download` icon.
  - Clicking this button opens a responsive modal or slide-over panel: `DownloadManagerModal.tsx`.
- **FR-10 (Desktop State Polling & Remote Management):**
  - When the modal is open, it initiates a 1.5s polling loop calling `ufm_api_request("GET", "/tv/download-manager/status", ...)` via Tauri.
  - Renders:
    - TV Download storage location card with available disk space and a "Change Path" button that lets the user choose any remote directory from the active device's drives/volumes (`loadRemoteVolumes` / `listRemoteFiles`).
    - Network policy badge / toggle (Wi-Fi only vs All Networks).
    - Transfer list with real-time progress bars, download speeds, ETA, connected seed/peer counts, and status badges.
    - Transfer controls: Pause, Resume, Retry, and Delete (with explicit options: "Remove from list only" vs "Delete files & remove").
    - Action buttons to add Direct URL, Magnet link, or browse a local `.torrent` file using `@tauri-apps/plugin-dialog`.
  - The modal stops polling immediately upon closing or when the window is hidden.

### 7. TV Server Endpoints (`PairingServer.kt`)
- **FR-11 (API Endpoints on TV):**
  - All endpoints require standard LAN HTTPS authentication (`X-UFM-DeviceId` and `Authorization: Bearer <token>`):
    1. `GET /tv/download-manager/status`
       - Returns JSON:
         ```json
         {
           "success": true,
           "settings": {
             "downloadDir": "/storage/emulated/0/Download",
             "freeBytes": 45124800000,
             "totalBytes": 64000000000,
             "networkPolicy": "WIFI_ONLY",
             "legalAccepted": true
           },
           "transfers": [
             {
               "id": "uuid-or-hash",
               "title": "Ubuntu-24.04-desktop-amd64.iso",
               "type": "TORRENT",
               "state": "DOWNLOADING",
               "totalBytes": 6124890000,
               "downloadedBytes": 2041630000,
               "progressPercent": 33,
               "speedBytesPerSec": 4512000,
               "etaSeconds": 905,
               "connectedPeers": 42,
               "totalPeers": 128,
               "connectedSeeds": 18,
               "targetFolder": "/storage/emulated/0/Download",
               "errorMessage": null
             }
           ]
         }
         ```
    2. `POST /tv/download-manager/settings`
       - Payload: `{"downloadDir": "...", "networkPolicy": "WIFI_ONLY"|"ALL_NETWORKS"}`
       - Updates TV preferences via `DownloadPreferencesManager`.
    3. `POST /tv/download-manager/action`
       - Payload: `{"id": "...", "action": "pause"|"resume"|"retry"|"delete", "deleteFiles": false|true}`
       - Executes transfer action via `DownloadTransferManager.cancelTransfer(id, deleteFiles)`.
    4. `POST /tv/download-manager/add-url`
       - Payload: `{"url": "...", "filename": "optional", "headers": {"header": "val"}}`
       - Launches HTTP transfer on TV.
    5. `POST /tv/download-manager/add-magnet`
       - Payload: `{"magnetUri": "...", "saveDir": "optional", "selectedIndices": [0, 1, 3]}`
       - Adds magnet transfer on TV.
    6. `POST /tv/download-manager/add-torrent`
       - Payload: `{"torrentBase64": "...", "saveDir": "optional", "selectedIndices": [0, 1, 3]}`
       - Adds torrent file transfer on TV.

---

## Non-Functional Requirements
- **NFR-01 (Security & LAN Containment):**
  - All communication must strictly traverse the existing TLS 1.2/1.3 encrypted HTTPS channel (`LanHttpsClient` on mobile, rustls with SHA-256 fingerprint pinning on Windows/Linux) with device authentication tokens.
  - RFC1918 / loopback address verification (SEC-CRIT-1) must be strictly enforced on all `/tv/download-manager/*` routes.
- **NFR-02 (Performance & Low Latency):**
  - Status polling response time over LAN Wi-Fi must be under 100ms.
  - Transfer state updates must run completely asynchronously without blocking the UI thread on mobile or companion desktops.
- **NFR-03 (Battery & Resource Efficiency):**
  - Polling must only be active when the Download Manager activity/modal is in the foreground.
  - Background polling or leaks when the activity/modal is closed are strictly prohibited.
- **NFR-04 (Cross-Platform Consistency):**
  - The feature set, deletion semantics ("Remove from list" vs "Delete files & remove"), storage selection, and add-download capabilities must remain completely uniform across Mobile, Windows, and Linux companions.

---

## Edge Cases & Error States
- **EC-01 (TV Inactive or Connection Interrupted):** If the TV goes to sleep, changes IP, or loses Wi-Fi connection, both mobile and desktop companions must display a clean "Reconnecting to TV…" status banner with a manual "Retry" button.
- **EC-02 (TV Storage Full or Preflight Failure):** When dispatching a download to the TV, if the target storage has insufficient space, the TV returns HTTP 400 with a detailed error message (`INSUFFICIENT_STORAGE`) and the client presents an explanatory alert suggesting clearing space or changing the download location.
- **EC-03 (External USB Unmounted on TV):** If the configured TV download directory is on an unmounted external drive, the TV server detects this, notifies the client, and prompts the user to select an alternate directory.
- **EC-04 (Duplicate Transfer Added):** If a magnet or URL already active on the TV is added again, the TV returns an informative message ("Transfer already exists") without disrupting current progress.
- **EC-05 (TV Legal Gate Pending):** If the TV has never accepted the first-use download legal disclaimer, the TV status reports `legalAccepted: false`. The mobile or companion UI prompts the user to acknowledge the legal notice for the TV.

---

## Out of Scope
- Downloading torrent/HTTP files to the PC or phone and then streaming or mirroring the incomplete download block-by-block to the TV. All remote downloads are executed natively on the TV hardware.
- Controlling downloads on third-party external apps (Transmission, uTorrent, Aria2, etc.). Only UFM Pro's native download engines are managed.

---

## Success Criteria
- [ ] Tapping "Download Manager" on a paired TV root from mobile opens `RemoteDownloadManagerActivity`.
- [ ] Clicking "Download Manager" in Windows Companion (`UFM-Windows`) or Linux Companion (`UFM-Linux`) opens the remote download management console for the active paired TV.
- [ ] Active and completed transfers on the TV are listed on mobile and desktop companions with real-time %, speeds, ETAs, and peer counts.
- [ ] Pausing, resuming, retrying, and deleting transfers (both "Remove from list" and "Delete files & remove") on mobile or desktop instantly updates the TV engine state.
- [ ] Changing the download storage location on mobile or desktop displays TV storage drives/folders and updates the TV's configured directory.
- [ ] Adding Direct URLs, Magnet links, and `.torrent` files from mobile or desktop starts downloading on the TV.
- [ ] All API communication is authenticated, encrypted, and resilient to transient network dropouts across Mobile, Windows, and Linux.
