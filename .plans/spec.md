# Specification: Selective File/Folder Download & Content Inspector for Torrents and Magnet Links

## Overview
Currently, adding a `.torrent` file or a `magnet:` link in Ultimate File Manager Pro initiates a transfer that automatically downloads the entire contents of the torrent without presenting the user with an opportunity to inspect, select, or exclude specific files and folders. For torrents containing multiple files (such as discographies, TV seasons, document archives, software suites, or sample packs), users are forced to consume excessive bandwidth, battery, and local storage downloading unwanted items.

Additionally, on Android TV, when a torrent or magnet download is actively running, real-time progress updates cause the RecyclerView to lose focus and snap the cursor back to the top section/toolbar every second, preventing the user from navigating or focusing on active transfers. Furthermore, pressing D-pad OK on a transfer on TV does not present an action dialog to pause, resume, or delete/cancel the torrent.

This specification defines the functional, technical, and UI/UX requirements for:
1. A **Selective File & Folder Download Inspector** adhering strictly to `UFMStandard` (across Mobile and Android TV) with collapsible folders, live size metrics, search/filtering, and `libtorrent4j` priority masking.
2. An **Android TV Focus Stability & Transfer Action System** that guarantees rock-solid D-pad focus retention during real-time updates and presents a comprehensive UFMStandard action dialog when pressing D-pad OK on any transfer.

---

## User Stories
- **As a power user downloading multi-file torrents**, I want to see the complete list of files and folders inside a `.torrent` or `magnet:` link before downloading begins, so that I can download only the specific items I need.
- **As a mobile or TV user with limited device storage**, I want to deselect large unwanted files (such as extras, unneeded video resolutions, or supplementary assets) so that the download fits within my available free disk space.
- **As a user wanting quick downloads**, I want all files and folders to be selected by default with a clear "Select All" / "Deselect All" action, so that I can proceed immediately with the full torrent without extra friction if I want everything.
- **As an Android TV user navigating with a D-pad remote**, I want a clear, scrollable glass dialog with distinct focus states, enabling intuitive navigation and toggling of checkboxes using the remote control.
- **As an Android TV user watching an active download**, I want my D-pad focus to stay locked on the selected transfer instead of resetting back to the top header every second when progress updates.
- **As an Android TV user selecting a transfer card**, I want pressing D-pad OK on any transfer to display an action dialog where I can easily pause, resume, open download folder, or remove/delete the torrent.
- **As a user entering a Magnet link**, I want the app to resolve the torrent metadata from the swarm and display the file selector, or allow me to proceed with a full download if metadata resolution is deferred.

---

## Functional Requirements

### 1. Pre-Download Torrent Content Inspection Flow
- **FR-01: Automatic Content Inspection on Torrent Input**:
  - Whenever a `.torrent` file is opened (via "Open .torrent File" picker, clicked in UFM File Browser, or received via external Intent/Action View), the app must parse the bencoded metadata (`TorrentInfo`) and immediately display the **Torrent Content Selection Dialog**.
  - Direct download bypass: The transfer must not begin writing payload data to disk until the user confirms their selection via "Start Download".
- **FR-02: Magnet Link Swarm Metadata Resolution**:
  - When a `magnet:` URL is entered, pasted, or intercepted from an external app, the app must initiate a lightweight metadata fetch from DHT/trackers/peers.
  - While metadata is resolving, display a UFMStandard progress state: *"Fetching torrent metadata from swarm..."* with an active spinner and a "Cancel" button.
  - Once metadata is received, automatically populate and transition to the **Torrent Content Selection Dialog**.
  - Provide a fallback action: *"Download All Directly"* allowing the user to bypass metadata waiting and immediately queue the magnet link to download all files.

### 2. Selection Controls & Default State
- **FR-03: Default State (Select All)**:
  - All files and folders within the torrent must be **selected by default** upon opening the dialog (`selectAll = true`).
  - Total selected size must initially equal the full torrent size.
