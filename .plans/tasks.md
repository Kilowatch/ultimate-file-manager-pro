# Task List: Storage Analyzer – Large Files Action Menu (Move, Delete, Open, Open Folder Location) with Auto-Reindex

## Setup
- [x] [T001] Add string resources in `app/src/main/res/values/strings.xml` for single-file delete confirmation (`analyzer_delete_single_confirm_title`, `analyzer_delete_single_confirm_msg`), move progress, and success feedback.
- [x] [T002] [P] Create Mobile UFMStandard Glass Action Dialog layout (`app/src/main/res/layout/dialog_analyzer_large_file_actions.xml`) with file header badge, filename, path, size, glass divider, and action rows for Open, Open Folder, Move, Delete, and Cancel.
- [x] [T003] [P] Create Android TV UFMStandard Glass Action Dialog layout (`app/src/main/res/layout/dialog_analyzer_large_file_actions_tv.xml`) with 650dp width styling, D-pad focus traversal, and TV-compliant focus selectors.

## Foundation
- [x] [T004] [P] Update `AnalyzerLargeFileAdapter.kt` to add `onLongClick: ((FileIndex, View) -> Boolean)? = null` callback, bind `setOnLongClickListener` on item views, and add `removeItem(file: FileIndex)` for zero-latency UI updates.
- [x] [T005] Update `LargeFilesTabFragment` in `StorageAnalyzerActivity.kt` to wire the adapter's `onLongClick` callback to `(activity as? StorageAnalyzerActivity)?.showLargeFileActionDialog(file)`.

## Feature Implementation
- [x] [T006] [P] Implement `showLargeFileActionDialog(file: FileIndex)` in `StorageAnalyzerActivity.kt` supporting Mobile and TV layouts with transparent window backgrounds, proper window dimensions, and action button event wiring.
- [x] [T007] Implement "Open File" (via `FileViewerRouter.openFile`) and "Open Folder Location" (via `FileBrowserActivity` intent with `EXTRA_INITIAL_PATH` and `EXTRA_FOCUS_PATH`) in `StorageAnalyzerActivity.kt`.
- [x] [T008] [P] Implement Folder Picker integration and Move pipeline in `StorageAnalyzerActivity.kt`: register `moveFolderPickerLauncher` with `StorageBrowserActivity` (`EXTRA_LOCATION_PICKER = true`), validate destination (guard against same folder), resolve collisions via `TransferConflictHelper`, and execute atomic move across volumes.
- [x] [T009] [P] Implement single-file Delete flow in `StorageAnalyzerActivity.kt`: show UFMStandard glass delete confirmation dialog (`dialog_analyzer_delete_confirm` / TV variant), execute deletion via `SafTreeManager` / `ShizukuShellWrapper` / direct `File.delete()`, and show completion feedback.
- [x] [T010] [P] Implement post-operation auto-reindexing and live report refresh in `StorageAnalyzerActivity.kt`: synchronize `IndexingRepository` database, trigger background folder reindexing, notify `MediaScannerConnection`, and refresh `viewModel.analyze(...)` while immediately updating the adapter list.

## Polish & Testing
- [x] [T011] Run `python -u translate_strings.py verify` to check translation consistency for newly added strings.
- [x] [T012] Execute Gradle compile and build check (`./gradlew assembleDebug`) to verify zero compiler errors or missing resources.
- [x] [T013] Verify edge case handling: same-folder move rejection, name collision resolution (Overwrite/Keep Both/Skip/Cancel), permission errors, cancellation flows, and Android TV D-pad focus states.
