# Specification: First-Run Onboarding Setup Flow with Dedicated Per-Setting Activities

## Overview
Introduce a visual first-run onboarding setup sequence immediately following permissions in `WelcomeActivity`. Every individual setting is implemented as its own dedicated Activity with paired mobile and TV layouts. Each Activity dedicates full screen focus to that single setting, rendering rich explanatory text alongside a **large, live visual preview example** that is **always pre-rendered on the recommended default selection** upon opening (or the user's saved preferences if re-running from Settings). The sequence features contextual skip buttons: **"Skip & Use Defaults"** on Screen 1, and **"Skip Remaining"** from Screen 2 onwards, which **preserves all settings configured up to that point while applying recommended defaults to all remaining skipped settings**. The flow provides full Android TV 16:9 D-pad parity, checks hardware security compatibility, uses polished horizontal slide transitions, guarantees unified preference persistence with `SettingsActivity`, and adds an on-demand re-entry card in Settings.

## User Stories
- **As a new user entering Screen 1 who wants to bypass setup immediately**, I want a clear **"Skip & Use Defaults"** button so I can open the app in one tap with all recommended defaults applied.
- **As a user who customizes the first few screens and then wants to jump into the app**, I want the button on Screen 2 onwards to read **"Skip Remaining"** so I know my custom choices are preserved while remaining unvisited options use defaults.
- **As a visual learner**, I want every screen to open with the live preview actively demonstrating the recommended default so I immediately see the baseline experience before touching anything.
- **As a user exploring choices**, I want the preview to instantly react as I change options so I can compare alternatives against the default before deciding.
- **As an Android TV user**, I want every screen to provide a 10-foot TV layout with glowing D-pad focus outlines, remote-friendly controls, and automatic exclusion of mobile-only touch screens.
- **As an existing user re-running the wizard from Settings**, I want the wizard to open with my current settings already selected, and return back to Settings once finished without disrupting my current session.

## Functional Requirements

- **FR-01: Dedicated Per-Setting Activity Architecture**
  - Every one of the 10 settings shall be implemented as its own standalone Activity class.
  - Every Activity must strictly follow the project Activity pattern:
    - `attachBaseContext` override with `LocaleManagerWrapper`.
    - Edge-to-edge support with window insets.
    - One Activity class, two layouts: `activity_<name>.xml` (mobile) and `activity_<name>_tv.xml` (TV).
  - Clean linear Activity backstack with "Back" returning to the previous setting Activity and preserving prior selections.
  - Transitions: Fluid horizontal slide animation (`slide_in_right` / `slide_out_left` on Next; `slide_in_left` / `slide_out_right` on Back).

- **FR-02: Sequential Flow of the 10 Dedicated Activities**

  ```
  [WelcomeActivity] (Permissions Complete)
         │
         ▼
  1.  [OnboardingViewDensityActivity]        (Button: "Skip & Use Defaults")
         │
         ▼
  2.  [OnboardingFontSizeActivity]           (Button: "Skip Remaining")
         │
         ▼
  3.  [OnboardingScrollingTextActivity]      (Button: "Skip Remaining")
         │
         ▼
  4.  [OnboardingHoldDurationActivity]       (Button: "Skip Remaining")
         │
         ▼
  5.  [OnboardingLeftHandedFabActivity]      (Button: "Skip Remaining" — Mobile Only, skipped on TV)
         │
         ▼
  6.  [OnboardingEdgeDrawerActivity]         (Button: "Skip Remaining" — Mobile Only, skipped on TV)
         │
         ▼
  7.  [OnboardingRecycleBinActivity]         (Button: "Skip Remaining")
         │
         ▼
  8.  [OnboardingHiddenFilesActivity]        (Button: "Skip Remaining")
         │
         ▼
  9.  [OnboardingAppLockActivity]            (Button: "Skip Remaining" — Skipped if hardware lacks secure lock)
         │
         ▼
  10. [OnboardingDiagnosticsActivity]       (Button: "Skip Remaining" / "Start Browsing")
         │
         ▼
  [StorageBrowserActivity] (Main App — Configured & Ready!)
  ```

- **FR-03: Detailed Specification of Each Dedicated Activity & Live Preview**

  ### 1. `OnboardingViewDensityActivity` (Screen 1)
  - **What It Offers:** Controls row height, spacing, and thumbnail sizing in file listings.
  - **Options:** *Standard List (Recommended Default)*, *Compact List*, *Large Grid*.
  - **Live Preview:** Prominent file directory mockup (*Documents*, *Photos*, *Music*).
    - *Default:* Pre-renders in **Standard List** (48dp row height, standard padding).
    - *On Selection:* Fluidly adjusts between Compact (36dp tight rows) and Large Grid cards.
  - **Skip Button Label:** **"Skip & Use Defaults"** (skips all and commits all recommended defaults).

  ### 2. `OnboardingFontSizeActivity` (Screen 2)
  - **What It Offers:** Independently scales typography across file names, path breadcrumbs, metadata, and dialogs.
  - **Options:** *Small (85% / 13sp)*, *Medium (100% / 15sp — Recommended Default)*, *Large (115% / 17sp)*.
  - **Live Preview:** Sample metadata row (`"Documents • 14 files • 2.4 GB"`).
    - *Default:* Pre-renders at **Medium (100% / 15sp)**.
    - *On Selection:* Live text immediately rescales when a chip is tapped.
  - **Skip Button Label:** **"Skip Remaining"**.

  ### 3. `OnboardingScrollingTextActivity` (Screen 3)
  - **What It Offers:** Automatically animates long file and folder names that exceed screen width, preventing clipped extensions.
  - **Options:** *Marquee Auto-Scroll (Recommended Default)*, *Truncate with Ellipsis*.
  - **Live Preview:** Mock row with long filename (`"Ultimate_Backup_Archive_2026_Final_v2.tar.gz"`).
    - *Default:* Marquee auto-scrolling is **actively running** on screen open.
    - *On Selection:* Switching to Truncate stops animation and displays static ellipsis (`...`).
  - **Skip Button Label:** **"Skip Remaining"**.

  ### 4. `OnboardingHoldDurationActivity` (Screen 4)
  - **What It Offers:** Sets the physical press-and-hold duration required to trigger multi-selection edit mode.
  - **Options:** *Short (300ms)*, *Normal (500ms — Recommended Default)*, *Long (800ms)*.
  - **Live Preview:** Interactive "Touch & Hold to Test" visual ring.
    - *Default:* Pre-configured at **Normal (500ms)**.
    - *On Interaction:* User presses down; a glowing cyan ring fills at the chosen duration (300ms, 500ms, or 800ms) with a haptic pulse and checkmark upon completing the hold.
  - **Skip Button Label:** **"Skip Remaining"**.

  ### 5. `OnboardingLeftHandedFabActivity` (Screen 5 — Mobile Only)
  - **What It Offers:** Re-anchors the primary Tools & Quick Actions button to the bottom-left corner for left-handed ergonomics.
  - **Options:** *Right-Handed (Default — Off)*, *Left-Handed (On)*.
  - **Live Preview:** Miniature phone frame mockup.
    - *Default:* FAB sits at standard **Bottom-Right**.
    - *On Selection:* Smoothly translates the button over to **Bottom-Left** with glow indicator.
  - **Skip Button Label:** **"Skip Remaining"**.
  - **Platform Handling:** Skipped automatically on TV.

  ### 6. `OnboardingEdgeDrawerActivity` (Screen 6 — Mobile Only)
  - **What It Offers:** A swipe-from-edge translucent drawer providing instant access to bookmarks and drives from anywhere in UFM.
  - **Options:** *Enabled (Recommended Default — On)*, *Disabled (Off)*.
  - **Live Preview:** Phone mockup with edge handle.
    - *Default:* Shows the edge handle and animated shortcut drawer sliding into view.
    - *On Selection:* Toggling Off dims and collapses the edge drawer.
  - **Skip Button Label:** **"Skip Remaining"**.
  - **Platform Handling:** Skipped automatically on TV.

  ### 7. `OnboardingRecycleBinActivity` (Screen 7)
  - **What It Offers:** Two-stage deletion safety net allowing restoration of accidentally deleted files.
  - **Options:** *Safe Delete (Recommended Default — On)*, *Permanent Erase (Off)*.
  - **Live Preview:** Visual state comparison card.
    - *Default:* Displays **Green Shield (Safe Delete Active: Files can be recovered)**.
    - *On Selection:* Toggling Off switches to an **Orange Alert (Permanent Erase: Files immediately destroyed)**.
  - **Skip Button Label:** **"Skip Remaining"**.

  ### 8. `OnboardingHiddenFilesActivity` (Screen 8)
  - **What It Offers:** Controls whether dotfiles and hidden directories (e.g. `.nomedia`, `.config`) appear in listings.
  - **Options:** *Hidden (Recommended Default — Off)*, *Visible (On)*.
  - **Live Preview:** File list preview showing a standard `DCIM` folder and `.thumbnails` folder.
    - *Default:* `.thumbnails` folder is **hidden**.
    - *On Selection:* Toggling On reveals `.thumbnails` with a subtle dimmed dotfile indicator.
  - **Skip Button Label:** **"Skip Remaining"**.

  ### 9. `OnboardingAppLockActivity` (Screen 9)
  - **What It Offers:** Enforces 4-digit PIN or biometric authentication (fingerprint/face) each time UFM is opened.
  - **Hardware Capability Check:** Automatically checks device keyguard/biometric capabilities upfront; if the hardware or OS does not support secure lock, this Activity is completely bypassed in the sequence.
  - **Options:** *Open Access (Recommended Default — Off)*, *Protected Lock (On)*.
  - **Live Preview:** Glassmorphic security card illustrating the biometric fingerprint sensor and glowing 4-digit PIN pad.
  - **Setup Action:** Toggling On immediately launches the PIN setup dialog; dismissing/cancelling reverts the toggle to Off.
  - **Skip Button Label:** **"Skip Remaining"**.

  ### 10. `OnboardingDiagnosticsActivity` (Screen 10)
  - **What It Offers:** Transmits anonymous, privacy-safe crash stack traces and ANR reports to help the developer fix bugs.
  - **Privacy Guarantee:** 100% anonymous. Zero personal data, zero file names, zero storage contents, zero IP addresses.
  - **Options:** *On (Recommended Default)*, *Off*.
  - **Live Preview:** Transparency card highlighting privacy badges (*Enabled by Default*, *No Personal Data*, *Anonymous Stack Traces Only*, *Zero File Tracking*).
  - **Actions:** Displays **"Skip Remaining"** and **"Start Browsing"** (both save preferences and complete onboarding).

- **FR-04: Contextual Skip Button & Progressive Persistence Behavior**
  - **Screen 1 (`OnboardingViewDensityActivity`):** Button reads **"Skip & Use Defaults"**. Tapping it applies all recommended defaults and immediately opens the storage browser.
  - **Screens 2 through 10:** Button reads **"Skip Remaining"**. Tapping it:
    1. Immediately preserves and commits all choices made on current and previous screens.
    2. Identifies all subsequent unvisited/skipped screens and commits their recommended defaults:
       - View Appearance: Standard List (if skipped)
       - Font Size Scale: Medium 100% (if skipped)
       - Scrolling Text: Marquee Auto-Scroll Enabled (if skipped)
       - Edit Mode Hold Duration: Normal 500ms (if skipped)
       - Left-Handed FAB Mode: Disabled / Bottom-Right (if skipped)
       - Quick Access Edge Drawer: Enabled (if skipped)
       - Recycle Bin: Safe Delete Enabled (if skipped)
       - Show Hidden Files: Disabled / Hidden (if skipped)
       - App Lock & Security: Disabled / Open Access (if skipped)
       - Crash & ANR Reporting: Enabled (if skipped)
    3. Sets the onboarding completion flag in `PolicyAcceptanceManager`.
    4. Immediately launches `StorageBrowserActivity` (or returns to `SettingsActivity` in Reconfigure Mode).

- **FR-05: Preference Persistence & Zero Settings Drift**
  - All selections write directly to their canonical preference managers:
    - `ViewAppearancePreferenceManager`
    - `FontSizePreferenceManager`
    - `ScrollingTextPreferenceManager`
    - `LongPressDurationManager`
    - `LeftHandedFabPreferenceManager`
    - `QuickAccessPreferenceManager`
    - `RecycleBinPreferenceManager`
    - `HiddenFilesPreferenceManager`
    - `AppSecurityManager`
    - `CrashReportManager`
  - Values apply immediately on initial dashboard load without requiring an app restart.

- **FR-06: Android TV 16:9 Side-by-Side Parity & 10-Foot Adaptation**
  - Every onboarding activity implements a paired `_tv.xml` layout.
  - Side-by-side split screen:
    - **Left Column**: D-pad navigable options, chips, and toggles with glowing cyan borders.
    - **Right Column**: Large, glowing live visual preview card reflecting current selection.
  - High-visibility focus indicators with glowing cyan borders.
  - Automatically skips mobile-only Activities 5 and 6 on TV.

- **FR-07: Re-Entry Shortcut & Reconfigure Mode from Settings**
  - Add a "Quick Setup Wizard" card row in `SettingsActivity` under `layoutSectionGeneral`.
  - Tapping launches `OnboardingViewDensityActivity` with intent extra `EXTRA_RECONFIGURE_MODE = true`.
  - In Reconfigure Mode:
    - Pre-populates all previews and selection controls with the user's *currently saved preferences* (rather than resetting to factory defaults).
    - On completion or Skip, finishes directly back to `SettingsActivity` rather than restarting or navigating away to the storage dashboard.

## Non-Functional Requirements
- **NFR-01: Visual Polish**: Follows the app's established dark glassmorphism design language with frosted translucent cards and cyan/accent glows.
- **NFR-02: 60fps+ Live Preview Animations**: Visual previews (marquee scrolling, FAB positioning animation, density transitions) and horizontal slide activity transitions must animate smoothly at 60fps+.
- **NFR-03: Rapid Completion**: The full sequence takes under 45 seconds for a user to walk through, or 1 second to tap Skip.

## Edge Cases & Error States
- **One-Tap "Skip & Use Defaults" on Screen 1**: Saves all defaults and immediately opens the storage browser.
- **Mid-Flow "Skip Remaining" (e.g. Screen 4)**: Persists user's choices on screens 1, 2, 3, applies defaults for 4 through 10, and opens storage browser.
- **Back Navigation**: User can press Back at any step to return to the previous Activity; prior selections are preserved.
- **App Killed Mid-Flow**: Launching resumes at the current onboarding Activity without resetting earlier selections.
- **Device Lacks Security Capabilities**: `OnboardingAppLockActivity` is completely bypassed in the sequence.

## Out of Scope
- Modifying existing language (`LanguageWelcomeActivity`) or legal policies (`PolicyWelcomeActivity`).
- Modifying deep technical settings (e.g. SMB threads, TV server background mode, mock USB).
- Theme selection (Theme remains in Settings).

## Success Criteria
- [ ] Screen 1 displays button labeled "Skip & Use Defaults".
- [ ] Screens 2 through 10 display button labeled "Skip Remaining".
- [ ] Tapping Skip commits all prior user selections and applies recommended defaults to all skipped screens.
- [ ] Option 10 (Crash & ANR Reporting) is correctly defaulted to On.
- [ ] 10 dedicated Activities implemented with mobile and TV layout pairs following the project activity pattern.
- [ ] Horizontal slide transitions connect all 10 Activities smoothly.
- [ ] Every visual preview is fully rendered and animated on the recommended default selection on screen open.
- [ ] Android TV layout uses an ergonomic 16:9 side-by-side split screen with full D-pad focus traversal.
- [ ] Device security capability check properly protects against showing App Lock on unsupported hardware.
- [ ] Reconfigure mode correctly reads current user settings and returns to SettingsActivity upon completion.
- [ ] All settings persist to existing preference managers with 100% parity with `SettingsActivity`.
- [ ] Onboarding runs only once on first install.
