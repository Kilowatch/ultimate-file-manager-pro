# Specification: Smart Bookmark Hub (Mobile Only)

## 1. Overview & Vision
Ultimate File Manager Pro currently integrates into Android's system share sheet with two primary entry points:
1. **"Open In..." (`OpenWithActivity`)** — Immediate preview/inspection of incoming files.
2. **"Save to..." (`ShareReceiverActivity`)** — Browsing and selecting a destination to save incoming files.

This specification introduces a third flagship share target and home screen feature: the **Smart Bookmark Hub**. Positioned under the **"Organize"** category on the Main Menu (Mobile only), this feature merges the best capabilities of top-tier bookmark and research managers (such as **Raindrop.io** and **Instapaper**) with UFM's robust file management architecture. 

It provides an all-inclusive, premium-grade experience with **zero paywalls or gated restrictions**. Every feature—visual dashboards, smart auto-grouping by source app, rich OpenGraph previews, distraction-free reader mode, text-to-speech audio reading, permanent offline media preservation, biometric vault locking, and comprehensive HTML/Markdown/JSON exporting—is fully available to all users.

---

## 2. Platform & UI/UX Standards
- **Target Platform**: **Mobile only** (Android phones and foldables/tablets). Android TV is explicitly excluded as share sheet receivers, link parsing, and reading queues are mobile workflows.
- **Main Menu Location**: Placed under `MainMenuViewModeManager.CATEGORY_ORGANIZE` in `StorageBrowserActivity`, alongside Smart Sort, Storage Analyzer, and Recycle Bin.
- **Design Language**: Strictly adheres to `UFMStandard`:
  - Frosted glass headers (`@color/mobile_glass_header`, 56dp height).
  - Squircle icon buttons (`@drawable/bg_btn_icon_frosted`, 40dp).
  - Glass cards (`bg_reindex_option_card` / `bg_dialog_glass`).
  - Interactive filter chips, smooth bottom sheets, and haptic feedback.

---

## 3. High-Level Architecture & Components

```
                          ┌─────────────────────────────┐
                          │   External App Share Sheet  │
                          │ (WhatsApp, Reddit, Web, etc)│
                          └──────────────┬──────────────┘
                                         │
                                         ▼
                      ┌──────────────────────────────────────┐
                      │ BookmarkReceiverActivity (Share Target│
                      │  "Bookmark with UFM"                 │
                      └──────────────────┬───────────────────┘
                                         │
                                         ▼
                      ┌──────────────────────────────────────┐
                      │ Glass Quick Save Bottom Sheet        │
                      │ • Source Icon & Name (e.g. Reddit)   │
                      │ • Auto-Assigned Group                │
                      │ • Title & Notes Input                │
                      │ • 1-Tap Save / Confirm               │
                      └──────────────────┬───────────────────┘
                                         │
                 ┌───────────────────────┴───────────────────────┐
                 ▼                                               ▼
      ┌─────────────────────┐                         ┌─────────────────────┐
      │ Link / Text Scraper │                         │ Media Cloner & Cache│
      │ • OpenGraph title   │                         │ • Clones shared     │
      │ • Description & URL │                         │   images, videos,   │
      │ • Favicon & Cover   │                         │   PDFs to internal  │
      │ • Full text content │                         │   bookmarks storage │
      └──────────┬──────────┘                         └──────────┬──────────┘
                 │                                               │
                 └───────────────────────┬───────────────────────┘
                                         │
                                         ▼
                      ┌──────────────────────────────────────┐
                      │ SQLite / Room Bookmarks Repository   │
                      │ • Full metadata, groups, tags, notes │
                      └──────────────────┬───────────────────┘
                                         │
                                         ▼
                      ┌──────────────────────────────────────┐
                      │ BookmarkHubActivity (Mobile UI)      │
                      │ • Organize Tile on Main Menu         │
                      │ • Grid / List View Toggle            │
                      │ • Smart Auto-Groups & Custom Groups  │
                      │ • Distraction-Free Reader Mode + TTS │
                      │ • Full-Text Search, Sort & Filters   │
                      │ • Biometric Vault Lock & Export/Sync │
                      └──────────────────────────────────────┘
```

---

## 4. Detailed Functional Requirements

### FR-01: Share Target Registration ("Bookmark with UFM")
- Registered in `AndroidManifest.xml` as `za.kilowatch.ultimatefilemanager.ui.BookmarkReceiverActivity`.
- Intent filters:
  - `ACTION_SEND` and `ACTION_SEND_MULTIPLE`.
  - Categories: `DEFAULT`.
  - MIME types: `text/*` (links, post shares, copied text, notes) and `*/*` (images, videos, audio, documents, archives, APKs).
