# Clarifications: Immediate Other Storage Export Validation & WebDAV Folder Performance Optimization

## Date: 2026-11-10

### Clarification 1: Remote Download Timing for "Upload to Other Storage"
- **Question**: For 'Upload to Other Storage' on remote network shares (when providers exist), at what stage should the remote files/folders be downloaded?
- **Decision**: **Only download after provider & export format selection**.
- **Rationale**:
  1. If no provider is mounted, immediately show toast and open `OtherStorageActivity` without downloading any remote data.
  2. When providers exist, the user first picks the destination provider (`showSelectMountedProviderDialog`).
  3. If multiple items or a folder are selected, the user chooses between "Export as ZIP" and "Upload via Provider App" before any network transfer begins. If single file, proceeds directly.
  4. If the user cancels at either dialog, zero bytes are downloaded.
  5. Downloading to staging occurs strictly after confirmation, feeding directly into the export step.

---

### Clarification 2: Subdirectory Child Counting on Remote Network Shares
- **Question**: How should directory child counts (item counts) be handled for subfolders in remote network shares (WebDAV, SMB, NFS, Cloud) in `NetworkFileAdapter`?
- **Decision**: **Skip remote child counting**.
- **Rationale**:
  1. Precomputing child counts by recursively issuing `listFiles` on every subdirectory causes massive network storms (>15s to >30s delays on WebDAV, SMB, NFS, and Cloud).
  2. For remote network shares (`share.type` is WebDAV, SMB, NFS, FTP, SFTV, Cloud, etc.), do not spawn sequential `listFiles` child-count background probes.
  3. The directory item will display its standard folder label/subtitle (or cached count if already known), ensuring instantaneous folder opening and rendering.

---

### Clarification 3: Remote Staging Progress Dialog & Telemetry
- **Question**: Which progress dialog should be shown while downloading remote files/folders for export to Other Storage?
- **Decision**: **`ArchiveProgressDialog`**.
- **Rationale**:
  1. `ArchiveProgressDialog` is UFM's canonical `UFMStandard` glass progress dialog supporting title, file/item counter, percentage, indeterminate/determinate states, and explicit cancellation.
  2. Adheres to TV and mobile dual-layout standards (`dialog_archive_progress.xml` and `dialog_archive_progress_tv.xml`).
  3. Cancelling the dialog cleanly cancels the coroutine job, aborts remote streaming, and immediately deletes any temporary files staged under `cacheDir/upload_other_*`.
