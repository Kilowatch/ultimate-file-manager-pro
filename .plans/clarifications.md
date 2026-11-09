# Clarifications: UFM Media Player Loop and Continuous Playback Control

## Status
Clarifications confirmed — ready for /plan and /tasks

---

## Questions & Confirmed Answers

### 1. Repeat Mode Cycle Order
- **Question**: What cycle order should tapping the Repeat button follow?
- **Decision**: **`Repeat Off` → `Repeat All` → `Repeat One` → `Repeat Off`**.
  - Tapping cycles through standard media player modes in that exact sequence.

### 2. Default Repeat State
- **Question**: What should the default repeat mode be for new installs and initial playback?
- **Decision**: **`Repeat Off` (plays once and stops)**.
  - Ensures newly opened files play once without unwanted infinite looping.

### 3. Queue Population with Auto-Play Disabled
- **Question**: When Auto-play Next is disabled, how should the folder playlist/queue behave?
- **Decision**: **Load all sibling media files into the queue, but stop automatically when the track ends**.
  - All sibling audio/video tracks remain accessible in the queue drawer for manual Next/Previous navigation and playlist selection, but automatic progression at track end is inhibited.

### 4. Auto-Play Next Toggle Location
- **Question**: Where should the Auto-play Next toggle be accessible?
- **Decision**: **Persist in Settings only (`Settings → Media Player`)**.
  - Keeps the player toolbar and HUD clean and uncluttered.

### 5. Repeat Mode Persistence
- **Question**: Should the selected Repeat Mode persist across app/player launches?
- **Decision**: **Persist globally in Player preferences (`PlayerPreferencesManager`)**.
  - The chosen repeat mode survives player exits, process recreation, and subsequent file opens until explicitly changed by the user.

### 6. Video vs. Audio Parity
- **Question**: Should this behavior apply to both audio and video files in UFM Player?
- **Decision**: **Yes, apply identically to both audio and video playback**.
  - Unified playback engine behavior across all media types in `UFMPlayerActivity` and `UFMPlaybackService`.

### 7. Android TV Parity
- **Question**: Should the 3-state repeat and auto-play controls be synchronized on Android TV?
- **Decision**: **Yes, full parity on Android TV**.
  - Android TV player UI supports D-pad remote cycling of the 3-state repeat button with TV HUD toast confirmations matching Mobile.

---

## Plan & Implementation Impact
1. **`PlayerPreferencesManager.kt`**:
   - Add `RepeatMode` enum (`OFF`, `ALL`, `ONE`).
   - Store `repeat_mode` with default `RepeatMode.OFF`.
   - Provide getter, setter, and cycle helper functions.
2. **`UFMPlaybackService.kt`**:
   - Track completion listener (`STATE_ENDED`):
     - If `RepeatMode.ONE`: Replay current track (seek 0 + play).
     - If `RepeatMode.ALL`: If queue has items, advance to next; if at last track, wrap around to 0.
     - If `RepeatMode.OFF`:
       - If `AutoplayPreferenceManager.isEnabled(...)` is `false`: **STOP** immediately (pause, release audio focus, safeStopForeground).
       - If `AutoplayPreferenceManager.isEnabled(...)` is `true`: Advance to next track, but if at the end of the queue (`currentIndex >= queue.size - 1`), **STOP** instead of wrapping to 0.
   - Suppress `NextTrackOverlayView` countdown when auto-play next is disabled.
3. **`QueueManager.kt`**:
   - Adjust `nextIndex()` logic or call-site handling so repeat-off does not wrap around to index 0 on track completion.
4. **Icons & UI (Mobile + Android TV)**:
   - Provide visual indication for `ic_repeat` (Repeat All) vs `ic_repeat_one` (Repeat One) vs dimmed/inactive (Repeat Off).
   - Add string resources for toasts: `player_toast_repeat_off`, `player_toast_repeat_all`, `player_toast_repeat_one`.
