# Tasks: Complete Icon Options Across All Sections in Customize Icons

- [x] **[T-001] Add missing `icon_settings_*` string resources in `strings.xml`**
  Add localized string resources in `app/src/main/res/values/strings.xml` for the 21 newly exposed Settings icon items (or reuse existing setting title strings where exact matches exist).

- [x] **[T-002] Update `IconCustomizationActivity.buildMainMenuTiles()` to include Remote and all Main Menu tiles**
  Source tiles from `StorageBrowserActivity.buildAllKnownTiles(this)` (deduplicated by tile ID), ensure `extracts_tile` and `recycle_bin_tile` are always included, append user-created Custom Tiles from `CustomTileManager.loadCustomTiles(this)`, and remove the inverted `if (isTv)` block for `tv_remote_tile`.

- [x] **[T-003] Update `IconCustomizationActivity.buildToolbarIcons()` with all missing Toolbar Actions**
  Add `toolbar_open_with`, `toolbar_open_as`, `toolbar_upload_other_storage`, `toolbar_protect`, `toolbar_unprotect`, `toolbar_retrigger_thumbnails`, `toolbar_duplicate_finder`, `toolbar_large_files_finder`, `toolbar_extract_subtitles`, `toolbar_extract_audio`, and `toolbar_convert_to_mp4`.

- [x] **[T-004] Update `IconCustomizationActivity` Settings & remaining categories**
  Add the 21 missing `settings_*` items from `SettingsActivity.applySettingsCardIcons()` (gating `settings_foss_update` on `BuildConfig.IS_FOSS`, `settings_security` on `!isTv`, and `settings_tv_background_server` on `isTv`), and ensure `network_dlna` / all remaining categories are in sync.

- [x] **[T-005] Synchronize `IconPackExportActivity.loadCategories()` with `IconCustomizationActivity`**
  Update `toolbar`, `settings`, and all other category key lists in `IconPackExportActivity.kt` so they match `IconCustomizationActivity` 1:1.

- [x] **[T-006] Build & run unit tests**
  Run `./gradlew assembleMobileGoogleDebug` and `./gradlew testMobileGoogleDebugUnitTest` to verify compilation and tests pass.

- [x] **[T-007] Run `python -u translate_strings.py verify` to sync translations**
  Ensure all newly added English string resources in `strings.xml` are synced across all locale `values-*/strings.xml` files.
