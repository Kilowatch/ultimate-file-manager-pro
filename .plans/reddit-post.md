# Reddit Post: New Feature Added - Android 17 Support and Local Network Protection

## Title
New Feature Added - Android 17 Support and Local Network Protection

## Platform
TV and Mobile

## Post

Good Day Community,

### What is new
I updated the app to target Android 17 and added full support for its new local network privacy controls.
This update ensures your local network storage, file sharing, and wireless debugging continue running smoothly on newer versions of Android.

### Why I built it
Android 17 introduces new system protections that require apps to ask for permission before accessing devices on your local Wi-Fi or Ethernet network.
I wanted to ensure everything continues working without interruptions while respecting your privacy.
Rather than nagging you with permission dialogs when you first install the app, I built a contextual flow.
The app only asks for local network permission when you actually open a local network share or connect to a local device.
I also made sure local connections to your own device remain completely exempt so local diagnostic tools and internal servers need no permissions.

### How to use it
* This feature is available on both the mobile and TV versions of the app.
* When you open a network share such as SMB or NFS for the first time on Android 17, a dialog will appear explaining why local network access is needed.
* Tap Grant to allow access, and your folder will load immediately.
* If you connect to another device using Wireless ADB or pair a remote screen, the permission prompt will appear right before the connection begins.
* If you start a local WebShare server to share files with other computers on your network, the app checks for permission before starting the server.
* If you decline the permission, an inline banner will appear at the top of your network list with a quick button to grant access whenever you are ready.
* You can also view or change your permission status at any time from the settings menu.
* Open Settings from the main menu and scroll down to the Network section.
* Select Local Network Permission to check your current status or open your system settings to manage access.
* On Android TV, the rationale dialog and settings controls are fully optimized for remote controls using standard D-pad navigation.

Thank you and kind regards
