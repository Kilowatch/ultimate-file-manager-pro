# Implementation Plan: First-Run Onboarding Setup Flow with Dedicated Per-Setting Activities

## Goals
- Create a visual first-run onboarding setup sequence consisting of 10 standalone Activities.
- Each Activity puts 100% focus on a single setting, featuring rich explanatory text and a **large live visual preview example** pre-rendered on the recommended default selection.
- Implement contextual skip controls: **"Skip & Use Defaults"** on Screen 1, and **"Skip Remaining"** from Screen 2 onwards (progressively saving prior selections and defaulting only remaining unvisited settings).
- Support full Android TV 16:9 split-screen D-pad parity (skipping mobile touch screens automatically).
- Guarantee zero preference drift with `SettingsActivity` and add an on-demand re-entry card in Settings.

## Architecture Overview
The 10 Activities share a common base architecture `BaseOnboardingStepActivity`:
- Implements `attachBaseContext` with `LocaleManagerWrapper`.
- Calls `EdgeToEdge.enable(this)` and applies window insets.
- Manages standardized header and bottom button bar ("Back", "Next", "Skip Remaining" / "Skip & Use Defaults").
- Connects screens with horizontal slide transitions (`ufm_slide_in_right` / `ufm_slide_out_left`).
- Centralizes progressive skip logic: commits prior choices + applies defaults to unvisited settings + finishes onboarding.
- Supports `EXTRA_RECONFIGURE_MODE`: pre-populates with user's current preferences and returns to `SettingsActivity`.

```
Sequential Flow:
1.  [OnboardingViewDensityActivity]        (Button: "Skip & Use Defaults")
2.  [OnboardingFontSizeActivity]           (Button: "Skip Remaining")
3.  [OnboardingScrollingTextActivity]      (Button: "Skip Remaining")
4.  [OnboardingHoldDurationActivity]       (Button: "Skip Remaining")
5.  [OnboardingLeftHandedFabActivity]      (Button: "Skip Remaining" — Mobile Only)
6.  [OnboardingEdgeDrawerActivity]         (Button: "Skip Remaining" — Mobile Only)
7.  [OnboardingRecycleBinActivity]         (Button: "Skip Remaining")
8.  [OnboardingHiddenFilesActivity]        (Button: "Skip Remaining")
9.  [OnboardingAppLockActivity]            (Button: "Skip Remaining" — Skipped if hardware lacks secure lock)
10. [OnboardingDiagnosticsActivity]       (Button: "Skip Remaining" / "Start Browsing")
```

## Files to Create

### Architecture & Base
- `app/src/main/java/za/kilowatch/ultimatefilemanager/onboarding/BaseOnboardingStepActivity.kt`: Abstract base coordinator for all 10 activities, handling navigation, transitions, TV detection, and progressive skip logic.

### 10 Dedicated Activities (One Activity Class, Two Layouts Each)
1. `OnboardingViewDensityActivity.kt` + `res/layout/activity_onboarding_view_density.xml` + `res/layout/activity_onboarding_view_density_tv.xml`
2. `OnboardingFontSizeActivity.kt` + `res/layout/activity_onboarding_font_size.xml` + `res/layout/activity_onboarding_font_size_tv.xml`
3. `OnboardingScrollingTextActivity.kt` + `res/layout/activity_onboarding_scrolling_text.xml` + `res/layout/activity_onboarding_scrolling_text_tv.xml`
4. `OnboardingHoldDurationActivity.kt` + `res/layout/activity_onboarding_hold_duration.xml` + `res/layout/activity_onboarding_hold_duration_tv.xml`
5. `OnboardingLeftHandedFabActivity.kt` + `res/layout/activity_onboarding_left_handed_fab.xml` + `res/layout/activity_onboarding_left_handed_fab_tv.xml`
6. `OnboardingEdgeDrawerActivity.kt` + `res/layout/activity_onboarding_edge_drawer.xml` + `res/layout/activity_onboarding_edge_drawer_tv.xml`
7. `OnboardingRecycleBinActivity.kt` + `res/layout/activity_onboarding_recycle_bin.xml` + `res/layout/activity_onboarding_recycle_bin_tv.xml`
8. `OnboardingHiddenFilesActivity.kt` + `res/layout/activity_onboarding_hidden_files.xml` + `res/layout/activity_onboarding_hidden_files_tv.xml`
9. `OnboardingAppLockActivity.kt` + `res/layout/activity_onboarding_app_lock.xml` + `res/layout/activity_onboarding_app_lock_tv.xml`
10. `OnboardingDiagnosticsActivity.kt` + `res/layout/activity_onboarding_diagnostics.xml` + `res/layout/activity_onboarding_diagnostics_tv.xml`

## Files to Modify

### `WelcomeActivity.kt`
- Transition to `OnboardingViewDensityActivity` when permissions are granted instead of directly launching `StorageBrowserActivity`.

### `SettingsActivity.kt` + `activity_settings.xml` + `activity_settings_tv.xml`
- Add `cardQuickSetupWizard` under `layoutSectionGeneral` to launch the wizard in Reconfigure Mode.

### `AndroidManifest.xml`
- Register all 10 new Activities with `android:exported="false"`.

### `strings.xml`
- Add translatable titles, subtitles, and button labels for each onboarding Activity and preview card.

## Data Model & Persistence
No new database tables or preference files. Direct integration with existing preference managers:
- `ViewModeManager`
- `FontSizeHelper`
- `ScrollingTextPreferenceManager`
- `LongPressDurationManager`
- `LeftHandedFabPreferenceManager`
- `QuickAccessManager`
- `RecycleBinSettingsManager`
- `HiddenFilesManager`
- `AppSecurityManager`
- `CrashReportManager`

## Test Plan
- Compile with `./gradlew assembleMobileGoogleDebug`.
- Verify full 10-screen sequence from fresh install.
- Verify Screen 1 button is "Skip & Use Defaults"; Screens 2-10 are "Skip Remaining".
- Verify mid-flow skip correctly preserves configured settings and defaults remaining.
- Verify TV 16:9 side-by-side layout and D-pad remote focus.
- Verify Reconfigure Mode from `SettingsActivity`.
