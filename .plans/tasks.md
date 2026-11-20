# Task List: Modern Categorized View Expanded Category Redesign (Mobile)

## Setup
- [x] [T001] Verify layout dependencies and drawable assets for category headers and child list rows.

## Foundation
- [x] [T002] Refactor child tile layout in `app/src/main/res/layout/item_storage_nested_card.xml`: convert into seamless, borderless list row container (`cardStorage`) with inset hairline divider (`dividerRow`) and trailing chevron (`imgChevron`).
- [x] [T003] Refactor category group layout in `app/src/main/res/layout/item_storage_category_group.xml`: remove old count pill badge, add inline count text view (`txtCategoryInlineCount`), add collapsed storage summary progress bar (`progressCategoryStorage`), and zero out child container margins/padding while preserving `layoutCategoryItems` drag-and-drop drop-target capabilities.

## Feature Implementation
- [x] [T004] [P] Update `CategoryGroupViewHolder` in `app/src/main/java/za/kilowatch/ultimatefilemanager/storage/StorageAdapter.kt`: bind inline count, compute aggregate storage capacity metrics (free vs. total) and percentage for collapsed Storage category, and manage expanded/collapsed header state.
- [x] [T005] [P] Update `createChildTileView` and child population logic in `app/src/main/java/za/kilowatch/ultimatefilemanager/storage/StorageAdapter.kt`: configure inset divider visibility (hidden for first row, visible between consecutive rows), bind trailing chevron, and ensure drag-and-drop long-press listeners, cross-category drop hover, and real-time live reordering remain fully active and fluid.

## Polish & Testing
- [x] [T006] [P] Verify clean build via `./gradlew assembleMobileGoogleDebug` and ensure zero errors or layout inflation warnings.
- [x] [T007] Verify inter-category and intra-category drag-and-drop reordering functionality.
