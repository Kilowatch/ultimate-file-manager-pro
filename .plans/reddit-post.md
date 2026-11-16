# Reddit Post: New Feature Added - Smart Bookmark Hub with Distraction-Free Reader, Dynamic Auto-Grouping & Offline Media Preservation

## Title
New Feature Added - Smart Bookmark Hub with Distraction-Free Reader, Dynamic Auto-Grouping & Offline Media Preservation

## Platform
Mobile Only

## Post

Good Day Community,

### What's new
I added a full-featured **Smart Bookmark Hub** to Ultimate File Manager Pro on mobile devices.
It functions as a central repository for links, articles, media, files, and personal notes shared from any app on your device (WhatsApp, Reddit, YouTube, Chrome, Telegram, Twitter/X, and more).

* **New Share Target ("Bookmark with UFM")**: Whenever you share content on Android, you will now see **"Bookmark with UFM"** alongside UFM's existing "Open In..." and "Save to..." options.
* **Dynamic Smart Collections & Auto-Cleanup**: The hub starts completely clean with only an "All" collection. Smart groups (e.g., *YouTube*, *Reddit*, *WhatsApp*, *X*, or website domain names) are dynamically created on the fly as you bookmark items. If all items in a dynamic group are deleted or moved to a custom collection, that group automatically cleans itself up so your hub never accumulates empty folders. You can also tap the **"+"** button at any time to create your own permanent custom collections.
* **Biometric Vault Protection**: You can lock sensitive bookmark collections behind fingerprint, face, or lock-screen PIN/pattern/password authentication using UFM's Vault. Locked collections display a distinctive `🔒` badge and cannot be viewed without biometric verification. You can lock collections upon creation or long-press any existing custom collection to toggle Vault protection on the fly.
* **Strict Private Sandbox & Media Isolation**: When you share photos, videos, voice clips, or PDFs (from WhatsApp, Telegram, etc.), UFM automatically clones a physical copy into the application's internal private sandbox (`context.filesDir/bookmarks/media/`). These files are 100% hidden from external galleries, third-party file managers, MTP USB transfers, and the Android MediaStore. They are also intentionally excluded from UFM's general file browsing lists so private bookmarks are never exposed during routine file browsing.
* **Intent & History Lifecycle Security**: Share targets run in detached, ephemeral tasks (`excludeFromRecents`, `noHistory`, `finishOnTaskLaunch`). Both the bookmark and file share receivers are hardened against `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY` replay and saved state recreation, ensuring sensitive shared URLs, text, or media never linger in memory or inadvertently pop back up when opening UFM from the home launcher.
* **SSRF-Protected Scraper & Clean Reader Mode**: The built-in metadata scraper features Server-Side Request Forgery (SSRF) defense, blocking attempts to probe loopback (`127.0.0.1`), private LANs (`192.168.x.x`, `10.x.x.x`, `172.16-31.x.x`), and link-local addresses. In Reader Mode, all executable scripts, forms, frames, and tracking elements are stripped for a safe, tracker-free reading experience.
* **Sort & Group by Collection (A–Z / Z–A)**: In addition to chronological and alphabetical sorting, you can choose **"Group by Collection"** with sorting from **A to Z** or **Z to A**. Your bookmarks are displayed neatly grouped beneath dedicated section headers in both list and 2-column grid views.
* **Smart YouTube & Media Detection**: Sharing YouTube clips and music videos automatically resolves the actual song/video title, artist/band name, and high-resolution thumbnail preview instead of displaying a raw URL link.
* **Personal Notes on Cards & Full Backup**: Any personal notes you add when saving are clearly visible on the cards in both list and grid views. When you export your bookmarks to Netscape HTML or Markdown, all your personal notes are preserved and fully importable into desktop browsers (Chrome, Firefox, Safari).
* **Rich Link Previews & Distraction-Free Reader Mode**: Web links and Reddit posts scrape titles, descriptions, favicons, cover images, full post bodies, and top discussion comments. Articles can be opened in a clean, clutter-free reader view featuring Light, Sepia, and Dark themes, adjustable text sizing, one-tap star toggling, and a built-in Android Text-to-Speech (TTS) engine with speed controls (0.75x–2.0x).
* **UFMStandard Glass Design**: The hub features UFM's signature glassmorphism interface, consolidated 3-dot toolbar menu (`⋮`), custom glass card overflow popups, squircle thumbnails with corner app badges, and empty collection management.

### How to use it
1. **Sharing Content**:
   * In any app (such as YouTube, Reddit, WhatsApp, or your browser), tap **Share** and select **"Bookmark with UFM"**.
   * A glass bottom sheet will slide up displaying the detected sender app, preview, and auto-selected group.
   * For YouTube and articles, the video/song title or headline is automatically detected. Edit the title or add personal notes if you wish, then tap **Save Bookmark**.
   * If you share an item you have already bookmarked, UFM will detect the duplicate, update the timestamp to bump it to the top of your feed, and append any new notes.

2. **Accessing the Bookmark Hub**:
   * Open UFM on your phone or tablet.
   * On the Main Menu, scroll to the **"Organize"** category and tap the **"Smart Bookmarks"** tile.
   * Tap the **3-dot menu** (`⋮`) in the top right to change view mode between List and 2-column Grid (your preference is automatically remembered), sort, or export.
   * Use the group chips at the top to filter by platform (e.g., view only YouTube videos, Reddit posts, or WhatsApp media), or tap **"+"** to create custom collections with optional **Biometric Vault Lock**.
   * Long-press any custom collection chip to lock/unlock it with fingerprint or PIN, or to delete it. If a custom collection is empty, a quick **"Delete Collection"** button also appears at the bottom.

3. **Reading & Listening**:
   * Tapping any saved article opens the **Distraction-Free Reader Mode** (including full Reddit post text and top comments).
   * Tap the **Listen Aloud (Play)** button in the bottom audio bar to have your phone read the article to you while you commute or multitask.
   * Adjust reading speed with the compact speed pill button (1.0x, 1.25x, 1.5x, 2.0x).
   * Star or de-star the article directly from the top-right toolbar.
   * Tap "Open in Browser" at the top whenever you want to visit the original live page.

4. **Managing & Exporting**:
   * Tap the 3-dot menu (`⋮`) on any card to open the UFMStandard glass menu: read, open in browser, copy links/text, move collections, view full notes, or save files to your device's Downloads folder.
   * Deleting an item removes it immediately with a 5-second **Undo Snackbar** before local files are cleared.
   * Long-press any card to enter multi-select mode to batch move, batch delete, or export your bookmarks (including all personal notes) to Netscape HTML or Markdown.

Thank you and kind regards
