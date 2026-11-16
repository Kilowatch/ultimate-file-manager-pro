# Specification: Enhanced Long Filename Display & Interactive Scrolling Options

## Overview
In single-line filename display modes, files with long names that differ only at their suffix or version tag (e.g., `backup_project_report_2026_v1.docx` vs `backup_project_report_2026_v2.docx`) are difficult to distinguish when truncated at the end (`...`) or when multiple long filenames scroll simultaneously in a distracting manner. This specification defines requirements for advanced filename presentation in single-line listings, introducing middle truncation (preserving both prefix and discriminating suffix/extension) and customizable marquee scrolling options (configurable scroll speed, one-shot vs continuous looping, focused/selected item scrolling scope, and manual touch scrubbing).

---

## User Stories
- **As a user managing multiple file revisions, versions, or date-stamped backups**, I want long filenames to truncate in the middle (displaying the beginning, an ellipsis, and the trailing suffix before the extension) so that I can immediately distinguish between versions without opening or renaming files.
- **As a user browsing file lists with scrolling text enabled**, I want to customize the scroll speed (Slow, Normal, Fast) so that the animation moves at a comfortable reading pace for my eyes.
- **As a user reading long filenames**, I want the option to have marquee scrolling loop continuously back-and-forth rather than stopping after one cycle, so that I don't miss the ending if my attention was elsewhere when the item came into view.
- **As a user navigating dense directories**, I want the ability to restrict automatic scrolling to only the currently selected or focused file (rather than all visible files scrolling at once), reducing visual clutter and distraction.
- **As a mobile user**, I want to manually scrub/swipe horizontally across a truncated or long filename with my finger to immediately inspect any portion of the filename on demand.
- **As an Android TV user**, I want D-pad focus to cleanly drive scrolling on the focused item while maintaining clear readability on unfocused items.

---

## Functional Requirements

### 1. Middle Truncation Mode ("Truncate Middle")
- **FR-01: Middle Truncation Option**: Provide a dedicated display mode option in Settings alongside existing options:
  - *Scrolling Text (Marquee)*
  - *2 Lines (Wrapped)*
  - *3 Lines (Wrapped)*
  - *Unlimited Lines (Full Name)*
  - *Truncate End (`abc...`)*
  - *Truncate Middle (`abc...xyz.ext`)*
- **FR-02: Preservation of Identifying Suffix and Extension**:
  - In Middle Truncation mode, single-line filenames that exceed the available width must be abbreviated by placing the ellipsis (`...`) in the middle of the base filename.
  - The display must preserve the beginning of the filename, an ellipsis, and at least 3–5 trailing characters of the base filename plus the file extension (e.g., `VeryLongProjectFileName_Final_...rev3.docx`).
  - Files without extensions must truncate cleanly in the middle of the full name.
  - Files with compound extensions (e.g., `.tar.gz`) must preserve the complete extension suffix.

### 2. Configurable Marquee Scroll Options
- **FR-03: Adjustable Scroll Speed**:
  - Under Scrolling Text settings, allow users to choose the animation speed:
    - *Slow* (e.g., ~30 dp/s)
    - *Normal* (default, e.g., ~50 dp/s)
    - *Fast* (e.g., ~80 dp/s)
- **FR-04: Loop & Repeat Behavior**:
  - Provide a toggle or selector for marquee scroll repetition:
    - *One-Shot* (default): Scroll forward to reveal the end, pause, scroll back to start, and stop.
    - *Continuous Loop*: Scroll forward, pause, scroll back, pause, and continuously repeat the cycle while visible.
- **FR-05: Scroll Scope & Activation Mode**:
  - Provide an option to control which files trigger scrolling text:
    - *All Visible Files*: Any visible file item with an overflowing name automatically scrolls.
    - *Selected / Focused Item Only*: Filenames remain static (truncated) until an item is focused (Android TV / keyboard) or selected/tapped, scrolling only that active item.

