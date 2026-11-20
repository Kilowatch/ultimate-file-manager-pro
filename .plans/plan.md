# Implementation Plan: Tip Jar Progress Popup Visual Redesign (Mobile)

## Goals
Transform the visual appearance of the mobile Tip Jar progress popup card (`cardTipJarProgress`) in `StorageBrowserActivity` to match the exact aesthetic presented in `tipjar.jpg`:
- Electric cyan glowing container stroke (`#5900B0FF` / `#8038BDF8`) replacing deep purple.
- Soft ambient cyan gradient aura behind the jar icon and along the top banner.
- High-contrast electric cyan month text span (`Tip jar progress • Nov`) and percentage indicator (`8%`).
- Sleek cyan/blue neon gradient progress fill (`#FF00E5FF` to `#FF00B0FF`).
- Frosted glass capsule "Fuel it!" button with subtle cyan stroke and translucent dark fill.
- Increment app version to `2.2.8` and `versionCode` to `318` in `app/build.gradle.kts`.
- Update `CHANGELOG.md` with the user-approved entry.

## Architecture Overview
The redesign is purely visual and presentation-layer focused, preserving all existing runtime business logic (network fetching, cache persistence in `LoyaltyPrefs`, dismiss animations, auto-dismiss timers, and Supporter Loyalty intent routing):
1. **Color Tokens**:
   Update `tile_tip_jar_accent` and `tile_tip_jar_stroke` in both `res/values/colors.xml` and `res/values-night/colors.xml` from purple to electric cyan/blue neon tokens (`#FF00B0FF` / `#FF38BDF8`).
2. **Gradient Banner**:
   Update `bg_tip_jar_card_banner.xml` from deep violet/purple to a dark translucent electric blue/cyan gradient aura (`#3300B0FF` -> `#121E35` -> `#00000000`).
3. **Jar Icon Ambient Aura**:
   Add a soft circular cyan aura drawable `bg_jar_glow_aura.xml` positioned behind `ic_tip_jar_glow` to reproduce the luminous glow from `tipjar.jpg`.
4. **Progress Bar Fill**:
   Update `bg_tip_jar_progress.xml` to replace the purple gradient end-color (`#FF8B2FC9`) with electric bright blue (`#FF00B0FF`).
5. **Action Button Styling**:
   Update `btnFuelIt` in `activity_storage_browser.xml` from solid purple to a frosted glass capsule button:
   - Semi-transparent dark glass background (`#2638BDF8` or `@drawable/bg_btn_fuel_it`).
   - 1dp electric cyan border stroke (`#6638BDF8`).
   - Rounded pill corners (18dp).
   - White text and white coffee icon (`ic_coffee`).
6. **Version & Documentation**:
   Update `appVersionName` to `2.2.8` and `appVersionCode` to `318` in `app/build.gradle.kts`.
   Prepend the user-approved `2.2.8` section to `CHANGELOG.md`.

## Files to Create
### `app/src/main/res/drawable/bg_jar_glow_aura.xml` — Soft ambient aura behind glowing jar icon
- **What:** Radial/shape gradient providing the subtle luminous cyan halo surrounding the tip jar icon.
- **Key details:** Center color `#4000B0FF`, outer color `#00000000`.

### `app/src/main/res/drawable/bg_btn_fuel_it.xml` — Frosted glass pill background for "Fuel it!"
- **What:** Capsule shape drawable with semi-transparent dark blue/cyan background, subtle cyan stroke, and ripple.

## Files to Modify
### `app/build.gradle.kts` — Version increment
- **What:** Bump `appVersionName` from `"2.2.7"` to `"2.2.8"`, bump `appVersionCode` from `316` to `318`.

### `CHANGELOG.md` — Release notes
- **What:** Add `## [2.2.8] — 2026-11-20` entry with approved description.

### `app/src/main/res/values/colors.xml` & `app/src/main/res/values-night/colors.xml` — Color tokens
- **What:** Update `tile_tip_jar_accent`, `tile_tip_jar_stroke`, and `tile_tip_jar_icon_bg` to electric cyan/blue tokens.

### `app/src/main/res/drawable/bg_tip_jar_card_banner.xml` — Card banner gradient
- **What:** Update gradient from purple to dark cyan/blue translucent glass aura.

### `app/src/main/res/drawable/bg_tip_jar_progress.xml` — Progress bar fill
- **What:** Update progress gradient from cyan-purple to cyan-electric blue (`#FF00E5FF` -> `#FF00B0FF`).

### `app/src/main/res/layout/activity_storage_browser.xml` — Tip jar card layout
- **What:** Wrap jar icon with ambient glow aura; configure `btnFuelIt` with frosted glass styling and subtle cyan stroke.

## Data Model
No changes to database models, preferences keys, or data models. `LoyaltyPrefs` continues to handle caching and preferences.

## Dependencies
None. All components use standard Android SDK and Material Components already in the project.

## Test Plan
- Compile with `./gradlew assembleMobileGoogleDebug` to guarantee clean zero-warning build.
- Verify visual layout in preview/rendering.
- Ensure all dismiss and click actions retain original behavior.

## Risks & Mitigations
- **Risk:** Contrast issues on light backgrounds.
  - **Mitigation:** The card sits inside UFM's dark theme container (`@color/mobile_glass_card`); high-contrast cyan text (`#FF38BDF8` / `#FF00E5FF`) exceeds WCAG AA contrast against dark backgrounds.
- **Risk:** Button touch target shrinking.
  - **Mitigation:** Retain 36dp height and 18dp corner radius with adequate horizontal padding ensuring standard touch responsiveness.
