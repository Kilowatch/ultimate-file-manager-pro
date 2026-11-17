# Specification: Flat View (Indexed Root Browser Mode)

## Overview
Flat View is a specialized virtual browsing and presentation layout in Ultimate File Manager Pro (UFM), accessible exclusively on **indexed storage volumes** from the **root folder**. It presents the contents of the root and all descendant subdirectories in a unified, structured view where folders act as expandable and collapsible sections (all expanded by default). 

Flat View queries directly from UFM's Room `file_index` database table for instant loading across 100,000+ files at 60fps. It preserves standard UFM operations and controls: users can enter edit/multi-select mode, invoke the selection Tools FAB (`fabTools`) and Floating Quick Action Bar (`floatingQuickBar`), and execute all normal file operations (copy, move, delete, rename, share, compress, etc.) targeting real source files. System folders (`Android/data`, `Android/obb`) are excluded by default to avoid clutter and permission barriers, but can be enabled via a dedicated Flat View settings section under "Browsing & File List" in `SettingsActivity`.

---

## User Stories

- **US-01 (Root-Scoped Activation on Indexed Storage):** As a user browsing the root directory of an indexed storage volume (such as Primary Internal Storage or an indexed SD card), I want to tap the 3-dots overflow menu and select "Flat View" (positioned directly below "Twin Window") so that I can browse all nested files in a single unified view.
- **US-02 (Default-Expanded Tree Sections):** As a user in Flat View, I want all subfolders (and the root) to be expanded by default so that I immediately see all descendant files, with the ability to collapse or expand individual folders or the root header to organize my view.
- **US-03 (Quick Folder Navigation):** As a user viewing a subfolder header, I want an "Open Folder" action on the header so that I can instantly jump to standard hierarchical browsing at that specific directory if I choose.
- **US-04 (Standard Edit Mode & Tools FAB):** As a power user, I want normal edit mode, long-press multi-select, the selection Tools FAB (`fabTools`), and the Floating Quick Action Bar (`floatingQuickBar`) to function exactly as they do in standard browsing, with folder header checkboxes cascading to contained files and "Select All" selecting across all folders.
- **US-05 (Exclusion of System Folders):** As a user, I want `Android/data` and `Android/obb` excluded by default so the flat list is not overwhelmed with inaccessible system files, while retaining an opt-in toggle in Settings.
- **US-06 (Dedicated Flat View Settings):** As a user, I want a dedicated "Flat View" card in Settings under "Browsing & File List" to toggle system folder inclusion and configure expansion defaults.
- **US-07 (Contextual Relative Paths):** As a user, I want each item row to display its relative folder path so that files with identical names from different subdirectories are instantly recognizable.
- **US-08 (Android TV Remote & Mobile Usability):** As an Android TV or mobile user, I want Flat View to support full platform ergonomics—touch/multi-select on mobile and seamless D-pad focus navigation and 10-foot readability on Android TV.

---

## Functional Requirements

### 1. Scope, Availability & Entry Point
- **FR-01 (Indexed Storage Gating):** Flat View is strictly available on storage volumes that are fully indexed by UFM's indexing repository. On unindexed or unsupported storages, the Flat View menu item is hidden.
- **FR-02 (Volume Root Only):** Flat View can only be initiated from the **root folder** of an indexed storage volume (e.g. `/storage/emulated/0/` or SD card root). It is not offered when browsing deeper inside child subfolders.
- **FR-03 (Overflow Menu Integration):** Flat View is exposed in the 3-dots overflow popup menu across Local Standalone, TAB Mode, and Twin Window, positioned directly below the "Twin Window" menu item (`menuItemFlatView`).
- **FR-04 (Exit & Back Navigation):** Pressing Back or switching view modes returns the browser to the standard hierarchical view at the root directory, preserving scroll and selection state where feasible.

### 2. Collapsible & Expandable Folder Structure
- **FR-05 (Direct Parent Section Headers):** Files are grouped under their direct parent folder headers displaying their full relative path (e.g., `Documents/Work/2026/ (12 files)`), eliminating horizontal clipping and multi-level indentation.
- **FR-06 (All Expanded by Default):** When entering Flat View, the root folder header and all discovered subfolder headers must be **expanded by default**, showing all descendant files immediately.
- **FR-07 (Folder Toggle Interaction):** Tapping or clicking (or pressing D-pad Center on TV) on any folder header row toggles its collapsed/expanded state.
- **FR-08 (Open Folder Action):** Each folder header provides an "Open Folder" action button allowing the user to navigate directly into that folder in standard hierarchical browsing.
- **FR-09 (Root Header Collapse):** The root directory itself is presented with a top-level header that allows collapsing or expanding the entire flat hierarchy at once.

