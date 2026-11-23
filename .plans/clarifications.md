# Clarifications: First-Run Onboarding Setup Flow with Dedicated Per-Setting Activities

## Status
Confirmed — Ready for /plan

## Questions and Answers

### Architecture & Activity Structure
**Q1.** How should the setup steps be structured architecturally?
**A:** Each individual preview setting has its own dedicated Activity (10 standalone Activities in total). Each Activity follows the strict project Activity pattern (`attachBaseContext` with `LocaleManagerWrapper`, `EdgeToEdge.enable`, and paired mobile + TV layouts).

The 10-Activity sequence:
1. `OnboardingViewDensityActivity`
2. `OnboardingFontSizeActivity`
3. `OnboardingScrollingTextActivity`
4. `OnboardingHoldDurationActivity`
5. `OnboardingLeftHandedFabActivity` (Mobile only; skipped on TV)
6. `OnboardingEdgeDrawerActivity` (Mobile only; skipped on TV)
7. `OnboardingRecycleBinActivity`
8. `OnboardingHiddenFilesActivity`
9. `OnboardingAppLockActivity` (Skipped if device lacks secure lock hardware)
10. `OnboardingDiagnosticsActivity`

### Skip Button Contextual Labeling & Progressive Behavior
**Q2.** What should the Skip button be named and how should it behave?
**A:**
- **Screen 1 (`OnboardingViewDensityActivity`):** Labeled **"Skip & Use Defaults"** (since nothing has been configured yet; applies all defaults and opens the app).
- **Screens 2 through 10:** Labeled **"Skip Remaining"** (preserves and commits all choices made on previous screens, applies recommended defaults to all remaining skipped screens, marks onboarding complete, and opens `StorageBrowserActivity`).

### Option 10 Default Value
**Q3.** Is Option 10 (Crash & ANR Reporting) on by default?
**A:** Yes! In the codebase (`CrashReportManager.isEnabled()`), Crash & ANR Reporting defaults to `true` (Enabled/ON). In `OnboardingDiagnosticsActivity`, the toggle and preview open in the active state (*Enabled by default — 100% anonymous technical crash reports, zero personal data*).

### Preview Initial State
**Q4.** How should the live preview render when each Activity opens?
**A:** Always pre-rendered and actively displaying the **recommended default selection** immediately upon opening (or currently saved preferences if running in Reconfigure Mode).

### TV Layout & Screen Arrangement
**Q5.** How should the visual preview be arranged on Android TV displays?
**A:** Side-by-side split screen (Left: Options & Controls, Right: Large Live Visual Preview) to make full, ergonomic use of 16:9 TV landscape screens.

### Re-Entry Mode from Settings
**Q6.** When re-running the wizard from `SettingsActivity`, how should current preferences and exit navigation behave?
**A:** Pre-populate live previews and options with the user's currently saved preferences (rather than resetting to defaults), and return directly back to `SettingsActivity` upon completion or skip.

### Activity Transition Animations
**Q7.** What transition animation should be used between the 10 onboarding Activities?
**A:** Polished horizontal slide animations (slide in from right on Next, slide back from left on Back) to reinforce a fluid, premium wizard flow.

### Security / App Lock Hardware Check
**Q8.** If a user enables App Lock during onboarding, how should PIN creation be handled?
**A:** Check device keyguard/security support upfront; if unsupported, skip `OnboardingAppLockActivity`. If supported and enabled, immediately present the PIN creation dialog on-screen, reverting the toggle to Off if cancelled.

## Confirmed — Ready for /plan
All questions have been clarified and approved. Proceed to `/plan` to design the technical architecture and implementation strategy.
