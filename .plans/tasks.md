# Task List: Android TV Onboarding Screens Redesign & D-Pad Navigation Fix

## Setup
- [x] [T001] [P] Add new localized string resources in `app/src/main/res/values/strings.xml` for previously hardcoded TV onboarding section headers and preview labels (leaving existing translated keys unmodified).

## Foundation
- [x] [T002] [P] Update `app/src/main/java/za/kilowatch/ultimatefilemanager/onboarding/BaseOnboardingStepActivity.kt` — add shared TV focus helpers (`setupTvCardFocus`, `setupTvFooterAndHeaderFocus`, and `requestInitialTvFocus`) using `TvFocusHelper.colors(this)` and `TvFocusHelper.applyMarker` so Back, Skip, Next/Start Browsing, and option cards have unmistakable focus states distinct from selected states.
- [x] [T003] [P] Redesign `app/src/main/res/layout/layout_onboarding_header_tv.xml` and `app/src/main/res/layout/layout_onboarding_footer_tv.xml` — apply `@drawable/selector_tv_icon_btn` to `btnOnboardingBack`, refine header/footer spacing within `@dimen/tv_safe_horizontal`, and ensure `btnOnboardingSkip` and `btnOnboardingNext` have clear TV focus styling.

## Feature Implementation
- [x] [T004] [P] Redesign `app/src/main/res/layout/activity_onboarding_scrolling_text_tv.xml` and update `OnboardingScrollingTextActivity.kt` — move `layoutMarqueeSubOptions` outside `cardOptionMarquee` as independent focusable chips, wire `setupTvCardFocus` + chip focus styling + `requestInitialTvFocus`, balance 2-column proportions, and collapse `layoutMarqueeSubOptions` cleanly when non-Marquee modes are selected.
- [x] [T005] Redesign `app/src/main/res/layout/activity_onboarding_view_density_tv.xml` and update `OnboardingViewDensityActivity.kt` — balance 2-column layout fit, wire `setupTvCardFocus` on View Mode / Spacing / Column cards, replace hardcoded preview labels with string resources, and set initial TV focus on the active mode card.
- [x] [T006] Redesign `app/src/main/res/layout/activity_onboarding_font_size_tv.xml` and update `OnboardingFontSizeActivity.kt` — vertically center the 3 scale cards and right-hand preview card for clean TV fit, wire `setupTvCardFocus` and `requestInitialTvFocus`, and localize section headers.
- [x] [T007] Redesign `app/src/main/res/layout/activity_onboarding_hold_duration_tv.xml` and update `OnboardingHoldDurationActivity.kt` — vertically center the 3 duration cards and interactive test preview, wire `setupTvCardFocus` and `requestInitialTvFocus`, and localize section/preview labels.
- [x] [T008] Redesign `app/src/main/res/layout/activity_onboarding_recycle_bin_tv.xml` and update `OnboardingRecycleBinActivity.kt` — vertically center the 2 policy cards and safety preview, wire `setupTvCardFocus` and `requestInitialTvFocus`, and localize section/preview labels.
- [x] [T009] Redesign `app/src/main/res/layout/activity_onboarding_hidden_files_tv.xml` and update `OnboardingHiddenFilesActivity.kt` — vertically center the 2 visibility cards and file-list preview, wire `setupTvCardFocus` and `requestInitialTvFocus`, and localize section/preview labels.
- [x] [T010] Redesign `app/src/main/res/layout/activity_onboarding_app_lock_tv.xml` and update `OnboardingAppLockActivity.kt` — balance the authentication policy cards and live preview card, wire `setupTvCardFocus` and `requestInitialTvFocus`, and localize section/preview labels.
- [x] [T011] Redesign `app/src/main/res/layout/activity_onboarding_diagnostics_tv.xml` and update `OnboardingDiagnosticsActivity.kt` — vertically center the 2 diagnostic cards and privacy transparency preview, wire `setupTvCardFocus` and `requestInitialTvFocus`, and localize section/preview labels.

## Polish & Testing
- [x] [T012] [P] Build and run unit tests (`.\gradlew.bat assembleMobileGoogleDebug testMobileGoogleDebugUnitTest`) to verify zero compilation, resource, or regression errors.
- [x] [T013] Run `python translate_strings.py verify` (if applicable for new string keys) and verify all 8 TV screens against `.plans/spec.md` success criteria.
