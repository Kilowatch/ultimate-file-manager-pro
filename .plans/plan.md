# Implementation Plan: Modern Categorized View Expanded Category Redesign (Mobile)

## Goals
Redesign the expanded category view in Modern Categorized view mode on mobile to match `expanded.jpg`:
1. **Unified Card Structure**: Eliminate isolated, bordered nested sub-cards. Child tiles render as seamless, edge-to-edge rows inside the single outer category card container.
2. **Inset Dividers**: Add hairline dividers between consecutive child rows inset ~68dp to align with the text column.
3. **Category Header Modernization**:
   - Expanded state: subtle top-corner glass highlight (`bg_category_header_highlight`), category title, item count subtitle below it, upward chevron (`^`).
   - Collapsed state: category title with inline item count text (old pill badge removed), right chevron (`>`).
4. **Collapsed Storage Capacity & Progress Summary**:
   - When the Storage category is collapsed, compute and display the sum of total free vs. total capacity (e.g., "52.97 GB free of 145 GB") and display a summary progress bar matching `expanded.jpg`.
   - When expanded, individual storage drive tiles display their respective progress bars and capacity subtitles.

## Architecture Overview
The changes touch the presentation layer for `VIEW_TYPE_CATEGORY_HEADER` in `StorageAdapter.kt`, its outer card group layout (`item_storage_category_group.xml`), and the child item layout (`item_storage_nested_card.xml`).

```
┌────────────────────────────────────────────────────────┐
│ Outer MaterialCardView (cardCategoryGroup, r=24dp)     │
│ ┌────────────────────────────────────────────────────┐ │
│ │ Header Row (highlighted when expanded, r_top=24dp) │ │
│ │ [Icon]  Connect                           [^]      │ │
│ │         6 items                                    │ │
│ └────────────────────────────────────────────────────┘ │
│ ┌────────────────────────────────────────────────────┐ │
│ │ Child Row 0 (Paired TVs)                  [>]      │ │
│ ├────────────────────────────────────────────────────┤ │
│ │ ────── inset divider (marginStart=68dp) ─────────  │ │
│ │ Child Row 1 (Remote)                      [>]      │ │
│ ├────────────────────────────────────────────────────┤ │
│ │ ────── inset divider (marginStart=68dp) ─────────  │ │
│ │ Child Row 2 (Remote Manage)               [>]      │ │
│ └────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────┘
```

1. **`item_storage_category_group.xml`**:
   - Remove `txtCategoryCountPill`.
   - Add `txtCategoryInlineCount` next to `txtCategoryTitle`.
   - Add `progressCategoryStorage` below the title/subtitle for the collapsed Storage category summary.
   - Set `layoutCategoryItems` padding to `0dp` horizontal/top so child rows integrate seamlessly.
2. **`item_storage_nested_card.xml`**:
   - Convert outer `cardStorage` to borderless (`strokeWidth="0dp"`), transparent (`cardBackgroundColor="@android:color/transparent"`), zero-margin (`marginVertical="0dp"`), flat (`elevation="0dp"`) container with ripple.
   - Add top inset hairline divider (`dividerRow`) with `layout_marginStart="68dp"`.
   - Add trailing chevron `imgChevron` (`ic_chevron_right`).
3. **`StorageAdapter.kt`**:
   - In `CategoryGroupViewHolder.bind()`:
     - When collapsed:
       - Show `txtCategoryInlineCount`.
       - If category is `CATEGORY_STORAGE`, calculate aggregate free and total bytes from `groupItems` (storage drives), format subtitle (e.g., "28.02 GB free of 113 GB"), and show `progressCategoryStorage`.
       - For non-storage categories, hide subtitle and progress bar.
     - When expanded:
       - Hide inline count and progress bar.
       - Show `txtCategorySubtitle` with count text.
       - Show top highlight background on header.
   - In `createChildTileView()`:
     - Set `dividerRow.visibility` based on child index (hidden for first child, visible for subsequent children).
     - Bind `imgChevron` visibility and trailing actions (eject button for removable media).

## Files to Create
*None required. All necessary drawables (`bg_category_header_highlight.xml`, `bg_storage_progress_cyan.xml`, `bg_squircle_nested_icon.xml`, `ic_chevron_right.xml`) already exist in the project.*

## Files to Modify
### `app/src/main/res/layout/item_storage_category_group.xml`
- **What:** Modernize header structure (inline count, storage summary progress bar) and remove horizontal/top padding on `layoutCategoryItems`.
- **Key details:** Remove `txtCategoryCountPill`, add `txtCategoryInlineCount` and `progressCategoryStorage`.

### `app/src/main/res/layout/item_storage_nested_card.xml`
- **What:** Refactor child tile into seamless divider-separated row without outer card borders or vertical margins.
- **Key details:** `strokeWidth="0dp"`, `elevation="0dp"`, `cardBackgroundColor="@android:color/transparent"`, add inset `dividerRow` and trailing `imgChevron`.

### `app/src/main/java/za/kilowatch/ultimatefilemanager/storage/StorageAdapter.kt`
- **What:** Update `CategoryGroupViewHolder.bind()` to support inline count, collapsed storage aggregate calculation, and child item inset divider management.
- **Key details:** Calculate `sumOf { totalBytes }` and `sumOf { usedBytes }` for collapsed Storage, manage child divider visibility.

## Data Model
No changes to database entities or persistent storage schemas. Calculations are performed at runtime from existing `StorageItem` objects in memory.

## Dependencies
None. Uses standard Android SDK and existing Material Components.

## Test Plan
- Compile with `./gradlew assembleMobileGoogleDebug` to guarantee zero errors.
- Verify collapsed categories show inline count without pills.
- Verify collapsed Storage category displays aggregate storage capacity and usage progress bar.
- Verify expanded category displays unified card, inset dividers between rows, trailing chevrons, and smooth expand/collapse transitions.
- Verify child tile clicks, drag-and-drop reordering, and USB eject buttons function identically.

## Risks & Mitigations
- **Risk:** Drag-and-drop reordering visuals could look disjointed if row dimensions change during drag.
  - **Mitigation:** Retain `cardStorage` ID on the row container and preserve existing drag listeners and alpha dimming in `StorageAdapter`.
- **Risk:** Calculation of total bytes on empty or unmounted drives.
  - **Mitigation:** Filter for `!it.isUnmounted && it.totalBytes > 0L` before summing to prevent skewing metrics with offline network shares or empty slots.
