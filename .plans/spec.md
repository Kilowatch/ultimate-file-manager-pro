# Specification: Android TV Onboarding Screens Redesign & D-Pad Navigation Fix

## Overview
Redesign all 8 Android TV onboarding setup screens (View Density, Font Size, Scrolling Text, Hold Duration, Recycle Bin, Hidden Files, App Lock, and Diagnostics) so each screen fits cleanly within TV safe margins with balanced proportions, professional visual hierarchy, and effortless remote-control navigation. Fix D-pad focus visibility across all interactive controls (including the Next, Skip, and Back buttons) and resolve the focus-trapping issue in the Scrolling Text screen's Marquee Scroll sub-options so users can focus and select every individual sub-option.

## User Stories
- As an Android TV user setting up the app for the first time (or reconfiguring from Settings), I want all 8 onboarding screens to fit comfortably on my TV screen without awkward scrolling, clipped elements, or wasted empty space so the setup feels polished and intentional.
- As an Android TV user navigating with a D-pad remote, I want to clearly see which button, card, or chip currently has focus—especially the Next, Skip, and Back buttons—so I never lose track of where my cursor is.
- As an Android TV user on the Scrolling Text onboarding step, I want to navigate into the Marquee Scroll sub-options (Scroll Speed, Loop Mode, and Scroll Scope) and select individual items instead of only being able to highlight the entire Marquee block.
- As an Android TV user stepping through onboarding, I want initial focus to land on the currently selected option (or first option) with predictable Up/Down/Left/Right movement to reach all options, Skip, Next, and Back without getting stuck.

## Functional Requirements
- **FR-01 (Consistent 2-Column TV Layout & Fit)**: All 8 TV onboarding screens must use a balanced two-column layout (options on the left, live preview or contextual visual on the right) within TV overscan safe margins, sized and spaced so each screen's content fits cleanly on standard 1080p and 720p TV displays without awkward dead space or cramped controls.
- **FR-02 (Per-Screen Visual Balance)**:
  - Binary or 2–3 option screens (Hold Duration, Recycle Bin, Hidden Files, Diagnostics, Font Size) must vertically center or evenly distribute their option cards and align their right-hand preview panels so they do not leave large empty gaps.
  - Dense multi-option screens (View Density with view mode + item size/columns, Scrolling Text with 6 display modes + Marquee sub-options, and App Lock with authentication policies) must use compact, well-proportioned cards and spacing so options remain easy to scan and navigate on TV.
- **FR-03 (Distinct Focus vs. Selected Visual States)**: Every focusable control across all 8 TV screens—including option cards, sub-option chips/buttons, Next/Start Browsing button, Skip button, and Back button—must display a high-contrast, unmistakable focus indicator (e.g., bright focus ring/border and subtle highlight or scale) that is visually distinct from the active/selected state (e.g., checkmark, radio indicator, or accent badge).
- **FR-04 (Header & Footer Button Focus Visibility)**: The footer Next / Start Browsing button, Skip button, and header Back button must all show clear focus states when highlighted via D-pad, ensuring users can immediately tell when Next or Skip is focused before pressing OK/Select.
- **FR-05 (Marquee Sub-Options Individual D-Pad Selection)**: On Step 3 (Scrolling Text), the Marquee Scroll sub-options (Scroll Speed: Slow/Normal/Fast; Loop Mode: One-Shot/Continuous; Scroll Scope: All Visible/Focused Only) must be placed as independent focusable items below the Marquee mode card—never trapped inside a single parent focusable card—so users can navigate to and select each individual chip with the D-pad.
- **FR-06 (Contextual Visibility of Marquee Sub-Options)**: On Step 3 (Scrolling Text), the Marquee sub-options section must be visible and focusable when Marquee Auto-Scroll is selected, and hidden or collapsed when any non-Marquee mode is selected so D-pad navigation skips irrelevant controls.
- **FR-07 (Predictable Initial Focus & D-Pad Traversal)**: When each TV onboarding screen opens, initial D-pad focus must land on the currently selected option card (or the first option if none is selected). D-pad directional navigation must allow smooth movement between option cards, sub-options (when visible), the footer Skip and Next buttons, and the header Back button (when visible on steps 2–8).
- **FR-08 (Live Preview Parity)**: Each screen's right-hand preview panel must accurately reflect the user's current selection (including Marquee speed, loop, and scope changes in real time on Step 3) and remain legible from a 10-foot TV viewing distance.

## Non-Functional Requirements
- **NFR-01 (10-Foot Readability & Overscan Safety)**: All text, icons, badges, and interactive targets must respect TV overscan safe padding and use 10-foot-friendly typography and contrast against the dark TV background.
- **NFR-02 (Localization)**: All visible section headers and labels on the 8 TV onboarding screens must use localized string resources rather than hardcoded text.
- **NFR-03 (Touch/Mobile Isolation)**: Mobile/tablet onboarding layouts and behavior must remain unchanged; all layout and navigation improvements target the TV experience.
- **NFR-04 (Smooth Focus Transitions)**: Focus state changes and scroll-into-view behavior on D-pad movement must feel responsive without lag or jarring layout jumps.

## Edge Cases & Error States
- **App Lock availability on TV**: When the TV device does not support a secure lock screen, Step 7 (App Lock) is skipped automatically, and Step 6 (Hidden Files) advances directly to Step 8 (Diagnostics) with step numbering and Back navigation remaining consistent.
- **Reconfigure Mode from Settings**: When launching onboarding in reconfigure mode from Settings, previously saved selections must be pre-selected and focused on each screen, and completing or skipping must return cleanly to Settings.
- **Switching away from Marquee on Step 3**: If a user selects a non-Marquee mode (e.g., 2 Lines or Truncate) while Marquee sub-options were previously visible, the sub-options collapse cleanly without leaving D-pad focus stranded on a hidden view.
- **Long translated strings**: Localized labels inside option cards, chips, and footer buttons must wrap or size gracefully without clipping or overlapping on narrow TV viewports (720p).

## Out of Scope
- Mobile and tablet onboarding layouts.
- Changing the number of TV onboarding steps (8 steps) or their underlying preference defaults.
- Modifying the post-onboarding main file browser or Settings screens.

## Success Criteria
- [ ] All 8 TV onboarding screens render with balanced two-column proportions, clean alignment, and no awkward dead space or clipped content on both 720p and 1080p TV screens.
- [ ] Focusing the Next, Skip, and Back buttons with a D-pad produces an unmistakable visual focus state.
- [ ] Every option card and sub-option chip across all 8 screens has a clear focus indicator distinct from its selected indicator.
- [ ] On Step 3 (Scrolling Text), users can D-pad into the Marquee sub-options and individually focus and select Speed, Loop Mode, and Scroll Scope items, with the live preview updating immediately.
- [ ] Initial focus lands on the active/first option on each screen, and users can traverse all interactive elements and complete or skip onboarding using only a TV remote D-pad.
