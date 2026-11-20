# Clarifications: Settings Hierarchy, Granular Groupings & Logical Sequencing

## Status
Answers received — ready for /plan

## Questions and Answers

### UI and Layout
**Q1.** Which section header titles and breakdown do you prefer for the split categories?
**A:** Approved all proposed group titles:
- *Permissions & Storage Access* (All Files Access, Root Access, Simulate USB Drive)
- *Browsing & Display* (Show Hidden Files, Flat View, Folder Sort Overrides, Search Results Limit)
- *File Operations & Safety* (Recycle Bin, Duplicate Retention Strategy)
- *Organization & File Tools* (Manage Favorites, Custom Drive Names, File Tags, APK / XAPK Extract)
- *Theme & Display* (Theme/Appearance, View Appearance Presets, Font Size)
- *Interface Details & Animations* (Icons, Default Icon Colors, Folder Transitions, Breadcrumbs, Scrolling Text, Grid Indicators)
- *Media Playback* (Side-by-Side Video, Repeat Controls, Auto-play Next, Background Video Mode, Resume After Interruption)
- *Player Controls & Gestures* (Mini-Player, Gestures, Button Feedback Toasts, Skip Length, Controls Timeout, Subtitle Style)
- *Transfers & Downloads* (QuickTransfer, Download Manager, Cache Copy, Multi-threading)
- *Network Caching & Streaming* (Network File Caching, Network Media Processing)
- *Servers & Indexing* (Background TV Sharing, File Server System Tiles, Storage Indexer)

### Placement & Categorization
**Q2.** Where should the 2 'Other Storage & SAF Transfers' items be placed?
**A:** Keep as a distinct section titled 'Storage Transfers & SAF' placed directly after 'Organization & File Tools'.

**Q3.** Where should the Accessibility section (Colorblind Mode) be sequenced?
**A:** Place Accessibility near the bottom under System & Maintenance, directly before Backup & Restore and Privacy & Diagnostics.

### Android-Specific & TV Parity
**Q4.** How should the Android TV settings layout be updated?
**A:** Full parity: apply the exact same section hierarchy and sequence to Android TV (`activity_settings_tv.xml`), omitting only mobile-specific items, preserving 100% design and mental model parity.

## Spec / Plan Impact
All answers confirm the specifications set forth in `.plans/spec.md`. No changes required to `spec.md`.

## Confirmed — Ready for /plan
All questions have been clarified and approved. Proceed to `/plan` to design the architecture and implementation steps.
