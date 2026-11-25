# Specification: Complete Icon Options Across All Sections in Customize Icons

## Overview
In **Settings -> Customize Icons** (`IconCustomizationActivity`), each expandable dropdown section displays a list of customizable icons. Currently, the **Main Menu Tiles** section omits `remote_tile` (Remote Manage) and several other Main Menu tiles, and has inverted platform gating for `tv_remote_tile` (`isTv` instead of `!isTv`). In addition, other sections—most notably **Toolbar Actions** and **Settings**—are missing numerous icon entries that are actively registered and used elsewhere in the app (`StorageBrowserActivity.buildAllKnownTiles()`, `ToolbarIconsActivity.getSections()`, and `SettingsActivity.applySettingsCardIcons()`), and `IconPackExportActivity` is similarly out of sync.

This specification defines the requirement to make **all** available customizable icon options available in **all** dropdown sections of Customize Icons (and synchronized with Icon Pack Export), on both Mobile and Android TV.

## User Stories
- As a user opening **Settings -> Customize Icons**, when I expand the **Main Menu Tiles** dropdown section, I want to see **Remote** (`remote_tile`) and every other available Main Menu tile (including Online Storages, Network Shares, Paired Devices, Favorites, SAF locations, Downloads Manager, Shizuku, Root Storage, Support, Add Storage Location, Other Storage, and TV Remote on mobile) so I can customize any tile's icon and colors.
- As a user expanding **Toolbar Actions**, **Settings**, or any other category in **Customize Icons**, I want every customizable icon in that category to be listed—without missing recently added settings cards or toolbar actions—so I can customize the entire app consistently.
- As an Android TV user, I want the **Main Menu Tiles** and **Settings** sections to accurately reflect TV-appropriate tiles/settings (e.g., showing `tv_remote_tile` on mobile where TV Remote is used, and `settings_tv_background_server` on TV) and let me customize every available option with the D-pad.
- As a user exporting an icon pack (`IconPackExportActivity`), I want the exported category icon lists to include all the same customizable icon IDs as **Customize Icons** so my customizations are never silently omitted from an exported pack.

## Functional Requirements
- **FR-01 (Main Menu Tiles Completeness & Remote Tile)**:
  - `IconCustomizationActivity.buildMainMenuTiles()` must include **Remote** (`remote_tile`) and all other tiles from `StorageBrowserActivity.buildAllKnownTiles(this)` (connected physical/USB storages, Online Storages, Network Shares, Paired Devices, Favorites, standalone SAF locations, and all feature shortcut tiles: `twin_window_tile`, `recent_files_tile`, `notepad_tile`, `scanner_tile` on `!isTv`, `downloads_manager_tile`, `apps_tile`, `remote_tile`, `search_tile`, `analyzer_tile`, `smart_sort_tile`, `bookmark_hub_tile` on `!isTv`, `vault_tile`, `extracts_tile` when present, `recycle_bin_tile`, `paired_devices_tile`, `tv_remote_tile` on `!isTv`, `terminal_tile`, `shizuku_tile`, `root_storage_tile` on `!isTv`, `network_tile`, `online_storages_tile`, `sync_tile`, `advanced_sync_tile`, `file_server_tile`, `add_storage_location_tile`, `other_storage_tile`, `settings_tile`, `legal_tile`, `rate_us_tile`, `tip_jar_tile`, `support_tile`, `about_tile`), deduplicated by tile ID.
  - Default icon drawables and labels in `buildMainMenuTiles()` must match `StorageBrowserActivity.buildAllKnownTiles()` (e.g., `file_server_tile` -> `ic_file_server`, `tip_jar_tile` -> `ic_coffee`, `tv_remote_tile` -> `ic_tv_remote`).
  - Fix the inverted `tv_remote_tile` check in `IconCustomizationActivity` (`!isTv` instead of `isTv`).
- **FR-02 (Toolbar Actions Completeness)**:
  - `IconCustomizationActivity.buildToolbarIcons()` must include every customizable toolbar action registered in `ToolbarIconsActivity.getSections()`, adding all currently missing items:
    - `toolbar_open_with`, `toolbar_open_as`, `toolbar_upload_other_storage`
    - `toolbar_protect`, `toolbar_unprotect`
    - `toolbar_retrigger_thumbnails`, `toolbar_duplicate_finder`, `toolbar_large_files_finder`
    - `toolbar_extract_subtitles`, `toolbar_extract_audio`, `toolbar_convert_to_mp4`
