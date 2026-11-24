# Specification: Milestone Badges & Dynamic App Icon Switcher

## Overview

In Ultimate File Manager Pro (UFM), the "Fuel the Developer" Tip Jar screen serves as the community support hub. This specification establishes a dedicated **Badges** section within "Fuel the Developer" exclusively for the **Google Mobile** variant.

Users can browse milestone achievement badges (from 5 to 1,000 total points), view prominent, high-resolution badge artwork, and select any badge to immediately update the mobile home screen app launcher icon. The architecture is engineered for seamless runtime icon switching via Android `activity-alias` components and is modularly prepared for future expansions (such as progressive unlock tiers, point milestones, and additional asset packs).

---

## Target Audience & Platform Boundary

### Platform Constraints (Strict)
- **Included**: Google Play Mobile builds (`mobileGoogle` flavor, phone and tablet form factors, where `DeviceUtils.isTvDevice(this) == false`).
- **Excluded**:
  - **Android TV / Leanback**: The Badges section is completely hidden (`View.GONE`) on TV devices.
  - **Amazon Appstore (`amazon` flavor)**: Excluded; no Badges section or aliases present.
  - **FOSS (`foss` flavor)**: Excluded; no Badges section or aliases present.

---

## User Stories

- **As a Google Mobile user**, I want to browse all 10 milestone badges in a dedicated "Badges" section inside "Fuel the Developer" with large, high-detail previews so that I can appreciate the artwork.
- **As a supporter**, I want to tap any badge to immediately set it as my mobile app icon on my phone's home screen.
- **As a user**, I want the badges listed vertically in ascending order (smallest 5 points first, up to 1,000 points last) with clear point values and tier titles.
- **As a user**, I want an option to restore the default UFM app icon at any time.
- **As an Android user**, I want the updated app icon to conform to modern Adaptive Icon standards (emblem cutout on matching color radial gradient background) so it respects my launcher's mask shape (circle, squircle, rounded rectangle) without edge clipping.
- **As a developer**, I want the badge data model and manager to be cleanly decoupled so that future expansions (point unlocks, seasonal badges, custom themes) can be added with zero architectural friction.

---

## Functional Requirements

### FR-01: Dedicated "Badges" Section Header
- **Placement**: Directly below the coffee tipping products in `activity_supporter_loyalty.xml` (mobile layout).
- **Header Card**:
  - Title: **Badges** (`@string/badge_section_title`).
  - Explanatory Subtitle: *"Unlock and set these milestone badges as your custom app icon on your home screen. Celebrate your achievements with prestigious themes."* (`@string/badge_section_subtitle`).
  - Active Icon Showcase: A circular highlight ring displaying the currently active badge cutout thumbnail.
- **Visibility**: Defaults to `View.GONE` in XML; set to `View.VISIBLE` programmatically **only** when running on Google Mobile (`!isTv && !isAmazonDevice`).

### FR-02: Prominent "Nice and Big" Badge Item Cards
- Each badge in the vertical selection list is presented in an elevated, rounded card (`MaterialCardView`):
  - **Large Square Preview**: Sized at **72×72 dp** (with rounded corners) displaying the full radial gradient badge from `app_icons_square` (`app_icon_points_{pts}.webp`), ensuring numerals, laurel leaves, ribbons, and crystal facets are crisp and legible.
  - **Cutout Accent**: Displays the transparent emblem cutout thumbnail (`badge_points_{pts}.webp`) beside the points label.
  - **Tier Title**: Bold title (e.g., *"10 Points Bronze Supporter"*).
  - **Points Metric**: Clear points display (e.g., *"10 Points"*).
  - **Selection Indicator**: Prominent checkmark circle (`ic_check_circle`) and an **"Active"** badge chip shown only on the currently active icon.

### FR-03: Vertical Ordering & Tier Mapping
Badges are listed vertically in strict ascending order:
1. **Default App Icon**: Standard UFM launcher icon (reset option)
2. **5 Points**: Supporter (`app_icon_points_5`)
3. **10 Points**: Bronze Supporter (`app_icon_points_10`)
4. **20 Points**: Silver Supporter (`app_icon_points_20`)
5. **50 Points**: Gold Supporter (`app_icon_points_50`)
6. **100 Points**: Ultimate Supporter (`app_icon_points_100`)
7. **200 Points**: Platinum Supporter (`app_icon_points_200`)
8. **300 Points**: Diamond Supporter (`app_icon_points_300`)
9. **500 Points**: Royal Supporter (`app_icon_points_500`)
10. **750 Points**: Legendary Supporter (`app_icon_points_750`)
11. **1,000 Points**: Supreme Supporter (`app_icon_points_1000`)

### FR-04: Dynamic Launcher Icon Switching
- **Activity Alias Architecture**: 10 `<activity-alias>` elements declared in `app/src/google/AndroidManifest.xml` targeting `.onboarding.LanguageWelcomeActivity`.
- **Runtime Component State**:
  - Managed via `BadgeIconManager.setSelectedBadge(context, points)`.
  - When a badge is selected:
    1. The target alias `.BadgeIcon{points}` is enabled (`COMPONENT_ENABLED_STATE_ENABLED`).
    2. The default component (`LanguageWelcomeActivity`) is disabled (`COMPONENT_ENABLED_STATE_DISABLED`).
    3. All other 9 badge aliases are disabled (`COMPONENT_ENABLED_STATE_DISABLED`).
    4. Uses `PackageManager.DONT_KILL_APP` to prevent launcher crashes or disruptive app restarts.
  - When "Default UFM Icon" is selected:
    1. The default component (`LanguageWelcomeActivity`) is re-enabled.
    2. All 10 badge aliases are disabled.
