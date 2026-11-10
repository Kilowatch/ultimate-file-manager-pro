# Implementation Plan: Advanced Sync Remote Folder Target Resolution

## Goals
Ensure that Advanced Sync (and Classic Sync) profiles configured with a specific remote subfolder path strictly execute all sync operations (listing, uploading, downloading, deleting, and two-way conflict resolution) within the selected target directory rather than defaulting to the root of the remote share.

Reference Spec Criteria:
- Advanced Sync download profile targeting a WebDAV (Nextcloud) subfolder populates the local folder exclusively with files from that subfolder.
- Root files are never downloaded or modified when a subfolder is selected.
- Upload and two-way sync profiles correctly upload/sync into the remote subfolder and auto-create missing remote directories.
- Download sync fails safely with an error notification if the target remote folder does not exist, preserving local files from accidental deletion.
- Standard SMB and SMB Server-Mode path resolution remain fully functional without regressions.

---

## Architecture Overview

### Root Cause Analysis
In `AdvancedSyncWorker.kt`, the path resolution block previously used:
```kotlin
val syncRemotePath: String = when {
    share.isServerMode || share.remotePath.isEmpty() -> "" // intended for SMB server-mode
    share.type == ShareType.SMB -> { ... }
    else -> profile.remotePath
}
```
For WebDAV, SFTP, FTP, and S3 shares, `share.remotePath` is almost always empty (`""`) because non-SMB protocols do not divide paths into SMB share-name partitions. Consequently, `share.remotePath.isEmpty()` matched, causing `syncRemotePath` to become `""`. Because `WebDavShareClient` and other non-SMB clients take the target path as a method argument rather than reading `share.remotePath`, they received `""`, effectively targeting the root of the remote storage.

### Solution Design
1. **Extract Dedicated `SyncRemotePathResolver`**:
   - Centralize path resolution logic in `za.kilowatch.ultimatefilemanager.sync.SyncRemotePathResolver`.
   - Strictly isolate SMB server-mode dynamic share stripping to `ShareType.SMB && (share.isServerMode || share.remotePath.isEmpty())`.
   - For all other protocols (WebDAV, Nextcloud, SFTP, FTP, NFS, S3, RClone), sanitize and preserve the user's `profile.remotePath.trim('/')`.
   - Provide a safe `buildRemoteFilePath(basePath, fileName)` helper that avoids illegal leading slashes (`//` or `/$name` when basePath is empty).

2. **Integrate into `AdvancedSyncWorker`**:
   - Use `SyncRemotePathResolver.resolve(share, profile.remotePath)` to obtain `effectiveShare` and `syncRemotePath`.
   - Parameterize `listRemoteFiles(share, remotePath, autoCreate = true/false)`.
   - In `doDownload`, pass `autoCreate = false`. If the remote directory does not exist or fails to list, safely abort and display `R.string.failed_to_access_remote_path_for_profilename` without deleting local files.
   - In `doUpload` and `doTwoway`, pass `autoCreate = true` (or auto-create via `mkdirByType`), ensuring missing remote directories are created before file transfers.
   - Fix all remote file path concatenations across `doUpload`, `doDownload`, `doTwoway`, `resolveRemoteSize`, and deletion loops to use `buildRemoteFilePath`.

3. **Align `SyncWorker` (Classic 1-Way Sync)**:
   - Update `SyncWorker` to use `SyncRemotePathResolver`.
   - Expand `SyncWorker` protocol handling to support WebDAV and SFTP in addition to SMB, NFS, and FTP.

---

## User Review Required
> [!IMPORTANT]
> - For **Download** sync jobs, if the designated remote directory is deleted or missing on the server, the job will fail safely with a notification rather than creating an empty remote folder. This prevents local files from being wiped when "Sync deletions" is enabled.
> - For **Upload** and **Two-way** sync jobs, if the target remote directory does not exist, the sync engine will automatically create the remote folder structure (`mkdir`) and proceed.

---

## Proposed Changes

### Component 1: Path Resolution Engine (`sync/`)

