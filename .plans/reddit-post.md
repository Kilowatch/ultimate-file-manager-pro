# Reddit Post: Flat View (Recursive Indexed Directory Browser)

## Title
New Feature Added - Flat View (Recursive Indexed Directory Browser)

## Platform
TV and Mobile

## Post

Good Day Community,

### What's new
A new **Flat View** browser mode is now available in Ultimate File Manager Pro for both Android phones/tablets and Android TV / Fire TV.

When managing large storage drives or deep folder structures, navigating through subfolders one by one can be time-consuming. Flat View flattens your entire storage directory into a unified, virtualized list directly from the root folder—giving you full visibility and management over all your files in one place.

> **Important Prerequisite**: **Flat View is available exclusively for indexed storage drives.** Because it relies on UFM's local Room database indexing engine to provide instantaneous loading across massive directory structures without slowing down or freezing your device, the target storage volume (Internal Storage, SD card, or USB OTG drive) must be fully indexed first. If a drive is not indexed, Flat View will prompt you to run indexing.

* **Exclusively for Indexed Drives**: Flat View is purpose-built to take advantage of UFM's high-speed local database index. It operates only on indexed volumes from the root directory, ensuring lightning-fast access and zero filesystem lag even across 100,000+ files.
* **Main Menu Category Styling & Hierarchy**:
  * **Root Categories**: Immediate root-level folders (e.g. *A Google Review*, *Documents*, *Download*) are styled identically to the modern category cards on UFM's main menu (24dp rounded corners, 1dp glass stroke, squircle folder icon, and an elevated highlight header when expanded).
  * **Nested Subfolders**: Subfolders inside a category (e.g. a subfolder called *Hello* inside *A Google Review*) are treated like items within categories—nested 20dp elevated cards with squircle folder badges. Subfolder names are stripped of the parent category prefix (displaying clean relative names like **`Hello`**, never redundant paths like `A Google Review/Hello`).
  * **Clean File Rows**: Files sit cleanly indented underneath their respective subfolders with squircle file-type icons, sizes, and modification dates.
* **Expand All & Collapse All Controls**: Dedicated toolbar action buttons allow you to fold everything down to top-level category cards at once with **Collapse All** (`^`), or unfold every subfolder level simultaneously with **Expand All** (`v`). Individual folder headers can also be tapped to toggle expansion.
* **Direct Jump to Folder ("Open Folder")**: Spot a folder you want to inspect hierarchically? Each root category card and nested subfolder card includes a direct **"Open Folder"** action button that jumps straight to that location in standard file browser mode.
* **System Folder Filtering**: By default, noisy Android system folders (`Android/data` and `Android/obb`) are excluded from Flat View to keep the list clean and focused on your personal data. You can toggle this on or off in settings.
* **Full Multi-Selection & Edit Mode Parity**:
  * **Cascading Checkboxes**: Checkboxes on root category headers select all files in that category across all subfolders; checkboxes on nested subfolder cards select all files within that specific subfolder.
  * **Toggle Select All / Deselect All**: The selection header features a dedicated toggle button that dynamically switches between **Select All** and **Deselect All**.
  * **Auto-Exit Edit Mode**: As soon as all items are deselected (or "Deselect All" is tapped), Flat View automatically exits edit mode without requiring you to manually close it.
  * **Toolbar Synchronization (Quick Bar vs. Tools FAB)**: Fully respects your Toolbar settings. When the Floating Quick Action Bar is active, it appears alone at the bottom; when disabled, the Tools FAB appears alone at the bottom corner—never cluttering the screen together.
  * **Standard File Tools**: Tapping Tools opens UFM's standard `FileToolsBottomSheet` with full support for Copy, Move (Cut), Delete, Rename, Share, Properties, and Checksum.
* **Live Real-Time Filter**: A search bar at the top lets you instantly filter across filenames, subfolders, and categories in real time.
* **100% UFMStandard Glass Dialogs**: All dialogs (Flat View Settings, Delete Confirmation, and Not Indexed notice) strictly adhere to `UFMStandard` with frosted glass card styling (`@drawable/bg_dialog_glass`), squircle hero icon badges, and embedded action buttons on both Mobile and Android TV.
* **Android TV Remote Optimized**: Built with `UFMStandard` dual layouts. On Android TV and Fire TV, Flat View features a 72dp glass header, full D-pad remote navigation, and high-visibility yellow focus indicators.

### How to use it
1. **Prerequisite & Opening Flat View**:
   * **Ensure drive is indexed**: Flat View is only available for indexed storage volumes (Internal Storage is typically indexed by default; for external SD cards or USB drives, ensure indexing is enabled and finished).
   * Navigate to the **root** of your indexed storage volume in Local Standalone or Tabbed browser mode.
   * Tap the **3-dot menu** (`⋮`) in the top right corner.
   * Tap **Flat View** (located directly below *Twin Window*).
2. **Browsing & Expanding**:
   * Scroll smoothly through your root category cards and nested subfolders.
   * Tap any category or subfolder card to expand or collapse that section.
   * Tap **"Collapse All"** (`^`) in the top toolbar to collapse all folders down to top-level category cards.
   * Tap **"Expand All"** (`v`) in the top toolbar to unfold every level at once.
   * Tap the arrow button (**Open Folder**) on any category or subfolder header to jump to that folder in standard hierarchical browser mode.
   * Tap any file to open it directly.
3. **Filtering & Searching**:
   * Tap the search icon in the toolbar and type to filter filenames instantly across all categories. Matching files and their parent folders are automatically expanded.
4. **Multi-Selection & Batch Actions**:
   * Tap the checkbox on any file to select it and enter Edit Mode.
   * Tap the checkbox on a subfolder card to select or deselect all files in that subfolder.
   * Tap the checkbox on a root category card to select or deselect all files in that entire category.
   * Tap **Select All** in the selection header to select all files across the drive; tap **Deselect All** to clear everything and exit Edit Mode automatically.
   * Use the **Floating Quick Bar** or **Tools FAB** to perform Copy, Move, Delete, Share, or view Properties.
5. **Settings & Customization**:
   * Open **Settings** -> **Browsing & File List** -> **Flat View**.
   * Toggle **Include System Folders** if you want `Android/data` and `Android/obb` included.
   * Toggle **Default to All Expanded** if you prefer folders to start collapsed by default.

Thank you and kind regards
