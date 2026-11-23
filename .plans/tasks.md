# Task List: First-Run Onboarding Setup Flow with Dedicated Per-Setting Activities

## Phase 1: Setup & Resources
- [x] [T001] [P] Define string resources in `app/src/main/res/values/strings.xml` for all 10 onboarding screens, titles, detailed option descriptions, live preview labels, and contextual skip button labels ("Skip & Use Defaults", "Skip Remaining", "Start Browsing").

## Phase 2: Foundation & Base Architecture
- [x] [T002] [P] Create `BaseOnboardingStepActivity.kt` in `za.kilowatch.ultimatefilemanager.onboarding`:
  - Enforce project Activity pattern (`attachBaseContext` with `LocaleManagerWrapper`, `EdgeToEdge.enable`, window insets, `isTv`).
  - Standardized top progress header and bottom action bar (Back, Next, contextual Skip button).
  - Progressive skip logic: commits prior selections + writes recommended defaults to all unvisited screens + completes onboarding.
  - Reconfigure mode handling (`EXTRA_RECONFIGURE_MODE`) to return to `SettingsActivity`.
  - Sequential activity navigator with TV and hardware capability skipping.

## Phase 3: Screen 1–3 (Display & Typography)
- [x] [T003] [P] Create `OnboardingViewDensityActivity.kt` with `activity_onboarding_view_density.xml` (mobile) and `activity_onboarding_view_density_tv.xml` (TV):
  - Options: Standard List (Default), Compact List, Large Grid (`ViewModeManager`).
  - Live directory preview mockup pre-rendered in Standard List (48dp).
  - Action button: "Skip & Use Defaults".
- [x] [T004] [P] Create `OnboardingFontSizeActivity.kt` with `activity_onboarding_font_size.xml` (mobile) and `activity_onboarding_font_size_tv.xml` (TV):
  - Options: Small (85%), Medium (100% — Default), Large (115%) (`FontSizeHelper`).
  - Live typography preview pre-rendered at Medium (100%).
  - Action button: "Skip Remaining".
- [x] [T005] [P] Create `OnboardingScrollingTextActivity.kt` with `activity_onboarding_scrolling_text.xml` (mobile) and `activity_onboarding_scrolling_text_tv.xml` (TV):
  - Options: Marquee Auto-Scroll (Default) vs Truncate (`ScrollingTextPreferenceManager`).
  - Live file row preview with long filename actively scrolling with marquee on screen open.
  - Action button: "Skip Remaining".

## Phase 4: Screen 4–6 (Controls & Ergonomics)
- [x] [T006] [P] Create `OnboardingHoldDurationActivity.kt` with `activity_onboarding_hold_duration.xml` (mobile) and `activity_onboarding_hold_duration_tv.xml` (TV):
  - Options: Short 300ms, Normal 500ms (Default), Long 800ms (`LongPressDurationManager`).
  - Interactive "Touch & Hold to Test" visual progress ring with haptic checkmark.
  - Action button: "Skip Remaining".
- [x] [T007] [P] Create `OnboardingLeftHandedFabActivity.kt` with `activity_onboarding_left_handed_fab.xml` (mobile) and `activity_onboarding_left_handed_fab_tv.xml` (TV):
  - Options: Right-Handed (Default — Off), Left-Handed (On) (`LeftHandedFabPreferenceManager`).
  - Miniature phone frame preview showing the FAB animating between Bottom-Right and Bottom-Left.
  - Action button: "Skip Remaining" (Mobile only; skipped on TV).
- [x] [T008] [P] Create `OnboardingEdgeDrawerActivity.kt` with `activity_onboarding_edge_drawer.xml` (mobile) and `activity_onboarding_edge_drawer_tv.xml` (TV):
  - Options: Enabled (Default — On), Disabled (Off) (`QuickAccessManager`).
  - Miniature phone frame preview illustrating the edge handle and slide-out shortcut drawer.
  - Action button: "Skip Remaining" (Mobile only; skipped on TV).

## Phase 5: Screen 7–8 (File Safety & Behavior)
- [x] [T009] [P] Create `OnboardingRecycleBinActivity.kt` with `activity_onboarding_recycle_bin.xml` (mobile) and `activity_onboarding_recycle_bin_tv.xml` (TV):
  - Options: Permanent Erase (Default — Off), Safe Delete (On) (`RecycleBinSettingsManager`).
  - State comparison card pre-rendered with Permanent Erase Active.
  - Action button: "Skip Remaining".
- [x] [T010] [P] Create `OnboardingHiddenFilesActivity.kt` with `activity_onboarding_hidden_files.xml` (mobile) and `activity_onboarding_hidden_files_tv.xml` (TV):
  - Options: Hidden (Default — Off), Visible (On) (`HiddenFilesManager`).
  - Live directory preview pre-rendered with `.thumbnails` hidden; toggling On reveals with dimmed dotfile badge.
  - Action button: "Skip Remaining".

## Phase 6: Screen 9–10 (Security & Diagnostics)
- [x] [T011] [P] Create `OnboardingAppLockActivity.kt` with `activity_onboarding_app_lock.xml` (mobile) and `activity_onboarding_app_lock_tv.xml` (TV):
  - Hardware capability check (`KeyguardManager` / biometric support; automatically skips activity if unsupported).
  - Options: Open Access (Default — Off), Protected Lock (On) (`AppSecurityManager`).
  - Glassmorphic biometric sensor and 4-digit PIN pad preview card.
  - Action button: "Skip Remaining".
- [x] [T012] [P] Create `OnboardingDiagnosticsActivity.kt` with `activity_onboarding_diagnostics.xml` (mobile) and `activity_onboarding_diagnostics_tv.xml` (TV):
  - Options: Enabled (Default — On), Disabled (Off) (`CrashReportManager`).
  - Transparency card with privacy badges (Anonymous Stack Traces, Zero Personal Data).
  - Action buttons: "Skip Remaining" and "Start Browsing".

## Phase 7: App Integration & Settings Re-entry
- [x] [T013] [P] Register all 10 new Activities in `app/src/main/AndroidManifest.xml`.
- [x] [T014] [P] Update `WelcomeActivity.kt` to launch `OnboardingViewDensityActivity` upon permissions completion instead of directly launching `StorageBrowserActivity`.
- [x] [T015] [P] Add "Quick Setup Wizard" card row in `SettingsActivity.kt` and layouts (`activity_settings.xml`, `activity_settings_tv.xml`) under `layoutSectionGeneral` launching with `EXTRA_RECONFIGURE_MODE = true`.

## Phase 8: Verification & Build
- [x] [T016] [P] Build debug APK via `./gradlew assembleMobileGoogleDebug` to verify complete compilation with zero layout, XML, or Kotlin errors.
- [x] [T017] Verify first-run onboarding sequence, live previews on default selection, contextual skip button, TV 16:9 split layout, and Settings re-entry.
