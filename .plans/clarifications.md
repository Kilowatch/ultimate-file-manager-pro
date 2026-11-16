# Clarifications: Selective File/Folder Download & Content Inspector

The following functional and design clarifications have been established for the Torrent & Magnet Selective File/Folder Download Inspector feature:

---

### Clarification 1: Magnet Metadata Resolution Timeout & Fallback Flow
- **Question**: When fetching metadata for a Magnet link takes longer than 20 seconds, how should the app handle it?
- **Decision**: **Timeout Dialog with 3 Actions**:
  - If swarm metadata resolution exceeds 20 seconds, display a UFMStandard dialog offering:
    1. **Keep Waiting**: Extends timeout by an additional 30 seconds while continuing swarm peer search.
    2. **Download All Directly**: Immediately bypasses metadata inspection and queues the magnet download in `DownloadTransferManager` with all files selected (default full download).
    3. **Cancel**: Aborts swarm metadata fetch and cleans up temporary session resources.

---

### Clarification 2: Folder Tree Hierarchy & Collapsible Nodes
- **Question**: How should multi-level nested folders be presented in the scrollable selection list?
- **Decision**: **Collapsible Folder Cards with Hierarchical Indentation**:
  - Folders render with collapsible toggle arrows, folder icon, folder name, child item count, total folder size, and a folder-level master checkbox.
  - Tapping the expand/collapse indicator toggles visibility of nested children, keeping multi-layer or high-file-count torrents tidy and performant.
  - Tapping the folder checkbox recursively checks/unchecks all descendant files and subfolders.
  - Folder checkbox visually indicates state: checked (all children selected), unchecked (0 children selected), or partial/indeterminate (some children selected).

---

### Clarification 3: Search Bar & File-Type Category Filter Chips
- **Question**: Should the selection dialog include quick filter chips or a search bar for torrents with dozens or hundreds of files?
- **Decision**: **Search Bar + Quick File-Type Filter Chips**:
  - The dialog includes an interactive search input (`TextInputEditText` with clear button) allowing real-time filtering of items by name.
  - Quick filter chips are provided: **All**, **Videos**, **Audio**, **Images**, **Archives**, **Docs/Subtitles**.
  - Tapping a chip filters the list view and allows targeted actions (e.g., selecting only video files or deselecting sample/doc files).

---

### Clarification 5: Android TV D-Pad Focus Retention on Active Transfers
- **Problem**: In `DownloadManagerActivity` on TV, 1-second progress ticks previously called `adapter.submitList()` with full `notifyDataSetChanged()`, recycling active ViewHolders and resetting D-pad focus to the top toolbar/sidebar every second.
- **Decision**: **Granular Payload Updates & Stable IDs**:
  - Replace full list dataset resets with granular item payload updates (`notifyItemChanged(pos, PAYLOAD_PROGRESS)` or DiffUtil with stable IDs).
  - Existing focused ViewHolder remains focused continuously without jumping.
  - Set explicit directional navigation rules (`nextFocusLeft`, `nextFocusRight`) between the sidebar and the transfers list.

---

### Clarification 6: Transfer Action Dialog & Platform Parity (Mobile & TV)
- **Question**: How should tapping a transfer card behave on mobile touchscreens vs Android TV?
- **Decision**: **Unified Transfer Action Dialog across Mobile and TV**:
  - Tapping any transfer card on Mobile or pressing D-pad OK on TV opens the **Transfer Action Dialog** (`dialog_transfer_action.xml` on mobile, `dialog_transfer_action_tv.xml` on TV).
  - The small inline play/pause icon button on the card remains available for instant 1-tap pausing/resuming without opening the dialog.
  - The dialog displays complete transfer overview, stats (ETA, peers, seeds, file path), Pause/Resume toggle, Open Folder, and Remove/Delete.

---

### Clarification 7: Transfer Deletion Options & Sub-Prompt Flow
- **Question**: When deleting/canceling a transfer from the Action Dialog, how should file deletion options be presented?
- **Decision**: **Dual Distinct Glass Action Buttons**:
  - Tapping "Delete / Remove Transfer" presents two distinct glass options:
    1. **Remove Transfer Only (Keep Downloaded Files)**
    2. **Remove Transfer & Delete Downloaded Files**
    - Stacked with **Cancel** below.

---

### Clarification 8: Android TV Two-Column Selection Dialog Layout
- **Question**: On Android TV, what layout structure should the Torrent Content Selection Dialog use?
- **Decision**: **Two-Column Split Layout (`800dp` width)**:
  - **Left Sidebar**: Displays torrent title, total/selected size metrics, destination folder, free space, Select All / Deselect All buttons, and Start Download / Cancel buttons.
  - **Right Pane**: Displays the full-height scrollable file and folder tree with search and category filters.
  - Provides a desktop-class, widescreen experience optimized for 16:9 television screens, minimizing vertical scrolling and keeping action controls immediately accessible.

---

### Clarification 9: Scope Boundary for In-Progress File Priority Modification
- **Question**: Should the Transfer Action Dialog also allow re-selecting files for torrents that are already actively downloading?
- **Decision**: **Pre-Download Selection Only for this Phase**:
  - File/folder selection and priority assignment happen strictly pre-download before transfers begin.
  - The Transfer Action Dialog manages Pause, Resume, Open Folder, and Remove/Delete. Dynamic runtime modification of file priorities on already active transfers is deferred.
