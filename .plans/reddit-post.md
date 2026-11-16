# Reddit Post: New Feature Added - Selective Torrent Downloads and TV Transfer Controls

## Title
New Feature Added - Selective Torrent Downloads and TV Transfer Controls

## Platform
TV and Mobile

## Post

Good Day Community,

### What's new
I added a selective download inspector for torrent and magnet transfers.
I also resolved an issue on Android TV where active download progress updates interrupted remote focus.
* When you open a torrent file or magnet link, you can now preview the complete folder tree before downloading.
* You can pick exactly which files you want and exclude the rest.
* The app calculates the selected download size in real time so you only spend bandwidth and storage on what you need.
* This feature is available on both the mobile and TV versions of the app.

### How to use it
* Open the Download Manager screen in the app.
* Add a transfer by choosing a torrent file or entering a magnet link.
* For magnet links, the app will connect to the swarm to fetch the file structure first.
* Once the inspector appears, you will see the entire hierarchy of folders and files.
* All files are selected by default.
* You can tap Deselect All to clear the selection, or Select All to check everything.
* Use the search bar at the top to quickly find specific files by name.
* You can also use the category filter chips to narrow the view to videos, audio, images, archives, or documents.
* Tap any folder row to toggle that folder and all of its contents together.
* On Android TV, use the D-pad remote to move through the tree.
* Press D-pad Right to expand a folder, or press D-pad Left to collapse it.
* You can change the destination folder right from the inspector by selecting the save location card.
* When you are ready, select Start Download to begin fetching only your chosen items.
* Once transfers are running, you can click or press OK on any active transfer to open its action dialog.
* From that dialog, you can pause or resume the download, jump directly to its folder in the file browser, or remove it.

### What changed
* On Android TV, active downloads previously caused the remote focus to jump back to the top toolbar every second.
* That happened because progress updates were refreshing the entire list on every tick.
* I updated the list so it refreshes progress values in place without disturbing the focused item.
* Your remote focus now stays right where you leave it while downloads are running.
* I also fixed an issue where selecting Open Download Folder would take you to the root of storage instead of the actual download location.
* It now takes you straight to the folder where your files are being saved.

Thank you and kind regards
