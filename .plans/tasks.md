# Task List: Actual vs. Nominal File Size Detection & Sparse/Incomplete File Handling

## Setup & Resources
- [x] [T001] [P] Add string resources in `app/src/main/res/values/strings.xml` for dual size labels, sparse warning badges, retention policies, compare results, and analyzer cards — blocks UI integration.
- [x] [T002] Create amber badge drawable `app/src/main/res/drawable/bg_badge_sparse.xml` for partial/incomplete download indicators.

## Foundation & Core Utilities
- [x] [T003] [P] Create `za.kilowatch.ultimatefilemanager.util.SparseFileUtils.kt` with POSIX `st_blocks` inspection, `FileAllocation` data model, and `comparePartialFiles()` — foundation for all sparse file detection.
- [x] [T004] Create unit tests in `app/src/test/java/za/kilowatch/ultimatefilemanager/util/SparseFileUtilsTest.kt` verifying allocation calculation, percentage math, and threshold edge cases.

## Feature Implementation
- [x] [T005] Update `app/src/main/res/layout/dialog_file_properties.xml` to add `rowSizeOnDisk` (label, formatted value, and `badgeSparseWarning`) adhering to UFMStandard glass theme.
- [x] [T006] Update `FilePropertiesBottomSheet.kt` to query `SparseFileUtils.getFileAllocation()`, populate actual size on disk, and show the sparse warning badge.
- [x] [T007] [P] Update `DuplicateRetentionManager.kt` to add `MODE_MOST_COMPLETE = "most_complete"` and `KEEP_MOST_COMPLETE` retention preset.
- [x] [T008] Update `item_analyzer_duplicate_file.xml` and `item_analyzer_duplicate_file_tv.xml` to add `txtDupSparseBadge`.
- [x] [T009] [P] Update `AnalyzerDuplicateAdapter.kt` to bind sparse badges and enforce complete file protection + `KEEP_MOST_COMPLETE` in `selectAllDuplicates()`.
- [x] [T010] Update `FileCompareDialogFragment.kt`, `dialog_compare_files.xml`, and `dialog_compare_files_tv.xml` (strictly adhering to UFMStandard mobile/TV glass styling) to perform partial file header/offset checks and display partial match status.
- [x] [T011] Update `StorageAnalyzerEngine.kt` to add `getIncompleteDownloads()` and generate the `Incomplete / Sparse Downloads` cleanup recommendation.

## Polish, Verification & Testing
- [x] [T012] Update `app/src/test/java/za/kilowatch/ultimatefilemanager/storage/DuplicateRetentionTest.kt` with tests for `KEEP_MOST_COMPLETE` and complete file protection.
- [x] [T013] Run unit test suite and execute debug build (`./gradlew assembleMobileGoogleDebug`) to verify zero compilation errors.
