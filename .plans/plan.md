# Implementation Plan: Settings Hierarchy, Granular Groupings & Logical Sequencing

## Goals
- Break down monolithic settings groups (File Browsing 14 items, Media Player 11 items, Network & Performance 9 items, Appearance 9 items) into intuitive, bite-sized groupings of 2 to 5 items each.
- Re-sequence the settings screen top-to-bottom so it flows logically from visual/interaction setup, through core file operations, to media, connectivity, and system maintenance.
- Maintain complete symmetry between Mobile (`activity_settings.xml`) and Android TV (`activity_settings_tv.xml`).
- Ensure 100% compatibility with real-time settings search filtering, TV D-pad focus traversal, and card icon customizations.

## Architecture Overview
The settings screen uses a vertical `layoutSettingsList` inside a ScrollView. Each section is a `LinearLayout` containing:
1. A small all-caps bold section header `TextView` (e.g. `headerGeneral`, `headerThemeDisplay`).
2. A single `MaterialCardView` (`cardGroup...`) with rounded corners and border stroke.
3. An inner vertical `LinearLayout` containing the setting rows (`card...`) separated by hairline `View` dividers.

`SettingsActivity.kt` dynamically inspects every child of `layoutSettingsList`, traverses inner group cards, and performs real-time search filtering. By retaining this exact architectural contract for each new section, search filtering and focus management will work out-of-the-box without touching the core search traversal logic.

```
Settings Flow:
1. General & Interface
2. Appearance & Display (Theme & Display -> Interface Details & Animations)
3. Navigation & Controls (Quick Access [Mobile] -> Controls & Gestures -> Dual-Pane)
4. File Management & Storage (Permissions -> Browsing & Display -> File Safety -> Tools -> SAF)
5. Thumbnails & Media (Thumbnails & Previews -> Media Playback -> Player Controls & Gestures)
6. Network & Connectivity (Transfers & Downloads -> Network Caching & Streaming -> Servers & Indexing)
7. Security & Access (Security & App Lock)
8. System, Maintenance & About (Accessibility -> Backup & Restore -> Privacy & Diagnostics)
```

## Files to Modify

### `app/src/main/res/values/strings.xml`
- **What:** Add localized section title string resources:
  - `settings_section_theme_display` ("Theme & Display")
  - `settings_section_interface_animations` ("Interface Details & Animations")
  - `settings_section_permissions_storage` ("Permissions & Storage Access")
  - `settings_section_browsing_display` ("Browsing & Display")
  - `settings_section_file_safety` ("File Operations & Safety")
  - `settings_section_organization_tools` ("Organization & File Tools")
  - `settings_section_saf_transfers` ("Storage Transfers & SAF")
  - `settings_section_thumbnails` ("Thumbnails & Previews")
  - `settings_section_media_playback` ("Media Playback")
  - `settings_section_player_controls` ("Player Controls & Gestures")
  - `settings_section_transfers_downloads` ("Transfers & Downloads")
  - `settings_section_network_caching` ("Network Caching & Streaming")
  - `settings_section_servers_indexing` ("Servers & Indexing")
  - `settings_section_security` ("Security & App Lock")

### `app/src/main/res/layout/activity_settings.xml`
- **What:** Reorganize the settings container into the newly defined 21 granular sections and sequence them in the approved top-to-bottom order.
- **Key details:**
  - Wrap each group of 2-5 cards inside its own `layoutSection*` and `cardGroup*`.
  - Maintain consistent divider insets (`56dp`) and card elevation / stroke styling.
  - Preserve all existing card IDs (`cardSettingsSearch`, `cardFontSize`, `cardDuplicateRetention`, etc.).

### `app/src/main/res/layout/activity_settings_tv.xml`
- **What:** Reorganize the TV settings layout symmetrically into matching granular sections and order.
- **Key details:**
  - Maintain TV-specific dimensions (`20dp` corner radius, `1.5dp` stroke width, `14sp` header font, `64dp` divider margin).
  - Include all TV cards in their corresponding new sections while excluding mobile-only cards.

### `app/src/main/java/za/kilowatch/ultimatefilemanager/settings/SettingsActivity.kt`
- **What:** Verify section view bindings and TV focus listener mappings.
- **Key details:**
  - `layoutSectionSecurity` and `layoutSectionQuickAccess` maintain their exact IDs and behavior.
  - Validate TV focus helper and search visibility recursion.

## Data Model
No data model or SharedPreferences schema changes required. All settings keys remain unchanged.

## Dependencies
None. Uses existing Android Jetpack and Material components.

## Test Plan
- Compile and build debug APK with `./gradlew assembleMobileGoogleDebug`.
- Verify settings page rendering on mobile layout: visually confirm headers, card groupings, and divider lines.
- Verify settings search: test partial queries for items in different sections; confirm empty state displays when query doesn't match; confirm full list restores on clear.
- Verify TV layout inflation and D-pad navigation.

## Risks & Mitigations
- **Risk:** Search filter hides a section header when some cards match.
  - **Mitigation:** The existing `filterSettings()` function checks `sectionHasVisibleRow`; if any child card matches, the entire section remains visible, which will automatically keep the new granular section headers visible.
- **Risk:** Divider visibility glitch at the bottom of a card group when some cards are hidden.
  - **Mitigation:** `filterSettings()` dynamically scans visible rows and hides trailing/leading dividers within `container`.
