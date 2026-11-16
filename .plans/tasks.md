# Task List: Torrent & Magnet Selective File Download & TV Focus Stability

## Phase 1: Strings & Data Model Foundation
- [x] [T001] Add string resources in `app/src/main/res/values/strings.xml`:
  - Add strings for selection summary (`torrent_selected_summary`), buttons (`torrent_select_all`, `torrent_deselect_all`), swarm metadata fetch (`torrent_fetching_metadata`, `torrent_metadata_timeout_title`, `torrent_metadata_timeout_msg`, `torrent_keep_waiting`, `torrent_download_all_direct`), transfer action dialog (`transfer_action_title`, `transfer_action_pause`, `transfer_action_resume`, `transfer_action_open_folder`, `transfer_action_delete_title`, `transfer_delete_keep_files`, `transfer_delete_remove_files`), and category filters (`filter_all`, `filter_videos`, `filter_audio`, `filter_images`, `filter_archives`, `filter_docs`).
- [x] [T002] Create `TorrentFileNode.kt` in `za.kilowatch.ultimatefilemanager.download`:
  - Build hierarchical tree structure representing folders and files.
  - Implement `fromTorrentInfo(torrentInfo: TorrentInfo): TorrentFileNode` to parse paths into nested nodes.
  - Implement recursive selection methods: `setSelectionRecursive(selected: Boolean)`, `isAllSelected()`, `isNoneSelected()`, `getSelectedBytes()`, `getSelectedFileIndices()`, and `buildPriorityArray(totalFiles: Int): Array<Priority>`.
- [x] [T003] Create `TorrentFileNodeTest.kt` in `app/src/test/java/za/kilowatch/ultimatefilemanager/download`:
  - Verify hierarchical parsing from multi-level paths.
  - Verify recursive cascading selection/deselection of folders and children.
  - Verify byte accumulation and `Priority[]` generation.

## Phase 2: Engine & Manager Selective Download Integration
- [x] [T004] Update `TorrentEngine.kt`:
  - Update `downloadTorrentFile(torrentFile: File, saveDir: File, filePriorities: Array<Priority>? = null)` to pass file priorities into `sessionManager.download()`.
  - Implement `fetchMagnetMetadata(magnetUri: String, timeoutSeconds: Int = 20, onResult: (File?) -> Unit)` using `sessionManager.fetchMagnet()` on IO dispatcher.
  - In `handleTorrentFinished()`, clean up any 0-byte boundary chunk files and empty subdirectories left by unselected files (`Priority.IGNORE`), and index only selected downloaded files into Android MediaStore.
- [x] [T005] Update `DownloadTransferManager.kt`:
  - Overload `startTorrentFileDownload` to accept `selectedFileIndices: Set<Int>? = null` and `customTotalBytes: Long? = null`.
  - Pass the effective total bytes to `StoragePreflightValidator.validate` and generate the `Priority[]` array for `TorrentEngine`.
  - Set `TransferItem.totalBytes` to match the selected payload size.

## Phase 3: Android TV D-Pad Focus Fix & Transfer Action Dialog
- [x] [T006] Refactor `DownloadTransferAdapter.kt`:
  - Enable `setHasStableIds(true)` using `item.id.hashCode().toLong()`.
  - Implement granular `notifyItemChanged(pos, PAYLOAD_PROGRESS)` updates during 1-second progress ticks to preserve ViewHolders and retain D-pad focus without jumping to top toolbar/sidebar.
  - Add `onItemClicked: (DownloadTransferManager.TransferItem) -> Unit` listener.
- [x] [T007] Create Transfer Action Dialog layouts:
  - Create `app/src/main/res/layout/dialog_transfer_action.xml` (Mobile glass dialog).
  - Create `app/src/main/res/layout/dialog_transfer_action_tv.xml` (TV glass dialog with `@drawable/selector_tv_button_yellow`).
- [x] [T008] Integrate `showTransferActionDialog` in `DownloadManagerActivity.kt`:
  - Bind `onItemClicked` from adapter to display the Transfer Action Dialog on TV D-pad OK or Mobile card tap.
  - Implement Pause / Resume toggle, Open Folder in `FileBrowserActivity`, and dual-option Delete ("Remove Transfer Only" vs "Remove Transfer & Delete Downloaded Files").

## Phase 4: Selective Torrent Content Selection Dialog UI
- [x] [T009] Create list row layouts:
  - Create `app/src/main/res/layout/item_torrent_file.xml` with checkable row, file icon, name, and size.
  - Create `app/src/main/res/layout/item_torrent_folder.xml` with expand/collapse arrow, folder icon, folder name, child count, total folder size, and master checkbox.
- [x] [T010] Create `TorrentFileSelectionAdapter.kt`:
  - Implement tree-aware adapter projecting visible nodes with expand/collapse animations.
  - Implement checkbox click handler with recursive updates and metric change notifications.
  - Implement real-time text search and category filter chips (Videos, Audio, Images, Archives, Docs).
- [x] [T011] Create selection dialog layouts:
  - Create `app/src/main/res/layout/dialog_torrent_file_selection.xml` (Mobile UFMStandard glass layout).
  - Create `app/src/main/res/layout/dialog_torrent_file_selection_tv.xml` (TV 800dp widescreen two-column layout with sidebar and full-height tree pane).
- [x] [T012] Connect Selection Dialog in `DownloadManagerActivity.kt`:
  - Wire `.torrent` file picker, external file intents, and FileBrowser launches to show `showTorrentFileSelectionDialog()`.
  - Wire magnet link input to trigger swarm metadata fetch with a 20s timeout prompt (Keep Waiting / Download All Directly / Cancel).
  - Launch download with selected file priorities upon tapping "Start Download".

## Phase 5: Verification & Translation Sync
- [x] [T013] Run unit tests with `./gradlew testMobileGoogleDebugUnitTest --tests "za.kilowatch.ultimatefilemanager.download.*"`.
- [x] [T014] Verify build compilation with `./gradlew compileMobileGoogleDebugKotlin` and `./gradlew compileTvGoogleDebugKotlin`.
- [x] [T015] Run `python -u translate_strings.py verify` to synchronize translations across all non-English `strings.xml` files.
