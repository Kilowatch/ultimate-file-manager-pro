# Task List: Settings Hierarchy, Granular Groupings & Logical Sequencing

## Setup & String Resources
- [x] [T001] [P] Define new translatable section header strings in `app/src/main/res/values/strings.xml`

## Mobile Settings Layout (`activity_settings.xml`)
- [x] [T002] [P] Restructure General, Appearance, and Navigation/Controls sections in `app/src/main/res/layout/activity_settings.xml`:
  - Sequence `layoutSectionGeneral`
  - Split Appearance into `layoutSectionThemeDisplay` and `layoutSectionInterfaceAnimations`
  - Sequence `layoutSectionQuickAccess`, `layoutSectionControls`, and `layoutSectionTwinWindow`
- [x] [T003] [P] Restructure File Management & Storage sections in `app/src/main/res/layout/activity_settings.xml`:
  - Split 14-item File Management into `layoutSectionPermissionsStorage`, `layoutSectionBrowsingDisplay`, `layoutSectionFileSafety`, and `layoutSectionOrganizationTools`
  - Sequence `layoutSectionSafTransfers`
- [x] [T004] [P] Restructure Media Player and Network sections in `app/src/main/res/layout/activity_settings.xml`:
  - Sequence `layoutSectionThumbnails`
  - Split Media Player into `layoutSectionMediaPlayback` and `layoutSectionPlayerControls`
  - Split Network & Performance into `layoutSectionTransfersDownloads`, `layoutSectionNetworkCaching`, and `layoutSectionServersIndexing`
- [x] [T005] [x] Restructure Security, Accessibility, Backup & System sections in `app/src/main/res/layout/activity_settings.xml`:
  - Sequence `layoutSectionSecurity`
  - Sequence `layoutSectionAccessibility`, `layoutSectionBackup`, and `layoutSectionPrivacyDiagnostics`

## Android TV Settings Layout (`activity_settings_tv.xml`)
- [x] [T006] [P] Restructure General, Appearance, and Controls/Twin Window sections in `app/src/main/res/layout/activity_settings_tv.xml`:
  - Sequence `layoutSectionGeneral`
  - Split Appearance into `layoutSectionThemeDisplay` and `layoutSectionInterfaceAnimations`
  - Sequence `layoutSectionControls` and `layoutSectionTwinWindow`
- [x] [T007] [P] Restructure File Management & Storage sections in `app/src/main/res/layout/activity_settings_tv.xml`:
  - Split TV File Management into `layoutSectionBrowsingDisplay`, `layoutSectionFileSafety`, and `layoutSectionOrganizationTools`
  - Sequence `layoutSectionSafTransfers`
- [x] [T008] [P] Restructure Media Player and Network sections in `app/src/main/res/layout/activity_settings_tv.xml`:
  - Sequence `layoutSectionThumbnails`
  - Split Media Player into `layoutSectionMediaPlayback` and `layoutSectionPlayerControls`
  - Split Network & Performance into `layoutSectionTransfersDownloads`, `layoutSectionNetworkCaching`, and `layoutSectionServersIndexing`
- [x] [T009] [x] Restructure Accessibility, Backup & Privacy sections in `app/src/main/res/layout/activity_settings_tv.xml`:
  - Sequence `layoutSectionAccessibility`, `layoutSectionBackup`, and `layoutSectionPrivacyDiagnostics`

## Controller & View Bindings
- [x] [T010] Verify `SettingsActivity.kt` to ensure `layoutSectionSecurity` and `layoutSectionQuickAccess` references, TV card focus bindings, and recursive search filter traversal match all new section containers.

## Polish & Testing
- [x] [T011] [P] Build debug APK using `./gradlew assembleMobileGoogleDebug` to verify clean compilation with zero layout, XML, or resource binding errors.
- [x] [T012] Verify in-page settings search functionality and empty states to ensure section headers dynamically show/hide accurately according to query matches.
