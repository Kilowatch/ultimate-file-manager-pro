# Task List: Flat View (Indexed Root Browser Mode)

## Setup
- [x] [T001] [P] Add string resources in `app/src/main/res/values/strings.xml`:
  - Add translatable strings: `flat_view`, `flat_view_subtitle`, `flat_view_open_folder`, `flat_view_expand_all`, `flat_view_collapse_all`, `flat_view_include_system_folders`, `flat_view_include_system_folders_desc`, `flat_view_default_expanded`, `flat_view_empty_notice`, `flat_view_settings_title`, `flat_view_settings_desc`, `flat_view_not_indexed_title`, `flat_view_not_indexed_msg`.
- [x] [T002] Create vector drawable `app/src/main/res/drawable/ic_flat_view.xml`:
  - Vector icon representing flat/unfolded directory view for menu items and settings.
- [x] [T003] Create `FlatViewPreferenceManager.kt` in `za.kilowatch.ultimatefilemanager.settings`:
  - SharedPreferences accessors for `isSystemFoldersIncluded`, `setSystemFoldersIncluded`, `isDefaultExpanded`, and `setDefaultExpanded`.
- [x] [T004] Register Flat View preferences in `SettingsTransferManager.kt`:
  - Add `SettingItem` entries for Flat View preferences to ensure persistence across settings backup/restore.

## Foundation
- [x] [T005] [P] Add `getFlatViewFiles` query in `FileIndexDao.kt`:
  - Implement optimized Room query `getFlatViewFiles(storageId: String, excludeSystem: Boolean): List<FileIndex>` filtering out `Android/data` and `Android/obb` when `excludeSystem` is true.
- [x] [T006] Create `FlatViewItem.kt` in `za.kilowatch.ultimatefilemanager.flatview`:
  - Define data model representing items in the flattened adapter list: `FlatFolderHeader` (folder path, relative path, file count, isExpanded, isSelected, isRoot) and `FlatFileEntry` (fileIndex, relativeParentPath, isSelected).
- [x] [T007] [P] Create `FlatViewViewModel.kt` in `za.kilowatch.ultimatefilemanager.flatview`:
  - Room database querying on `Dispatchers.IO`, in-memory folder grouping with relative paths, expansion state tracking (all expanded by default), local text filter, and selection state.

## Feature Implementation
- [x] [T008] Create layout files for folder headers and file items:
  - Create `app/src/main/res/layout/item_flat_folder_header.xml` and `item_flat_folder_header_tv.xml` (chevron, folder icon, relative path, count badge, checkbox, "Open Folder" jump button).
  - Create `app/src/main/res/layout/item_flat_file.xml` and `item_flat_file_tv.xml` (thumbnail/icon, filename, relative parent path, size, date, checkbox).
- [x] [T009] [P] Create `FlatViewAdapter.kt` in `za.kilowatch.ultimatefilemanager.flatview`:
  - Virtualized `RecyclerView.Adapter` with view types for root header, folder header, and file item.
  - Implement collapse/expand with differential updates (`notifyItemRangeInserted` / `notifyItemRangeRemoved`).
  - Wire folder header click (toggle expand/collapse), "Open Folder" click, and file clicks.
  - Wire D-pad focus states and yellow focus borders on TV.
- [x] [T010] Create activity layouts for `FlatViewActivity`:
  - Create `app/src/main/res/layout/activity_flat_view.xml` (56dp frosted glass header, search/filter bar, action chips for Expand All/Collapse All, RecyclerView, `fabTools`, `floatingQuickBar`).
  - Create `app/src/main/res/layout/activity_flat_view_tv.xml` (72dp glass header, TV buttons, D-pad navigable list).
- [x] [T011] [P] Create `FlatViewActivity.kt` in `za.kilowatch.ultimatefilemanager.flatview`:
  - Implement single Activity class switching between mobile and TV layouts (`DeviceUtils.isTvDevice(this)`).
  - Override `attachBaseContext` (`LocaleManagerWrapper`), enable edge-to-edge (`EdgeToEdge.enable`).
  - Implement edit mode, long-press selection, folder header checkbox cascading to contained files, Select All across all folders.
  - Wire `fabTools` and `floatingQuickBar` to existing `FileOperations` (Copy, Move, Delete, Rename, Share, Zip, Properties).
  - Register `FlatViewActivity` in `AndroidManifest.xml`.
- [x] [T012] Add Flat View entry point to 3-dots popup menu:
  - Update `app/src/main/res/layout/popup_header_options_menu.xml`: Add divider and `menuItemFlatView` directly below `menuItemTwinWindow`.
  - Update `FileBrowserActivity.kt` (`showOverflowMenu`): Show `menuItemFlatView` when browsing at root of an indexed storage, and launch `FlatViewActivity`.
  - Update `TabbedBrowserActivity.kt` (`showOverflowMenu`): Show `menuItemFlatView` when active tab is at root of an indexed storage, and launch `FlatViewActivity`.
- [x] [T013] Add Flat View configuration card in `SettingsActivity.kt`:
  - Add `cardFlatView` in the "Browsing & File List" section in `SettingsActivity.kt` (and TV layout).
  - Wire click to open Flat View settings dialog/sheet (toggle "Include System Folders", toggle "Default Folder Expansion").

## Polish & Testing
- [x] [T014] Implement unit tests for `FlatViewViewModel` and `FileIndexDao`:
  - Unit tests for grouping, expand/collapse logic, system folder filtering, and search filtering.
- [x] [T015] [P] Compile and verify build with `assembleMobileGoogleDebug`:
  - Execute `./gradlew assembleMobileGoogleDebug` to ensure clean build, zero compiler errors, and verification of Android conventions.
