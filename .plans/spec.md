# Specification: Download Manager — Torrent, Magnet & Direct HTTP/HTTPS (Zero-Seeding, P2P Transparency & Legal Compliance)

## Overview
Adds a unified native **Download Manager** to **Ultimate File Manager Pro (UFM)** across Mobile and Android TV. The feature is accessible directly from the Main Menu under the **"Utilities"** category as **Download Manager** (*"Torrent, Magnet & HTTP downloads"*).

The dashboard faithfully implements the conceptual layout in [`images/layout.jpeg`](file:///F:/Share/UFM/images/layout.jpeg), providing dual-engine capability:
1. **Torrent & Magnet Downloads**: Full BitTorrent downloading via `libtorrent4j:2.1.0-39` with strict zero-seeding, immediate post-download swarm ejection, and anti-piracy gating.
2. **Direct Downloads (HTTP / HTTPS)**: Native direct file downloading for web links (`http://` and `https://`), enqueued into the same unified active transfers list.

Per user requirements, this specification establishes eight fundamental architectural, regulatory, and security pillars:
1. **Strict Zero-Seeding Policy**: The engine operates exclusively as a downloader (leecher). Outbound upload bandwidth is throttled to minimal protocol overhead during active download, and the torrent is immediately halted upon reaching 100% completion so no data is seeded back to the swarm.
2. **Immediate Post-Download Swarm Ejection & IP Privacy Protection**: The exact millisecond a download reaches 100%, the engine severs all peer sockets, unregisters the user's IP address from trackers via `event=stopped`, stops DHT announcements, and closes network activity. The user's IP address is **never exposed or visible in the swarm once the file is downloaded**.
3. **Hardened Security & Complete File/Folder Isolation (Zero Data Exposure)**:
   - **Zero Inbound Data Access**: The BitTorrent client is isolated exclusively to the designated download folder and will never read, expose, index, or transmit any other file, folder, Vault, cloud account, or network share on the user's device.
   - **Path Traversal & Zip-Slip Defense**: Rigorous sanitization of all filenames and relative paths inside torrent payloads to prevent writing outside the designated download folder.
   - **Protected Directory Blacklist**: Prohibits selecting UFM Encrypted Vaults, app-private database directories, or root/system directories as download destinations.
   - **Protocol Encryption (PE/MSE)**: Forced/preferred protocol encryption to safeguard traffic against ISP inspection and packet manipulation.
   - **Cryptographic Chunk Verification**: Every piece is strictly validated against the cryptographic info-hash before disk commit; bad peers sending corrupted data are automatically banned.
4. **Mandatory Available Storage & Filesystem Pre-Flight Validation**:
   - **Available Free Space Check**: Explicitly inspects `usableSpace` / `StatFs` against the download size (plus a 100 MB system safety buffer). Real-time validation in the pre-download inspection sheet warns the user and disables the download button if space is insufficient. If triggered, a UFMStandard dialog offers a 1-tap "Choose Location" redirect to another drive.
   - **FAT32 4 GB Limit Check**: Detects FAT32/vfat mount points and blocks individual files $\ge$ 4 GB (e.g. an 11 GB file on a FAT32 USB drive) with a UFMStandard dialog prompting for an alternate location.
5. **Network Policy & Dedicated Settings Section (Wi-Fi Only vs. All)**:
   - Default setting is **Wi-Fi Only**.
   - A dedicated **"Download Manager"** card in Settings allows switching between **"Wi-Fi only"** and **"All networks (Wi-Fi, Ethernet, Mobile data)"**.
   - If not on Wi-Fi when a download is requested, an alert popup offers **"Enable Mobile / Settings"**, **"Queue"** (auto-starts once Wi-Fi connects), or **"Cancel"**.
6. **Comprehensive Anti-Piracy & Lawful-Use Governance**: The feature is strictly intended for lawful downloads (open-source software, Linux distributions, public domain archives, Creative Commons media, authorized business datasets). A modern UFMStandard legal disclaimer dialog with a mandatory "I Agree" tick gate must be accepted before any torrent or magnet operations are permitted, and full anti-piracy clauses must be integrated into website and in-app legal policies.
7. **P2P Education & Public IP Address Disclosure**: Prominently explains what Peer-to-Peer (P2P) technology is, clarifies how it differs from traditional client-server downloads, and gives an unambiguous privacy warning that participating in an active BitTorrent swarm necessarily reveals the device's public IP address to other peers during download.
8. **Canonical UFM UX, FAB Folder Picker & Date Updates**: UI directly implements the mobile layout established in [`images/layout.jpeg`](file:///F:/Share/UFM/images/layout.jpeg) and adopts UFM's canonical **Folder Picker Standard** (StorageBrowserActivity → FileBrowserActivity with FAB confirmation), while bumping legal policy dates to `November 2026` across all documents.

---

## User Stories
- **As a user**, I want to tap a dedicated **"Download Manager"** tile in the **Utilities** section of the Main Menu to see and manage all my downloads in one central place.
- **As a user on a limited cellular plan**, I want downloads to default to **Wi-Fi Only** so I don't accidentally burn through mobile data, and if I try downloading on mobile data, I want a clear prompt with a **"Queue"** button that automatically starts downloading as soon as my phone or TV connects to Wi-Fi.
- **As a user who wants downloads anytime**, I want a dedicated **"Download Manager"** section in Settings where I can switch between **"Wi-Fi only"** and **"All networks"**.
- **As a security-conscious user**, I want 100% certainty that participating in a BitTorrent swarm will NEVER expose, leak, or allow remote peers to access any of my personal files, folders, photos, Encrypted Vault, or network credentials.
- **As a user preparing to download a file**, I want UFM to verify that there is enough free space on the destination drive before downloading starts so that my download does not run out of disk space partway through.
- **As a user with low storage space**, I want the pre-download inspection sheet to show a live comparison of required space vs. available space (e.g. *"Requires 4.8 GB — 12.2 GB available"* in green or *"⚠️ Requires 4.8 GB — only 1.2 GB available"* in red), and provide a 1-tap button to pick a different drive (like an external USB drive or SD card).
- **As a user downloading large files (e.g. 11 GB Linux ISO or archive)**, I want UFM to warn me before downloading if my chosen USB drive or SD card is formatted as FAT32 (which cannot store files $\ge$ 4 GB), presenting a clear UFMStandard dialog allowing me to redirect the download to Internal Storage or an exFAT/NTFS drive with a single tap.
- **As a mobile user**, I want a clean, modern Downloads dashboard (modeled after [`images/layout.jpeg`](file:///F:/Share/UFM/images/layout.jpeg)) with side-by-side cards for **Direct Downloads (HTTP / HTTPS)** and **Torrent Downloads (Magnet / Torrent)**, live active transfers, and a single-tap download location selector.
- **As a user downloading web files**, I want to paste direct HTTP or HTTPS URLs (e.g. firmware zips, APKs, ISOs, media files) and have them download in the background with live speed, progress, and pause/resume controls.
- **As an Android TV user**, I want to input or paste a Magnet URL, `.torrent` file, or direct HTTP URL using standard D-pad navigation and download media directly to local storage or an attached USB drive.
- **As a user choosing a download directory**, I want to navigate through internal storage, SD cards, and USB drives and tap the bottom Floating Action Button ("Select as Download Folder") to confirm the target folder using UFM's canonical picker flow.
- **As a privacy-focused user**, I want complete certainty that as soon as a torrent file finishes downloading, my device immediately disconnects from the swarm, unregisters my IP from trackers, and stops broadcasting my IP address entirely.
- **As a compliance-conscious developer and user**, I want crystal-clear anti-piracy terms and an explicit gate requiring users to acknowledge that copyright infringement is strictly prohibited before using the feature.
- **As a user reading the legal policies**, I expect the Terms & Conditions and Privacy Policy (both on the website and inside the app) to display current, accurate effective dates reflecting the latest updates (`November 2026`).

---

## Architecture & Dependency Selection

### 1. Torrent Engine: `libtorrent4j:2.1.0-39` (Option 3)
Wraps Rasterbar `libtorrent` (industry standard C++ BitTorrent engine):
- **Maven Coordinates**:
  ```kotlin
  implementation("org.libtorrent4j:libtorrent4j:2.1.0-39")
  implementation("org.libtorrent4j:libtorrent4j-android-arm:2.1.0-39")
  implementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-39")
  implementation("org.libtorrent4j:libtorrent4j-android-x86:2.1.0-39")
  implementation("org.libtorrent4j:libtorrent4j-android-x86_64:2.1.0-39")
  ```
- **Android 15+ 16 KB Page Alignment**: Precompiled `.so` binaries in `2.1.0-39` are built with modern Android NDK toolchains supporting 16 KB ELF segment alignment (`ALIGN 0x4000`), meeting Google Play targetSdk 36 requirements.
- **R8 / ProGuard Keep Rules** (no blanket wildcards):
  ```proguard
  -keep class org.libtorrent4j.swig.libtorrent_jni { *; }
  -keepclassmembers class org.libtorrent4j.** {
      public <init>(...);
  }
  ```

### 2. Direct HTTP/HTTPS Download Engine
- Native OkHttp/HttpURLConnection streaming engine with chunked buffer writing directly to target file descriptors.
- Supports HTTP `Range` headers for resumable downloads.
- Integrated into the unified `DownloadTransferManager` and foreground service.

---

## Functional Requirements

### FR-01: Main Menu Tile Registration in "Utilities" Category
Add the **Download Manager** tile to the Main Menu according to the repository's strict registration rules:
1. **`StorageItem.kt`**:
   - Add flag `val isDownloadsTile: Boolean = false`.
   - Tile ID: `"downloads_manager_tile"`.
   - Title: `R.string.download_manager_title` (*"Download Manager"*).
   - Subtitle: `R.string.download_manager_subtitle` (*"Torrent, Magnet & HTTP downloads"*).
   - Icon: `R.drawable.ic_download` (or `R.drawable.ic_download_manager`).
2. **`StorageBrowserActivity.loadStorageVolumes()`**:
   - Enqueue `StorageItem(id = "downloads_manager_tile", label = getString(R.string.download_manager_title), ...)` inside the feature shortcut tiles list.
3. **`StorageBrowserActivity.buildAllKnownTiles()`**:
   - Register the tile in this static registry so it renders inside custom category tiles and view modes.
4. **`StorageBrowserActivity.onStorageTileClicked()`**:
   - Clicking `"downloads_manager_tile"` launches `DownloadManagerActivity`.
5. **`StorageAdapter.kt`**:
   - In `categoryForTile()`:
     ```kotlin
     item.isExtractsTile || item.isTwinWindowTile || item.isDownloadsTile -> MainMenuViewModeManager.CATEGORY_UTILITIES
     ```
   - Automatically groups the tile under the **Utilities** header (`main_menu_category_utilities`).
6. **`TileOrderManager.kt`**:
   - In `isFeatureTile()`: include `item.isDownloadsTile` to preserve user tile ordering across restarts.

### FR-02: Downloads Dashboard Layout (Modeled After `images/layout.jpeg`)
The main Download Manager screen implements the visual hierarchy and structure shown in [`images/layout.jpeg`](file:///F:/Share/UFM/images/layout.jpeg):
- **Header**:
  - UFM Standard Toolbar (Mobile: 56dp glass header with 40dp frosted back button; TV: 72dp glass header with TV button selector).
  - Title: *"Downloads"* (or *"Download Manager"*).
- **Top Quick Action Cards (Side-by-Side Grid / Row)**:
  1. **Direct Downloads (HTTP / HTTPS)**:
     - Icon: Download arrow (`ic_download`).
     - Title: *"Direct Downloads"*
     - Subtitle: `HTTP / HTTPS`.
     - Action: Opens dialog to paste a direct HTTP/HTTPS file URL.
  2. **Torrent Downloads (Magnet / Torrent)**:
     - Icon: Magnet icon (`ic_magnet`).
     - Title: *"Torrent Downloads"*
     - Subtitle: `Magnet / Torrent`.
     - Action: Triggers the legal check (if not yet accepted), then opens the "Add Torrent / Magnet URL" dialog or file picker for `.torrent` files.
- **Active Transfers Section**:
  - Section heading: *"Active transfers"*.
  - Unified list displaying both HTTP/HTTPS and Torrent transfers:
    - Leading icon representing file type (`ic_file`, `ic_archive`, `ic_video`, etc.).
    - Title: Filename or torrent name (e.g. *"Example Linux ISO"*).
    - Status badge on top right: e.g. `50%` (green pill), `Queued` (neutral pill), `Paused`, or `Completed`.
    - Live stats row: `2.4 GB / 4.8 GB · 12 MB/s` or `Waiting for peers` / `Queued (Waiting for Wi-Fi)`.
    - Clean horizontal progress bar (accent green styling).
- **Download Location Selector Row (Bottom of Dashboard)**:
  - Formatted as a dedicated clickable card row:
    - Left label: *"Download location"*.
    - Right value: Formatted path (e.g. *"USB Storage / Downloads"* or *"Internal Storage / Downloads"*).
  - Tapping this row initiates the **Canonical FAB Folder Location Picker** (FR-03).

### FR-03: Canonical FAB Folder Location Picker Flow (`folder_picker_standard.md`)
Following UFM's established folder selection architecture ([`.claude/memory/folder_picker_standard.md`](file:///F:/Share/UFM/.claude/memory/folder_picker_standard.md)):
1. **Calling Activity**:
   - Registered via `ActivityResultLauncher` with `ActivityResultContracts.StartActivityForResult()`.
   - Launches `StorageBrowserActivity` with intent extra:
     ```kotlin
     const val EXTRA_TORRENT_DOWNLOAD_LOCATION_PICKER = "extra_torrent_download_location_picker"
     ```
2. **Navigation**:
   - `StorageBrowserActivity` displays all available storage volumes (Internal Storage, Secondary SD Card, OTG USB Drives, and Network Shares).
   - Selecting a volume opens `FileBrowserActivity` (or `NetworkBrowserActivity`) forwarding `EXTRA_TORRENT_DOWNLOAD_LOCATION_PICKER = true`.
3. **FAB Presentation & Confirmation in `FileBrowserActivity`**:
   - In `onResume()` mode-detection block:
     - Sets FAB text: `R.string.torrent_location_picker_fab_text` (*"Select as Download Folder"*).
     - Sets FAB icon: `R.drawable.ic_folder` (or `R.drawable.ic_check`).
     - FAB visibility: `View.VISIBLE`.
     - `setOnClickListener`: Displays canonical `MaterialAlertDialogBuilder(this, R.style.UFM_Dialog)` confirmation dialog:
       - Title: *"Set Download Location"*
       - Message: *"Set '[Folder Name]' as your default download folder?"*
       - Positive Button: Sets `RESULT_OK` with:
         ```kotlin
         putExtra(FileBrowserActivity.RESULT_SELECTED_LOCAL_PATH, currentDir.absolutePath)
         ```
       - Finishes activity and returns to calling screen.
4. **Result Handling**:
   - The calling Activity receives the selected path, saves it to `SharedPreferences` (e.g. `KEY_TORRENT_DEFAULT_DOWNLOAD_PATH`), and updates the **Download location** row label to human-readable volume and relative path (e.g. *"USB Storage / Downloads"*).

### FR-04: Mandatory Storage Pre-Flight Validation (Capacity & Filesystem Checks)
Before initiating any download (Torrent or HTTP/HTTPS), the system conducts a mandatory two-part pre-flight inspection of the destination directory:

#### Part 1: Available Free Storage Check
- **Storage Calculation**:
  - For Direct Downloads: Check `Content-Length` from the pre-flight `HEAD` request.
  - For Torrent Downloads: Calculate the sum of all currently selected files in the multi-file checklist. Deselected files (`Priority.IGNORE`) are excluded from the required space sum.
- **Safety Margin Buffer**: Add a mandatory 100 MB headroom margin to the required space to prevent filling the device to 0 bytes (which risks Android OS SQLite database locking and system UI hangs).
- **Available Space Query**: Query `targetFolder.usableSpace` using `StatFs(targetFolder.path)`.
- **Live UI Feedback (Pre-Download Sheet)**:
  - Dynamically updates as files are selected/deselected:
    - Sufficient space: Text displays in neutral/green: *"Requires 3.4 GB — 14.8 GB available on USB Storage"*
    - Insufficient space: Text displays in prominent red with alert icon: *"⚠️ Requires 3.4 GB — only 1.1 GB available on Internal Storage"*
    - **Disable Button**: The "Start Download" button is disabled whenever required space exceeds available space.
- **UFMStandard Insufficient Space Dialog**:
  - If triggered (or if attempted programmatically/via intent without prior inspection):
    - **Title**: *"Insufficient Storage Space"* (`download_insufficient_space_title`)
    - **Hero Icon**: `ic_warning`
    - **Message**: *"This download requires %1$s, but '%2$s' only has %3$s available.\n\nPlease choose a different location (such as an external USB drive or SD card) or free up space."*
    - **Primary Button**: **"Choose Location"** (`download_choose_location`) → Immediately launches the FAB Folder Location Picker (FR-03).
    - **Secondary Button**: **"Cancel"** (`cancel`) → Aborts the transfer cleanly.

#### Part 2: FAT32 4 GB Single-File Limit Detection
- **Limit Constant**: `FAT32_MAX_FILE_SIZE = 4_294_967_295L` (4 GB minus 1 byte).
- **Filesystem Query**: Inspect destination mount point via `/proc/mounts` and `StorageVolume` metadata to determine filesystem type (`vfat`, `fat32`, `msdos`, `fuse` wrapping FAT32 vs. `exfat`, `ntfs`, `ext4`, `f2fs`).
- **File Size Evaluation**:
  - Check individual file sizes: If destination filesystem is FAT32/vfat **AND** any selected file exceeds `4_294_967_295L` (e.g., an 11 GB file):
    - **Block the download from starting** to prevent partial corruption and mid-transfer disk errors.
    - **UFMStandard Limit Dialog**:
      - Title: *"Storage Limit Detected"* (`download_fat32_limit_title`)
      - Hero Icon: `ic_warning`
      - Message: *"The file '%1$s' (%2$s) exceeds the maximum single-file limit of 4 GB supported by the FAT32 file system on '%3$s'.\n\nPlease choose a different location (such as Internal Storage or an exFAT/NTFS drive) to continue."*
      - Primary Button: **"Choose Location"** (`download_choose_location`) → Immediately launches the FAB Folder Location Picker (FR-03).
      - Secondary Button: **"Cancel"** (`cancel`) → Aborts the transfer cleanly.

### FR-05: Network Policy & Dedicated Settings Section (Wi-Fi Only vs. All)
1. **Dedicated Settings Section in `SettingsActivity`**:
   - Add a clean, separated section card titled **"Download Manager"** (`settings_section_download_manager`) built according to `UFMStandard`.
   - **Network Restriction Option**:
     - Label: *"Network Connection Policy"* (`settings_download_network_policy_title`).
     - Subtitle: Reflects the active mode (*"Wi-Fi only"* or *"All networks (Wi-Fi, Ethernet, Mobile Data)"*).
     - Clicking opens a UFMStandard dialog allowing the user to select between:
       1. **Wi-Fi only** (Default): Downloads strictly on Wi-Fi or wired Ethernet to preserve mobile data allowances.
       2. **All networks**: Allows transfers on cellular data, Wi-Fi, and Ethernet.
   - Preference key: `KEY_DOWNLOAD_NETWORK_POLICY` stored in `SharedPreferences`.
2. **Cellular / Offline Interception & Queuing**:
   - If a download is started while the device is NOT connected to Wi-Fi (and policy is set to "Wi-Fi only"):
     - **UFMStandard Cellular Alert Popup**:
       - **Hero Icon**: `ic_network_cell` or `ic_warning`.
       - **Title**: *"Wi-Fi Connection Required"* (`download_wifi_required_title`).
       - **Message**: *"You are currently not connected to Wi-Fi. Downloads are set to Wi-Fi only to protect your mobile data allowance."*
       - **Button 1 (Enable Mobile / Settings)**: *"Settings / Enable Mobile"* (`download_btn_enable_mobile`) → Navigates to Settings → Download Manager to permit cellular transfer.
       - **Button 2 (Queue)**: *"Queue"* (`download_btn_queue`) → Enqueues the transfer with status `Queued (Waiting for Wi-Fi)`.
       - **Button 3 (Cancel)**: *"Cancel"* (`cancel`) → Dismisses the download action.
3. **Automatic Wi-Fi Resume Listener**:
   - Register a `ConnectivityManager.NetworkCallback` inside the background download engine.
   - The moment an unmetered Wi-Fi connection becomes available, all items in `Queued (Waiting for Wi-Fi)` state automatically transition to active downloading without requiring manual user intervention.

### FR-06: Advanced Security Architecture & Strict File/Folder Isolation
To ensure absolute data protection and prevent any device data from being exposed or accessed by peers in a BitTorrent swarm:
1. **Zero Inbound Data Access (Zero-File-Exposure)**:
   - The BitTorrent engine operates strictly as an isolated downloader for the active torrent's specific payload pieces.
   - **No local file server behavior**: The engine has zero awareness of, and zero access to, any other files, folders, or directories on the device.
   - Peer `REQUEST` messages for any data outside the active torrent's pieces are dropped/rejected at the native session boundary.
   - Files stored in UFM's **Encrypted Vault**, private app databases, cached network credentials, and user media libraries are completely inaccessible to the BitTorrent process.
2. **Directory Traversal & Path Sanitization (Zip-Slip / Torrent-Slip Defense)**:
   - Malicious `.torrent` files can contain crafted filenames (e.g., `../../../../system/...` or `../../vault/...`).
   - Every file path declared in torrent metadata must undergo strict canonical path verification before allocation:
     ```kotlin
     val targetFile = File(downloadDirectory, sanitizedRelativePath).canonicalFile
     if (!targetFile.path.startsWith(downloadDirectory.canonicalPath)) {
         throw SecurityException("Malicious path traversal detected: $sanitizedRelativePath")
     }
     ```
   - Strip leading slashes, backslashes, `../`, `..\\`, hidden dot directories, and symlink references.
3. **Protected Directory Blacklist**:
   - The user cannot set the download directory into sensitive locations:
     - The UFM Encrypted Vault directory.
     - App private data directories (`/data/data/za.kilowatch.ultimatefilemanager/`, `/data/user/0/...`).
     - Root system paths (`/system`, `/vendor`, `/etc`, `/sbin`).
   - If a user attempts to select a blacklisted path, the folder picker rejects it with a UFMStandard security notice.
4. **Protocol Encryption (PE/MSE)**:
   - BitTorrent Message Stream Encryption (MSE / PE) is enforced in `settings_pack`:
     - Outgoing encryption: `pe_settings::forced` (or `enabled`).
     - Incoming encryption: `pe_settings::enabled`.
     - Prevents local network sniffing, ISP packet inspection, and man-in-the-middle piece tampering.
5. **Cryptographic Integrity Verification & Bad Peer Banning**:
   - Every single piece received from the swarm is cryptographically hashed (SHA-1 / SHA-256) and verified against the torrent's root hash before writing to storage.
   - Any peer delivering corrupt, tampered, or mismatched pieces is immediately blacklisted and banned from the session (`ban_peers_with_bad_chunks = true`).
6. **Local Network / Intranet Probing Prevention (SSRF Defense)**:
   - Restrict peer connections to public swarm endpoints; reject connections to private RFC 1918 addresses (`10.0.0.0/8`, `192.168.0.0/16`, `172.16.0.0/12`, `127.0.0.1`) unless explicitly designated by the user, preventing malicious trackers from using UFM to port-scan local LAN routers or smart home devices.

### FR-07: Direct HTTP / HTTPS Downloads Engine
- **URL Input Dialog**: UFMStandard dialog to paste or enter any valid HTTP/HTTPS URL.
- **Pre-Flight Inspection**:
  - Sends a `HEAD` request to query `Content-Length`, `Content-Disposition` (for accurate filename), and `Accept-Ranges` support.
  - Runs Storage Pre-Flight Validation (FR-04) and Network Policy check (FR-05).
  - Displays filename, size, and destination folder.
- **Execution & Background Streaming**:
  - Streams data in background coroutines via the foreground service.
  - Tracks downloaded bytes, speed (MB/s), ETA, and percentage progress.
  - Supports pause and resume if the remote server supports HTTP `Range` headers.
  - Flushes to target destination file; notifies Android `MediaScanner` upon completion.

### FR-08: First-Use Legal, Non-Piracy & P2P IP Disclosure Gate (UFMStandard)
- **Invocation Trigger**: The disclaimer dialog must be presented whenever a user attempts to access the Torrent Downloader feature for the first time (via Main Menu tile, Tools FAB, clicking a `.torrent` file, opening a `magnet:` link, or launching the Torrent Manager activity).
- **UFMStandard Dialog Presentation**:
  - Built using `MaterialAlertDialogBuilder` with `R.style.UFM_Dialog` and canonical frosted glass styling (`bg_dialog_glass` / `dialog_confirm_action_tv`).
  - Mobile & Android TV dual-layout responsiveness: touch-friendly on phones/tablets; crisp focus states and full D-pad remote navigation on Android TV.
  - Distinct hero icon: `ic_shield_check` or `ic_network_privacy`.
  - Title: *"P2P Network Notice & Lawful Use Policy"* (or localized string).
  - **Body Content & Structural Disclosures**:
    1. **What is Peer-to-Peer (P2P)?**:
       - Explains in clear, plain language that BitTorrent is a decentralized **Peer-to-Peer (P2P)** protocol. Unlike traditional downloads from a central website or cloud drive, your device connects directly to other computers and devices ("peers") around the world to download pieces of the file.
    2. **Public IP Address Visibility Warning**:
       - **Prominent privacy disclosure**: In order to connect to swarm peers and transfer file pieces, **your public IP address is broadcast to and visible to other participants in the swarm** during active downloading as a fundamental requirement of the BitTorrent protocol.
       - Recommends that users seeking network anonymity utilize a trusted VPN service.
    3. **Post-Download Privacy Guarantee**:
       - Reassures the user that as soon as the file finishes downloading, UFM immediately severs all peer connections and shuts down swarm communications so your IP address is **no longer visible in the swarm**.
    4. **Complete Local Storage Isolation**:
       - Reassures the user that the downloader is strictly sandboxed to the active torrent; no other files, photos, Encrypted Vaults, or folders on the device are ever shared or exposed.
    5. **Permitted Lawful Uses**:
       - Linux distribution ISOs, open-source software, public domain media, Creative Commons datasets, and authorized personal/business file sync.
    6. **Strict Anti-Piracy Prohibition**:
       - Explicit zero-tolerance warning prohibiting the downloading, distribution, or acquisition of copyrighted movies, music, games, software, or proprietary intellectual property without authorization.
    7. **Zero-Seeding Architecture**:
       - Clarifies that UFM operates exclusively as a download client and will not seed data back to the swarm after download completion.
    8. **Liability Disclaimer**:
       - KiloWatch does not host, curate, or index torrents and assumes no liability for user activities.
- **Mandatory Interactive Tick Gate**:
  - Prominent interactive checkbox/tick: *"I understand that my IP is publicly visible in P2P swarms during download and agree to use this feature solely for lawful, non-copyrighted content."*
  - **Cancel Button**: Closes the dialog, dismisses the operation, and prevents any torrent or magnet action from executing.
  - **Continue Button**: **Disabled and visually dimmed by default**. Dynamically enables only when the "I Agree" checkbox is ticked.
- **Acceptance State Persistence**:
  - When the user ticks "I Agree" and taps "Continue", store the acceptance state and timestamp in `SharedPreferences` (e.g. `torrent_legal_disclaimer_accepted_time`).
  - On subsequent uses, bypass the gate if already accepted. Provide an option in Settings → Legal to review and re-read the policy at any time.

### FR-09: Strict Zero-Seeding & Post-Download Swarm Disconnection (No IP Exposure)
- **Bandwidth Throttling During Download**:
  - Global upload rate limit throttled to minimum protocol overhead (`upload_rate_limit = 1`).
  - Outgoing seeding connections disabled (`seeding_outgoing_connections = false`).
  - Active seeds limit clamped to zero (`active_seeds = 0`).
- **Exact Post-Download Lifecycle (Instant Swarm Ejection)**:
  The millisecond the download reaches 100% or `torrent_finished_alert` fires:
  1. **Immediate Peer Severance**: All active TCP and UDP peer sockets are immediately dropped and closed.
  2. **Tracker De-Registration (`event=stopped`)**: The engine dispatches a final `stopped` event to active trackers, ordering them to purge the user's IP from their swarm peer lists.
  3. **DHT Teardown**: Stop announcing the info-hash on the DHT network.
  4. **State Transition**: The torrent handle is paused and evicted from active networking (`torrent_handle.pause()`). It transitions to status "Completed".
  5. **IP Exposure Termination**: Because all sockets are closed and tracker/DHT announces are halted, **the user's IP address is no longer broadcast, queried, or visible in the swarm**.
- **File Finalization & Disk Flush**:
  - All file streams and piece storage buffers are flushed to disk.
  - Trigger Android `MediaScannerConnection.scanFile()` so new files (videos, audio, documents) immediately appear in UFM and system gallery/media players.

### FR-10: Background Execution & Foreground Service
- Register and launch a dedicated foreground service (`foregroundServiceType="dataSync"`) holding `PowerManager.PARTIAL_WAKE_LOCK` and `WifiManager.WifiLock`.
- Post an ongoing notification displaying active download count/name, aggregate speed (KB/s, MB/s), progress percentage, and **Pause All** / **Cancel** actions.
- Automatically release locks and dismiss the ongoing transfer notification when all active downloads complete or are paused. Upon completion, post a clean single completion notice: *"Download Complete: [File Name] — tap to open"*.

### FR-11: Website Policies — Date Updates, Anti-Piracy & P2P Disclosures
1. **Date Updates**:
   - In [`WEBSITE/UFMPrivacyPolicy.html`](file:///F:/Share/UFM/WEBSITE/UFMPrivacyPolicy.html): Update `<span class="policy-badge">Last Updated: August 2026</span>` → `<span class="policy-badge">Last Updated: November 2026</span>`.
   - In [`WEBSITE/UFMTerms.html`](file:///F:/Share/UFM/WEBSITE/UFMTerms.html): Update `<span class="policy-badge">Last Updated: August 2026</span>` → `<span class="policy-badge">Last Updated: November 2026</span>`.
2. **Website Terms & Conditions ([`WEBSITE/UFMTerms.html`](file:///F:/Share/UFM/WEBSITE/UFMTerms.html))**:
   - Add a comprehensive section on **Download Manager — Acceptable Use & Non-Piracy Policy**:
     - **Explanation of P2P**: Detail what Peer-to-Peer architecture is, contrasting it with standard server downloads, and noting that devices communicate directly with swarm participants.
     - **Public IP Address Warning & Post-Download Disconnect**: Disclose that connecting to a torrent swarm exposes the user's public IP address to other swarm peers during download, but clarify that UFM severs all swarm connections immediately upon download completion.
     - **File Isolation Guarantee**: State unequivocally that the torrent client cannot access, read, or share any existing user files or folders.
     - **Sole Intended Use**: Explicitly define that the Download Manager in UFM is provided solely for downloading lawful, authorized, or public-domain digital assets (such as open-source operating system distribution images, Creative Commons licensed media, and verified non-infringing files).
     - **Strict Prohibition of Piracy**: Strictly prohibit using UFM to download, transmit, cache, or distribute copyrighted materials without express permission from the copyright owner.
     - **User Indemnity & Legal Responsibility**: The user agrees to indemnify and hold harmless KiloWatch, its developers, and affiliates against any copyright infringement claims, penalties, or damages arising from the user's misuse of the download tool.
     - **No Indexing, Hosting or Swarm Affiliation**: KiloWatch does not host, curate, index, track, or verify torrent files, Magnet links, or swarm contents. UFM is an impartial software tool only.
     - **Zero-Seeding Architecture**: Disclose that the software is engineered to minimize peer upload and prevent post-completion seeding.
3. **Website Privacy Policy ([`WEBSITE/UFMPrivacyPolicy.html`](file:///F:/Share/UFM/WEBSITE/UFMPrivacyPolicy.html))**:
   - Add a comprehensive subsection on **BitTorrent P2P Communications & IP Visibility**:
     - Explain how peer-to-peer swarms operate: connecting to public BitTorrent swarms exposes your IP address to other participating peers in the swarm as a functional requirement of the BitTorrent protocol.
     - Detail the post-download ejection policy: connections close, trackers receive a `stopped` un-register event, and DHT stops announcing the device once 100% downloaded.
     - Reiterate the complete sandboxing of local files: no user files, media, or vaults are ever accessible to swarm peers.
     - Advise users regarding VPN usage if they require IP address masking during the download phase.
     - Reiterate that KiloWatch does not collect, log, transmit, or monitor torrent info-hashes, file names, download contents, or peer IP addresses.
     - State that all torrent metadata, state files, and resume data are stored solely in local private application storage and are deleted when the app is uninstalled or cleared.

### FR-12: In-App Strings Policy, Date Bump & Translation Cleanup
1. **In-App Strings Policy ([`strings_policy.xml`](file:///F:/Share/UFM/app/src/main/res/values/strings_policy.xml))**:
   - **Date Bump**: Update `<string name="policy_last_updated">Last Updated: August 2026</string>` → `<string name="policy_last_updated">Last Updated: November 2026</string>`.
   - **`tc_features`**: Add item: `Download Manager — download authorized files directly via HTTP, HTTPS, .torrent files, and Magnet links with zero background seeding, automatic post-download swarm disconnection, and sandboxed storage isolation.`
   - **`tc_s4` (Your Responsibilities)**: Add explicit item: `You agree never to use the Download Manager for copyright infringement, software piracy, or downloading unauthorized proprietary content.`
   - **`tc_s7` / `tc_opensource`**: Add item: `libtorrent4j / Rasterbar libtorrent — modern BitTorrent protocol library.`
   - **`pp_network_labels` / `pp_network_details`**: Document P2P BitTorrent swarm connections, public IP visibility during download, sandboxed file isolation, and immediate swarm ejection at completion.
   - **Main Menu Tile Strings**:
     - `download_manager_title`: *"Download Manager"*
     - `download_manager_subtitle`: *"Torrent, Magnet & HTTP downloads"*
   - **Settings Section Strings**:
     - `settings_section_download_manager`: *"Download Manager"*
     - `settings_download_network_policy_title`: *"Network Connection Policy"*
     - `settings_download_network_policy_wifi_only`: *"Wi-Fi only"*
     - `settings_download_network_policy_wifi_only_desc`: *"Download only when connected to Wi-Fi or wired Ethernet"*
     - `settings_download_network_policy_all`: *"All networks"*
     - `settings_download_network_policy_all_desc`: *"Allow downloads over mobile data as well as Wi-Fi"*
   - **Storage Validation & Security Strings**:
     - `download_fat32_limit_title`: *"Storage Limit Detected"*
     - `download_fat32_limit_message`: *"The file '%1$s' (%2$s) exceeds the maximum single-file limit of 4 GB supported by the FAT32 file system on '%3$s'.\n\nPlease choose a different location (such as Internal Storage or an exFAT/NTFS drive) to continue."*
     - `download_insufficient_space_title`: *"Insufficient Storage Space"*
     - `download_insufficient_space_message`: *"This download requires %1$s, but '%2$s' only has %3$s available.\n\nPlease select another drive or free up space."*
     - `download_space_status_ok`: *"Requires %1$s (%2$s available on %3$s)"*
     - `download_space_status_insufficient`: *"⚠️ Requires %1$s (only %2$s available on %3$s)"*
     - `download_wifi_required_title`: *"Wi-Fi Connection Required"*
     - `download_wifi_required_message`: *"You are currently not connected to Wi-Fi. Downloads are set to Wi-Fi only to protect your mobile data allowance."*
     - `download_btn_enable_mobile`: *"Settings / Enable Mobile"*
     - `download_btn_queue`: *"Queue for Wi-Fi"*
     - `download_status_queued_wifi`: *"Queued (Waiting for Wi-Fi)"*
     - `download_security_traversal_blocked`: *"Security Alert: Download blocked due to invalid path traversal in torrent file."*
     - `download_security_protected_dir`: *"Security Alert: Protected system or vault directories cannot be used as download destinations."*
     - `download_choose_location`: *"Choose Location"*
   - **Disclaimer Dialog Strings**:
     - `torrent_legal_disclaimer_title`: *"P2P Network Notice & Lawful Use Policy"*
     - `torrent_legal_disclaimer_p2p_heading`: *"What is P2P (Peer-to-Peer)?"*
     - `torrent_legal_disclaimer_p2p_body`: *"Unlike normal downloads from a single website, BitTorrent is a decentralized Peer-to-Peer network where you download directly from other users (peers) across the internet."*
     - `torrent_legal_disclaimer_ip_warning`: *"Public IP Disclosure: To receive file chunks, your device's public IP address is visible to other participants in the swarm while downloading. As soon as your download completes, UFM immediately disconnects from all peers and trackers so your IP is no longer exposed."*
     - `torrent_legal_disclaimer_isolation_notice`: *"Complete File Isolation: The downloader is sandboxed exclusively to this download. No other files, photos, Encrypted Vaults, or folders on your device are ever shared or exposed."*
     - `torrent_legal_disclaimer_anti_piracy`: *"Strict Non-Piracy Rule: This tool is strictly for lawful content (Linux distributions, open-source software, public domain media). Downloading copyrighted content without permission is strictly prohibited."*
     - `torrent_legal_disclaimer_checkbox`: *"I understand that my IP is publicly visible in P2P swarms during download and agree to use this feature solely for lawful content without copyright infringement."*
     - `torrent_legal_disclaimer_btn_continue`: *"Continue"*
   - **Folder Location Picker Strings**:
     - `torrent_location_picker_fab_text`: *"Select as Download Folder"*
     - `torrent_location_picker_confirm_title`: *"Set Download Location"*
     - `torrent_location_picker_confirm_message`: *"Set '%1$s' as your default download folder?"*
     - `torrent_location_picker_confirm_btn`: *"Set Location"*
2. **Translation Cleanup Rule (`AGENTS.md`)**:
   - For every modified or updated string resource in `strings_policy.xml` (specifically including `policy_last_updated` and modified arrays), delete the corresponding keys from all 17 translation files (`values-*/strings_policy.xml`).

### FR-13: Legal -> Open Source Licenses Integration
1. **Licenses Activity ([`LicensesActivity.kt`](file:///F:/Share/UFM/app/src/main/java/za/kilowatch/ultimatefilemanager/ui/policy/LicensesActivity.kt))**:
   - Add entry in `loadLibraries()` defensive check:
     ```kotlin
     if (list.none { it.name.contains("libtorrent4j", ignoreCase = true) }) {
         list.add(LibInfo("libtorrent4j (org.libtorrent4j:libtorrent4j)", "2.1.0-39", "BSD-3-Clause / MIT", "https://github.com/aldenml/libtorrent4j"))
     }
     ```
   - Add identical entry in `staticFallback()` list.
2. **Licensee Plugin Configuration ([`app/build.gradle.kts`](file:///F:/Share/UFM/app/build.gradle.kts))**:
   - Verify that `BSD-3-Clause` and `MIT` cover `libtorrent4j` artifacts.

---

## Non-Functional Requirements
- **NFR-01: Zero Seeding & Post-Download IP Teardown**: 0 B/s seeding after completion; complete socket severance within 500ms of reaching 100% completion; no lingering tracker announces.
- **NFR-02: Target SDK 36 & 16 KB Page Compatibility**: Native `.so` files must load without `UnsatisfiedLinkError` on Android 15/16 with 16 KB memory page size kernels.
- **NFR-03: Multi-Platform TV & Mobile Parity**: Full Android TV remote D-pad navigation, high-contrast focus rings, and edge-to-edge support.
- **NFR-04: Strict Privacy & Zero Telemetry**: Torrent info hashes, filenames, and peer data are never logged or transmitted to KiloWatch or third-party analytics.
- **NFR-05: UFMStandard Adherence**: All popups, warnings, file selection sheets, and action dialogs must strictly implement the frosted glass and high-contrast D-pad focus conventions established in `UfmDialogHelper` and `R.style.UFM_Dialog`.
- **NFR-06: Battery & WakeLock Safety**: Service releases wake locks immediately when transfers pause or complete; capped safety timeouts prevent runaway wake locks.
- **NFR-07: Defense-in-Depth Storage Isolation**: Path traversal attempts (Zip-Slip) are rejected with a hard exception; Encrypted Vault and app data paths are blacklisted; inbound peer request handlers only reference the isolated payload files of that specific torrent.

---

## Edge Cases & Error Handling
1. **User Declines Legal Disclaimer**: If the user clicks "Cancel" or dismisses the legal dialog, abort opening the torrent engine immediately; no network sockets are opened and no metadata is queried.
2. **Single File Exceeds FAT32 Limit (>= 4 GB)**: Detected upfront in pre-flight checks; blocks transfer and launches FAB folder location picker with a single tap.
3. **Multi-File Torrent on FAT32**: If total torrent size is 10 GB, but individual files are all under 4 GB, download proceeds normally on FAT32 (provided total free space is sufficient).
4. **Insufficient Storage Space**: Live pre-download check disables "Start Download" button and dialog guides user to redirect to another volume via the 1-tap FAB folder picker.
5. **No Wi-Fi Connection (Wi-Fi Only Mode Active)**: Cellular popup offers "Enable Mobile / Settings", "Queue for Wi-Fi", or "Cancel"; queued items auto-resume when Wi-Fi connects.
6. **Malicious Path Traversal in Torrent Metadata (`../../`)**: Caught by path canonicalization check; download is immediately aborted and user alerted without touching disk.
7. **Target Destination in Encrypted Vault or App-Private Dir**: Rejected by destination validator; prompts user to select a valid public or external folder.
8. **Download Finishes During Screen-Off**: WakeLock and background service handle the 100% completion event seamlessly in the background, close peer connections, dispatch `event=stopped` to trackers, flush files, and release the WakeLock immediately so device sleeps.
9. **Invalid Magnet Link / Corrupted Torrent**: Provide clear, user-friendly toast or banner notifications without application crash.
10. **Swarm With Zero Seeders**: Display "Looking for peers…" with an optional timeout prompt allowing the user to cancel or keep waiting.
11. **Storage Full (ENOSPC)**: Check storage space prior to starting download; pause immediately with a clear alert if disk space runs out.
12. **Process Death / Reboot**: Fast-resume data enables resuming incomplete torrents from the exact byte offset without re-checking full files.
13. **Server Does Not Support HTTP Resumption**: Inform the user gracefully; restart download from byte 0 if needed rather than corrupting target file.

---

## Out of Scope
- Seeding or acting as a swarm seeder after download completion.
- Creating / publishing new `.torrent` files.
- Built-in torrent search engine or web scraper.

---

## Success Criteria Checklist
- [ ] New Main Menu tile **Download Manager** (*"Torrent, Magnet & HTTP downloads"*) appears in the **Utilities** category (`CATEGORY_UTILITIES`).
- [ ] The tile is registered in `loadStorageVolumes()`, `buildAllKnownTiles()`, `onStorageTileClicked()`, and `TileOrderManager.isFeatureTile()`.
- [ ] Dedicated **Download Manager** settings section in `SettingsActivity` allows selecting **Wi-Fi only** (default) or **All networks**.
- [ ] Attempting a download while on mobile data (under Wi-Fi only mode) presents a UFMStandard dialog offering **Enable Mobile / Settings**, **Queue for Wi-Fi**, or **Cancel**.
- [ ] Queued items automatically resume downloading as soon as the device connects to Wi-Fi.
- [ ] Complete file/folder isolation: no files, photos, Encrypted Vaults, or other directories are exposed to swarm peers.
- [ ] Path traversal (Zip-Slip) attacks in torrent metadata are sanitized and blocked with canonical path checks.
- [ ] Protected directories (Vault, app-private databases, system folders) are blacklisted from being set as download destinations.
- [ ] Protocol encryption (PE/MSE) is enforced in the BitTorrent engine.
- [ ] Available storage space is checked before starting download (with a 100 MB system safety margin).
- [ ] Pre-download inspection sheet displays live required vs. available space indicators, and disables download if space is insufficient.
- [ ] Attempting to download with insufficient space triggers a UFMStandard dialog offering to redirect to another drive with one tap.
- [ ] Pre-flight storage validation checks individual file sizes against FAT32 limit (4 GB).
- [ ] Attempting to download a $\ge$ 4 GB file (e.g. 11 GB ISO) to a FAT32 location triggers a UFMStandard dialog offering to choose a new location with one tap.
- [ ] User is presented with a modern UFMStandard legal, non-piracy & P2P public IP disclosure dialog upon first access.
- [ ] Dialog explains what P2P is, warns that public IP is visible to other swarm peers during download, reassures that local files are completely isolated, and confirms IP visibility ceases immediately at completion.
- [ ] Continue button remains disabled until the "I Agree" tick box is checked; clicking Cancel halts the feature cleanly.
- [ ] Legal acceptance state persists across sessions so agreed users can continue seamlessly.
- [ ] Downloads dashboard matches layout concept ([`images/layout.jpeg`](file:///F:/Share/UFM/images/layout.jpeg)): top cards (Direct Downloads vs Torrent Downloads), Active transfers list, and Download location selector.
- [ ] Direct Downloads allows entering HTTP/HTTPS links and downloading them in the background.
- [ ] Tapping "Download location" launches canonical FAB folder picker flow ([`folder_picker_standard.md`](file:///F:/Share/UFM/.claude/memory/folder_picker_standard.md)) with confirmation dialog.
- [ ] User can download files via `magnet:` links and `.torrent` files.
- [ ] Selective multi-file downloading functions with priority `IGNORE` for unchecked files.
- [ ] Zero seeding: download halts immediately upon reaching 100%, and upload rate is restricted during download.
- [ ] Complete swarm disconnection upon 100% completion: all peer sockets severed, trackers sent `event=stopped`, DHT unannounced, terminating IP exposure.
- [ ] Foreground service with persistent progress notification and Pause/Cancel actions; cleanly released upon completion.
- [ ] Policy dates bumped from `August 2026` to `November 2026` across [`WEBSITE/UFMPrivacyPolicy.html`](file:///F:/Share/UFM/WEBSITE/UFMPrivacyPolicy.html), [`WEBSITE/UFMTerms.html`](file:///F:/Share/UFM/WEBSITE/UFMTerms.html), and in-app [`strings_policy.xml`](file:///F:/Share/UFM/app/src/main/res/values/strings_policy.xml).
- [ ] Full anti-piracy, file isolation & P2P IP disclosures added to Website Terms and Privacy Policy.
- [ ] In-app [`strings_policy.xml`](file:///F:/Share/UFM/app/src/main/res/values/strings_policy.xml) updated and translation keys (including `policy_last_updated`) cleaned up across all `values-*/strings_policy.xml`.
- [ ] Legal → Open Source Licenses screen in [`LicensesActivity.kt`](file:///F:/Share/UFM/app/src/main/java/za/kilowatch/ultimatefilemanager/ui/policy/LicensesActivity.kt) updated with `libtorrent4j`.
- [ ] All dialogs adhere strictly to UFMStandard on Mobile and Android TV.
- [ ] All native binaries pass 16 KB page size alignment requirements on Android 15 (API 35+).