### 3. Manual Touch Scrubbing / Interactive Pan
- **FR-06: Horizontal Scrub Gesture**:
  - When viewing single-line filenames (whether in Truncate End, Truncate Middle, or Marquee mode), users can drag or swipe horizontally across the filename text to manually scrub and inspect the hidden text.
  - The gesture must disambiguate cleanly between a horizontal text scrub, a tap to open/select the file, and a vertical list scroll.
  - When manually scrubbed, the text remains fixed at the scrubbed position and any ongoing marquee animation halts for that view instance.
  - The text offset stays at the scrubbed position while the row remains visible on screen, and resets back to normal behavior when the row is scrolled off-screen and re-bound by the RecyclerView.

### 4. Settings UI & Live Preview
- **FR-07: Unified File Name Display Settings UI**:
  - The Settings screen ("Scrolling text" / "File Name Display") must provide a comprehensive configuration dialog conforming to `UFMStandard`.
  - Include an interactive live preview demonstrating how sample long filenames look with the selected mode, speed, and loop settings.
- **FR-08: Settings Backup & Restore**:
  - All new preferences (display mode, scroll speed, loop behavior, scroll scope) must be registered with `SettingsBackupManager` to persist across device backup and restore.

---

## Non-Functional Requirements
- **NFR-01: Performance & Frame Rate**: Scrolling animations and touch scrub listeners must maintain 60/120 FPS on standard Android devices. Views must not trigger repetitive layout passes or measure passes during animation.
- **NFR-02: Memory & View Recycling**: All animators, handlers, and touch listeners must cleanly detach and cancel when views are recycled in RecyclerView (`onViewRecycled`), preventing memory leaks.
- **NFR-03: TV & Accessibility Compliance**:
  - Android TV layout must support D-pad focus navigation with clear focus indicators.
  - If the user disables system animations in Android Accessibility settings (`ANIMATOR_DURATION_SCALE == 0`), marquee animations must be disabled, gracefully falling back to static middle or end truncation.
- **NFR-04: UI Consistency**: All dialogs, radio selectors, and sliders/spinners must adhere to `UFMStandard` (glass morphism styling, custom buttons, dual mobile/TV layouts).

---

## Edge Cases & Error States
- **Extremely Short Filenames**: Files whose full names easily fit within the available width must never display ellipses or trigger animations.
- **No File Extension or Hidden Files**: Files like `.gitignore`, `LICENSE`, or files with no dot must middle-truncate without syntax errors or miscalculating extension indices.
- **Long Extensions**: Files with unusually long extensions (e.g. `.compressed_backup_data`) must not crowd out the base filename; minimum base characters before and after the ellipsis must always be preserved.
- **RTL & Complex Scripts**: Ensure truncation and scrolling handle bi-directional text and complex scripts without corrupting character rendering or reversing punctuation.
- **RecyclerView Fling / Rapid Scroll**: Fast fling gestures must immediately cancel ongoing text animations on recycled rows without visual glitches or text jumping.
- **Selection / Drag-and-Drop Interaction**: In multi-select mode or drag-to-reorder modes, touch scrubbing on filename text must not conflict with item selection checkmarks or item dragging.

---

## Out of Scope
- Renaming, editing, or modifying the actual file names on disk.
- Changing multi-line wrapping behavior for users who explicitly choose 2-line, 3-line, or unlimited line modes.
- Modifying grid view layout items beyond preserving existing grid truncation standards.
- Text-to-speech or voice readout of filenames.

---

## Success Criteria
- [ ] Users can select "Truncate Middle" mode in Settings, displaying `prefix...suffix.ext` in single-line listings.
- [ ] Base filename suffix (last 3–5 characters) and extension remain discernible for long filenames in middle truncation mode.
- [ ] Users can configure marquee scroll speed (Slow, Normal, Fast).
- [ ] Users can select between One-Shot and Continuous looping marquee animation.
- [ ] Users can restrict scrolling to Selected/Focused files only to eliminate distracting multi-item scrolling.
- [ ] Users can manually touch-scrub horizontally across long filenames to inspect hidden parts.
- [ ] Settings backup and restore correctly exports and restores all new filename display preferences.
- [ ] Feature works consistently on both Mobile and Android TV interfaces without performance regressions.
