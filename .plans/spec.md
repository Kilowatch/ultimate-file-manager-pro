# Specification: Immediate Other Storage Export Validation & WebDAV Folder Performance Optimization

## Overview
This specification addresses two critical issues reported during network and remote storage operations (specifically WebDAV, SMB, and NFS):
1. **Unvalidated Pre-Download on "Upload to Other Storage"**: When a user selects a file or folder on a remote network share (WebDAV, SMB, NFS, etc.) and triggers `Tools -> Upload to Other Storage`, the application currently downloads the entire file or recursively downloads the whole directory structure over the network into local cache *before* verifying if any external Document Provider is configured. When no provider is mounted ("Other Storage" not selected), the user experiences a 15–30+ second delay before abruptly seeing "No storage provider added. Select a provider to add." and being navigated to `OtherStorageActivity`, wasting bandwidth, time, and device flash storage.
2. **WebDAV First Connection & Subfolder Navigation Latency**: Browsing WebDAV shares (particularly Windows 11 IIS/WebDAV with HTTPS and self-signed certificates) experiences excessive latency (>15s to >30s) when entering folders and subfolders. This is amplified by adapter-level sequential child-count queries across all subdirectories, lack of connection/session reuse, and unthrottled network probing.

This specification establishes the requirements for instant pre-flight provider validation, deferred on-demand downloading with cancellation telemetry, and WebDAV directory listing acceleration.

---

## User Stories
- **As a user attempting to upload remote files or folders to Other Storage without any providers configured**, I want the app to immediately inform me and open the Other Storage setup screen in 0 milliseconds without downloading any network data.
- **As a user uploading remote files or folders to a configured Other Storage provider**, I want to select the destination provider and export options *before* any remote downloading starts, with a visible, cancellable progress dialog showing transfer progress.
- **As a user browsing WebDAV servers (including Windows 11 with HTTPS and self-signed certificates)**, I want folder and subfolder listings to open promptly without 15–30 second hangs or blocking child-count storms.
- **As a user navigating across SMB, NFS, WebDAV, and Cloud storages**, I want consistent, responsive feedback across standalone, tabbed, and twin-window browser modes.

---

## Functional Requirements

### 1. Instant Pre-Flight Validation for "Upload to Other Storage"
- **FR-01: Zero-Delay Provider Verification**: Before initiating any file downloads, recursive directory traversal, or temporary file staging, the system must immediately check `DocumentProviderRepository.getMountedProviders(context)`.
- **FR-02: Instant Setup Navigation on Empty Providers**: If no document providers are mounted (`mountedProviders.isEmpty()`):
  - Display the `no_mounted_doc_providers_toast` toast ("No storage provider added. Select a provider to add.").
  - Immediately launch `OtherStorageActivity`.
  - Zero bytes of remote data must be transferred or staged to disk.
- **FR-03: Upfront Destination Selection**: If mounted document providers exist:
  - The provider selection dialog (and single-file vs zip export options where applicable) must be presented to the user **prior** to downloading the remote content.
  - If the user cancels the provider selection or export dialog, no network download shall take place.
- **FR-04: Deferred Streaming / Staging with Progress Dialog**:
  - Once the user confirms the export destination, remote download and staging must execute with a canonical modal progress dialog (`ArchiveProgressDialog` or standard UFM progress dialog) displaying current item name, file counter, and an explicit **Cancel** button.
  - If the user cancels the operation during download, remote streaming must immediately abort and any partially downloaded files in `cacheDir` must be pruned cleanly.
- **FR-05: Multi-Browser & Multi-Protocol Parity**: This pre-flight validation and deferred download workflow must be synchronized across:
  - `NetworkBrowserActivity.kt` (Standalone Network/Cloud Browser)
  - `NetworkBrowserFragment.kt` (Tabbed & Twin Window Network/Cloud Browser)
  - `FileBrowserActivity.kt` (Standalone Local & SAF Browser)
  - `FileBrowserFragment.kt` (Tabbed & Twin Window Local & SAF Browser)
  - Applicable to all network protocols: WebDAV, SMB, NFS, FTP, SFTP, and Cloud providers.

### 2. WebDAV Connection & Subfolder Navigation Optimization
- **FR-06: Elimination of Subfolder Listing Storms**:
  - `NetworkFileAdapter` must not execute sequential blocking `listFiles` queries over WebDAV (and slow remote protocols) for every subdirectory in the folder view solely to count child items.
  - Subdirectory child counts for remote WebDAV/SMB/NFS shares must either be disabled, fetched lazily on viewport visibility, or limited with a tight per-folder timeout and concurrency limiter to avoid starving connection threads.
- **FR-07: WebDAV Connection Re-use & Keep-Alive**:
  - In `WebDavShareClient`, ensure connection parameters and client sessions maintain persistent HTTPS connections across requests to eliminate repetitive SSL handshakes and TCP connection overhead to the same host.
- **FR-08: Cancellation & Stale Job Pruning**:
  - When the user navigates into a subfolder or back out of a folder, any running background listings or child count jobs for the previous path must be cancelled immediately.

---

## Non-Functional Requirements
- **NFR-01: Response Time**: "Upload to Other Storage" when unconfigured must transition to `OtherStorageActivity` within 100 milliseconds of user tap.
- **NFR-02: Bandwidth & Flash Storage Preservation**: No network traffic or disk cache writes shall occur for unconfigured or cancelled export actions.
- **NFR-03: Robust Cleanup**: Any temporary staging folder created under `cacheDir/upload_other_*` must be reliably deleted upon completion, failure, or cancellation.
- **NFR-04: Standard Conformance**: All dialogs, toasts, progress indicators, and navigation flows must conform strictly to `UFMStandard` (glass styling, embedded buttons, dark/light themes, TV D-pad focusability).

---

## Edge Cases & Error States
- **Rapid Tap / Double Click**: Rapid taps on "Upload to Other Storage" must not trigger concurrent duplicate export or download jobs.
- **Network Disconnect Mid-Transfer**: If the remote server disconnects while downloading staged items, the progress dialog must dismiss gracefully, show an error snackbar/dialog, and clean up temporary partial files.
- **Low Local Disk Space**: Before staging large remote folders, verify sufficient local cache space exists. If space is insufficient, notify the user and cancel before filling flash memory.
- **Self-Signed Certificates & Untrusted TLS**: Ensure `allowInsecureTls` remains honored across all WebDAV staging and listing calls without triggering connection resets or SSL handshake hangs.

---

## Out of Scope
- Rebuilding or restructuring the underlying `rclone.aar` or Go WebDAV binary.
- Modifying third-party document provider apps (Google Drive, Proton Drive, Nextcloud apps).

---

## Success Criteria
- [ ] Tapping `Upload to Other Storage` on WebDAV, SMB, NFS, or Local storage when no provider is mounted immediately opens `OtherStorageActivity` with the toast in <100ms, with zero network download activity.
- [ ] When providers are mounted, provider and export format selection dialogs appear before remote downloading begins.
- [ ] Downloading remote files for export displays a progress dialog with cancel capability and cleans up temporary files on exit.
- [ ] Navigating into WebDAV folders and subfolders is responsive and no longer suffers 15–30+ second delays caused by adapter child-count storms.
- [ ] Tested and verified across Mobile touch and Android TV D-pad interfaces in Standalone, Tabbed, and Twin Window modes.
