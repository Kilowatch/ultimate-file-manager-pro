# Reddit Post: New Features Added - Anonymous Loyalty Pass, Claim Past Tips, Custom App Icon & Milestone Badges

## Title
New Features Added - Anonymous Loyalty Pass, Claim Past Tips, Custom App Icon & Milestone Badges

## Platform
Mobile only (Google Play)

## Post

Good Day Community,

### What is new
I have released a major update to the **Fuel the Developer** supporter loyalty system in Ultimate File Manager:

1. **Anonymous Loyalty Pass (Privacy-First Sync)**:
   * Generate an Anonymous Loyalty Pass with a collision-proof 12-character Loyalty ID (`XXX-XXX-XXX-XXX`) and a secure 16-character recovery Passkey (`AAAA-BBBB-CCCC-DDDD`).
   * **Absolute Zero Personal Data**: No name, email, Google account, phone number, or device identifier is ever requested, tracked, or stored.
   * Enables seamless restoration of your points, unlocked badges, and customization across multiple devices or after a clean reinstall.

2. **Claiming Past Tips via Google Play Order ID (GPA)**:
   * If you contributed to the Tip Jar in the past (or on another device), you can now claim those tips directly to your Anonymous Loyalty Pass using your Google Play Order ID (`GPA.XXXX-XXXX-XXXX-XXXXX`) found in your Google Play receipt email.
   * Effortless numeric-only keypad entry with automatic hyphenation and fixed `GPA.` prefix.
   * Real-time server-side verification instantly updates your authoritative points total ($1 contributed = 10 loyalty points).

3. **Custom App Icon (Personal Photo / Image)**:
   * Select any photo, artwork, or image from your device storage using UFM's built-in file browser to create a personalized home screen shortcut icon!
   * Unlocks with just 10 points ($1 Tip Jar tier).
   * Automatically crops, squares, and scales to 512×512, saving a local copy internally so it stays permanent even if you delete or move the original photo.

4. **Milestone Badges (10 Supporter Tiers)**:
   * Ten custom-designed milestone badge tiers (from 5 points up to 1,000 points) that transform your app launcher icon, plus an option to restore the original app icon anytime.

5. **Dynamic In-App Branding**:
   * Applying either a Custom Icon or a Milestone Badge automatically updates the in-app logos on the main dashboard toolbar and the Edge Swipe Menu in real time!

---

### Why I built it
Many of you have generously supported the continued development of Ultimate File Manager through the Tip Jar over the months and years.

I wanted to give back with two big improvements:
* **Tangible, Rewarding Customization**: Giving you complete creative control over how UFM looks on your home screen and inside the app, whether through your own personal photos or milestone emblems.
* **Fair, Privacy-Respecting Continuity**: If you get a new phone, reinstall the app, or contributed tips in the past, your support shouldn't vanish. Most apps solve this by forcing you to sign up with an email or social account. I firmly believe in privacy, so I built a completely anonymous cryptographic pass system: you get a private Loyalty ID and secret Passkey that belong entirely to you, with zero personal tracking.

---

### How to use it

* This feature is available on the mobile version of the app on Google Play.
* Open the main menu and tap **Fuel the Developer** to open the supporter loyalty screen.

#### 1. Anonymous Loyalty Pass & Claiming Past Tips
* **Active Pass Card**: Located right on the loyalty screen, displaying your active Loyalty ID and authoritative synced points total.
* **Claiming a Past Tip**:
  * Tap **Claim Past Tip**.
  * The dialog opens with a numeric keyboard and fixed `GPA.` prefix.
  * Enter the 17 numbers from your Google Play receipt email (the app automatically inserts hyphens as you type).
  * Tap **Verify & Claim** — the order is verified in real time, and its points are instantly added to your pass!
  * If you do not have an Anonymous Loyalty Pass yet, the app prompts you to generate one in one tap or link to an existing pass.
* **Viewing Your Secret Passkey**:
  * Tap **View Passkey** to reveal your 16-character recovery Passkey and your Loyalty ID.
  * You can copy them to your clipboard or keep them in a safe place for restoring your pass on a new device.
* **Restoring an Existing Pass on a New Device**:
  * Tap **Switch / Restore Pass** (or **Restore Loyalty Pass** if no pass is active).
  * Enter your 12-character Loyalty ID and 16-character Passkey.
  * Your points, claimed orders, and unlocked customizations are immediately restored.

#### 2. Creating a Custom App Icon (10 Points)
* Locate the **Custom App Icon** section right above the milestone badges.
* Tap **Choose Image** to open the UFM file browser and select any supported image file (PNG, JPG, WebP, GIF, BMP, HEIC).
* An interactive preview dialog appears showing your icon with rounded launcher corners.
* Tap **Apply & Close** to update your in-app logos and pin the new custom icon as a shortcut on your home screen!
* *Note on Android Platform Architecture*: Due to Android OS security architecture, only pre-compiled icons bundled inside the APK can replace the app's primary launcher icon in-place (which is how the Milestone Badges work). When using your own personal photo or image from storage, Android requires it to be added as a home screen shortcut rather than replacing the system app drawer icon, and Android places a small app logo badge in the corner of the shortcut for identity verification. Inside UFM itself, your custom photo seamlessly updates the toolbar and Edge Swipe Menu branding directly!

#### 3. Using Milestone Badges (5 to 1,000 Points)
* Below the Custom Icon section, you will find the original app icon followed by ten milestone badge tiers.
* Tapping any unlocked badge opens a preview dialog displaying the custom emblem.
* Tap **Apply & Close** to set that badge as your launcher icon. Ultimate File Manager will close so your Android launcher can register the new icon.
* If you tap a locked tier, a dialog shows you a full preview of the badge and tells you how many points are needed to unlock it.
* Tapping **Fuel to Unlock** smoothly scrolls you back to the coffee tip options.
* To revert back to the original icon at any time, tap **Default App Icon** at the top of the badges list and confirm.

Thank you and kind regards
