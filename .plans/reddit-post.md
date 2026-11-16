# Reddit Post: Picture-in-picture with custom launchers

## Title
Bug Fixed - Picture-in-picture with custom launchers

## Platform
Mobile only

## Post

Good Day Community,

### What's new
I fixed a bug that prevented Picture-in-picture mode from working with custom launchers.

### What changed
* If you use a third-party home screen launcher like One4Home, Nova, or Lawnchair, returning home while playing a video will now enter Picture-in-picture automatically.
* Previously this only worked with stock launchers like Samsung OneUI.
* The video player now hooks directly into modern Android navigation gestures.
* It no longer relies on the launcher to tell the app that you went home.
* The floating window now matches the real dimensions of your video instead of forcing a fixed shape.
* The transition from full screen to the floating window is much smoother.
* If you pause playback before leaving, the app will minimize normally without opening an unwanted window.
* In a mixed playlist, advancing to an audio track closes the floating window and keeps playing your audio seamlessly in the background.
* This fix is for the mobile version of the app.

Thank you and kind regards
