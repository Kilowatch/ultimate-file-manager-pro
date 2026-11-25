# Clarifications: Complete Icon Options Across All Sections in Customize Icons

## Status
Answers received — ready for /tasks

## Questions and Answers

### Business Logic and Edge Cases
**Q1.** In `StorageBrowserActivity.buildAllKnownTiles()`, `extracts_tile` is only included when `UFM-Extracted` is non-empty and `recycle_bin_tile` is only included when Recycle Bin is enabled. In **Customize Icons -> Main Menu Tiles**, should `extracts_tile` and `recycle_bin_tile` always be listed (even when empty/disabled), or should they match `buildAllKnownTiles()` and only appear when active?
**A:** Always list both `extracts_tile` and `recycle_bin_tile` so users can customize them anytime.

**Q2.** `SettingsActivity` hides `cardFossUpdateCheck` (`settings_foss_update`) on non-FOSS builds (Google Play / Amazon). Should `settings_foss_update` only appear in **Customize Icons -> Settings** on FOSS builds, or be listed on all builds?
**A:** Only show `settings_foss_update` on FOSS builds (matching `SettingsActivity`).

**Q3.** Should user-created Custom Tiles (folder/group tiles created on the Main Menu) also be listed in **Customize Icons -> Main Menu Tiles**, or should the list only include the tiles from `StorageBrowserActivity.buildAllKnownTiles()`?
**A:** Include user-created Custom Tiles in Main Menu Tiles if they have customizable icons.

## Spec / Plan Impact
- **FR-01 (Main Menu Tiles Completeness & Remote Tile)** in `.plans/spec.md`: Clarified that `extracts_tile` and `recycle_bin_tile` are always listed in Customize Icons (even when `UFM-Extracted` is empty or Recycle Bin is disabled), and user-created Custom Tiles (if customizable via `TileIconManager`) are also included alongside `StorageBrowserActivity.buildAllKnownTiles(this)`.
- **FR-03 (Settings Icons Completeness)** in `.plans/spec.md`: Clarified that `settings_foss_update` is gated to FOSS builds (`!isTv` / FOSS flavor check matching `SettingsActivity`), just as `settings_security` is gated to `!isTv` and `settings_tv_background_server` is gated to `isTv`.

## Confirmed — Ready for /tasks
All questions are answered and recorded. You can proceed to `/plan` or `/tasks`.
