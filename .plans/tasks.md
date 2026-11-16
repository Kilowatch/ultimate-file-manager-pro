# Task List: Picture-in-Picture Compatibility for Custom Launchers and Modern Android

## Foundation
- [x] [T001] [P] Implement dynamic aspect ratio calculation and PiP parameter builder in `UFMPlayerActivity.kt`:
  - Implement `calculateValidAspectRatio(): Rational` with `unappliedRotationDegrees` swap handling and safe clamping between `Rational(100, 239)` and `Rational(239, 100)` using GCD reduction.
  - Implement `buildPiPParams(isPlaying: Boolean): PictureInPictureParams.Builder` with remote actions (Prev, Play/Pause, Next), aspect ratio, `sourceRectHint` from `playerView` visible bounds, and `setAutoEnterEnabled(canAutoEnter)` on Android 12+ (API 31+).

## Feature Implementation
- [x] [T002] [P] Add `onVideoSizeChanged` to `activityPlayerListener` in `UFMPlayerActivity.kt`:
  - Listen for video size/aspect ratio updates from Media3/ExoPlayer and trigger `updatePiPParams()` to keep the PiP aspect ratio synchronized.
- [x] [T003] [P] Synchronize playback state updates, view layout, and playlist audio track transitions:
  - Call `updatePiPParams()` in `onPlaybackStateChanged` so `autoEnterEnabled` reflects play vs pause state in real time.
  - In `playbackCallback.onTrackChanged`, automatically dismiss PiP (`finish()`) if transitioning to an audio-only track while in PiP mode, allowing audio playback to continue seamlessly via `UFMPlaybackService`.
  - Update `onResume()` and attach layout change listener to `playerView` to ensure `sourceRectHint` and PiP parameters are refreshed when views are laid out.
- [x] [T004] Refactor `onUserLeaveHint()` in `UFMPlayerActivity.kt`:
  - Retain legacy fallback on Android 8.0–11 (API 26–30) and safety check for API 31+ to enter PiP via `buildPiPParams(true).build()`.
  - Maintain `finish()` behavior when returning home during playback if audio-only or if Background Video Mode is set to "Audio Only".

## Polish & Verification
- [x] [T005] [P] Run build verification with `./gradlew assembleMobileGoogleDebug` to ensure clean compilation without errors or warnings.
- [x] [T006] Execute verification checklist across custom launcher (One4Home), stock launcher (OneUI), paused state, audio-only transitions, and aspect ratio matching.