- **FR-03 (Settings Icons Completeness)**:
  - The **Settings** section in `IconCustomizationActivity` must include every settings card registered in `SettingsActivity.applySettingsCardIcons()`, adding all currently missing items:
    - `settings_folder_transitions`, `settings_default_icon_colors`, `settings_auto_backup`, `settings_search_limit`
    - `settings_saf_file_action`, `settings_saf_copy_destination`, `settings_folder_sort`, `settings_duplicate_retention`
    - `settings_flat_view`, `settings_download_manager`, `settings_autoplay_next`, `settings_background_video`
    - `settings_mini_player`, `settings_resume_interruption`, `settings_skip_length`, `settings_subtitle_style`
    - `settings_scrolling_text`, `settings_grid_indicators`, `settings_grid_text_position`
    - `settings_tip_jar_popup`, `settings_foss_update`
  - Preserve platform filtering (`settings_security` on `!isTv`, `settings_tv_background_server` on `isTv`).
- **FR-04 (All Other Sections & Export Parity)**:
  - Audit all remaining sections (`file_types`, `folders`, `navigation`, `media_player`, `utility`, `status`, `view_modes`, `feature_tiles`) against their runtime consumers (`FileTypeIconProvider`, `IconCustomizationManager` call sites, and `IconPackExportActivity`) and add any missing options so no section omits an available icon.
  - Synchronize `IconPackExportActivity.loadCategories()` with `IconCustomizationActivity` so every customizable icon ID in `file_types`, `folders`, `toolbar`, `navigation`, `settings`, `main_menu_tiles`, `media_player`, `utility`, `status`, `view_modes`, and `feature_tiles` is included in icon pack exports.
- **FR-05 (Mobile & Android TV Interaction)**:
  - All newly added items in every dropdown section must support tapping/selecting to customize (opening `TileColorBottomSheet` / `TileColorTvActivity` for `tile_*` items, and `IconPickerSheet` / `IconPickerTvActivity` for all other items) and resetting to default.
- **FR-06 (Localization)**:
  - All new icon item labels must use string resources in `app/src/main/res/values/strings.xml` (reusing existing setting/tile/toolbar strings where appropriate or adding `icon_*` strings following the repo naming convention) with no hardcoded English strings in Kotlin, and translations must be verified with `python -u translate_strings.py verify`.

## Non-Functional Requirements
- **NFR-01 (Single Source of Truth / No Duplicate Drift)**: Prefer reusing `StorageBrowserActivity.buildAllKnownTiles(this)` (or shared definitions) rather than maintaining divergent hardcoded lists where practical, so future tiles/settings/toolbar icons do not silently go missing again.
- **NFR-02 (No Regression on Existing Overrides)**: Existing user icon customizations (`IconCustomizationManager` and `TileIconManager`) must continue to load, display, and reset cleanly without migrating or breaking saved keys.
- **NFR-03 (Performance)**: Expanding any section in `IconCustomizationActivity` on Mobile or TV must remain smooth without blocking the UI thread.

## Edge Cases & Error States
- **Dynamic tiles when empty/disabled**: Tiles such as `extracts_tile` (when `UFM-Extracted` is empty/absent) or `recycle_bin_tile` (when Recycle Bin is disabled) — `buildAllKnownTiles()` only includes `extracts_tile` if non-empty and `recycle_bin_tile` if enabled, whereas `IconCustomizationActivity` previously always included `recycle_bin_tile`; ensure `recycle_bin_tile` and other fixed feature tiles remain customizable or match `buildAllKnownTiles()` cleanly without crashes if an override exists.
- **Duplicate tile IDs**: If a tile ID appears in both `getConnectedStorages()` and `buildAllKnownTiles()` (e.g., `root_storage_tile`), deduplicate by tile ID so no duplicate row appears in the **Main Menu Tiles** section.
- **Platform-specific items**: Mobile-only items (`scanner_tile`, `bookmark_hub_tile`, `tv_remote_tile`, `root_storage_tile`, `settings_security`) and TV-only items (`settings_tv_background_server`) must respect `isTv` / `!isTv` consistently.

## Out of Scope
- Redesigning the `IconCustomizationActivity` UI layout or adding new icon drawables not already in the project.
- Changing how `IconPickerSheet`, `TileColorBottomSheet`, or `IconPackImportActivity` apply icons.

## Success Criteria
- [ ] Expanding **Main Menu Tiles** in **Settings -> Customize Icons** displays **Remote** (`remote_tile`) and all other available Main Menu tiles from `StorageBrowserActivity.buildAllKnownTiles()`.
- [ ] `tv_remote_tile` appears on Mobile (`!isTv`) and not on TV (`isTv`), matching the Main Menu.
- [ ] Expanding **Toolbar Actions** shows all customizable toolbar actions from `ToolbarIconsActivity`.
- [ ] Expanding **Settings** shows all customizable settings icons from `SettingsActivity.applySettingsCardIcons()`.
- [ ] All remaining sections (`file_types`, `folders`, `navigation`, `media_player`, `utility`, `status`, `view_modes`, `feature_tiles`) contain all available options, and `IconPackExportActivity` exports all of them.
- [ ] Every newly added option can be customized and reset on both Mobile and Android TV.
- [ ] All new string resources (if any) are verified via `python -u translate_strings.py verify`.