#### [NEW] `app/src/main/java/za/kilowatch/ultimatefilemanager/sync/SyncRemotePathResolver.kt`
- Encapsulates path resolution into a standalone, testable object.
- Provides `resolve(share: NetworkShare, profileRemotePath: String): ResolvedSharePath`.
- Provides `buildRemoteFilePath(basePath: String, fileName: String): String`.

---

### Component 2: Advanced Sync Worker (`sync/advanced/`)

#### [MODIFY] `app/src/main/java/za/kilowatch/ultimatefilemanager/sync/advanced/AdvancedSyncWorker.kt`
- Replace inline path resolution with `SyncRemotePathResolver.resolve(share, profile.remotePath)`.
- Update `listRemoteFiles` to accept `autoCreate: Boolean = true`.
- In `doDownload`, handle missing remote directories by safely aborting and alerting the user via `R.string.failed_to_access_remote_path_for_profilename`.
- In `resolveRemoteSize`, fix empty path concatenation from `"${remotePath.trimEnd('/')}/$fileName"` to `SyncRemotePathResolver.buildRemoteFilePath(remotePath, fileName)`.

---

### Component 3: Classic Sync Worker (`sync/`)

#### [MODIFY] `app/src/main/java/za/kilowatch/ultimatefilemanager/sync/SyncWorker.kt`
- Replace legacy SMB server-mode check with `SyncRemotePathResolver.resolve(share, profile.remotePath)`.
- Add `ShareType.WEBDAV` and `ShareType.SFTP` / `ShareType.SCP` branches to connection testing, listing, directory creation, and output stream opening.
- Use `SyncRemotePathResolver.buildRemoteFilePath` for remote file targets.

---

### Component 4: Unit Tests (`test/sync/`)

#### [NEW] `app/src/test/java/za/kilowatch/ultimatefilemanager/sync/SyncRemotePathResolverTest.kt`
- Comprehensive unit tests verifying:
  1. WebDAV with subfolder (`"Photos/Vacation"`) -> `effectiveShare` unchanged, `syncRemotePath = "Photos/Vacation"`.
  2. WebDAV with root (`"/"` and `""`) -> `syncRemotePath = ""`.
  3. WebDAV with messy slashes (`"/Nextcloud/Docs/"`) -> `syncRemotePath = "Nextcloud/Docs"`.
  4. SMB Server Mode with empty share remotePath -> `effectiveShare.remotePath = profile.remotePath`, `syncRemotePath = ""`.
  5. SMB Standard Share Mode with subfolder (`"Media/Movies"`) -> `syncRemotePath = "Movies"`.
  6. SMB Standard Share Mode with share root (`"Media"`) -> `syncRemotePath = ""`.
  7. SFTP, FTP, S3, NFS path normalization.
  8. `buildRemoteFilePath` empty vs nested paths.

---

## Verification Plan

### Automated Tests
Run unit tests for sync path resolution and helper classes:
```powershell
./gradlew testDebugUnitTest --tests "za.kilowatch.ultimatefilemanager.sync.*"
```

Compile the standard debug build:
```powershell
./gradlew assembleMobileGoogleDebug
```

### Manual Verification
1. **Advanced Sync (WebDAV / Nextcloud Download)**:
   - Configure Advanced Sync download profile between local folder and a WebDAV subfolder (e.g., `/TestSync`).
   - Run sync.
   - Verify only files from `/TestSync` are downloaded to the phone.
2. **Advanced Sync (WebDAV Upload)**:
   - Configure Advanced Sync upload profile targeting a WebDAV subfolder.
   - Run sync.
   - Verify files are uploaded into `/TestSync` and not the root directory.
3. **SMB Sync Regression**:
   - Run sync against an SMB share in both server-mode and standard share-mode.
   - Verify files continue to sync to the correct SMB share subfolder.
4. **Missing Remote Folder Safety**:
   - Delete the remote subfolder on WebDAV and trigger Download sync.
   - Verify sync aborts safely with a notification and does not wipe local files.
