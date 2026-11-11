# Specification: External Video Player Playback History Tracking (VLC & External Players)

## Overview
When users launch media files (specifically video and audio) from Ultimate File Manager into external media players such as VLC for Android, the played media fails to record in the external player's playback history and resume database. While some players (like XPlayer) track playback history via intent display names or transient streams, VLC and multiple other Android players require identifiable media references (such as verifiable file paths or standard MediaStore projections) to record history and resume positions. This specification defines the requirements for ensuring video and audio handoff to external players provides all necessary media metadata and URI descriptors for reliable playback history recording.

---

## User Stories
- **As a user opening videos in VLC from UFM**, I want played videos to appear in VLC's "History" tab immediately after playback, so that I can easily see what I recently watched and resume unfinished videos directly from VLC.
- **As a user using other external players (e.g., Just Player, Next Player, MPV, Nova Video Player, MX Player)**, I want media launched from UFM to register in their respective playback history and resume points consistently across storage types.
- **As a user playing media from different storage sources (internal storage, SD card, USB OTG, SAF, and network shares)**, I want the app to provide the best possible media identity to external players so that local files retain persistent history and streamed network files display meaningful titles and playback state.

---

## Functional Requirements

### 1. Media Handoff & Identity Resolution
- **FR-01: Verifiable Media Identity for Local Media**: When opening a local video or audio file in external players, the intent data must expose media identity attributes that external players' media libraries (such as VLC's `libmedialibrary`) can resolve to a physical or indexed media item.
- **FR-02: Content Provider Projection Compatibility**: The content provider serving media URIs to external applications must support standard media data projections (including `_data` / `MediaStore.MediaColumns.DATA` and display metadata) when queried by external players, allowing players that attempt path resolution from content URIs to successfully obtain the underlying file path.
- **FR-03: MediaStore URI Fallback / Integration**: For media files located on shared or indexed storage volumes, the application should resolve and prioritize standard media provider URIs (or compatible projections) when handing off to external players that reject private content provider authorities.
- **FR-04: Intent Metadata Preservation**: All existing media metadata extras (`title`, `item_title`, `org.videolan.vlc.MediaTitle`, `android.intent.extra.TITLE`, `size`, `from_start = false`, and subtitle extras) must continue to be attached to ensure players that read intent extras for title and resume progress continue to function without regressions.

### 2. Multi-Storage Environment Support
- **FR-05: Local Storage (Internal & Removable SD/USB)**: Media opened from primary internal storage and secondary removable media must provide full path/MediaStore resolvability to external players.
- **FR-06: SAF (Storage Access Framework) & Added Locations**: Media opened from SAF-granted trees must provide external players with the necessary query projections or streaming proxy descriptors so that player history tracking functions to the maximum extent supported by the Android security model.
- **FR-07: Network & Cloud Media Streaming**: For network and cloud files streamed via the local proxy server (`NetworkHttpProxyServer`), the stream URL and intent extras must supply consistent titles and deterministic, persistent session identifiers. When an external player later reopens a stream directly from its playback history, `NetworkHttpProxyServer` must dynamically re-resolve the target share and resume streaming on demand.

### 3. Open With Flow Consistency
- **FR-08: All Entry Points Synchronized**: The history-compatible media intent construction must be utilized across all avenues where media is launched into external players:
  - Direct file tap (when default action is set to External App)
  - "Open with" selection dialog / bottom sheet
  - Context menu "Open with"
  - Tools FAB / Quick Action Bar "Open with"
  - Floating toolbar actions across Standalone, Tabbed Mode, and Twin Window Mode

---

## Non-Functional Requirements
- **NFR-01: Android Security & Scoped Storage Compliance**: Media intent and URI handling must fully comply with Android 10-16+ Scoped Storage requirements without triggering `FileUriExposedException` or violating sandbox boundaries.
- **NFR-02: Privacy & Permission Scoping**: URI read permissions granted to external players must be strictly scoped to the target file and any associated subtitle files (`FLAG_GRANT_READ_URI_PERMISSION`).
- **NFR-03: Backward Compatibility**: Existing behavior for non-media files (PDFs, archives, text documents, APKs) and built-in viewers must remain completely unaffected.
- **NFR-04: Performance**: Resolving media identity or querying MediaStore identifiers must execute asynchronously or with near-zero latency (< 50ms) to prevent UI lag or launch delays when opening media.

---

## Edge Cases & Error States
- **Non-Indexed / Newly Created Files**: Video files freshly downloaded or created that have not yet been indexed by the Android MediaStore scanner must still open cleanly, either triggering an on-demand scan or providing compatible provider queries.
- **Removable Media & OTG Drives**: USB drives and FAT32/exFAT SD cards where file paths may not follow standard `/storage/emulated/0` conventions.
- **Files with Special / Unicode Characters**: Filenames containing emoji, non-ASCII characters, symbols, or URI-escaped characters must resolve correctly in external player history without title corruption.
- **Player Lacks Storage Permissions**: If an external player lacks `MANAGE_EXTERNAL_STORAGE` or `READ_MEDIA_VIDEO` permissions, playback must still succeed via standard content URI streaming even if the player's internal history database fails to resolve the physical path.
- **Transient Network Streams**: Network files played over the HTTP proxy where the IP/port may vary between launches.

---

## Out of Scope
- Modifying VLC for Android's internal application code or database logic (we can only adapt what UFM sends and provides via Android IPC).
- Altering UFM's internal media player (`UFMPlayerActivity`) playback history or controls.
- Changing non-media file routing or external open behaviors for documents, text files, or archives.

---

## Success Criteria
- [ ] Videos opened from UFM in VLC for Android appear in VLC's "History" tab after playback.
- [ ] Videos opened from UFM in VLC can be resumed from their last playback position from VLC's history.
- [ ] Videos opened in other external players (e.g. XPlayer, Just Player, MPV) continue to appear in history with correct titles and resume progress.
- [ ] Videos streamed from network shares (SMB, SFTP, WebDAV, Cloud) can be reopened from VLC's history tab with automatic session revival.
- [ ] No regression when opening media in UFM's built-in player or internal viewers.
- [ ] No `FileUriExposedException` or security crashes on modern Android versions (Android 11 through Android 16/17).
- [ ] Tested and verified across internal storage, SD card/USB OTG, and network storage.
