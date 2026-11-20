# Specification: Settings Hierarchy, Granular Groupings & Logical Sequencing

## Overview
Reorganize the settings in Ultimate File Manager Pro by breaking down oversized, confusing categories (e.g., File Browsing & Management with 14 items, Media Player with 11 items, and Network & Performance with 9 items) into intuitive, focused groupings of 2 to 5 related settings each. In addition, establish a natural, top-to-bottom sequence of settings categories that guides the user logically from general setup and visual appearance, through core file browsing, media, and networking, down to security, maintenance, and diagnostics.

## User Stories
- **As a UFM Pro mobile user**, I want settings to flow in a logical sequence (General -> Appearance -> Navigation/Controls -> File Management -> Media -> Network -> Security -> System) so that configuration feels intuitive and progressive rather than scattered.
- **As a user looking for a specific option**, I want related settings to be clustered into focused, cohesive groups (such as "Permissions & Storage Access", "File Operations & Safety", "Playback & Streaming", and "Player Controls") so that I can quickly spot and adjust options without scrolling through massive, unfocused lists.
- **As an Android TV user**, I want the TV settings screen to follow the exact same logical sequence with clear section headers so that navigating via D-pad is structured and effortless.
- **As a power user searching settings**, I want in-page search filtering to reveal matching items beneath clear, contextual sub-headers that preserve their natural categorized place in the hierarchy.

## Functional Requirements

- **FR-01: Break Down Oversized "File Browsing & Management" Section**
  - Replace the monolithic 14-item section with focused, specialized groups:
    - **Permissions & Storage Access**: Elevated/system access and virtual drives (*All Files Access*, *Root Access*, *Simulate USB OTG Drive*).
    - **Browsing & Display**: File discovery and listing behavior (*Show Hidden Files*, *Flat View*, *Folder Sort Overrides*, *Search Results Limit*).
    - **File Operations & Safety**: Data protection and cleanup (*Recycle Bin*, *Duplicate Retention Strategy*).
    - **Organization & File Tools**: Metadata and management tools (*Manage Favorites*, *Custom Drive Names*, *File Tags*, *APK / XAPK Extract*).

- **FR-02: Break Down Oversized "Media Player & Playback" Section**
  - Replace the 11-item section with two focused groups:
    - **Media Playback**: Playback logic and video automation (*Side-by-Side Video*, *Controls on Repeat*, *Auto-play Next*, *Background Video Mode*, *Resume After Interruption*).
    - **Player Controls & Gestures**: Player UI, touch interaction, and timing (*Show Mini-Player*, *Player Gestures*, *Button Feedback Toasts*, *Skip Length*, *Controls Auto-Hide Duration*, *Subtitle Style*).

- **FR-03: Break Down Oversized "Network & Performance" Section**
  - Replace the 9-item section with distinct categories:
    - **Transfers & Downloads**: Active transfers and connection handling (*QuickTransfer*, *Download Manager*, *Cache Copy*, *Multi-threading*).
    - **Network Caching & Streaming**: Caching engine and stream processing (*Network File Caching*, *Network Media Processing*).
    - **Servers & Indexing**: Host server and indexer utilities (*Background TV Sharing*, *File Server System Tiles*, *Storage Indexer*).

- **FR-04: Streamline "Appearance & Customization" Section**
  - Group visual options logically:
    - **Theme & Display**: High-level visual styles (*Theme/Appearance*, *View Appearance Presets*, *Font Size*).
    - **Interface Details & Animations**: UI micro-customizations (*Icons*, *Default Icon Colors*, *Folder Transitions*, *Path Breadcrumbs*, *Scrolling Text*, *Grid Item Indicators*, *Grid Text Position on TV*).

- **FR-05: Preserved Focused Groups**
  - Maintain well-scoped groups without unnecessary fragmentation:
    - **General & Interface** (*Search Bar*, *Default Start Screen*, *Language*, *Default Apps*, *Main Menu Layout*).
    - **Quick Access** (*Style*, *Swipe Edge*, *Edge Handle*, *Hide Docked Tiles*, *Manage Tiles* - mobile).
    - **Other Storage & Transfers** (*SAF Picked File Action*, *Copy Destination Folder*).
    - **Controls & Gestures** (*Edit Mode Hold Duration*, *Toolbar Icons*, *Keyboard Shortcuts*, *Tap Icon to Edit*, *Selection Checkboxes*, *Left-Handed FAB*).
    - **Dual-Pane (Twin Window)** (*Layout*, *Start in Twin Panel*).
    - **Thumbnails & Caching** (*Media Thumbnails*, *Video Thumbnail Time*, *Network Thumbnails*).
    - **Security** (*App Lock*, *Auth Method*, *Change PIN*, *Lock Frequency*).
    - **Backup & Restore** (*Backup & Restore*, *Auto Backup*).
    - **Privacy & Diagnostics** (*Tip Jar Popup*, *Usage Analytics*, *Crash Reporting*, *Check for Updates*).
    - **Accessibility** (*Colorblind Mode*).

