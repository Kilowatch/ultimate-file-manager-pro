# Test Plan: UFM Media Player Loop and Continuous Playback Control

## Scope
Verification of loop and continuous playback control in UFM Media Player:
- Stopping at track completion when "Auto-play next in UFM Player" is disabled.
- 3-state Repeat Mode cycling (`Repeat Off` → `Repeat All` → `Repeat One` → `Repeat Off`).
- Visual iconography (`ic_repeat_one`) and HUD toast announcements.
- Mobile and Android TV parity.

## Build to Test
- Target APK: `app/build/outputs/apk/mobileGoogle/debug/app-mobileGoogle-debug.apk`
- Command: `./gradlew assembleMobileGoogleDebug`

---

## 1. Happy Path Tests

### TC-001: Single File Play Once (The core bug in error.txt)
**Covers:** FR-01, FR-03
**Preconditions:** In Settings → Media Player, "Auto-play next in UFM Player" is Disabled. Repeat Mode is Repeat Off.
**Steps:**
1. Open a folder containing multiple audio files (e.g. 3 MP3s).
2. Tap the first audio file to open it in UFM Player.
3. Seek to within 5 seconds of the end of the file.
4. Let the file finish playing.
**Expected Result:** Playback stops immediately. Audio focus is abandoned, wake lock is released, and notification is dismissed/idled. Subsequent tracks do NOT play. Track 1 does NOT repeat.
**Pass Criteria:** Player stops playing and remains stopped.

### TC-002: Three-State Repeat Mode Cycling
**Covers:** FR-02, FR-03
**Preconditions:** UFM Player is active.
**Steps:**
1. Observe the Repeat button: it is dimmed with `ic_repeat` ("Repeat Off").
2. Tap the Repeat button once.
3. Tap the Repeat button a second time.
4. Tap the Repeat button a third time.
**Expected Result:**
- Step 2: Toast shows "Repeat all", icon is active/lit `ic_repeat`.
- Step 3: Toast shows "Repeat current track", icon switches to `ic_repeat_one`.
- Step 4: Toast shows "Repeat off", icon returns to dimmed `ic_repeat`.
**Pass Criteria:** Cycles accurately through all 3 modes with correct icon and toast.

### TC-003: Repeat One Execution
**Covers:** FR-02
**Preconditions:** Repeat Mode is set to "Repeat current track" (`ic_repeat_one`).
**Steps:**
1. Play any audio or video track.
2. Seek to near the end and let the track finish.
**Expected Result:** Track automatically seeks back to 0:00 and continues playing without advancing to the next file.
**Pass Criteria:** Current track repeats continuously.

---

## 2. Edge Cases

### TC-004: Repeat Off with Auto-Play Enabled (Playlist End Stop)
**Covers:** FR-01, FR-02
**Preconditions:** In Settings, "Auto-play next in UFM Player" is Enabled. Repeat Mode is Repeat Off.
**Steps:**
1. Open a folder with 2 audio files.
2. Tap the last file (file 2).
3. Seek to near the end and let it finish.
**Expected Result:** Playback stops cleanly at the end of the last file. It does NOT loop back to file 1.
**Pass Criteria:** Playback stops at the end of the queue.

### TC-005: Manual Navigation with Auto-Play Disabled
**Covers:** FR-05
**Preconditions:** Auto-play is Disabled.
**Steps:**
1. Play a track in a multi-file folder.
2. Tap the **Next Track** button (`btnNext`).
3. Tap the **Previous Track** button (`btnPrev`).
**Expected Result:** Player jumps to next/previous tracks as commanded.
**Pass Criteria:** Manual skipping works without restriction.

---

## 3. Platform Parity

### TC-006: Android TV D-Pad Focus and Remote Navigation
**Covers:** FR-06, NFR-04
**Preconditions:** UFM Player running on Android TV.
**Steps:**
1. Highlight `btnRepeat` using D-pad navigation on the TV remote.
2. Press D-pad Center/OK to cycle repeat modes.
**Expected Result:** Focus ring highlights button clearly, icon updates dynamically, and TV HUD toast displays active mode.
**Pass Criteria:** D-pad navigation operates smoothly on TV.
