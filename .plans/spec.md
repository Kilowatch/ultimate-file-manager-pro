# Specification: Folder File List Stability & Background Sync Optimization (Fix Ghost Scrolling & Lag)

## Overview
This specification addresses an issue where entering any folder causes the file list to become laggy and autonomously jump/scroll up and down without user interaction. The root problem arises from background operations (such as startup indexing reconciliation, folder synchronization, and asynchronous directory child/size calculations) repeatedly triggering full list invalidations and conflicting scroll adjustments, resulting in visual jitter and interface lag. This document defines the requirements to guarantee a completely stationary list when untouched, seamless in-place updates, and fluid scrolling performance.

---

## User Stories
- **As a user opening any folder**, I want the file list to remain completely stationary until I choose to scroll it myself, so that I can comfortably view and select items without disorienting automatic jumps.
- **As a user viewing a directory while background indexing or folder synchronization is running**, I want visible items to update silently and seamlessly without resetting scroll position, stuttering, or dropping frames.
- **As a user scrolling through large folders**, I want smooth, fluid 60/120 fps scrolling with zero lag or frame drops.
- **As a user navigating back and forth between folders**, I want my exact scroll position and item focus to be accurately maintained without bouncing or shifting.
- **As an Android TV user**, I want focused list items to remain stably centered in focus without unexpected focus hopping or screen jumping.

---

## Functional Requirements
- **FR-01: Stationary List State (Zero Autonomous Movement)**: When a user enters any folder and does not touch the screen, the list must remain completely still. No ghost scrolling, jitter, or jumping up and down shall occur under any circumstances.
- **FR-02: Redundant Update Suppression**: Background operations (such as storage indexing, startup reconciliation, or folder synchronization) that do not alter the visible files or directories of the currently open folder must not cause re-rendering, layout recalculation, or scroll adjustments.
- **FR-03: Seamless In-Place Content Updates**: When background operations detect genuine changes in visible folder contents (e.g. a file is added, deleted, or renamed, or metadata like folder size/item count is resolved), changes must update in-place without resetting scroll offset or jarring the user's viewport.
- **FR-04: Viewport & Scroll Position Stability**: During same-folder content updates, the user's current scroll position and relative viewport offset must be strictly preserved without resetting to the top or drifting across updates.
- **FR-05: Non-Disruptive Asynchronous Metadata Resolution**: Background metadata resolution (including directory child counts, total folder sizes, and thumbnail caching) must complete asynchronously and apply updates smoothly without triggering full list invalidations or visible layout shifts.
- **FR-06: Cross-Browser Parity**: File list stillness, smooth scrolling, and scroll position stability must be consistent across all browser implementations:
  - Standalone File Browser (`FileBrowserActivity`)
  - Tabbed Browser and Twin Window Mode (`FileBrowserFragment`)
  - Both Mobile touch interfaces and Android TV D-pad navigation

---

## Non-Functional Requirements
- **NFR-01: Rendering Performance & Fluidity**: File list rendering must achieve smooth 60/120 fps scrolling. Background operations and database updates must not block the main thread, cause dropped frames, or introduce perceptible UI lag.
- **NFR-02: Zero Layout Thrashing**: Layout passes must be minimized. The adapter and layout manager must not perform repeated layout passes or post conflicting scroll commands when list data has not changed.
- **NFR-03: Resource & Battery Efficiency**: Background folder synchronization and database queries must operate with low CPU and memory footprint, preventing UI thread starvation on lower-end hardware and Android TV devices.

---

## Edge Cases & Error States
- **Empty Folders**: An empty folder undergoing background sync or indexing must remain in a stable empty state without flickering or resetting empty-state animations.
- **Large Directories (1,000+ items)**: Background metadata extraction and child counting in large folders must not cause memory pressure or repeated layout recalculations that freeze the list.
- **Rapid Navigation**: Navigating quickly between multiple nested folders before background sync completes must cancel obsolete sync and scroll jobs cleanly, preventing cross-folder scroll state leakage.
- **Active User Touch / Drag Gesture**: If the user is actively dragging, flinging, or scrolling the list when a content update occurs, the ongoing gesture must not be interrupted, aborted, or jerked.
- **External Storage / SAF / Protected Storage**: Directory listing on slower storage media (USB OTG, SD cards, SAF document trees) must maintain the same scroll stability and non-laggy behavior as internal storage.
- **Device Orientation & Configuration Changes**: Screen rotation or window resizing in split-screen/tablet mode must preserve scroll position without triggering recursive reload cycles.

---

## Out of Scope
- Modifying the underlying SQLite indexing database schema or storage tables.
- Altering the visual design, typography, or styling of file list item rows.
- Network or cloud storage browsers (e.g. SMB, WebDAV, Cloud Drive) which do not use local Room database indexing flows.

---

## Success Criteria
- [ ] Navigating into any folder and waiting without touching the screen leaves the list 100% stationary with zero autonomous movement.
- [ ] The file list does not jump, twitch, or reset its scroll position when background operations run.
- [ ] Scrolling through folders is smooth and responsive with no noticeable lag or stutter.
- [ ] Folder child counts, sizes, and thumbnails load progressively without causing list jumps.
- [ ] Verified across both Mobile and Android TV interfaces.