- **FR-04: One-Tap Quick Action Toggles & Filtering**:
  - Provide dedicated quick action buttons: **Select All** and **Deselect All**.
  - Tapping **Select All** selects 100% of files and folders and updates all checkboxes.
  - Tapping **Deselect All** unchecks all files and folders, reducing the selected size to 0 B.
  - **Search & Category Filter Chips**: Provide an interactive search input and category filter chips (**All**, **Videos**, **Audio**, **Images**, **Archives**, **Docs/Subtitles**) to rapidly locate and filter items in large torrents.
- **FR-05: Collapsible Hierarchical Folder & File Selection**:
  - Folders must be collapsible to maintain clean readability in large torrents.
  - When a user checks or unchecks a folder:
    - Checking a folder must recursively check all files and subfolders within that folder.
    - Unchecking a folder must recursively uncheck all files and subfolders within that folder.
  - When individual files are toggled:
    - If all files in a folder become selected, the folder checkbox reflects a checked state.
    - If some files in a folder are selected and some are deselected, the folder reflects a partial/indeterminate state.
    - If no files in a folder are selected, the folder reflects an unchecked state.
- **FR-06: Zero-Selection Prevention**:
  - If 0 files are selected, the primary "Start Download" button must be visually disabled or show a clear prompt informing the user to select at least one file before starting.

### 3. Real-Time Summary & Metrics
- **FR-07: Real-Time Dynamic Size & Count Indicator**:
  - The dialog header must display a live summary banner that updates immediately upon any selection change:
    - Number of selected files vs total files (e.g., `Selected: 3 / 15 files`).
    - Formatted selected payload size vs total size (e.g., `1.2 GB of 4.8 GB`).
- **FR-08: Target Directory & Free Space Display**:
  - Display the target download directory with the canonical "Change Location" button leveraging `StorageBrowserActivity` folder picker.
  - Display remaining available storage capacity on the selected volume.

### 4. Selective Download Engine Integration (`libtorrent4j`)
- **FR-09: File Priority Masking**:
  - Pass file priority arrays to `libtorrent4j`:
    - Selected files must be assigned `Priority.DEFAULT` (normal transfer priority).
    - Deselected files must be assigned `Priority.IGNORE` (`0`).
  - `libtorrent4j` must only download pieces belonging to files with priority > 0.
- **FR-10: Selective Storage Pre-Flight Validation**:
  - `StoragePreflightValidator.validate` must check available disk space and FAT32 limits against the **selected files only**, rather than the full nominal size of the entire torrent.
- **FR-11: Progress, Completion & Storage Cleanup**:
  - Download progress percent, ETA, and speed must be calculated based on `status.totalWanted()` and `status.totalWantedDone()` (reflecting selected files).
  - Storage allocation: Compact/sparse allocation ensures unselected files are not allocated nominal disk space.
  - Completion scrub: Upon reaching 100% of wanted pieces, zero-seeding compliance is enforced, zero-byte stubs or boundary piece markers for deselected files and empty folders are pruned, and only the selected downloaded files are indexed into the Android `MediaStore`.

### 5. Android TV D-Pad Focus Stability & Transfer Action Dialog
- **FR-12: TV D-Pad Focus Retention During Active Updates**:
  - The RecyclerView adapter (`DownloadTransferAdapter`) must not use full `notifyDataSetChanged()` during 1-second progress ticks, which destroys active ViewHolders and causes TV D-pad focus to snap back to the top toolbar/sidebar.
  - Implement granular payload updates (`notifyItemChanged(index, payload)`) and stable item IDs (`setHasStableIds(true)` or `ListAdapter` with `DiffUtil`) to update progress bars, speed, and peer counts without recreating ViewHolders or losing active D-pad focus.
  - Set explicit focus direction rules and ensure the transfer card itself is properly focusable and navigates cleanly to/from the left sidebar and adjacent items.