- Manifest label: `@string/share_target_bookmark` ("Bookmark with UFM").
- Displays alongside existing targets "Open In..." and "Save to...".

### FR-02: Quick Glass Save Bottom Sheet
- When triggered from an external share, launches a lightweight glass bottom sheet:
  - **Source App Header**: Automatically resolves calling package (e.g., `com.whatsapp`, `com.reddit.frontpage`, `com.google.android.youtube`, `com.android.chrome`, `org.telegram.messenger`) and displays app icon + name.
  - **Target Group Selector**: Automatically selects the corresponding auto-group (e.g., "Reddit Posts" or "WhatsApp Shares") with a single-tap dropdown to switch to any custom group or create a new group on the fly.
  - **Title & Note Field**: Editable title (defaults to shared title or URL) and optional note/tag input.
  - **Actions**: "Save Bookmark" (Primary button) and "Cancel". Auto-dismiss with confirmation toast upon saving.

### FR-03: Permanent Media & File Preservation in Private Application Sandbox
- External apps (especially WhatsApp, Telegram, or temporary download caches) frequently clean up or purge shared media.
- **Strict Private Application Directory Storage**:
  - All shared binary files (photos, videos, audio, PDFs, archives) are copied directly from the incoming `content://` stream into the app's private internal directory (`context.filesDir` / `context.noBackupFilesDir`, e.g., `files/bookmarks/media/`).
  - **No Direct User or External Access**:
    - The files are stored strictly within the app's sandboxed private storage (`/data/user/0/za.kilowatch.ultimatefilemanager/files/bookmarks/media/`).
    - Neither the user, external file explorers, MTP, nor Android MediaStore can scan, index, or browse this directory.
    - UFM's own local File Browser and Recents screens will **not** expose or list this private application directory.
    - Media and files are managed and viewed **exclusively** through the Smart Bookmark Hub UI.
  - If a user deliberately wants a file in their device's public storage, they must explicitly invoke the "Save to Storage" / "Export" action from within the Bookmark Hub card menu.

### FR-04: Smart Auto-Grouping & Custom Collections
- **Automatic Smart Groups**:
  - Automatically identifies sender app package and provisions smart groups:
    - **WhatsApp**: Incoming photos, videos, voice notes, documents, and chat links.
    - **Reddit**: Post links, image posts, comment threads.
    - **YouTube**: Video URLs, shorts, channel links.
    - **Twitter / X**: Shared posts, media links.
    - **Browser / Web**: General web URLs from Chrome, Firefox, Brave, Samsung Internet.
    - **Telegram / Messaging**: Chat media, links, forwarded documents.
    - **Files / Other**: Shared files from file pickers, cloud drives, or system apps.
- **Custom Collections / Groups**:
  - Users can create unlimited custom groups (e.g., "Research", "Recipes", "Work", "Tech Deals").
  - Customizable color accents and icons.
  - Drag-and-drop or batch move items between groups.
  - "All Bookmarks" master feed aggregating all groups.

### FR-05: Rich OpenGraph Link Previews & Metadata Scraping
- For web URLs:
  - Background asynchronous scraper fetches OpenGraph / Twitter Card metadata:
    - Webpage Title
    - Meta description snippet
    - Domain hostname and favicon
    - Primary high-resolution preview cover image
  - Caches thumbnail and metadata locally in UFM storage for instantaneous offline rendering.
- For Media & Files:
  - Images and videos display smooth local thumbnails.
  - Audio files display audio card with waveform / ID3 album art.
  - Documents (PDF, Word, Code) display rich format badges, page counts, and file sizes.

### FR-06: Distraction-Free Reader Mode & Text-to-Speech (TTS)
- Inspired by **Instapaper / Pocket**:
  - Tapping a bookmarked article gives the user the option to open the **Clean Reader View**.
  - Reader View strips ads, cookie banners, tracking scripts, navigation bars, and pop-ups, rendering clean, typography-optimized article text.
  - Reader features:
    - Adjustable font sizes and serif/sans-serif fonts.
    - Dark mode, light mode, and warm sepia themes.
    - **Text-to-Speech (TTS)**: Built-in Android TTS audio player allowing users to listen to bookmarked articles with play/pause, speed controls (1x, 1.25x, 1.5x, 2x), and skip buttons.
    - Offline reading: Entire parsed article text is stored locally so articles can be read with zero internet connection.

### FR-07: Search, Filtering & Chronological Sorting
- **Sorting Options**:
  - Date Added: Newest to Oldest (default), Oldest to Newest.
  - Title: Alphabetical A-Z, Z-A.
  - Source App: Grouped alphabetically by sender.
  - Size: Largest to Smallest (for media and file bookmarks).
