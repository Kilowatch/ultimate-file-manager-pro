# Specification: Picture-in-Picture Compatibility for Custom Launchers and Modern Android

## Overview
When playing a video in Ultimate File Manager Pro, navigating to the home screen (via gesture navigation or home button) fails to enter Picture-in-Picture (PiP) mode when using custom third-party launchers (such as One4Home, Nova Launcher, Lawnchair, Niagara, etc.) on modern Android versions, whereas it works under stock launchers (such as Samsung OneUI). This specification defines requirements to ensure robust, launcher-agnostic automatic Picture-in-Picture entry across all launchers and Android versions, providing parity with other modern Android media applications.

---

## User Stories
- **As a user running a custom launcher (such as One4Home, Nova, Lawnchair)**, I want UFM's video player to automatically and seamlessly transition into Picture-in-Picture mode when I return to the home screen, so that my video playback continues uninterrupted.
- **As a user watching a video**, I want the PiP window to match the aspect ratio of the playing video and transition smoothly without visual glitches, black frames, or abrupt snapping.
- **As a user playing audio-only files or who has set Background Video Mode to "Audio Only"**, I want returning to the home screen to follow my configured preference (audio playback in background) without launching an empty or unwanted PiP window.
- **As a user controlling playback from PiP**, I want responsive playback controls (Play/Pause, Next, Previous) that accurately reflect the current playback state inside the PiP window.
- **As an Android TV user**, I want standard fullscreen and TV playback behavior preserved without unintended mobile PiP triggers.

---

## Functional Requirements

### 1. Launcher-Independent Automatic PiP Entry
- **FR-01: Seamless Home Navigation PiP**: When an active video is playing and Background Video Mode is set to PiP, leaving the player to return to the home screen (via swipe-up gesture, Home button, or task switcher) must immediately transition into Picture-in-Picture mode regardless of the active launcher app.
- **FR-02: Playback State Awareness**: Automatic PiP entry must be active whenever video playback is active (playing or buffering), and disengaged when playback is stopped, completed, or explicitly closed by the user.

### 2. Window Framing & Aspect Ratio
- **FR-03: Dynamic Aspect Ratio Alignment**: The PiP window must dynamically adapt to the aspect ratio of the active video stream (constrained within the Android-supported aspect ratio limits of 1:2.39 to 2.39:1), preventing letterboxing or pillarboxing distortions.
- **FR-04: Smooth Transition Framing**: The system transition into PiP must use the active video rendering surface/view bounds as the animation origin, delivering a smooth fluid transition from full screen into the floating PiP window.

### 3. PiP Remote Actions & State Sync
- **FR-05: Real-Time Transport Controls**: The PiP window must display interactive actions for Previous Track, Play/Pause toggle, and Next Track.
- **FR-06: State Synchronization**: The Play/Pause action icon and description in the PiP window must update immediately when playback is paused or resumed.

### 4. Preference and Platform Respect
- **FR-07: Background Video Mode Respect**:
  - When Background Video Mode is configured as `PIP`: Video playback enters PiP on home navigation.
  - When Background Video Mode is configured as `AUDIO_ONLY`: Video does not enter PiP; the activity finishes or moves to background while audio continues via playback service.
- **FR-08: Audio-Only Media**: Audio files (MP3, FLAC, AAC, etc.) must never trigger PiP entry regardless of launcher or background settings.
- **FR-09: TV Device Exclusion**: PiP automatic entry must not trigger on Android TV devices (`isTv == true`).
- **FR-10: Legacy Android Fallback**: Maintain backwards compatibility on Android 8.0 through 11 (API 26–30) using existing lifecycle callbacks while utilizing modern platform automatic PiP capabilities on Android 12+ (API 31+).

### 5. Return and Dismissal Lifecycle
- **FR-11: Fullscreen Restoration**: Expanding the PiP window back to full screen must seamlessly restore the player activity, player controls, and playback UI without reinitializing the playback session or dropping frames.
- **FR-12: PiP Dismissal**: Dismissing the PiP window (closing or swiping away the floating window) must handle playback cessation or transition gracefully according to the configured background playback preferences.

---

## Non-Functional Requirements
- **NFR-01: Zero Transition Latency**: The PiP transition must execute without frame freezes, audio hiccups, or black-screen flashes during navigation.
- **NFR-02: Battery & Memory Efficiency**: The player in PiP mode must hide non-essential overlay controls and view hierarchies to minimize rendering overhead.
- **NFR-03: Stability & Crash Prevention**: The PiP lifecycle transitions must be protected against illegal state exceptions, window manager race conditions, or unhandled exceptions when home transitions occur rapidly.

---

## Edge Cases & Error States
- **EC-01: Video Paused on Home Navigation**: If a video is paused when the user navigates home, automatic PiP entry should not occur or should remain paused in PiP without auto-resuming playback unexpectedly.
- **EC-02: Extreme Aspect Ratios**: Videos with extreme aspect ratios (e.g. ultra-wide 32:9 or ultra-tall vertical videos) exceeding the system limits (narrower than 1:2.39 or wider than 2.39:1) must be clamped safely to valid Android bounds to prevent system crashes (`IllegalArgumentException`).
- **EC-03: Rapid Multitasking / App Switching**: Rapidly switching back and forth between UFM and the launcher or other apps must not cause duplicate PiP windows, orphaned playback surfaces, or memory leaks.
- **EC-04: System PiP Permission Disabled**: If the user has explicitly disabled "Picture-in-picture" permission for UFM in Android system settings (`App info > Picture-in-picture > Not allowed`), the app must fail gracefully without crashing and fall back to the configured background behavior.
- **EC-05: Playlist Track Change in PiP**: When a video finishes in PiP mode and advances to the next item:
  - If the next item is a video, PiP remains active and updates aspect ratio/title.
  - If the next item is an audio file, PiP should close or transition to background audio smoothly.

---

## Out of Scope
- Custom in-app floating window player inside UFM file browser panes (this specification strictly covers OS-level Picture-in-Picture).
- Overriding or modifying third-party launcher gesture implementations.
- Adding custom resizing handles or gestures outside of the standard OS-provided PiP window controls.

---

## Success Criteria
- [ ] Navigating home (swipe gesture or button) while playing video enters PiP mode when using One4Home or any third-party launcher.
- [ ] Navigating home while playing video continues to work seamlessly on stock launchers (Samsung OneUI, Pixel Launcher, etc.).
- [ ] The PiP window aspect ratio correctly matches the aspect ratio of the playing video.
- [ ] The transition into PiP is smooth with a seamless zooming animation from the video player view.
- [ ] Transport controls (Previous, Play/Pause, Next) inside the PiP window function reliably and stay synchronized with playback state.
- [ ] Audio-only tracks and users with "Audio Only" background mode setting never trigger PiP.
- [ ] Expanding from PiP back to fullscreen restores player controls and UI without interruptions.
- [ ] System PiP permission disabled states are handled cleanly without crashes.