- **FR-13: Unified Transfer Action Dialog (Mobile & TV)**:
  - Tapping any transfer card on Mobile or pressing **D-pad OK** on TV presents a dedicated UFMStandard transfer action dialog (`dialog_transfer_action.xml` on mobile, `dialog_transfer_action_tv.xml` on TV).
  - The inline pause/resume icon button on the card remains available for instant 1-tap toggling without opening the dialog.
  - The dialog displays:
    - Transfer title, type badge (Torrent / Magnet / Direct HTTP), state, downloaded vs total size, speed, ETA, and active save path.
    - **Pause / Resume** button: Primary stacked button (`@drawable/selector_tv_button_yellow` on TV, primary expressive on mobile) to toggle pause/resume.
    - **Open Folder** button: Navigates directly into the download directory using `FileBrowserActivity`.
    - **Delete / Remove** button: Prompts the user with clear glass actions: *"Remove Transfer Only (Keep Files)"* or *"Remove Transfer & Delete Downloaded Files"*, with Cancel below.
    - **Close / Dismiss** button.

---

## UI/UX & UFMStandard Requirements

### 1. Dialog Container & Styling
- **Root Background**: `@drawable/bg_dialog_glass` with transparent window background (`R.style.UFM_Dialog`).
- **Mobile Layout (`dialog_torrent_file_selection.xml`)**:
  - Dialog width: Match parent with standard system horizontal margins.
  - Dialog height: Responsive, with the file list bounded to a maximum height (e.g., 55–60% of viewport) to guarantee action buttons remain visible and pinned at the bottom.
  - Glass cards (`@color/mobile_glass_card`, `@color/mobile_glass_stroke`, 12dp corner radius).
- **Android TV Layout (`dialog_torrent_file_selection_tv.xml`)**:
  - Two-Column Split Layout (`800dp` width) adhering to UFMStandard TV widescreen conventions.
  - Left Sidebar: Torrent title, total/selected size stats, destination folder with Change button, Select All / Deselect All buttons, and Start Download / Cancel buttons.
  - Right Pane: Full-height scrollable file/folder tree with search input and category filter chips.
  - D-pad focusable rows with focus selectors (`@drawable/selector_tv_button_yellow` / `@drawable/selector_tv_button`).

### 2. Scrollable Content List Component
- **Component**: `RecyclerView` enclosed in a scrollable, nested scroll-safe container with vertical scrollbar enabled (`android:scrollbars="vertical"`).
- **Row Item Appearance (`item_torrent_file.xml` & `item_torrent_folder.xml`)**:
  - Checkbox: Custom glass-themed checkbox or MaterialCheckBox with accent tint.
  - Icon: Distinct file type icon (resolved via file extension: video, audio, image, document, archive, code, etc.) or folder icon (`ic_folder`).
  - Label: File name (single line, `ellipsize="middle"`) and relative directory path if applicable.
  - Size: Formatted file size (`14.2 MB`, `1.4 GB`) aligned to the right or below the file name.

### 3. Action Buttons & Hierarchy
- **Button Orientation**: Primary action stacked vertically on top, secondary/cancel button below.
- **Mobile Buttons**:
  - Primary: `MaterialButton` (`@style/Widget.Material3Expressive.Button`, 48-50dp height, `?attr/colorPrimary` background tint, 12dp radius, white bold text: *"Start Download"*).
  - Secondary: `MaterialButton` (`@style/Widget.Material3Expressive.Button.TextButton`, 44dp height, `@color/mobile_text_secondary`: *"Cancel"*).
- **TV Buttons**:
  - Primary: `MaterialButton` with `@drawable/selector_tv_button_yellow`, `@color/tv_button_focused_yellow_text`, D-pad focusable.
  - Secondary: `MaterialButton` with `@drawable/selector_tv_button`, `@color/tv_button_text_selector`, D-pad focusable.

---

