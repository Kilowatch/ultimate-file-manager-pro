# Task List: High-Quality Badge & Custom App Icon Asset Pipeline

## Setup
- [x] [T001] [P] Validate input files (`images/5.jpg` through `images/1000.jpg`) and initialize destination directories under `images/` (`badges_transparent/`, `app_icons_square/`, `app_icons_adaptive/`, `tv_banners/`).
- [x] [T002] [P] Create core pipeline module in `scripts/process_badges.py` with neural background removal (`rembg`), alpha thresholding/defringing, bounding-box detection, and k-means radial gradient palette generator.

## Asset Generation
- [x] [T003] [P] Generate **Set 1: In-App Transparent Badges** (`images/badges_transparent/`) — process all 10 tiers at 2048×2048 master, 512×512, and 256×256 at uniform 90% optical scale in both PNG and WebP.
- [x] [T004] [P] Generate **Set 2: Square App Icons** (`images/app_icons_square/`) — process all 10 tiers with matching radial gradient background and strict 16.67% mobile launcher safe-zone padding (`ic_launcher_foreground` standard) at 2048×2048 master and 512×512 Play Store standard in both PNG and WebP.
- [x] [T005] Generate **Set 2 (B): Adaptive Icon Layers** (`images/app_icons_adaptive/`) — generate separate foregrounds (66.67% safe zone on transparent square) and matching background layers at 2048×2048 master and 432×432 (`xxxhdpi`) in both PNG and WebP.
- [x] [T006] Generate **Set 3: Android TV Leanback Banners** (`images/tv_banners/`) — process all 10 tiers in 16:9 landscape format at 1920×1080 Full HD master, 1280×720 (`xxxhdpi`), and 320×180 Leanback standard in both PNG and WebP.

## Verification & Polish
- [x] [T007] [P] Implement automated verification script `scripts/verify_badges.py` validating file counts, exact dimensions, alpha purity, and safe-zone boundary compliance.
- [x] [T008] Execute verification script, review output artifacts against circular launcher mask, and confirm zero edge clipping or backdrop halos.

## Phase 2: "Fuel the Developer" Badges Section & Dynamic Launcher Icon (Google Mobile Only)
- [x] [T009] [P] Generate Android resources for Google Mobile: `drawable-nodpi/` (app icon square previews & badge cutouts), `mipmap-anydpi-v26/` (adaptive icon XMLs with cutout foreground & gradient background), and density mipmap buckets (`mdpi` through `xxxhdpi`) via `scripts/install_badge_resources.py`.
- [x] [T010] [P] Add 10 `<activity-alias>` declarations in `app/src/google/AndroidManifest.xml` targeting `LanguageWelcomeActivity` with matching adaptive icons and `android:enabled="false"`.
- [x] [T011] [P] Add string resources for badge titles and section headers in `app/src/main/res/values/strings.xml`.
- [x] [T012] [P] Create item layout `app/src/main/res/layout/item_supporter_badge.xml` and add `cardBadgesSection` to `app/src/main/res/layout/activity_supporter_loyalty.xml` (hidden by default).
- [x] [T013] [P] Implement `BadgeIconManager.kt` handling badge metadata, SharedPreferences persistence, and `PackageManager.setComponentEnabledSetting(..., DONT_KILL_APP)` switching.
- [x] [T014] [P] Connect Badges section in `SupporterLoyaltyActivity.kt` (only visible and populated when `!isTv` on Google Mobile) listing all 10 badges vertically from 5 to 1,000 points.
- [x] [T015] Verify compilation with Gradle on `mobileGoogleDebug` variant.

## Phase 3: Progressive Tipping Unlocks & Confirmation Dialog
- [x] [T016] [P] Implement cumulative points tracking in `BadgeIconManager.kt` using the $1 = 10 Points conversion (as requested by user).
- [x] [T017] [P] Update `item_supporter_badge.xml` with lock overlay and progress text for locked badge tiers.
- [x] [T018] [P] Implement the Confirmation Dialog modal displaying the updated icon preview and launcher refresh notice with an "OK" button (following UFMStandard).
- [x] [T019] Connect tipping purchase completions in `SupporterLoyaltyActivity.kt` to credit cumulative points and unlock new badge tiers.
- [x] [T020] Compile and verify with `./gradlew compileMobileGoogleDebugKotlin`.
