# Reddit Post: WebDAV & Network Favorites Fix, Protocol Badging, and Shortcut UX

## Title
Update - WebDAV & Network Favorites Resolution, Protocol Badging, and Home Shortcut UX

## Platform
Mobile and TV

## Post

Good Day Community,

### What changed
I addressed a set of issues related to WebDAV and remote network storage favorites, along with introducing visual protocol badging and clearer shortcut management:

* **Fixed Remote WebDAV and Network Favorite Opening:**
  Fixed an issue where opening in-app favorites or home screen shortcuts pointing to WebDAV folders or files (such as those hosted via rclone, Nextcloud, or ownCloud) would stall on "Opening..." or bounce back to the main Storage screen with a *"Previous storage location is unavailable"* error. The browser resolution engine now accurately detects and falls back between network and online storage repositories and passes complete provider metadata.
* **Connection Protocol Badging:**
  When managing multiple servers with similar folder structures (e.g. "Movies", "Documents", or "Backups"), it was previously difficult to tell which favorite belonged to which protocol or server. Favorites on the Storage screen and in Manage Favorites now display a clear `{Protocol} • {Type}` badge (e.g., `WebDAV • Folder`, `WebDAV • File`, `SMB • Folder`, `Local • Folder`).
* **Home Screen Shortcut Badging:**
  Pinned home screen shortcut icons now display a compact, high-contrast protocol badge pill (such as `WDAV`, `SMB`, `NFS`, `FTP`) on the bottom quadrant of the icon, making remote server shortcuts immediately identifiable on your launcher.
* **Clearer Home Screen Shortcut Removal UX:**
  Due to Android OS security restrictions, third-party apps cannot programmatically delete pinned shortcuts from your home screen launcher; Android only allows apps to disable them. When removing a shortcut from inside UFM, a new dialog now clearly explains this so you know the shortcut is disabled and can be dragged to "Remove" on your home screen grid.

### How to use it
* Open **Ultimate File Manager Pro** on your mobile device or Android TV.
* Navigate to any remote storage location (WebDAV, SMB, NFS, FTP, SFTP, or Cloud storage).
* Long-press any folder or file and choose **Add Favorite**.
* If you want a launcher icon on your home screen, toggle **Add to home screen** on.
* Notice the clean protocol badge pill (`WDAV`, `SMB`, etc.) on your home screen icon and the `{Protocol} • {Type}` subtitle in your in-app favorites list.
* Tapping the favorite or home screen shortcut will now reliably open the remote folder or launch the file directly without redirection errors.
* To remove a shortcut, open **Manage Favorites** in Settings and tap the shortcut toggle; the app will explain the launcher behavior and disable the shortcut safely.

Thank you and kind regards