- **FR-06: Natural Top-to-Bottom Settings Flow & Sequencing**
  - The overall page shall be ordered in an intuitive sequence from general preferences to system maintenance:
    1. **General & Interface**
    2. **Appearance & Display**
       - 2.1 *Theme & Display*
       - 2.2 *Interface Details & Animations*
    3. **Navigation & Controls**
       - 3.1 *Quick Access* (mobile)
       - 3.2 *Controls & Gestures*
       - 3.3 *Dual-Pane (Twin Window)*
    4. **File Management & Storage**
       - 4.1 *Permissions & Storage Access*
       - 4.2 *Browsing & Display*
       - 4.3 *File Operations & Safety*
       - 4.4 *Organization & File Tools*
       - 4.5 *Other Storage & Transfers (SAF)*
    5. **Thumbnails & Media**
       - 5.1 *Thumbnails & Previews*
       - 5.2 *Media Playback*
       - 5.3 *Player Controls & Gestures*
    6. **Network & Connectivity**
       - 6.1 *Transfers & Downloads*
       - 6.2 *Network Caching & Streaming*
       - 6.3 *Servers & Indexing*
    7. **Security & Access**
       - 7.1 *Security & App Lock*
    8. **System, Maintenance & About**
       - 8.1 *Accessibility*
       - 8.2 *Backup & Restore*
       - 8.3 *Privacy & Diagnostics*

- **FR-07: Unified Mobile and Android TV Structure**
  - Mirror the new section hierarchy and ordering symmetrically across both Mobile and Android TV layouts, respecting platform-specific item inclusions/exclusions.
  - Maintain TV D-pad focus traversal order and header styling.

- **FR-08: In-Page Search Filtering Compatibility**
  - Ensure the dynamic search filter in settings seamlessly handles the new section order, section headers, and group cards, properly showing matching rows with their respective section header and hiding empty sections with no matching rows.

- **FR-09: Card Icon & Preference Continuity**
  - Retain all existing settings keys, SharedPreferences bindings, click handlers, dialog triggers, and card icon customizations without regression.

## Non-Functional Requirements
- **NFR-01: Visual Consistency**: Section headers, card backgrounds, corner radii, and divider margins must match the app's established dark glassmorphism design system.
- **NFR-02: Localization**: Every new section header string must use standard Android string resources (`@string/...`) and be ready for translation.
- **NFR-03: Performance**: Scrolling through the newly sequenced settings list must remain smooth (60fps+) with zero stutter.
- **NFR-04: Accessibility**: All cards and switches must maintain appropriate touch targets (minimum 48dp on mobile) and clear focus outlines on TV.

## Edge Cases & Error States
- **Empty Search Query**: When clearing search, all sections and cards restore their original visibility states instantly in the correct sequence.
- **Search Query With No Matches**: All sections hide and the "No settings found" empty state appears correctly.
- **TV D-pad Navigation Across New Section Boundaries**: Focus must move smoothly between the last item of one section and the first item of the next section without getting trapped.
- **Dynamically Hidden Items**: Conditional rows (e.g. Root Access, TV Background Sharing) maintain clean spacing and divider visibility if hidden at runtime.

## Out of Scope
- Adding new functional settings or modifying how any specific setting works (e.g. changing how Backup & Restore or PIN encryption operates).
- Changing settings into multi-level nested activities or tabbed views (retaining the familiar, fast single-page hub layout).
- Altering the Main Menu tiles or Quick Access bar logic.

## Success Criteria
- [ ] No section in Settings contains more than 5-6 items.
- [ ] Settings follow a natural, logical sequence from general appearance through file management and media down to system maintenance.
- [ ] Both Mobile and Android TV layouts share the synchronized grouping hierarchy and sequence.
- [ ] In-page settings search accurately filters cards and dynamically hides/shows new section headers.
- [ ] All existing settings functionality, dialogs, toggles, and icon bindings function without regression.
