# Clarifications: Flat View (Indexed Root Browser Mode)

## Status
Answers received — ready for /tasks (or /plan if creating plan.md first).

## Questions and Answers

### 1. Data Source & Index Synchronization
**Q1.** How should Flat View query and load files for indexed storage volumes?  
**A:** Query directly from Room `file_index` database table. This provides instant load times, minimal memory consumption, and scales to 100,000+ files at 60fps without heavy I/O disk thrashing.

**Q2.** What qualifies as the root folder where Flat View is enabled?  
**A:** Top-level volume root only (e.g. `/storage/emulated/0/` for Internal Storage or `/storage/XXXX-XXXX/` for an SD card). It is strictly gated to the volume root on indexed storages.

### 2. Folder Grouping & Hierarchy Presentation
**Q3.** How should nested subfolder headers and items be structured in the list?  
**A:** Direct parent section headers with full relative path (e.g., `Documents/Work/2026/ (12 files)`). This groups files under their immediate parent folder, completely avoiding awkward deep horizontal nesting and horizontal scrolling on mobile and TV screens.

**Q4.** Should folder headers offer a way to navigate into that folder in standard browsing?  
**A:** Yes, provide an "Open Folder" icon button on each folder header to allow jumping directly into standard hierarchical browser mode at that specific folder.

### 3. Selection & Edit Mode (`fabTools` & Quick Bar)
**Q5.** In Edit Mode, how should checking a folder header checkbox behave?  
**A:** Checking a folder header checkbox automatically selects all files contained within that folder.

**Q6.** When the user taps "Select All" in Edit Mode, which items should be selected?  
**A:** Select all files across all folders (both currently expanded and collapsed folders).

### 4. System Folders Exclusion
**Q7.** Which system folder patterns should be excluded by default?  
**A:** Exclude `Android/data` and `Android/obb` by default. Keep dot directories visible unless hidden files are globally disabled in settings. Users can enable system folders via the Flat View settings toggle.

### 5. UI Entry Point & Settings Placement
**Q8.** Where should the user access the "Flat View" option in the browser UI?  
**A:** In the 3-dots overflow popup menu on the top right (across Local Standalone, TAB Mode, etc.), located directly below "Twin Window" (`menuItemFlatView`), visible when browsing at the root of an indexed storage volume.

**Q9.** Where in Settings should the dedicated Flat View configuration card be placed?  
**A:** Under the "Browsing & File List" section in `SettingsActivity`.

---

## Spec / Plan Impact
- **Entry Point**: Clarified that Flat View is launched from the top-right 3-dots overflow popup menu (`menuItemFlatView`) placed directly below "Twin Window" when at the indexed volume root.
- **Data Engine**: Clarified that the Room `file_index` database is the primary query engine for Flat View.
- **Folder Sections**: Clarified that grouping uses direct parent section headers with full relative paths and includes an "Open Folder" jump action.
- **Selection**: Clarified folder header checkbox cascades to all files within that folder, and "Select All" selects across both expanded and collapsed folders.
- **System Folders**: Clarified default exclusions specifically target `Android/data` and `Android/obb`.

---

## Confirmed — Ready for /tasks
All questions are answered and architectural decisions are solidified. No outstanding ambiguities remain.