### 3. System Folder Filtering & Configuration
- **FR-10 (System Folders Excluded by Default):** `Android/data` and `Android/obb` are automatically excluded from Flat View by default.
- **FR-11 (System Folder Inclusion Toggle):** Users can toggle "Include System Folders" in Settings to show these folders when needed.
- **FR-12 (Dedicated Settings Section):** Add a dedicated "Flat View" card to UFM Settings under "Browsing & File List" containing:
  - **Include System Folders:** Toggle switch (Default: Disabled / Off).
  - **Default Folder Expansion:** Option for "All Expanded" vs "All Collapsed" (Default: All Expanded).
  - **View Content Mode:** Files Only vs Files & Subfolders (Default: Files Only).

### 4. Edit Mode, Multi-Select & Tools FAB Synchronization
- **FR-13 (Full Edit Mode Parity):** Normal edit mode, long-press selection, select-all, and range selection must function identically to standard browser mode.
- **FR-14 (Folder Header Checkbox Cascade):** In Edit Mode, checking a folder header checkbox automatically selects all files contained within that folder.
- **FR-15 (Select All Scope):** Tapping "Select All" selects all files across the entire tree, including files inside currently collapsed folders.
- **FR-16 (Tools FAB & Floating Quick Bar):** The selection Tools FAB (`fabTools`) and Floating Quick Action Bar (`floatingQuickBar`) must trigger, display, and synchronize across all container environments (Standalone, TAB Mode, and Twin Window Mode).
- **FR-17 (Real Source Targeting):** All file operations triggered from the Tools FAB or quick actions (copy, move, delete, rename, share, zip, favorite, properties) must operate on the actual source file paths/URIs, never on synthetic or display paths.

### 5. Data Engine & Sorting
- **FR-18 (Room Database Index Engine):** Flat View queries files directly from the Room `file_index` database table (`FileIndexDao`), ensuring instant performance on datasets of 100,000+ files.
- **FR-19 (Relative Path Display):** Each file row displays its parent path relative to the root (e.g. `Documents/Invoices/`).
- **FR-20 (Deterministic Sorting):** Sorting options (Name, Size, Date, Type) apply deterministically across all items within their groups or across the unified list, with tie-breaking on relative path and stable ID.
- **FR-21 (In-Memory Filter):** The local filter bar filters the loaded entries in real time without querying the global search database or re-scanning the disk.

---

## Non-Functional Requirements

- **NFR-01 (Threading & Fluidity):** Database queries and tree grouping must execute on `Dispatchers.IO`. No database operations or sorting on the UI thread.
- **NFR-02 (Virtualization & Memory Scalability):** Large trees (100,000+ items) render via `RecyclerView` view holder recycling with differential updates (`notifyItemRangeRemoved` / `notifyItemRangeInserted`) on collapse/expand.
- **NFR-03 (UFMStandard Compliance):**
  - **Mobile:** 56dp glass header (`@color/mobile_glass_header`), squircle icon buttons (`@drawable/bg_btn_icon_frosted`), Material 3 Expressive buttons, glass cards for settings.
  - **Android TV:** 72dp glass header (`@color/tv_glass_white_10`), high-contrast yellow focus selectors (`selector_tv_button_yellow`, `selector_tv_icon_btn`), distinct folder expand/collapse focus states, and 10-foot readability.
- **NFR-04 (Permissions & Safeguards):** Honor Android scoped storage rules, all collision safeguards, overwrite prompts, and zero-byte file overwrite protections.

---

## Success Criteria

- [ ] Flat View is accessible exclusively from the root folder of indexed storages via the 3-dots overflow menu directly below Twin Window.
- [ ] Data queries load directly from the Room `file_index` database with instant rendering.
- [ ] Root and all descendant subfolders render as collapsible/expandable headers with relative paths, all expanded by default.
- [ ] Tapping any folder header or the root header toggles its collapsed/expanded state smoothly.
- [ ] Folder headers include an "Open Folder" action to jump to standard hierarchical browsing at that path.
- [ ] `Android/data` and `Android/obb` are hidden by default and included only when enabled in Settings.
- [ ] A dedicated "Flat View" card exists in Settings under "Browsing & File List".
- [ ] Normal edit mode, multi-select, Tools FAB (`fabTools`), and Floating Quick Action Bar (`floatingQuickBar`) work identically to standard browser mode.
- [ ] Checking a folder header in Edit Mode automatically selects all files in that folder.
- [ ] "Select All" selects all files across both expanded and collapsed folders.
- [ ] All file operations (copy, move, delete, rename, share, zip) correctly target actual physical file locations.
- [ ] Full Android TV remote/D-pad navigation and UFMStandard visual parity.