## Non-Functional Requirements
- **NFR-01: Performance with Large Torrents**:
  - The tree/list model and RecyclerView adapter must handle torrents containing 1,000+ files smoothly without UI thread lag, dropped frames, or ANRs.
  - Tree node toggling must perform batched updates (`notifyDataSetChanged` or diffing) efficiently.
- **NFR-02: Zero Memory Leaks**:
  - Torrent metadata objects and adapter references must be cleanly released upon dialog dismissal.
- **NFR-03: Localization & No Hardcoded Strings**:
  - All labels, buttons, headers, and formatted strings must reside in `strings.xml`. English string updates must follow the translation cleanup rule.
- **NFR-04: Zero-Seeding Compliance Preservation**:
  - Selective downloads must strictly maintain UFM's zero-seeding privacy policy (`active_seeds = 0`, immediate swarm disconnection upon 100% completion of wanted pieces).

---

## Edge Cases & Error States
- **EC-01: Single-File Torrents**:
  - If a torrent contains only one file, the dialog displays the single file row (checked by default). Deselecting it disables the Start button.
- **EC-02: Magnet Metadata Fetch Failure / 20s Timeout**:
  - If DHT/tracker peer discovery exceeds 20 seconds, present a timeout prompt offering three options:
    1. *"Keep Waiting"* (extends timeout by 30 seconds).
    2. *"Download All Directly"* (queues transfer immediately without preview).
    3. *"Cancel"* (cancels and cleans up).
- **EC-03: Insufficient Storage for Partial Selection**:
  - If the user selects more files than the free space on the destination drive, pre-flight check flags the error, showing the exact shortfall and offering the "Choose New Location" picker.
- **EC-04: Non-English Character File Paths**:
  - Torrents containing UTF-8 filenames, Asian characters, Cyrillic, or emoji must render accurately without truncation or encoding corruption.
- **EC-05: Deeply Nested Folder Trees**:
  - Torrents with multiple levels of nested directories must be clearly identifiable either via hierarchical indentation, breadcrumb paths, or collapsible nodes without breaking layout boundaries on mobile or TV.

---

## Out of Scope
- Dynamic piece re-prioritization of in-progress transfers while downloading (post-start file priority editing; this can be specified in a separate phase if desired).
- BitTorrent streaming/sequential piece playback while downloading (this feature covers selective download queueing).

---

## Success Criteria
- [ ] Opening any `.torrent` file presents the scrollable Torrent Content Selection Dialog before any download commences.
- [ ] Entering or opening a `magnet:` link resolves metadata and displays the same Torrent Content Selection Dialog (with a fallback to download all).
- [ ] All files and folders are selected by default upon opening.
- [ ] "Select All" checks all files and folders; "Deselect All" unchecks all files and folders.
- [ ] Collapsible folder cards expand/collapse and cleanly propagate recursive selection.
- [ ] Search bar and category filter chips (Videos, Audio, Images, Archives, Docs) filter list items accurately.
- [ ] Toggling a folder checkbox toggles all child files and subfolders.
- [ ] The header live summary dynamically updates the selected file count and formatted size.
- [ ] "Start Download" is disabled when 0 files are selected.
- [ ] Deselected files are passed to `libtorrent4j` with `Priority.IGNORE` (0) and are not written to disk.
- [ ] Pre-flight storage checks calculate space requirements based only on selected files.
- [ ] Completion prunes any empty folders and 0-byte boundary markers for deselected files.
- [ ] Android TV UI renders with 800dp two-column widescreen layout, glass styling, and full D-pad remote navigation.
- [ ] On TV, active torrent transfers retain D-pad focus without snapping back to top toolbar or sidebar during 1-second progress updates.
- [ ] Pressing D-pad OK on an active or completed transfer card opens the UFMStandard Transfer Action Dialog.
- [ ] From the Transfer Action Dialog on TV, user can pause/resume, open download location, or remove/delete transfer with a confirmation prompt.
- [ ] All text is localized in `strings.xml` with zero hardcoded English in code.
