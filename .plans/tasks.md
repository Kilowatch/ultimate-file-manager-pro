# Task List: Tip Jar Progress Popup Visual Redesign (Mobile)

## Setup
- [x] [T001] [P] Bump `appVersionName` to `"2.2.8"` and `appVersionCode` to `318` in `app/build.gradle.kts`.
- [x] [T002] [P] Update `CHANGELOG.md` with the user-approved `[2.2.8] — 2026-11-20` entry.

## Foundation
- [x] [T003] Update color tokens (`tile_tip_jar_accent`, `tile_tip_jar_stroke`, `tile_tip_jar_icon_bg`) in `app/src/main/res/values/colors.xml` and `app/src/main/res/values-night/colors.xml` to electric cyan/blue.
- [x] [T004] Update card top banner gradient in `app/src/main/res/drawable/bg_tip_jar_card_banner.xml` to dark cyan/blue translucent aura.
- [x] [T005] Update progress bar gradient in `app/src/main/res/drawable/bg_tip_jar_progress.xml` to pure cyan-blue neon gradient.

## Feature Implementation
- [x] [T006] Create ambient aura drawable `app/src/main/res/drawable/bg_jar_glow_aura.xml` for glowing halo behind the tip jar icon.
- [x] [T007] Create frosted glass button background drawable `app/src/main/res/drawable/bg_btn_fuel_it.xml` with subtle cyan stroke and translucent dark glass fill.
- [x] [T008] Update `cardTipJarProgress` layout in `app/src/main/res/layout/activity_storage_browser.xml` with ambient jar glow halo and frosted glass "Fuel it!" button styling.

## Polish & Testing
- [x] [T009] [P] Verify compilation via `./gradlew assembleMobileGoogleDebug` and ensure zero errors or warnings.
