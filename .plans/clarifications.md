# Clarifications: Download Manager — Torrent, Magnet & Direct HTTP/HTTPS

## Status
All clarifications confirmed by user — ready for `/plan`.

---

## Questions & Confirmed Decisions

### 1. Default Download Destination
- **Question**: What should be the default download destination before the user changes it?
- **Decision**: **System Downloads folder (`/storage/emulated/0/Download`)**.
  - Matches user expectations for standard Android file storage.
  - Can be changed at any time to internal subfolders, SD card, or USB OTG drives using the canonical FAB Folder Picker.

### 2. Network Usage Policy & Dedicated Settings Section
- **Question**: How should mobile data vs. Wi-Fi usage be handled?
- **Decision**:
  - **Default Mode**: **Wi-Fi Only**.
  - **Dedicated Settings Section**: Add a separated **"Download Manager"** section in `SettingsActivity` (conforming to UFMStandard card styling).
  - **Network Toggle**: Users can switch between:
    - **"Wi-Fi only"** (Default)
    - **"All networks"** (Wi-Fi, Ethernet, and Mobile Data)
  - **Cellular / Offline Warning Dialog (UFMStandard)**:
    - If a user attempts to start a download while not connected to Wi-Fi (and "Wi-Fi only" is active), present a modern UFMStandard dialog:
      - **Action 1: "Enable Mobile" / "Settings"**: Navigates directly to Settings → Download Manager to allow mobile data transfer.
      - **Action 2: "Queue"**: Enqueues the transfer in a `Queued` state; once the device connects to an active Wi-Fi network, the download automatically resumes/starts.
      - **Action 3: "Cancel"**: Cancels and dismisses the download action.

### 3. Entry Points & Main Menu Placement
- **Question**: Should the Download Manager also be added to the browser selection Tools FAB & Quick Action Bar?
- **Decision**: **Main Menu tile under "Utilities" + File/Magnet intent handler only**.
  - The Tools FAB remains dedicated to selected file manipulation (Copy, Move, Compress, Encrypt, etc.).
  - The feature is exposed via the **Download Manager** tile in the **Utilities** category (`CATEGORY_UTILITIES`) on the Main Menu, and via system intent filters for `.torrent` files and `magnet:` links.

### 4. Direct Downloads (HTTP / HTTPS) Inclusion
- **Question**: Will HTTP / HTTPS downloads be included as per the layout example?
- **Decision**: **Yes, fully included**.
  - Dashboard features two top action cards: **Direct Downloads (HTTP / HTTPS)** and **Torrent Downloads (Magnet / Torrent)**.
  - Both stream into the same active transfers list, foreground service, and storage destination.

### 5. Storage Pre-Flight Validation (FAT32 & Capacity)
- **Question**: How should storage limitations be handled?
- **Decision**:
  - **Available Free Space**: Check available disk capacity (+100 MB safety buffer) before starting any download. If insufficient, disable the Start button and offer a 1-tap UFMStandard dialog to choose a new location via the FAB folder picker.
  - **FAT32 4 GB Limit**: If an individual file $\ge$ 4 GB is targeted to a FAT32 filesystem (e.g. an 11 GB file), block the transfer upfront and display a UFMStandard dialog with a 1-tap "Choose Location" redirect.

### 6. Security & Sandboxing Guarantee
- **Question**: How to ensure no files or folders are exposed during torrent usage?
- **Decision**:
  - Zero-seeding and complete inbound peer isolation: swarm peers can never access, read, or request any existing files, media, Encrypted Vaults, or directories on the device.
  - Path traversal (Zip-Slip) defense and protected directory blacklisting (Vault, app-private databases).
  - Immediate complete swarm disconnection at 100% completion terminating IP visibility.

### 7. Policy & Date Updates
- **Decision**:
  - Terms & Privacy Policy dates bumped to **November 2026** across website HTML files and in-app strings.
  - Translation cleanup: Delete modified string keys from all 17 non-English translation files per `AGENTS.md`.

---

## Next Steps
Proceed to `/plan` to design the technical architecture and draft the detailed file-by-file changes in [.plans/plan.md](file:///F:/Share/UFM/.plans/plan.md).
