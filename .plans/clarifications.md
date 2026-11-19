# Clarifications: Remote TV Download Manager (Mobile, Windows & Linux Companions)

## Status
Answers received and approved — ready for /plan and /tasks

---

## Questions and Answers

### 1. Remote TV Storage Directory Picker
**Q:** How should the TV download storage location picker behave when changing the target folder from Mobile?  
**A:** **Interactive TV Storage Explorer dialog**: Browses TV Internal & USB storage drives dynamically, allows traversing folders, and supports creating new folders on the TV via `TvShareClient.listFiles` and `TvShareClient.mkdir`.

### 2. Torrent Sub-File Selection
**Q:** When adding a .torrent file to the TV, how should torrent sub-file selection be handled?  
**A:** **Client-Side Inspection & Selection**: Inspect torrent files locally (mobile or PC) and present a sub-file selection checklist dialog before dispatching to the TV. Only the user-selected file indices will be set for active downloading on the TV BitTorrent engine.

### 3. TV Background Service & Standby Resilience
**Q:** How should downloads proceed on the TV when the TV screen turns off or enters standby?  
**A:** **Persistent Foreground Service with Partial Wake Lock**: Keep TV `DownloadService` in the foreground with a partial wake lock so downloads finish even if the TV screen dims, sleeps, or turns off display output.

### 4. Desktop Companion Presentation (`UFM-Windows` & `UFM-Linux`)
**Q:** In the Windows and Linux companion apps, how should the Download Manager view be presented?  
**A:** **Top Navigation Modal / Slide-Over Dashboard**: A modal/slide-over dashboard opened via a top navigation 'Download Manager' button (`DownloadManagerModal.tsx`), consistent with the Sideload APK modal.

---

## Spec & Plan Impact
- **Mobile UI**: Implement an interactive TV folder picker bottom sheet / dialog in `RemoteDownloadManagerActivity` utilizing `TvShareClient`.
- **Desktop Companion UI**: Create `DownloadManagerModal.tsx` in `UFM-Windows/src/components/` and `UFM-Linux/src/components/`, add a `DownloadCloud` button in `App.tsx`, and wire polling to `ufm_api_request`.
- **Torrent Engine**: Integrate local parsing via `TorrentInfo` on Mobile and `bencode` / metadata inspection on Desktop to send `selectedIndices` array with `add-torrent` and `add-magnet` endpoints.
- **TV Background Service**: Ensure `DownloadService.kt` on TV holds a `WakeLock` (`PARTIAL_WAKE_LOCK`) while active transfers are downloading, allowing uninterrupted background downloading when the TV enters screen-off sleep.

---

## Confirmed — Ready for Next Phase
All questions confirmed. Proceed to `/plan` to detail the architecture and file modifications.
