# Specification: Tip Jar Progress Popup Visual Redesign (Mobile)

## Overview
Redesign the Tip Jar progress popup card on the main storage screen to match the modern neon-cyan glass aesthetic showcased in `tipjar.jpg`. This replaces the previous purple styling with an electric cyan-to-blue glow, frosted glass "Fuel it!" action pill, harmonized typography, and gradient progress tracking that seamlessly integrates with UFM Pro's dark glass design language.

## User Stories
- **As a UFM Pro user**, I want the tip jar progress card to have a sleek, modern neon-cyan glass appearance so that the support banner looks visually premium, cohesive with the rest of the dark interface, and non-intrusive.
- **As a supporter**, I want to clearly see the current month's tip progress percentage, progress bar, and a distinct "Fuel it!" button so that I can easily gauge community goal completion and contribute if I choose to.

## Functional Requirements
- **FR-01: Visual Layout & Card Structure**
  - The card shall display in the upper region of the main storage screen below the top app bar and above the storage list when enabled.
  - The card must feature a rounded corner container with an electric cyan border glow / stroke.
  - The upper banner section shall feature a soft cyan/blue radial/linear dark glass gradient aura accentuating the jar icon on the left.
- **FR-02: Jar Icon & Aura**
  - The glowing jar icon with floating heart and sparkles shall be displayed on the left with a subtle cyan ambient aura matching the reference image.
- **FR-03: Header & Month Formatting**
  - The header shall display "Tip jar progress • <Month>" where "Tip jar progress • " is styled in primary crisp text, and the month abbreviation (e.g., "Nov") is highlighted in vibrant cyan.
  - The subtitle shall state "Every tip helps keep UFM Pro free for everyone." spanning up to two lines in muted secondary text.
  - When offline or uninitialized, appropriate offline fallback status shall remain supported without breaking layout dimensions.
- **FR-04: Percentage Display**
  - The current progress percentage (e.g., "8%") shall be rendered in bold, large, high-contrast cyan text aligned to the right of the title section.
- **FR-05: Progress Bar Styling**
  - The progress bar track shall have a dark translucent background.
  - The progress fill shall render with a smooth cyan-to-blue gradient and rounded pill caps.
- **FR-06: "Fuel it!" Action Button**
  - The "Fuel it!" button shall be styled as a frosted glass pill button:
    - Dark translucent background (glass effect).
    - Subtle cyan/blue outline border.
    - White coffee cup icon with steam.
    - White bold "Fuel it!" label.
  - Tapping "Fuel it!" navigates to the Supporter Loyalty / Tip Jar screen.
- **FR-07: Dismiss Interaction**
  - A subtle frosted circular button with an "✕" icon shall be anchored at the top right of the card.
  - Tapping the dismiss button or letting the 10-second auto-timer elapse dismisses the card with a smooth exit transition.

## Non-Functional Requirements
- **NFR-01: Visual Consistency**: The styling, corner radii, stroke weights, and typography must match the provided reference (`tipjar.jpg`) and feel native to the modern glass theme.
- **NFR-02: Contrast & Legibility**: All text elements (title, month highlight, subtitle, percentage, and button label) must maintain strong readability against the dark glass background.
- **NFR-03: Responsiveness & Touch Targets**: Touch targets for "Fuel it!" and the dismiss button must meet minimum accessible sizing (at least 36–48dp touch areas).
- **NFR-04: Performance**: All glows, gradients, and shapes must render smoothly without frame drops during list scrolling or card dismiss animations.

## Edge Cases & Error States
- **Offline / No Cache**: Card displays "0%" with the offline notice without distorting the layout or button alignment.
- **100%+ Goal Reached**: Progress bar caps at 100% fill cleanly without visual overflow; percentage displays full value (e.g., "100%", "105%").
- **Long Month Names in Localized Languages**: Header gracefully elides or wraps without overlapping the percentage text or dismiss button.
- **Device Rotation & Small Screens**: Layout remains properly spaced and elements do not clip on narrow screens (e.g., 320dp width).

## Out of Scope
- Changes to Google Play Billing or Amazon Appstore IAP logic or product SKUs.
- Redesigning the full Supporter Loyalty screen (`SupporterLoyaltyActivity`).
- Modifying the Android TV layout (`activity_storage_browser_tv.xml`), unless specifically aligned for accent color consistency.

## Success Criteria
- [ ] The mobile tip jar popup card matches the aesthetic, colors, typography, and layout of `tipjar.jpg`.
- [ ] The "Fuel it!" button renders as a frosted glass pill with cyan outline rather than an opaque purple button.
- [ ] The glowing border, cyan-highlighted month, cyan percentage, and gradient progress bar render faithfully.
- [ ] Card dismiss, auto-dismiss, and navigation to the Tip Jar continue to function smoothly without regressions.
