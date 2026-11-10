# Task List: Advanced Sync Remote Folder Target Resolution

## Phase 1: Foundation — Pure Path Resolution Engine & Unit Tests
- [x] [T001] [P] [app/src/main/java/za/kilowatch/ultimatefilemanager/sync/SyncRemotePathResolver.kt] Create `SyncRemotePathResolver` object with `resolve(share: NetworkShare, profileRemotePath: String): ResolvedSharePath` and `buildRemoteFilePath(basePath: String, fileName: String): String`. Strictly isolate SMB server-mode dynamic share stripping to `ShareType.SMB && (share.isServerMode || share.remotePath.isEmpty())`, while preserving the sanitized `profileRemotePath.trim('/')` for WebDAV, Nextcloud, SFTP, FTP, NFS, and Cloud storage.
- [x] [T002] [P] [app/src/test/java/za/kilowatch/ultimatefilemanager/sync/SyncRemotePathResolverTest.kt] Create comprehensive unit test suite covering all path resolution permutations: WebDAV subfolder, WebDAV root, WebDAV with irregular slashes, SMB Server Mode, SMB standard share with subfolder, SMB standard share at root, SFTP/FTP/S3/NFS, and `buildRemoteFilePath` formatting.

## Phase 2: Advanced Sync Engine Integration & Safety Guards
- [x] [T003] [P] [app/src/main/java/za/kilowatch/ultimatefilemanager/sync/advanced/AdvancedSyncWorker.kt] Integrate `SyncRemotePathResolver.resolve(share, profile.remotePath)` in `doWork()`, replacing the bugged inline `when` branch that caused non-SMB shares to target `""`.
- [x] [T004] [app/src/main/java/za/kilowatch/ultimatefilemanager/sync/advanced/AdvancedSyncWorker.kt] Update `listRemoteFiles(share: NetworkShare, remotePath: String, autoCreate: Boolean = true)`: when `autoCreate == false`, throw exception on listing failure instead of attempting `mkdirByType`.
- [x] [T005] [app/src/main/java/za/kilowatch/ultimatefilemanager/sync/advanced/AdvancedSyncWorker.kt] In `doDownload()`, query files with `autoCreate = false`. If the remote directory does not exist or fails to list, catch the error, show the `R.string.failed_to_access_remote_path_for_profilename` error notification, and abort safely without modifying or deleting local destination files.
- [x] [T006] [app/src/main/java/za/kilowatch/ultimatefilemanager/sync/advanced/AdvancedSyncWorker.kt] Fix remote file path construction across `doUpload()`, `doDownload()`, `doTwoway()`, `resolveRemoteSize()`, and `deleteRemoteFileByType()` using `SyncRemotePathResolver.buildRemoteFilePath()` to eliminate accidental leading slashes on empty base paths.

## Phase 3: Classic Sync Worker Parity
- [x] [T007] [app/src/main/java/za/kilowatch/ultimatefilemanager/sync/SyncWorker.kt] Update `SyncWorker` to use `SyncRemotePathResolver.resolve(share, profile.remotePath)` and `buildRemoteFilePath()`, ensuring consistent path resolution across both sync engines.
- [x] [T008] [app/src/main/java/za/kilowatch/ultimatefilemanager/sync/SyncWorker.kt] Expand `SyncWorker` connection testing (`testConnection`), listing (`listFiles`), directory creation (`mkdir`), and file writing (`openOutputStream`) to support `ShareType.WEBDAV` and `ShareType.SFTP` / `ShareType.SCP`.

## Phase 4: Automated Testing & Build Verification
- [x] [T009] Execute unit tests (`./gradlew testDebugUnitTest --tests "za.kilowatch.ultimatefilemanager.sync.*"`) to confirm path resolution across all protocols.
- [x] [T010] Run `./gradlew assembleMobileGoogleDebug` to verify project compilation and zero regressions.
