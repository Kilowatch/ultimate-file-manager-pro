# Specification: Modern Categorized View Expanded Category Redesign (Mobile)

## Overview
Redesign the expanded category view within the Modern Categorized main menu on mobile to match the unified card aesthetic in `expanded.jpg`. When a category is expanded, its child tiles shall render as seamless, inset-divider-separated list rows inside a single cohesive container card, eliminating separate nested sub-cards, disjointed margins, and redundant inner borders. Additionally, when the Storage category is collapsed, it displays a summary storage usage progress bar with total capacity metrics (e.g., "52.97 GB free of 145 GB").

## User Stories
- **As a UFM Pro mobile user**, I want expanded categories to display child tiles as clean, integrated rows inside a unified card so that navigating my storage, connect, sync, and feature tiles feels cohesive, fluid, and uncluttered.
- **As a power user**, I want to see an overall storage capacity summary and progress bar when the Storage category is collapsed, and detailed individual drive metrics when expanded.
- **As a power user**, I want each child item row to clearly present its icon, primary title, descriptive subtitle, and trailing action chevron so that I can quickly identify and access features without visual noise.
- **As a user organizing tiles**, I want drag-and-drop reordering, edit mode actions, and tap interactions to continue operating seamlessly within the unified card structure.

## Functional Requirements
- **FR-01: Unified Outer Card Container**
  - An expanded category shall render as a single continuous glass card with standard corner radius (24dp) and subtle border stroke.
  - Individual child tiles shall NOT render as standalone nested cards with separate outlines and card elevations.
- **FR-02: Category Header Styling**
  - **Expanded State:**
    - Left: Category squircle icon container with primary icon tint.
    - Center: Category title in bold primary text, with item count (e.g., "6 items") displayed directly beneath as a secondary subtitle.
    - Right: Trailing chevron pointing upward (`^`) indicating the expanded state.
    - Background: A subtle header background highlight with rounded top corners visually separating the category header from child rows.
  - **Collapsed State:**
    - Category title on the left with item count formatted cleanly inline/subtitle (e.g., "Storage 7 items").
    - The old pill badge (`bg_category_count_pill`) is removed.
    - Right: Trailing chevron pointing right (`>`).
- **FR-03: Collapsed Storage Summary Progress & Capacity**
  - When the Storage category is collapsed, it shall display an aggregate storage capacity metric (sum of internal + primary mounted drives, e.g., "52.97 GB free of 145 GB") alongside a horizontal progress bar matching `expanded.jpg`.
  - When expanded, individual storage drives (*Internal Storage*, *SD Card*, etc.) display their own respective usage metrics and progress bars.
- **FR-04: Integrated Child Item Rows**
  - Each child tile shall render as an edge-to-edge list row within the card body.
  - Left: A rounded rectangle/squircle icon container with translucent backdrop and themed icon tint.
  - Center: Primary title (e.g., "Paired TVs", "Remote", "Network Shares") in bold text; secondary subtitle description (e.g., "Manage links with other devices", "SMB, FTP and more") directly below.
  - Right: Navigation chevron `>` (and context-dependent action buttons such as USB eject icon or storage usage progress where applicable).
  - Tap target: The entire row shall be touch-responsive with ripple feedback triggering tile navigation.
- **FR-05: Inset Row Dividers**
  - Hairline dividers (0.5dp–1dp) shall separate consecutive child rows within the card, inset from the left to align with the text column (indented past the icon badge) and extending to the right edge.
  - The final child row shall cleanly integrate with the card's rounded bottom padding without a trailing divider line.
- **FR-06: Storage Volume Progress & Action Integration**
  - For storage volumes with capacity metrics (e.g., Internal Storage, SD Card), child rows display individual progress bars beneath the subtitle.
  - Removable storage items continue to display their dedicated eject button.
- **FR-07: Drag-and-Drop & Reordering Compatibility**
  - Reordering tiles within an expanded category via drag-and-drop and moving tiles across categories in edit mode must remain fully supported.

## Non-Functional Requirements
- **NFR-01: Visual Fidelity**: Card hierarchy, typography, divider lines, and spacing strictly reflect `expanded.jpg`.
- **NFR-02: Animation Performance**: Expanding and collapsing categories must animate smoothly at 60fps+ without UI lag, frame drops, or layout glitches.
- **NFR-03: Touch Accessibility**: Every child row must maintain a minimum touch target height of at least 48dp.
- **NFR-04: Theme & Dynamic Density**: Dividers, text, and row backgrounds must respect dark theme tokens and scale crisply across different screen densities.

## Edge Cases & Error States
- **Single Item Categories**: Categories containing only 1 item render a single clean row with no divider lines.
- **Empty Custom Categories**: Categories with 0 items display an appropriate placeholder or clean collapsed state.
- **Long Titles and Subtitles**: Labels and subtitles must ellipsize gracefully without pushing the trailing chevron off-screen.
- **Removable Media Ejecting**: When an SD card or USB drive is unmounted/ejected, the row updates its status/eject icon immediately.
- **Fast Expand/Collapse Tapping**: Rapid tapping on category headers must not cause view duplication or layout stutter.

## Out of Scope
- Android TV main menu layout (`item_storage_category_header.xml` / `item_storage_grid_tv.xml`).
- Bottom navigation or main activity header/search layout refactoring.
- Grid view mode (`VIEW_TYPE_GRID`).

## Success Criteria
- [ ] Expanded category cards on mobile render child items as integrated divider-separated rows inside a single card container, matching `expanded.jpg`.
- [ ] Inset dividers cleanly separate child rows without extending beneath the icon badge.
- [ ] The count pill badge is removed, replaced by clean inline/subtitle text.
- [ ] Collapsed Storage category shows summary progress bar and aggregate free/total storage capacity.
- [ ] Category header displays subtle contrasting glass highlight with rounded top corners when expanded.
- [ ] Child rows display squircle icon, bold label, descriptive subtitle, and chevron `>`.
- [ ] Expanding, collapsing, tapping, and dragging tiles operate cleanly without regressions.
