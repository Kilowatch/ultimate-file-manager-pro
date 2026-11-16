# Task List: Smart Bookmark Hub (Mobile Only)

## Phase 1: Strings & Assets Setup
- [x] [T001] Add string resources in `app/src/main/res/values/strings.xml`:
  - Add strings for tile title (`bookmark_hub_title`), subtitle (`bookmark_hub_subtitle`), share target (`share_target_bookmark`), quick save sheet, duplicate notice (`bookmark_duplicate_notice`), default smart groups (WhatsApp, Reddit, YouTube, Twitter/X, Web, Telegram, Files), filter chips (All, Links, Media, Docs, Notes, Starred), reader mode controls, TTS options, undo delete notice (`bookmark_deleted_undo`), export formats, and vault lock prompts.
- [x] [T002] Create vector drawable `app/src/main/res/drawable/ic_bookmark_hub.xml` and register in `IconCustomizationManager.kt` (`ALL_BUILTIN_ICONS`) and `IconCustomizationActivity.kt`.

## Phase 2: Database Layer & Persistence
- [x] [T003] Create `BookmarkEntity.kt` in `za.kilowatch.ultimatefilemanager.bookmarks.db`:
  - Define Room entity for bookmarks with indices on `groupId`, `contentType`, `sourcePackage`, `createdAt`, `isStarred`, and `url`.
- [x] [T004] Create `BookmarkGroupEntity.kt` in `za.kilowatch.ultimatefilemanager.bookmarks.db`:
  - Define Room entity for smart and custom groups.
- [x] [T005] Create `BookmarkDao.kt` in `za.kilowatch.ultimatefilemanager.bookmarks.db`:
  - Implement reactive Flow queries, filtering, sorting, duplicate checks (`getBookmarkByUrl`), and group management.
- [x] [T006] Create `BookmarkDatabase.kt` in `za.kilowatch.ultimatefilemanager.bookmarks.db`:
  - Room database instance `ufm_bookmarks.db` with singleton accessor matching `RecycleBinDatabase`.
- [x] [T007] Create `BookmarkRepository.kt` in `za.kilowatch.ultimatefilemanager.bookmarks.db`:
  - Implement default smart group provisioning, dynamic third-party app grouping (`getOrCreateGroupForPackage`), duplicate detection/update logic, and private file cleanup.

## Phase 3: Cloner, Scraper & Sandbox Management
- [x] [T008] Create `BookmarkFileCloner.kt` in `za.kilowatch.ultimatefilemanager.bookmarks.util`:
  - Clone incoming `content://` streams to `context.filesDir/bookmarks/media/`.
  - Generate video/image thumbnails into `context.filesDir/bookmarks/thumbnails/`.
  - Provide physical file cleanup methods on permanent deletion.
- [x] [T009] Create `BookmarkMetadataScraper.kt` in `za.kilowatch.ultimatefilemanager.bookmarks.util`:
  - Scrape OpenGraph title, description, cover image, and domain favicon.
  - Parse clean readable article body text for offline reading.

## Phase 4: Share Sheet Integration & Quick Save Bottom Sheet
- [x] [T010] Create Quick Save Bottom Sheet layout `app/src/main/res/layout/bottom_sheet_bookmark_quick_save.xml`:
  - Glass container, source app icon and title, group selector dropdown, title/note input fields, duplicate notice, and "Save Bookmark" action.
- [x] [T011] Create `BookmarkQuickSaveBottomSheet.kt` in `za.kilowatch.ultimatefilemanager.bookmarks.ui`:
  - Handle user interaction, group selection, duplicate check, and asynchronous saving.
- [x] [T012] Create `BookmarkReceiverActivity.kt` in `za.kilowatch.ultimatefilemanager.bookmarks.ui`:
  - Handle `ACTION_SEND` and `ACTION_SEND_MULTIPLE` for `text/*` and `*/*`.
  - Extract URLs, text, and stream URIs; resolve sender app package; show `BookmarkQuickSaveBottomSheet`.
  - Register in `AndroidManifest.xml` with label `@string/share_target_bookmark`.

## Phase 5: Distraction-Free Reader Mode & Text-to-Speech (TTS)
- [x] [T013] Create Reader layout `app/src/main/res/layout/activity_bookmark_reader.xml`:
  - Clean typography reading container, theme controls (Light, Sepia, Dark), font size controls, TTS audio player bar, and top "Open in Browser" action.
- [x] [T014] Create `BookmarkReaderActivity.kt` in `za.kilowatch.ultimatefilemanager.bookmarks.ui`:
  - Typography rendering, theme/font toggles, and Android `TextToSpeech` engine with speed controls (0.75x, 1x, 1.25x, 1.5x, 2x).
  - Register in `AndroidManifest.xml`.

## Phase 6: Bookmark Hub UI & Management
- [x] [T015] Create Bookmark item card layouts:
  - Create `app/src/main/res/layout/item_bookmark_card.xml` (list view card).
  - Create `app/src/main/res/layout/item_bookmark_card_grid.xml` (grid view card).
- [x] [T016] Create `BookmarkAdapter.kt` in `za.kilowatch.ultimatefilemanager.bookmarks.ui`:
  - Coil preview loading, source app badges, star toggles, tap navigation (Reader vs Browser vs Viewer), and 3-dot overflow actions (Save to Storage, Move, Delete).
- [x] [T017] Create Bookmark Hub layout `app/src/main/res/layout/activity_bookmark_hub.xml`:
  - 56dp frosted glass toolbar, search bar, horizontal group chips, content type filter chips, sort menu, empty state, RecyclerView, and selection action bar.
- [x] [T018] Create `BookmarkHubActivity.kt` in `za.kilowatch.ultimatefilemanager.bookmarks.ui`:
  - Full-text search, filtering, chronological sorting, 5-second undo delete snackbar, biometric vault lock integration, and batch export (HTML/Markdown).
  - Register in `AndroidManifest.xml`.

## Phase 7: Main Menu Organize Tile & System Registration
- [x] [T019] Update `StorageItem.kt`: Add `isBookmarkHubTile = false`, include in `isHideable`.
- [x] [T020] Update `StorageAdapter.kt`: Map `item.isBookmarkHubTile` to `MainMenuViewModeManager.CATEGORY_ORGANIZE`.
- [x] [T021] Update `StorageBrowserActivity.kt`:
  - Register in `buildAllKnownTiles()` with `isBookmarkHubTile = true`.
  - Register in `loadStorageVolumes()` (when `!isTvDevice`) under `CATEGORY_ORGANIZE`.
  - Provide live subtitle in `getStorageSubtitle()` with bookmark item count.
  - Add tile click router to launch `BookmarkHubActivity`.
- [x] [T022] Update `TileOrderManager.kt`: Include `isBookmarkHubTile` in `isFeatureTile()` check.
- [x] [T023] Ensure UFM file browsing activities strictly ignore/hide `context.filesDir/bookmarks/media/` from regular file browsing.

## Phase 8: Verification & Translation Script
- [x] [T024] Run `./gradlew assembleDebug` and verify clean build with Room KSP generation.
- [x] [T025] Run `python -u translate_strings.py verify` to check translation consistency.