- **Persistence**: Active selection saved in `SharedPreferences` (`ufm_badge_prefs` -> `selected_badge_points`).

### FR-05: Icon Asset Format & Cutouts
- **Adaptive Icon XML (`mipmap-anydpi-v26/ic_launcher_points_{pts}.xml`)**:
  - `<background android:drawable="@mipmap/ic_launcher_points_{pts}_background" />` (matching color radial gradient).
  - `<foreground android:drawable="@mipmap/ic_launcher_points_{pts}_foreground" />` (transparent emblem cutout strictly padded to the 66.67% safe circle).
- **Density Buckets**: Installed across `mipmap-mdpi`, `mipmap-hdpi`, `mipmap-xhdpi`, `mipmap-xxhdpi`, and `mipmap-xxxhdpi` for universal device compatibility.

### FR-06: Tipping-to-Points Conversion & Progressive Unlocking
- **Formula**: Every **$1 spent** in the Tip Jar grants **10 Points**:
  - **Ristretto Shot ($1)**: 10 Points -> Unlocks 5, 10 Point tiers.
  - **A Quick Espresso ($3)**: 30 Points -> Unlocks 20 Point tier.
  - **The Cappuccino ($5)**: 50 Points -> Unlocks 50 Point tier.
  - **The Full Latte ($10)**: 100 Points -> Unlocks 100 Point tier.
  - **The Coffee Bean Bag ($25)**: 250 Points -> Unlocks 200 Point tier.
  - Higher tiers unlock through cumulative points over time:
    - 300 Points ($30 cumulative tips).
    - 500 Points ($50 cumulative tips).
    - 750 Points ($75 cumulative tips).
    - 1,000 Points ($100 cumulative tips).
- **Cumulative Persistence**: Points accumulate across all tip purchases via `LoyaltyPrefs` (`total_tipped * 10`).

### FR-07: Locked Badge Appearance
- **Visual Presentation**:
  - The badge art remains visible under a subtle translucent dimming layer.
  - A small lock badge (`ic_lock`) is displayed.
  - A clear progress indicator shows points progress toward that milestone (e.g., *"15 / 50 pts — Need 35 more pts"*).
- **Interactive Behavior**:
  - Tapping a locked badge opens an informational preview dialog displaying the badge and offering a direct shortcut to the coffee tipping buttons.

### FR-08: Selection Confirmation Dialog
- Upon selecting an unlocked badge or restoring the default icon:
  - Displays a modal Material dialog showcasing the newly applied app icon preview.
  - Dialog Title: *"App Icon Updated!"*
  - Dialog Message: *"Your home screen icon has been updated to [Badge Name]. Note: Some home screen launchers may take a few seconds to refresh the icon."*
  - Action Button: *"OK"* (dismisses the dialog).

---

## Technical Architecture & File Layout

```
app/
├── src/
│   ├── google/
│   │   ├── AndroidManifest.xml              # 10 <activity-alias> declarations
│   │   ├── java/.../billing/
│   │   │   ├── BadgeIconManager.kt          # Alias switching & preference persistence
│   │   │   └── SupporterLoyaltyActivity.kt  # Binds and populates Badges section on Mobile
│   │   └── res/
│   │       ├── drawable-nodpi/              # 512px WebP previews (app_icon_points_*, badge_points_*)
│   │       ├── mipmap-anydpi-v26/           # Adaptive icon XMLs (foreground cutout + background)
│   │       └── mipmap-{m,h,xh,xxh,xxxh}dpi/ # Scaled launcher icon layers
│   └── main/
│       └── res/
│           ├── layout/
│           │   ├── activity_supporter_loyalty.xml # Contains cardBadgesSection (gone by default)
│           │   └── item_supporter_badge.xml       # Large badge card layout
│           └── values/
│               └── strings.xml                    # Translatable titles and descriptions
```

---

## Verification & Acceptance Criteria

1. **Platform Isolation**: On Android TV (`isTv == true`), Amazon, and FOSS builds, `cardBadgesSection` is completely invisible and no badge aliases exist.
2. **Visual Prominence**: Badge icons in the list are rendered large (**72×72 dp**), visually sharp, with both the radial gradient square and emblem cutout visible.
3. **Ordering**: Badges appear in strict ascending order from 5 points to 1,000 points.
4. **Instant Update**: Selecting any badge row:
   - Updates the selection highlight and checkmark immediately.
   - Updates the header icon to the selected cutout badge.
   - Shows a Toast confirmation (`App icon updated to ...!`).
   - Updates the launcher icon on the device home screen upon returning to launcher.
5. **Restoration**: Selecting "Default App Icon" restores the original launcher icon.
6. **Zero Regressions**: `mobileGoogleDebug` compiles with zero errors or resource conflicts.
