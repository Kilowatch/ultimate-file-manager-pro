# Specification: Advanced Sync Remote Folder Target Resolution (WebDAV & Network Shares)

## Overview
When setting up an Advanced Sync profile between a local folder and a remote network share (such as WebDAV/Nextcloud, SMB, SFTP, FTP, or Cloud storage), the sync engine must strictly synchronize against the specific remote folder path selected by the user during profile setup. Currently, selecting a remote subfolder causes sync operations (especially download and upload) to target the root directory of the remote share instead of the chosen subfolder, improperly syncing unintended root-level files and ignoring the user's selected path.

---

## User Stories
- **As a user synchronizing files between a local directory and a remote WebDAV/Nextcloud folder**, I want the download sync operation to only pull files from the specific remote folder I selected during setup, so that my local storage receives the intended folder contents and not files from the share root.
- **As a user setting up upload or bidirectional Advanced Sync with a remote share**, I want files to be transferred to and from the designated remote folder path rather than the root directory, so that remote files outside the target directory remain untouched and protected.
- **As a user configuring or editing an Advanced Sync profile**, I want the selected remote path displayed in the UI to accurately reflect and correspond 1:1 with the actual path used by the background sync worker.

---

## Functional Requirements

### 1. Accurate Remote Path Targeting in Sync Operations
- **FR-01: Remote Folder Path Honor**: All sync directions (Download: remote → local, Upload: local → remote, and Two-way: bidirectional) must execute within the specific remote subfolder path specified in the `AdvancedSyncProfile` (`remotePath`).
- **FR-02: Remote Share Listing Scoping**: When listing remote files for synchronization (for difference comparison, size verification, hash generation, and download queuing), the query must be scoped to the target remote directory. Files outside this directory must never be enumerated or transferred.
- **FR-03: Remote Directory Creation & Scoping**: When uploading or syncing bidirectional changes into a remote directory that contains subfolders or does not yet exist, the sync worker must automatically create the missing remote folder hierarchy (`mkdir`) relative to the target remote directory.
- **FR-04: Remote Deletion Isolation (Sync Deletions)**: When `syncDeletions` is enabled, file deletions must be strictly constrained within the configured target remote folder. Under no circumstances should files or folders located at the share root or outside the target path be deleted.
- **FR-05: Protocol & Engine Consistency**: Remote path resolution must function consistently across both `AdvancedSyncWorker` and `SyncWorker`, covering all supported network protocols, including WebDAV (and Nextcloud/ownCloud), SMB/CIFS (server-mode and standard share), SFTP, FTP, NFS, and Cloud storage backends.

### 2. UI & Profile Configuration Fidelity
- **FR-06: Profile Remote Path Display**: In Advanced Sync profile creation and editing, the selected remote path must clearly indicate the selected folder relative to the share root (e.g., `/Documents/Work` or `/` for share root).
- **FR-07: Root Path Handling**: If the user explicitly selects the root of the remote share, the sync operation must correctly target the root directory without generating invalid path prefixes (such as leading double slashes `//` or empty path segments).

---

## Non-Functional Requirements
- **NFR-01: Data Safety & Integrity**: Syncing must never delete, overwrite, or mutate files outside the user's configured source and destination folders.
- **NFR-02: Performance & Bandwidth**: Remote file listing must only fetch metadata for the targeted directory branch, avoiding full-share crawls and reducing network bandwidth and API request quotas.
- **NFR-03: Backward Compatibility**: Existing Advanced Sync profiles saved with a custom remote path must immediately benefit from the correct path resolution on their next scheduled or manual run without requiring recreation.

---

## Edge Cases & Error States
- **Share Root vs Subfolder**: Explicit selection of the share root (`/` or `""`) must still work seamlessly without appending trailing or leading slashes incorrectly.
- **Deeply Nested Subfolders**: Remote paths with multiple directory tiers (e.g. `Photos/2026/Vacation`) must be resolved properly across listing, downloading, uploading, and folder creation.
- **Special Characters and Spaces**: Remote folder paths containing spaces, Unicode characters, accents, or special symbols must be resolved and URL/path-encoded appropriately according to the remote protocol requirements.
- **Trailing / Leading Slashes**: Irregular slash formatting in stored profiles (e.g., `/folder/subfolder/`, `folder/subfolder`, `/folder/subfolder`) must be normalized consistently before path concatenation.
- **Missing Remote Folder on Download**: If the selected remote folder has been deleted or moved on the remote server prior to download sync execution, the sync job must fail gracefully with a descriptive error notification without deleting or modifying any existing files in the local destination directory.
- **Missing Remote Folder on Upload**: If the target remote folder does not exist during an upload or two-way sync, the sync engine must automatically create the remote folder hierarchy before transferring files.

---

## Out of Scope
- Adding new network share protocols or cloud providers.
- Changing the layout or visual design of the Advanced Sync setup screens.
- Altering the fundamental sync scheduling engine (WorkManager periodic/one-time scheduling).

---

## Success Criteria
- [ ] Setting up an Advanced Sync download profile targeting a WebDAV (Nextcloud) subfolder populates the local folder exclusively with files from that subfolder.
- [ ] No files or directories from the root of the remote share are downloaded when a specific subfolder is selected.
- [ ] Upload and two-way sync profiles targeting a remote subfolder correctly upload/sync files into the remote subfolder and do not place files at the share root.
- [ ] Profiles targeting the root directory continue to synchronize root files without regressions.
- [ ] Deletion sync with subfolders never deletes files outside the designated remote folder.
- [ ] Verified across WebDAV and other network storage types on mobile and TV form factors.