- **Filtering Capabilities**:
  - Content Type Filter Chips:
    - **All**
    - **Articles & Links**
    - **Images**
    - **Videos**
    - **Audio & Voice**
    - **Documents & PDFs**
    - **Notes & Text**
  - Starred / Favorites toggle.
  - Read / Unread status filter for articles.
- **Full-Text Search**:
  - Real-time search across titles, URLs, notes, extracted descriptions, and cached article body text.

### FR-08: Biometric / Vault Lock Integration
- Users can toggle **"Lock with Vault"** on any specific bookmark group (e.g., private notes, confidential documents).
- Locked groups require device biometric authentication (Fingerprint, Face Unlock) or UFM Vault Master PIN before contents and previews become visible.
- Thumbnails for locked items are blurred or masked when locked.

### FR-09: Export, Import & File System Integration
- **Direct Save to Storage**: 1-tap action on any bookmarked file or media to copy/move it directly into any local storage folder or network share (SMB, FTP, SFTP, NFS, Cloud).
- **Standard Netscape HTML Export**: Export link collections into standard bookmarks HTML format, fully importable into Google Chrome, Firefox, Safari, Brave, or Edge.
- **Markdown & JSON Export**: Export bookmark lists as clean Markdown files (links + notes) or raw JSON backup.
- **Settings Backup Integration**: Fully registered in `SettingsBackupManager.kt` so all bookmarks, groups, and tags are preserved across device backups and restores.

### FR-10: Main Menu Tile Integration ("Organize" Section)
- Added to `StorageBrowserActivity.loadStorageVolumes()` and `buildAllKnownTiles()`.
- Categorized under `MainMenuViewModeManager.CATEGORY_ORGANIZE`.
- Custom distinct bookmark icon (`R.drawable.ic_bookmark_hub`).
- Live subtitle showing count of items and active groups (e.g., *"38 bookmarks in 5 groups"*).
- Mobile only: Excluded from Android TV menu.

---

## 5. Data Model & Database Schema

### Entity: `BookmarkItem`
- `id`: `String` (UUID primary key)
- `title`: `String`
- `url`: `String?`
- `filePath`: `String?` (Local cloned path for media/files)
- `thumbnailPath`: `String?`
- `faviconPath`: `String?`
- `contentType`: `String` (`LINK`, `IMAGE`, `VIDEO`, `AUDIO`, `DOCUMENT`, `TEXT_NOTE`)
- `sourcePackage`: `String` (e.g. `com.whatsapp`, `com.reddit.frontpage`)
- `sourceAppName`: `String` (e.g. "WhatsApp", "Reddit")
- `groupId`: `String`
- `groupName`: `String`
- `notes`: `String?`
- `cachedArticleText`: `String?` (Parsed clean text for offline reader)
- `fileSizeBytes`: `Long`
- `isStarred`: `Boolean`
- `isRead`: `Boolean`
- `isLocked`: `Boolean`
- `createdAt`: `Long` (Epoch millis)
- `updatedAt`: `Long` (Epoch millis)

### Entity: `BookmarkGroup`
- `id`: `String` (UUID primary key)
- `name`: `String`
- `iconResId`: `Int`
- `colorHex`: `String`
- `isAutoGroup`: `Boolean`
- `associatedPackage`: `String?`
- `isLocked`: `Boolean`
- `sortOrder`: `Int`
- `createdAt`: `Long`

---

## 6. Success & Verification Criteria
1. **Share Sheet**: Sharing any web link, Reddit post, WhatsApp photo/video, or PDF to "Bookmark with UFM" opens the glass save sheet, detects the source app, and saves the item without crash.
2. **Persistence & Sandbox Isolation**: Cloned media and documents are saved exclusively inside the app's internal private directory (`context.filesDir/bookmarks/media/`). They cannot be seen or indexed by external file explorers, MTP, or MediaStore, nor do they appear in UFM's general file browsing directories. Deleting the original shared photo or video in WhatsApp leaves the UFM bookmark and its preview fully playable and intact.
3. **Organize Tile**: Bookmark Hub tile appears under the "Organize" category in the mobile main menu with a live subtitle count and opens `BookmarkHubActivity`.
4. **Auto-Grouping**: Incoming items automatically route to their dedicated smart groups (WhatsApp, Reddit, Web, etc.) while allowing custom group creation.
5. **Sorting & Filtering**: Sorting from latest to oldest, oldest to latest, and filtering by content type or source app responds immediately.
6. **Reader Mode & TTS**: Articles open in distraction-free reader mode with clean text, theme controls, and working text-to-speech.
7. **Vault Protection**: Locked groups cannot be accessed or viewed without passing biometric or PIN authentication.
8. **Export**: Exporting bookmarks to HTML creates a valid Netscape Bookmarks file importable into web browsers.
