# Task List: Enhanced Long Filename Display & Interactive Scrolling Options

## Setup
- [x] [T001] [P] Add string resources to `app/src/main/res/values/strings.xml` for Truncate Middle, Marquee Speed (Slow, Normal, Fast), Marquee Loop (One-Shot, Continuous), Marquee Scope (All Files, Focused / Selected Only), and Preview.

## Foundation
- [x] [T002] [P] Update `ScrollingTextPreferenceManager.kt` with `MODE_TRUNCATE_MIDDLE`, preference keys (`KEY_SCROLL_SPEED`, `KEY_LOOP_MODE`, `KEY_SCROLL_SCOPE`), constants, and getters/setters.
- [x] [T003] [P] Implement `truncateMiddle(...)` algorithm in `FileNameDisplayHelper.kt` to calculate prefix + "…" + suffix (at least 4 chars) + full extension based on available text width and compound extensions.
- [x] [T004] [P] Enhance `ScrollingTextHelper.kt` with variable speed calculation (30, 50, 80 dp/s), continuous looping support, and manual touch scrub state management (`attachTouchScrubbing`, `cancelScrolling` reset).

## Feature Implementation
- [x] [T005] Update `FileNameDisplayHelper.applyFileNameDisplay` to support `MODE_TRUNCATE_MIDDLE`, Marquee scope checks (`SCOPE_ALL` vs `SCOPE_FOCUSED`), and wire `attachTouchScrubbing` for single-line modes on touch devices.
- [x] [T006] Update `FileAdapter.kt` to pass `isSelected` state into `applyFileNameDisplay`, hook TV focus changes to marquee scroll for `SCOPE_FOCUSED`, and ensure `recycle()` resets scroll and scrub state.
- [x] [T007] Update `NetworkFileAdapter.kt` to pass `isSelected` state into `applyFileNameDisplay`, hook TV focus changes for `SCOPE_FOCUSED`, and ensure `recycle()` resets scroll and scrub state.
- [x] [T008] Update `SafPickerActivity.kt` to support middle truncation and scrolling preferences consistently.
- [x] [T009] [P] Update `app/src/main/res/layout/dialog_file_name_display.xml` with `btnModeTruncateMiddle` option card, expandable Marquee sub-options (Speed chips, Loop chips, Scope chips), and an interactive preview card.
- [x] [T010] [P] Create `app/src/main/res/layout/dialog_file_name_display_tv.xml` adhering to `UFMStandard` (glass dialog background, 650dp width, D-pad focusable items with TV yellow focus selectors).
- [x] [T011] Update `SettingsActivity.kt` to wire `dialog_file_name_display.xml` and `dialog_file_name_display_tv.xml`, handle `MODE_TRUNCATE_MIDDLE`, manage speed/loop/scope toggles, run the live preview animation, and update settings subtitles.

## Polish & Testing
- [x] [T012] Verify debug compilation with `./gradlew assembleMobileGoogleDebug`.
- [x] [T013] Run `python -u translate_strings.py verify` to sync translations for all newly added string keys.
