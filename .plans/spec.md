# Specification: Storage Analyzer – Large Files Action Menu (Move & Delete) with Auto-Reindex

## Overview
The Large Files tab in Storage Analyzer displays the largest space-consuming files across indexed storage volumes, allowing users to quickly identify files taking up significant capacity. This feature introduces a long-press contextual action menu on large file entries—adhering strictly to UFMStandard glass component specifications on both Mobile and Android TV—enabling users to directly Move or Delete large files. Upon completing either operation, the system automatically initiates storage reindexing and refreshes the file list and analyzer metrics in real time.

---

## User Stories

- **US-01 (Long-Press Context Menu):** As a user inspecting large files, I want to long-press any file item (or hold D-pad Center / press Menu on Android TV) so that a styled contextual menu appears with options to Move or Delete the file.
- **US-02 (File Relocation / Move):** As a user managing disk space, I want to move an oversized file to another directory or external volume so that I can free up space on my primary drive while preserving the file.
- **US-03 (Folder Picker Compliance):** As a user selecting a move destination, I want the standard UFM Folder Picker to launch with only storage drives shown (suppressing feature tiles) and confirm the target directory via the primary FAB.
- **US-04 (Safe File Deletion):** As a user clearing unwanted large files, I want a dedicated UFMStandard confirmation dialog detailing the file name and size before deletion, so that I do not accidentally delete critical data.
- **US-05 (Automatic Reindex & UI Refresh):** As a user who just moved or deleted a large file, I want the system to automatically reindex the affected storage and refresh the Large Files list and analyzer summary statistics without requiring me to manually exit and re-analyze.
- **US-06 (Android TV Support):** As an Android TV user, I want the contextual menu, folder picker, and confirmation dialogs to be fully navigable via D-pad with clear focus indicators and TV-compliant dimensions (650dp width).

---

## Functional Requirements

### 1. Contextual Action Trigger & Presentation
- **FR-01 (Touch & Remote Triggers):** 
  - On mobile devices, long-pressing any item in the Large Files list must trigger the contextual action dialog.
  - On Android TV, holding the D-pad Center / OK button or pressing the remote Menu key on a focused item must trigger the contextual action dialog.
  - Single-tap behavior must remain unchanged (navigates to the file in File Browser).
- **FR-02 (UFMStandard Action Dialog):** 
  - The action dialog must be rendered using UFMStandard glass styling (`bg_dialog_glass`, transparent dialog window background).
  - The dialog header must display a file type icon badge, file name (truncated if long), formatted file size, and current parent directory path.
  - The dialog must present vertical interactive action rows:
    - **Open File**: Launches the default file viewing/opening workflow for the file type.
    - **Open Folder Location**: Opens the file's parent folder inside `FileBrowserActivity` with focus set to the file.
    - **Move**: Opens the UFM Folder Picker to relocate the file.
    - **Delete**: Indicated by a warning/danger icon to permanently remove the file via confirmation.
    - **Cancel / Dismiss**: Allows closing the dialog without action.
- **FR-03 (Dual-Layout TV & Mobile Adaptation):**
  - Mobile layout: Match-parent width with standard dialog margin insets.
  - TV layout: Fixed 650dp dialog width, D-pad focus highlight with TV button/card selectors, safe padding.

### 2. Move Operation Flow
- **FR-04 (Folder Picker Launch):**
  - Selecting "Move" must launch the UFM Folder Picker compliant with the UFM Folder Picker Standard (`StorageBrowserActivity` -> `FileBrowserActivity`/`NetworkBrowserActivity`).
  - Must strictly enforce the **All-Drives Initial View Rule**: feature tiles (Twin Window, Notepad, Document Scanner, etc.) must be suppressed so only storage drives/volumes are shown.
- **FR-05 (Destination Confirmation & Validation):**
  - Navigating to the desired folder allows confirming the destination via the picker Extended FAB.
  - The system must prevent selecting the file's current containing folder as the target, displaying an informative message.
  - If a file collision occurs at the destination, the existing `TransferConflictHelper` must be used to resolve the conflict (Overwrite, Skip, Keep Both / auto-rename, or Cancel).
  - The system must verify write permissions for the destination folder (prompting for SAF tree permission if external storage requires it).
- **FR-06 (Move Execution & Progress Feedback):**
  - Large files may require substantial transfer time (especially across volumes); a non-blocking progress indicator must be displayed during the move operation.
  - The move operation must support internal storage, secondary SD cards, USB OTG drives, and Shizuku/root if configured.

### 3. Delete Operation Flow
- **FR-07 (UFMStandard Delete Confirmation):**
  - Selecting "Delete" must prompt the user with a confirmation dialog styled with UFMStandard glass container (`bg_dialog_glass`).
  - The confirmation dialog must clearly indicate the file name, formatted size, and a warning that the file will be permanently deleted.
  - Actions must follow a vertical button hierarchy: "Delete" (prominent danger button) on top, and "Cancel" (secondary text button) below.
