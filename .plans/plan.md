# Implementation Plan: Immediate Other Storage Export Validation & WebDAV Folder Performance Optimization

## Goal Description
Resolve the severe 15–30+ second delays reported in `error.txt` when using "Upload to Other Storage" on remote network shares (WebDAV, SMB, NFS) and browsing WebDAV servers:
1. **Instant Other Storage Pre-Check**: Ensure that tapping `Upload to Other Storage` checks mounted document providers immediately. If unconfigured (`mountedProviders.isEmpty()`), immediately show the toast and open `OtherStorageActivity` in <100ms with **0 bytes** downloaded.
2. **Deferred Staging with Telemetry**: When providers exist, prompt the user for destination and export format (Single File, Zip, Provider App) *before* downloading remote content. When confirmed, download with `ArchiveProgressDialog` showing item progress, percentage, and cancellation support.
3. **WebDAV & Remote Network Subfolder Navigation Acceleration**: Eliminate the background subdirectory child-count storm in `NetworkFileAdapter` that executes sequential `listFiles` calls on every subfolder, restoring instantaneous folder opening on WebDAV, SMB, NFS, and Cloud storage.

---

## User Review Required
> [!IMPORTANT]
> - `NetworkFileAdapter` will no longer query remote subdirectories over the network to count child items. Folder subtitles in network views will display their modification date (and any cached count) without blocking network I/O.
> - "Upload to Other Storage" for network items will prompt for the destination provider and export mode upfront before downloading data over the network.

---

## Proposed Changes

### Component 1: Document Provider Export Helper (`storage/DocumentProviderExportHelper.kt`)
#### [MODIFY] `DocumentProviderExportHelper.kt`
- Extract a reusable `handleExportChoice` dialog helper that takes item count, has-directory flag, single file name, and destination provider.
- Keep `handleExportToProvider(activity, files, ...)` for local `File` workflows, delegating to `handleExportChoice`.
- Allow `showSelectMountedProviderDialog` to be called safely with empty local files, bypassing unneeded disk cleanup when remote downloads have not yet occurred.

---

### Component 2: Standalone Network Browser (`network/NetworkBrowserActivity.kt`)
#### [MODIFY] `NetworkBrowserActivity.kt`
- Refactor `uploadNetworkFilesToOtherStorage(files: List<NetworkFile>)`:
  1. Synchronously query `val mounted = DocumentProviderRepository.getMountedProviders(this)`.
  2. If `mounted.isEmpty()`, immediately show `no_mounted_doc_providers_toast` and launch `OtherStorageActivity`. Exit immediately (0ms delay, 0 network downloads).
  3. If `mounted.isNotEmpty()`, present `showSelectMountedProviderDialog`.
  4. Once provider is selected, invoke `DocumentProviderExportHelper.handleExportChoice(...)`.
  5. If user chooses:
     - **Single file export**: Show `ArchiveProgressDialog`, download the single file to temp staging, and launch SAF `ACTION_CREATE_DOCUMENT`.
     - **Export as ZIP**: Show `ArchiveProgressDialog`, recursively download selected items to temp staging, compress to ZIP via `ArchiveManager`, and launch SAF `ACTION_CREATE_DOCUMENT`.
     - **Upload via Provider App**: Show `ArchiveProgressDialog`, download selected items to temp staging, and launch share sheet with `exportFilesViaProviderApp`.
  6. Support cancel on `ArchiveProgressDialog`: aborts coroutine and deletes temp staging folder immediately.

---

### Component 3: Tabbed & Twin-Window Network Browser (`network/NetworkBrowserFragment.kt`)
#### [MODIFY] `NetworkBrowserFragment.kt`
- Apply the identical refactored workflow to `NetworkBrowserFragment.uploadNetworkFilesToOtherStorage(files: List<NetworkFile>)`.
- Ensure strict adherence to Fragment rules (no `(activity as? NetworkBrowserActivity)` casts, self-contained lifecycle handling via `viewLifecycleOwner.lifecycleScope`).

---

### Component 4: Remote Subfolder Child-Count Suppression (`network/NetworkFileAdapter.kt`)
#### [MODIFY] `NetworkFileAdapter.kt`
- Remove the sequential `childCountJob` background launch that issues `listFiles` across all directory entries over WebDAV, SMB, NFS, and Cloud storage.
- Keep `childCountCache` lookup in `bindDetails` and `bindItem` so cached counts (if previously opened) still display, while falling back gracefully to `dateStr` for unprobed folders.
- Ensure `childCountJob?.cancel()` is maintained.

---

### Component 5: Local & SAF Browser Parity (`storage/FileBrowserActivity.kt` & `FileBrowserFragment.kt`)
#### [MODIFY] `FileBrowserActivity.kt` & `FileBrowserFragment.kt`
- Verify that `upload_other_storage` pre-checks `mountedProviders.isEmpty()` before opening any dialogs, ensuring consistent instant-navigation behavior.

---

## Verification Plan

### Automated Tests
Run unit tests for network share models, diff callbacks, and file helpers:
```powershell
./gradlew testDebugUnitTest --tests "za.kilowatch.ultimatefilemanager.network.*"
```

### Manual Verification
1. **Upload to Other Storage (No Provider Configured)**:
   - Clear/unmount any Other Storage provider.
   - In Network Browser (WebDAV / SMB), select a folder or file.
   - Tap `Tools -> Upload to Other Storage`.
   - **Verify**: Toast "No storage provider added. Select a provider to add." appears instantly (<100ms) and navigates to Other Storage screen. No remote download occurs.
2. **Upload to Other Storage (Provider Configured)**:
   - Configure an Other Storage provider.
   - Select a folder on WebDAV and tap `Upload to Other Storage`.
   - **Verify**: Destination dialog opens instantly.
   - Select format (ZIP or App).
   - **Verify**: `ArchiveProgressDialog` appears showing download progress with an active Cancel button.
   - Test pressing **Cancel**: download stops immediately, temp directory is deleted.
3. **WebDAV Folder Navigation**:
   - Open WebDAV share with multiple subfolders (especially HTTPS with self-signed certificate).
   - Enter root and subfolders.
   - **Verify**: Listing opens immediately without 15–30s child-count lag.
