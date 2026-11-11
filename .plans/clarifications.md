# Clarifications: External Video Player Playback History Tracking (Issue #67)

This document records the resolved requirements and design clarifications for GitHub Issue #67 before moving to the task planning phase.

---

### 1. Local Media URI Resolution Strategy
- **Question**: How should UFM provide URI descriptors to external video and audio players for local media?
- **Confirmed Decision**: **MediaStore URI Priority with Fallback**.
  - When opening local video or audio files, UFM will query Android's system `MediaStore` (`MediaStore.Video.Media` and `MediaStore.Audio.Media`) to obtain the system content URI (`content://media/external/.../<id>`).
  - If found, this MediaStore URI is passed to the external player. This allows VLC (`libmedialibrary`) and other modern players running on Android 13–16+ (which hold `READ_MEDIA_VIDEO` / `READ_MEDIA_AUDIO` rather than `MANAGE_EXTERNAL_STORAGE`) to verify and index the media in their history and resume databases.
  - If the file is not found in MediaStore (e.g. root files, custom app folders, or unindexed files), UFM safely falls back to `UfmFileProvider` with enhanced `_data` and metadata projections.

---

### 2. Handling Unindexed / Fresh Local Files
- **Question**: What should happen if a local media file is not yet indexed in Android's MediaStore (e.g., freshly downloaded, moved, or extracted)?
- **Confirmed Decision**: **Fast On-Demand MediaScanner with Short Timeout**.
  - UFM will trigger a quick on-demand scan via `MediaScannerConnection.scanFile` (or asynchronous resolver with a tight ~200ms timeout) before launching.
  - If the scan completes and yields a MediaStore URI within the budget, it uses the MediaStore URI.
  - If it times out or fails (e.g. storage volume unindexed), it immediately falls back to `UfmFileProvider` without blocking the user or delaying the launch.

---

### 3. Media Types Scope
- **Question**: Should this playback history resolution apply to audio files as well as video files?
- **Confirmed Decision**: **Both Video and Audio Files**.
  - The history and MediaStore resolution mechanism applies to all video formats (`mp4`, `mkv`, `avi`, `webm`, `mov`, `flv`, etc.) and audio formats (`mp3`, `flac`, `m4a`, `ogg`, `wav`, `aac`, etc.).
  - Audio players (such as VLC, Poweramp, Musicolet) will benefit from the same persistent history and track progress tracking.

---

### 4. Network & Cloud Media Streaming History
- **Question**: How should external player playback history be handled for remote/network shares (SMB, WebDAV, SFTP, Cloud)?
- **Confirmed Decision**: **In Scope: Persistent Session URLs for Network Streaming**.
  - The user explicitly requested that network streaming support persistent history so external players can reopen streams from history later.
  - The local HTTP proxy (`NetworkHttpProxyServer`) will use deterministic / durable session identifiers (e.g., hash/encoded mapping of share ID + remote path) instead of purely ephemeral random UUIDs.
  - When an external player attempts to reopen a stream from its history, `NetworkHttpProxyServer` will recognize the stable identifier, re-resolve the network share credentials on demand, and resume streaming transparently.

---

### 5. Permissions & Google Play Policy
- **Question**: Which URI permission flags should be granted to external media players, and does `FLAG_GRANT_PERSISTABLE_URI_PERMISSION` require Play Console declarations?
- **Confirmed Decision**: **Grant `FLAG_GRANT_READ_URI_PERMISSION` + `FLAG_GRANT_PERSISTABLE_URI_PERMISSION`**.
  - **Google Play Compliance**: Fully compliant. `FLAG_GRANT_PERSISTABLE_URI_PERMISSION` is a standard framework flag in `android.content.Intent` (introduced in API 19) designed specifically for persisting content URI access across reboots.
  - **No Play Console Declarations Required**: It is NOT a restricted permission, not a manifest permission, and requires zero declarations or disclosures on the Play Console.

---

### 6. User Configuration & UI
- **Question**: Should this behavior be fully automatic or configurable via a user setting?
- **Confirmed Decision**: **Fully Automatic**.
  - Seamless under-the-hood behavior.
  - No new toggles or configuration screens required; all users automatically benefit from working external player history.
