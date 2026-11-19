# Task List: Network & WebDAV Favorites Resolution, Connection Badging, and Shortcut UX

## Phase 1: Resource Strings & UFMStandard Layout
- [x] [T001] Add string resources in `app/src/main/res/values/strings.xml` for `remove_home_shortcut_title`, `remove_home_shortcut_message`, `remove_home_shortcut_disable_btn`, and `fav_protocol_type_format` (`%1$s • %2$s`).
- [x] [T002] Create `app/src/main/res/layout/dialog_remove_home_shortcut.xml` adhering strictly to UFMStandard (`@drawable/bg_dialog_glass`, vertical button hierarchy with primary `@style/Widget.Material3Expressive.Button` and secondary `TextButton`, 18sp sans-serif-black title, 14sp secondary body).

## Phase 2: Core Resolution & Fallback Engine
- [x] [T003] Update `initNetworkClient()` in `NetworkBrowserActivity.kt` to implement bidirectional repository fallback between `NetworkShareRepository` and `OnlineStorageRepository`, preventing false storage unavailable redirects for WebDAV and online shares.
- [x] [T004] Update `isFavoriteTile` click handlers in `StorageBrowserActivity.kt` for folders and files to use universal online storage verification and attach `putExtra("isOnlineStorage", true)` for all WebDAV and online storage providers.
- [x] [T005] Update `routeNetworkFavorite()` in `FavoriteShortcutActivity.kt` to attach `putExtra("isOnlineStorage", true)` and `EXTRA_STORAGE_LABEL` when launching network file favorites to `NetworkBrowserActivity`.

## Phase 3: Dynamic Connection Protocol Badging
- [x] [T006] Implement helper function `getFavoriteProtocolLabel(context, item)` in `FavoritesManager.kt` or `StorageItem.kt` to resolve protocol display names (`WebDAV`, `SMB`, `NFS`, `FTP`, `SFTP`, `Google Drive`, `OneDrive`, `Dropbox`, or `Local`).
- [x] [T007] Update subtitle formatting in `StorageAdapter.kt` and `StorageBrowserActivity.kt` for `item.isFavoriteTile` to display `"{Protocol} • {Folder/File}"` (e.g. `WebDAV • Folder`, `WebDAV • File`, `SMB • Folder`, `Local • Folder`).
- [x] [T008] Update `ManageFavoritesActivity.kt` to render connection protocol badges alongside favorite item labels in the list.
- [x] [T009] Update `FavoriteIconComposer.kt` to draw a compact, high-contrast protocol badge pill (e.g. `WDAV`, `SMB`, `NFS`, `FTP`, `CLOUD`) on the bottom quadrant of generated home screen launcher icons.

## Phase 4: UFMStandard Shortcut Removal Dialog
- [x] [T010] Wire `dialog_remove_home_shortcut.xml` into `ManageFavoritesActivity.kt` and `StorageBrowserActivity.kt` to show the UFMStandard confirmation dialog explaining the Android OS platform restriction prior to calling `FavoriteHomeScreenManager.disableShortcut()`.

## Phase 5: Verification & Quality Assurance
- [x] [T011] Run unit tests (`./gradlew testDebugUnitTest`) to ensure shortcut and storage resolution pass without regression.
- [x] [T012] Verify build compilation (`./gradlew assembleMobileGoogleDebug`) to ensure clean compilation and resource validation.
