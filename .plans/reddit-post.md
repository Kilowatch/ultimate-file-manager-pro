# Reddit Post: Home Screen Shortcuts on Custom Android TV Launchers

## Title
Bug Fixed - Home Screen Shortcuts on Custom Android TV Launchers

## Platform
TV and Mobile

## Post

Good Day Community,

### What changed
I fixed an issue where creating home screen shortcuts for drives and favorite folders on Android TV was not working on custom launchers.
Until now, shortcuts on Android TV were sent exclusively to the standard Google TV and Android TV preview channels.
This meant that if you used a custom launcher such as ATV Launcher Pro, Projectivy Launcher, Monet, Wolf Launcher, or FLauncher, shortcuts would never show up on your home screen.
I reworked the shortcut system so it now detects which launcher you are using and picks the right method automatically.
On the stock Google TV launcher, favorites will continue to show up in the preview channel row as before.
On third-party custom TV launchers, the app now places shortcuts directly onto your home screen grid just like it does on mobile phones.
I also made sure artwork permissions are granted directly to your active launcher so icons and poster images render cleanly without missing graphics.
This fix is specifically for Android TV, while mobile shortcut behavior remains unchanged.

### How to use it
* Open Ultimate File Manager Pro on your Android TV.
* Navigate to the drive, storage volume, or folder you want to place on your home screen.
* Long press the item or press the menu button on your remote to open the context menu.
* Select the Add Favorite option.
* In the favorite dialog, turn on the Add to home screen switch.
* Confirm by selecting Done.
* If you use a custom launcher like ATV Launcher Pro or Projectivy, your launcher will pin the shortcut directly onto your home screen.
* If you use the stock Android TV launcher, the favorite will appear inside the UFM Favorites preview channel row.
* Selecting the shortcut on your home screen will open the app and take you directly to that drive or folder.
* To remove a shortcut later, open Manage Favorites in Settings and choose whether to remove the home screen shortcut or delete the favorite completely.
* This feature works on both Android TV and Android mobile devices.

Thank you and kind regards
