# Clarifications: Smart Bookmark Hub (Mobile Only)

The following architectural, functional, and user-experience clarifications have been established for the Smart Bookmark Hub feature:

---

### Clarification 1: Default Tap Behavior on Web Links
- **Question**: When tapping a saved web bookmark in the Bookmark Hub, what should be the default action?
- **Decision**: **Open in Reader Mode first if article text is available**:
  - If clean article body text was extracted during scraping, tapping the card opens the **Distraction-Free Reader Mode** immediately.
  - The reader screen features a prominent top button: *"Open in Browser"* allowing the user to view the original live webpage in Chrome/default browser at any time.
  - If the link has no extracted article body (e.g. video page, home page, web tool), it opens directly in the external browser.

---

### Clarification 2: Duplicate Share Handling
- **Question**: How should duplicate shares (sharing the same URL or file twice) be handled?
- **Decision**: **Detect and Update Existing Bookmark**:
  - If an incoming shared URL or identical file hash is already in the database:
    - Update its `updatedAt` timestamp so it moves to the top of the chronological feed.
    - If a new note was typed in the quick save sheet, append or update the existing note.
    - Display confirmation: *"Bookmark updated (already saved)"*.
  - Prevents cluttering the database with duplicate entries while preserving user notes.

---

### Clarification 3: Unrecognized & Third-Party Apps
- **Question**: When an item is shared from an app without a predefined smart group (e.g., Medium, Threads, Slack, Pocket Casts), what should happen?
- **Decision**: **Dynamically Create an Auto-Group Using the App's Display Name**:
  - Resolve the sender's package name and fetch its human-readable application label via `PackageManager` (e.g., "Medium", "Threads", "Slack").
  - Dynamically provision a smart auto-group for that app with `isAutoGroup = true`, linking its package name and assigning an accent color.
  - Subsequent shares from that app automatically route to this newly created group.

---

### Clarification 4: Deletion & File Cleanup Strategy
- **Question**: When deleting a bookmark with private cloned media/files, how should deletion behave?
- **Decision**: **Immediate Deletion with a 5-Second Undo Snackbar**:
  - Tapping Delete removes the item from the UI immediately.
  - Displays a glassmorphic Snackbar: *"Bookmark deleted — [Undo]"* for 5 seconds.
  - If "Undo" is tapped, restore the database item and keep files.
  - If the Snackbar times out or is dismissed, permanently delete the private files from `context.filesDir/bookmarks/media/` and `context.filesDir/bookmarks/thumbnails/`.

---

### Clarification 5: Private Sandbox Isolation & File Browsing Exclusion
- **Decision**: 
  - All media, documents, and thumbnail files remain strictly isolated inside `context.filesDir/bookmarks/media/` and `context.filesDir/bookmarks/thumbnails/`.
  - UFM's general file browsing activities (`FileBrowserActivity`, `FileBrowserFragment`, `StorageBrowserActivity` Recents) will strictly filter out and never expose this internal folder.
  - Users can explicitly copy/export files out into public storage via the card overflow menu: *"Save to Storage"*.
