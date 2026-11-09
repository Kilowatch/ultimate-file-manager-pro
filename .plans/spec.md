# Specification: UFM Media Player Loop and Continuous Playback Control

## Overview
Resolves playback behavior issues reported in `error.txt`, where audio tracks unintentionally repeat in an infinite loop or continuously advance through subsequent files despite disabling auto-play. This specification establishes strict adherence to user preferences for continuous playback (auto-play next), introduces a comprehensive 3-state repeat mode (Off, Repeat All, Repeat One), and guarantees that a selected file can play exactly once and stop automatically when finished.

---

## User Stories
- **As an audio listener**, I want to select a single audio file and have it play once and stop automatically when it finishes, so that my music or podcast does not continue playing indefinitely or repeat unwantedly.
- **As a user who disabled "Auto-play next file" in Settings**, I expect the media player to strictly honor this setting and stop immediately when the current track finishes, rather than automatically advancing to subsequent files.
- **As a music listener**, I want a clear repeat control allowing me to switch between **Repeat Off**, **Repeat All** (loop playlist), and **Repeat One** (loop current track), with distinct visual indicators and status announcements.
- **As an Android TV user**, I want full D-pad access to toggle and view the repeat and continuous playback modes directly from the TV media player controls.

---

## Functional Requirements

### FR-01: Strict Adherence to Auto-play Next / Continuous Playback Setting
- The media player and its background playback engine must strictly honor the global **"Auto-play next in UFM Player"** preference (`AutoplayPreferenceManager`):
  - **When Auto-play Next is Disabled (Off)**:
    - When the current audio or video file finishes playback, the player must immediately stop. It must **not** advance to the next track in the folder or playlist.
    - Playback stops cleanly: audio focus is abandoned, wake locks are released, and the foreground notification is dismissed or transitioned to an idle/completed state.
    - The countdown / next-track preview overlay must **not** appear when approaching the end of the track if auto-play next is disabled.
  - **When Auto-play Next is Enabled (On)**:
    - When the current track finishes, playback advances to the next track in the queue, subject to the active repeat mode.

### FR-02: Three-State Repeat Mode (Repeat Off, Repeat All, Repeat One)
- Replace binary repeat toggling with a standard 3-state repeat cycle:
  1. **Repeat Off** (Default): Play through the queue once. No endless looping.
     - If Auto-play Next is On: Plays through to the end of the playlist, then stops cleanly at the last track without looping back to track 1.
     - If Auto-play Next is Off: Plays only the current track once and stops.
  2. **Repeat All** (Loop Queue / Folder):
     - Plays tracks sequentially (or shuffled if shuffle is active).
     - When the last track in the queue finishes, playback automatically loops back to the beginning of the queue and continues.
  3. **Repeat One** (Loop Single Track):
     - When the current track reaches the end, it immediately restarts from the beginning (position 0) and plays again continuously until manually paused or skipped.
- Tapping or selecting the Repeat control cycles sequentially: `Repeat Off` → `Repeat All` → `Repeat One` → `Repeat Off`.

### FR-03: Visual Feedback & Iconography for Repeat States
- The in-player Repeat button on both Mobile and TV must visually distinguish all three states:
  - **Repeat Off**: Dimmed / inactive alpha tint (or standard inactive icon).
  - **Repeat All**: Active primary accent tint with standard loop icon (`ic_repeat`).
  - **Repeat One**: Active primary accent tint with repeat-one icon (`ic_repeat_one` or distinct badge).
- Every state change triggers a concise, user-friendly toast / HUD feedback banner:
  - *"Repeat off"*
  - *"Repeat all"*
  - *"Repeat current track"* (or *"Repeat one"*)

### FR-04: Preference Persistence Across Sessions
- User choices for **Repeat Mode** and **Auto-play Next** must persist across playback sessions, app restarts, and process re-creation.
- Starting a new file retains the user's chosen playback mode rather than silently resetting to infinite looping or default values.

### FR-05: Manual Navigation Independence
- Disabling auto-play next or repeat must **not** prevent manual user navigation:
  - The user can still tap the **Next** or **Previous** buttons at any time to manually advance to other tracks in the playlist.
  - When manual skip is used, the newly selected track starts playing immediately.

---

## Non-Functional Requirements
- **NFR-01: Battery & Background Cleanliness**: When playback finishes and auto-play is off, the player must not keep the device awake or consume background battery.
- **NFR-02: UI Responsiveness**: Repeat mode toggles and track-end transitions must be instantaneous with zero lag or audio stuttering.
- **NFR-03: UFMStandard Adherence**: Visual indicators, toasts, and buttons must adhere to the app's frosted glass styling and TV focus specifications.
- **NFR-04: Multi-Platform Parity**: Identical behavior and control states must be available on both Mobile (touch) and Android TV (D-pad remote).

---

## Edge Cases & Error States
- **Single-Track Playlist**: If a folder contains only one audio file:
  - With *Repeat Off*: Track plays once and stops.
  - With *Repeat One* or *Repeat All*: Track continuously loops from the beginning.
- **Last Track of Playlist with Repeat Off & Auto-play On**:
  - The playlist plays each song once in order. When the last song finishes, playback stops and does not wrap around to track 1.
- **Shuffle Mode Interactions**:
  - *Shuffle On + Repeat Off*: Plays each track in randomized order once. Once all tracks have played, stops.
  - *Shuffle On + Repeat All*: Plays tracks in randomized order and reshuffles / continues indefinitely.
  - *Shuffle On + Repeat One*: Continues repeating the single current track (Repeat One overrides shuffle until advanced).
- **Network / Cloud Streams**:
  - Network audio files (SMB, SFTP, WebDAV, Cloud) must cleanly stop without leaving unclosed sockets or dangling worker threads when stopping at track end.

---

## Out of Scope
- Gapless crossfading between audio tracks.
- A-B loop segment markers (looping a sub-segment within a single file).
- Equalizer or audio DSP modification.

---

## Success Criteria
- [ ] User can play an audio file with Auto-play Next disabled and have it play once and stop automatically when finished.
- [ ] Repeat button cycles through three distinct states: Repeat Off, Repeat All, and Repeat One.
- [ ] Each repeat mode exhibits distinct visual iconography/tinting on Mobile and Android TV.
- [ ] When Repeat is Off and Auto-play is On, reaching the end of a playlist stops playback without wrapping back to track 1.
- [ ] Toggling "Auto-play next in UFM Player" in Settings immediately and effectively governs playback behavior.
- [ ] Manual Next/Prev buttons remain fully functional regardless of auto-play or repeat settings.