- **FR-08 (Delete Execution):**
  - Upon user confirmation, the file must be deleted from the filesystem using the appropriate storage mechanism (standard File API, SAF DocumentFile, or Shizuku).
  - If deletion fails (e.g. write-protected or permission revoked), an informative error dialog or toast must notify the user.

### 4. Automatic Post-Operation Reindexing & List Refresh
- **FR-09 (Automatic Storage Reindex):**
  - Immediately following a successful Move or Delete operation, the system must trigger background reindexing for the affected storage location(s) so that the file index database remains completely synchronized.
  - For Move operations, both the source directory and destination directory must be updated in the index.
  - For Delete operations, the deleted file must be removed from the index database.
- **FR-10 (Real-Time File List & Report Refresh):**
  - The Large Files list must automatically refresh to immediately reflect the change:
    - Deleted files must be removed from the list.
    - Moved files must update their displayed path (or be removed if moved off the analyzed volume).
  - The Storage Analyzer report (including overall storage usage, category breakdown, and large files total count/size) must automatically refresh with updated metrics without requiring a manual rescan or activity reload.

---

## Non-Functional Requirements

- **NFR-01 (UFMStandard Compliance):** All UI elements (action menu, confirmation dialog, folder picker FAB, progress indicators) must strictly adhere to the project's UFMStandard specifications:
  - Frosted glass containers (`bg_dialog_glass`).
  - Transparent dialog windows (`dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))`).
  - Embedded XML buttons (no default Android alert button rows).
  - Vertical hierarchy for option cards and action buttons.
  - Proper TV selectors (`selector_tv_button_yellow`, `selector_tv_button`) and 650dp TV dialog width.
- **NFR-02 (UI Responsiveness & Background Execution):** Move, Delete, and Reindexing operations must never block the main UI thread. File I/O and database operations must run on IO dispatchers.
- **NFR-03 (Localization & Internationalization):** All user-facing strings (dialog titles, action labels, confirmation messages, progress descriptions) must be defined in `strings.xml`. In accordance with `AGENTS.md`, any modified existing English strings must be purged from non-English translation files.
- **NFR-04 (Graceful Lifecycle Handling):** Configuration changes (screen rotation, orientation changes) during Move or Reindexing must not crash the application or duplicate operations.

---

## Edge Cases & Error States

- **EC-01 (File Not Found / Deleted Externally):** If the file no longer exists on disk when the user attempts to Move or Delete it, the system must notify the user, remove the stale file from the index, and refresh the list.
- **EC-02 (Same Destination Selected for Move):** If the user selects the file's current folder as the destination in the folder picker, the move operation must be rejected with a user-friendly notice ("File is already in this folder").
- **EC-03 (Insufficient Destination Storage):** If the target drive does not have sufficient free space to accommodate the large file, the move operation must abort before copying and warn the user.
- **EC-04 (Destination Permission Required):** If moving to an external SD card or USB volume requiring SAF access that has not been granted, the app must initiate the standard SAF permission authorization flow.
- **EC-05 (Read-Only / Protected Files):** If a file cannot be deleted or moved due to OS-level system locks or lack of permissions, the operation must fail safely without crashing, presenting an actionable error message (e.g. requesting Shizuku or SAF access).
- **EC-06 (User Dismissal / Cancellation):** If the user dismisses the action dialog, backs out of the folder picker, or clicks "Cancel" on the delete confirmation, no filesystem or database modifications take place, and the list remains intact.
- **EC-07 (Rapid Multiple Actions):** Rapid successive taps or clicks on action buttons must be debounced to prevent concurrent duplicate file operations.

---

## Out of Scope

- Multi-select bulk actions in the Large Files tab (this specification focuses strictly on single-file long-press contextual Move & Delete).
- Renaming or opening with custom applications directly from this menu (available via the standard file browser flow).
- Cloud storage (SMB/SFTP/Google Drive) destination pickers for the Move operation (Move is restricted to local/mounted physical storage volumes analyzed by the engine).

---

## Success Criteria

- [ ] Long-pressing any file in the Large Files tab on mobile displays the UFMStandard glass action dialog with Move and Delete options.
- [ ] On Android TV, holding D-pad Center or pressing Menu on a focused file opens the TV-adapted 650dp action dialog.
- [ ] Selecting "Move" opens the UFM Folder Picker showing only storage drives (feature tiles suppressed), and confirming a valid destination moves the file.
- [ ] Selecting "Delete" presents a UFMStandard confirmation dialog with file details, requiring explicit confirmation before deletion.
- [ ] Immediately after Move or Delete completes, the affected storage volume is reindexed in the background.
- [ ] The Large Files list and Storage Analyzer overview statistics automatically refresh without manual reload.
- [ ] Moving to the same folder or an invalid location is gracefully rejected.
