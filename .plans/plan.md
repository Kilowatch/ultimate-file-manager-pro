# Implementation Plan: Android TV Onboarding Screens Redesign & D-Pad Navigation Fix

## Goals
Deliver all requirements in [spec.md](file:///F:/Share/UFM/.plans/spec.md):
1. Redesign all 8 Android TV onboarding layouts (`*_tv.xml`) plus shared TV header/footer (`layout_onboarding_header_tv.xml`, `layout_onboarding_footer_tv.xml`) so every screen fits cleanly within TV overscan safe margins on 720p and 1080p displays with balanced two-column proportions and zero dead space or clipped content.
2. Add unmistakable, consistent D-pad focus states across all interactive TV controls (Next/Start Browsing, Skip, Back, option cards, and sub-option chips) using the app's existing `TvFocusHelper` and TV focus selectors, clearly distinct from the active/selected state.
3. Fix Step 3 (`OnboardingScrollingTextActivity` + `activity_onboarding_scrolling_text_tv.xml`) so Marquee sub-options (Scroll Speed, Loop Mode, Scroll Scope) sit outside `cardOptionMarquee` as independent focusable chips, visible/focusable only when Marquee is selected.
4. Set initial D-pad focus on the currently selected (or first) option card on every TV onboarding screen and ensure predictable D-pad traversal between options, sub-options, Skip, Next, and Back.
5. Replace hardcoded English labels in the 8 TV onboarding layouts with string resources in `values/strings.xml`.

## Architecture Overview
- **Shared TV Focus & Navigation Coordinator (`BaseOnboardingStepActivity.kt`)**:
  - Add a shared TV focus helper method `setupTvCardFocus(card, isSelectedProvider)` powered by `TvFocusHelper.colors(this)` and `TvFocusHelper.applyMarker(card, hasFocus)`. On focus, cards get the unified TV focus stroke/fill/marker and subtle `1.02f` scale; on blur, they restore their selected vs. unselected stroke (`getActiveAccentColor()` vs. `getInactiveStrokeColor()`).
  - Add `setupTvFooterAndHeaderFocus()` in `setupCommonViews()` (when `isTv == true`) so `btnOnboardingNext`, `btnOnboardingSkip`, and `btnOnboardingBack` receive unmistakable focus visuals (bright focus fill/ring, text contrast, and subtle scale-up) using `TvFocusHelper` / `selector_tv_icon_btn` / `selector_tv_button`.
  - Add `requestInitialTvFocus(defaultView: View?)` helper called by each subclass after initial state is bound, ensuring focus lands on the currently selected option card on TV.
- **Step 3 Marquee Sub-Options Restructure (`activity_onboarding_scrolling_text_tv.xml` + `OnboardingScrollingTextActivity.kt`)**:
  - Move `layoutMarqueeSubOptions` out of `cardOptionMarquee` into the parent `LinearLayout` immediately below `cardOptionMarquee`.
  - Style each `Chip` (`chipSpeedSlow/Normal/Fast`, `chipLoopOneShot/Continuous`, `chipScopeAll/Focused`) with `android:focusable="true"`, TV-friendly touch/focus sizing, and explicit `TvFocusHelper`-backed focus styling so D-pad focus is unmistakable on each chip.
  - Wire explicit `nextFocusUp` / `nextFocusDown` / `nextFocusRight` relationships in `OnboardingScrollingTextActivity` / XML so D-pad moves smoothly: `cardOptionMarquee` → `chipSpeed*` → `chipLoop*` → `chipScope*` → `cardOption2Lines` → ... → `btnOnboardingNext` / `btnOnboardingSkip`.
  - When a non-Marquee mode is selected, collapse `layoutMarqueeSubOptions` (`View.GONE`) so D-pad skips it cleanly without stranding focus.
- **Per-Screen TV Layout Redesign (8 `*_tv.xml` + Header/Footer)**:
  - Standardize `layout_onboarding_header_tv.xml` (step badge pill, title, subtitle, `selector_tv_icon_btn` Back button) and `layout_onboarding_footer_tv.xml` (glass divider/bar, high-contrast focusable Skip and Next/Start Browsing buttons).
  - Balance the 2-column split (`48%` options / `52%` preview or `50/50` with `16dp–24dp` gutter) per screen:
    - **Binary / 2–3 option screens** (`font_size`, `hold_duration`, `recycle_bin`, `hidden_files`, `diagnostics`, `app_lock`): Center the option stack vertically in the left column and match the right preview card height/alignment so there is no awkward empty void.
    - **Dense screens** (`view_density`, `scrolling_text`): Use compact card padding, clear section headers, and smooth scroll-into-view on D-pad focus.

## Files to Create
None. All changes reuse existing activities, layouts, `TvFocusHelper`, and drawable selectors.

## Files to Modify
### `app/src/main/java/za/kilowatch/ultimatefilemanager/onboarding/BaseOnboardingStepActivity.kt`
- **What:** Add shared TV focus styling for footer/header buttons (`btnOnboardingNext`, `btnOnboardingSkip`, `btnOnboardingBack`), shared `setupTvCardFocus(...)` helper using `TvFocusHelper`, and `requestInitialTvFocus(...)`.
- **Key details:** Guard all TV focus/scale treatments behind `if (isTv)`. Ensure `btnOnboardingNext` and `btnOnboardingSkip` show high-contrast `ufmFocusFill` / `ufmFocusFillText` / stroke glow on focus so focused state is unmistakable.

### `app/src/main/java/za/kilowatch/ultimatefilemanager/onboarding/OnboardingScrollingTextActivity.kt`
- **What:** Wire `setupTvCardFocus` for the 6 mode cards, add TV focus styling for the 8 Marquee sub-option chips (`cgSpeed`, `cgLoop`, `cgScope`), set initial focus to the active mode card on TV, and ensure collapsing `layoutMarqueeSubOptions` never strands focus.
- **Key details:** When `layoutMarqueeSubOptions` is hidden, update `nextFocusDown` on `cardOptionMarquee` (or let Android's focus finder skip `GONE` views) and ensure `cardOptionMarquee` -> `chipSpeedNormal` -> `chipLoopOneShot` -> `chipScopeAll` -> `cardOption2Lines` works seamlessly.

### `app/src/main/java/za/kilowatch/ultimatefilemanager/onboarding/OnboardingViewDensityActivity.kt`
### `app/src/main/java/za/kilowatch/ultimatefilemanager/onboarding/OnboardingFontSizeActivity.kt`
### `app/src/main/java/za/kilowatch/ultimatefilemanager/onboarding/OnboardingHoldDurationActivity.kt`
### `app/src/main/java/za/kilowatch/ultimatefilemanager/onboarding/OnboardingRecycleBinActivity.kt`
### `app/src/main/java/za/kilowatch/ultimatefilemanager/onboarding/OnboardingHiddenFilesActivity.kt`
### `app/src/main/java/za/kilowatch/ultimatefilemanager/onboarding/OnboardingAppLockActivity.kt`
### `app/src/main/java/za/kilowatch/ultimatefilemanager/onboarding/OnboardingDiagnosticsActivity.kt`
- **What:** Apply `setupTvCardFocus` to each screen's interactive `MaterialCardView` options and call `requestInitialTvFocus` on the currently selected option card when `isTv` is true.
- **Key details:** Keep mobile behavior 100% unchanged. In `updateUI()`, ensure focused cards retain their focus stroke/marker rather than being overwritten by `updateUI()`.

### `app/src/main/res/layout/layout_onboarding_header_tv.xml`
### `app/src/main/res/layout/layout_onboarding_footer_tv.xml`
- **What:** Redesign TV onboarding header and footer for 10-foot polish and unmistakable focus visibility (`selector_tv_icon_btn` on Back, clear focusable button styling on Skip and Next/Start Browsing, clean top/bottom safe padding).

### `app/src/main/res/layout/activity_onboarding_view_density_tv.xml`
### `app/src/main/res/layout/activity_onboarding_font_size_tv.xml`
### `app/src/main/res/layout/activity_onboarding_scrolling_text_tv.xml`
### `app/src/main/res/layout/activity_onboarding_hold_duration_tv.xml`
### `app/src/main/res/layout/activity_onboarding_recycle_bin_tv.xml`
### `app/src/main/res/layout/activity_onboarding_hidden_files_tv.xml`
### `app/src/main/res/layout/activity_onboarding_app_lock_tv.xml`
### `app/src/main/res/layout/activity_onboarding_diagnostics_tv.xml`
- **What:** Redesign each of the 8 TV layouts for balanced 2-column proportions, vertical centering on sparse screens, compact card spacing on dense screens, `layoutMarqueeSubOptions` moved outside `cardOptionMarquee` in `scrolling_text_tv.xml`, and localized string references replacing hardcoded English text.

### `app/src/main/res/values/strings.xml`
- **What:** Add new string resources for any currently hardcoded section headers/preview labels in the 8 `*_tv.xml` layouts (without modifying existing keys unless necessary; if any existing key is modified, delete it from `values-*/strings.xml` per project rules).

## Data Model
No preference keys, storage schemas, or data models are changed. All existing preference managers (`MainMenuViewModeManager`, `FontSizeHelper`, `ScrollingTextPreferenceManager`, `LongPressDurationManager`, `RecycleBinSettingsManager`, `HiddenFilesManager`, `AppSecurityManager`, `CrashReportManager`) remain unchanged.

## Dependencies
- Existing `TvFocusHelper` (`za.kilowatch.ultimatefilemanager.util.TvFocusHelper`) and existing TV focus theme attributes (`ufmFocusFill`, `ufmFocusFillText`, `ufmFocusGlow`, `ufmFocusStrokeColor`, `ufmFocusStrokeWidth`, `ufmFocusAccent`).
- Existing drawable selectors (`selector_tv_icon_btn.xml`, `selector_tv_button.xml`, `selector_tv_card.xml`).
- No new third-party libraries.

## Test Plan
- **Build verification**: Run `./gradlew assembleMobileGoogleDebug` after all edits to confirm zero compilation/resource/layout errors.
- **Automated / Unit checks**: Run existing unit tests (`./gradlew testMobileGoogleDebugUnitTest`) to ensure no regressions.
- **Manual TV / Emulator Verification** (covering all FRs):
  1. Launch onboarding on an Android TV emulator/device (720p & 1080p) and step through all 8 screens (1 → 8) using only D-pad Up/Down/Left/Right/Center/Back.
  2. Verify on every screen that initial focus lands on the selected option, and that moving focus to **Next / Start Browsing**, **Skip**, and **Back** shows an unmistakable focus state.
  3. Verify on Step 3 (Scrolling Text) that selecting **Marquee Auto-Scroll** reveals Speed, Loop, and Scope chips below the card, and D-pad can focus and toggle every individual chip with live preview updates; verify switching to **2 Lines** hides the sub-options cleanly.
  4. Verify Reconfigure Mode from Settings pre-selects and focuses saved preferences and returns cleanly to Settings.
  5. Verify Mobile layout (`assembleMobileGoogleDebug` on phone) is visually and behaviorally unchanged.

## Risks & Mitigations
- **`updateUI()` overwriting focus border**: Calling `card.strokeColor = ...` inside `updateUI()` when a card is currently focused could erase its focus border. → *Mitigation*: In `BaseOnboardingStepActivity.setupTvCardFocus` / `applyCardVisualState`, check `card.isFocused` before applying unselected/selected resting stroke so focused state always wins.
- **ScrollView swallowing focus or clipping scale**: Scaling focused cards (`1.02f`) inside a `LinearLayout`/`ScrollView` can clip at container edges. → *Mitigation*: Set `android:clipChildren="false"` and `android:clipToPadding="false"` on card containers and give horizontal/vertical padding (`4dp–8dp`) inside `ScrollView`.
- **String translation rule**: Modifying existing English strings requires deleting those keys from `values-*/strings.xml`. → *Mitigation*: Only *add* new string keys for previously hardcoded English text in `*_tv.xml` layouts; do not modify existing translated keys.
