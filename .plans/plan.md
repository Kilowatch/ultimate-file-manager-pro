# Implementation Plan: Complete Icon Options Across All Sections in Customize Icons

## Architecture & Approach
`IconCustomizationActivity` is the single UI entry point for **Settings -> Customize Icons** on both Mobile and Android TV. Currently it maintains separate, manually curated lists for Main Menu Tiles, Toolbar Actions, and Settings icons, which drifted out of sync with `StorageBrowserActivity.buildAllKnownTiles()`, `ToolbarIconsActivity.getSections()`, and `SettingsActivity.applySettingsCardIcons()`. `IconPackExportActivity` also duplicates these category key lists.

To fix the missing options and prevent future drift:
1. **Main Menu Tiles (`IconCustomizationActivity.buildMainMenuTiles()`)**:
   - Source tiles from `StorageBrowserActivity.buildAllKnownTiles(this)` (deduplicated by tile ID) instead of a partial hardcoded list.
   - Explicitly ensure `extracts_tile` and `recycle_bin_tile` are always included (even when `UFM-Extracted` is empty or Recycle Bin is disabled).
   - Append user-created Custom Tiles from `CustomTileManager.loadCustomTiles(this)`.
   - Remove the inverted `if (isTv)` block for `tv_remote_tile` (`buildAllKnownTiles()` already gates it to `!isTv`).
2. **Toolbar Actions (`IconCustomizationActivity.buildToolbarIcons()`)**:
   - Add the 11 missing keys from `ToolbarIconsActivity.getSections()`: `toolbar_open_with`, `toolbar_open_as`, `toolbar_upload_other_storage`, `toolbar_protect`, `toolbar_unprotect`, `toolbar_retrigger_thumbnails`, `toolbar_duplicate_finder`, `toolbar_large_files_finder`, `toolbar_extract_subtitles`, `toolbar_extract_audio`, and `toolbar_convert_to_mp4`.
3. **Settings (`IconCustomizationActivity.loadIconCategories()`)**:
   - Add the 21 missing keys from `SettingsActivity.applySettingsCardIcons()`, with `settings_foss_update` gated on `BuildConfig.IS_FOSS`, `settings_security` gated on `!isTv`, and `settings_tv_background_server` gated on `isTv`.
4. **Remaining Categories & Export Sync (`IconCustomizationActivity` + `IconPackExportActivity`)**:
   - Ensure `utility` includes `network_dlna` and all categories in `IconPackExportActivity.loadCategories()` match `IconCustomizationActivity` 1:1.
5. **Strings & Translation Sync**:
   - Add any missing `icon_settings_*` string resources in `app/src/main/res/values/strings.xml` and run `python -u translate_strings.py verify`.

## Files to Modify
- `app/src/main/java/za/kilowatch/ultimatefilemanager/settings/IconCustomizationActivity.kt`
- `app/src/main/java/za/kilowatch/ultimatefilemanager/settings/IconPackExportActivity.kt`
- `app/src/main/res/values/strings.xml` (and locale `strings.xml` files via `translate_strings.py verify`)

## Verification
- `./gradlew assembleMobileGoogleDebug`
- `./gradlew testMobileGoogleDebugUnitTest`
- `python -u translate_strings.py verify`
- Manual verification on Mobile and Android TV in **Settings -> Customize Icons** and **Export Icon Pack**.
