# Task List: UFM Media Player Loop and Continuous Playback Control

## Phase 1: Setup & Data Model
- [x] [T001] [app/src/main/res/drawable/ic_repeat_one.xml] Create `ic_repeat_one.xml` Material vector drawable with the numeral "1" enclosed in repeat loop arrows.
- [x] [T002] [app/src/main/res/values/strings.xml] Add string resources: `player_toast_repeat_all`, `player_toast_repeat_one`, and `icon_media_repeat_one`.
- [x] [T003] [app/src/main/java/za/kilowatch/ultimatefilemanager/settings/PlayerPreferencesManager.kt] Define `RepeatMode` enum (`OFF`, `ALL`, `ONE`) and implement `getRepeatMode`, `setRepeatMode`, and `cycleRepeatMode` in `PlayerPreferencesManager`.

## Phase 2: Icon Registration Checklist
- [x] [T004] [app/src/main/java/za/kilowatch/ultimatefilemanager/settings/IconCustomizationManager.kt] Register `R.drawable.ic_repeat_one` in `ALL_BUILTIN_ICONS`.
- [x] [T005] [app/src/main/java/za/kilowatch/ultimatefilemanager/settings/IconCustomizationActivity.kt] Register `IconItemData("media_repeat_one", ...)` in Media category.
- [x] [T006] [app/src/main/java/za/kilowatch/ultimatefilemanager/settings/IconPackExportActivity.kt] Register `"media_repeat_one"` in `loadCategories()`.

## Phase 3: Playback Engine & Service Integration
- [x] [T007] [P] [app/src/main/java/za/kilowatch/ultimatefilemanager/viewer/UFMPlaybackService.kt] Replace `isRepeat: Boolean` with `repeatMode: RepeatMode`, initialize from `PlayerPreferencesManager`, add `cycleRepeatMode()`, and rewrite `STATE_ENDED` completion handler to check `AutoplayPreferenceManager.isEnabled(...)` and active `repeatMode` to stop or advance cleanly.

## Phase 4: Player UI & Interaction Sync (Mobile & Android TV)
- [x] [T008] [P] [app/src/main/java/za/kilowatch/ultimatefilemanager/viewer/UFMPlayerActivity.kt] Implement `updateRepeatButton(mode: RepeatMode)`, update `btnRepeat.setOnClickListener` to cycle repeat mode and show HUD toasts, sync initial state on service bind, and gate `NextTrackOverlayView` countdown based on autoplay next and repeat mode.

## Phase 5: Verification & Polish
- [x] [T009] [app/src/test/java/za/kilowatch/ultimatefilemanager/viewer/RepeatModeTest.kt] Create and run JUnit unit tests for `RepeatMode` values, serialization, and transitions.
- [x] [T010] [Verification] Run unit test suite: `./gradlew testMobileGoogleDebugUnitTest --tests "za.kilowatch.ultimatefilemanager.viewer.RepeatModeTest"` and `--tests "za.kilowatch.ultimatefilemanager.viewer.QueueManagerTest"`.
- [x] [T011] [Verification] Compile debug builds: `./gradlew compileMobileGoogleDebugKotlin` and `./gradlew assembleMobileGoogleDebug`.
- [x] [T012] [Verification] Run `python -u translate_strings.py verify` to sync translations.
