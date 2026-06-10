# Ultimate File Manager Pro (FOSS Edition)

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![F-Droid](https://img.shields.io/f-droid/v/za.kilowatch.ultimatefilemanager.foss)](https://f-droid.org/packages/za.kilowatch.ultimatefilemanager.foss)
[![Get it on Google Play](https://img.shields.io/badge/Google%20Play-Ultimate%20File%20Manager%20Pro-green.svg?logo=google-play&logoColor=white)](https://play.google.com/store/apps/details?id=za.kilowatch.ultimatefilemanager)

Ultimate File Manager Pro (UFM) is a powerful, dual-pane file manager for Android and Android TV, built for power users.

This repository contains the **Free and Open Source Software (FOSS) edition** of UFM. 

---

## ⚠️ Important: FOSS Edition Differences

To comply with open-source guidelines and maintain user privacy, the FOSS build is completely free of proprietary binaries and libraries. Please note the following differences between the Google Play/Amazon store versions and this FOSS build:

* **No Proprietary Cloud Storages**: Support for Google Drive, Microsoft OneDrive, and Dropbox has been removed, as they rely on proprietary closed-source SDKs and OAuth redirection schemes. You can still use fully open and self-hosted storage alternatives such as **WebDAV, SFTP, FTP, SMB, and AWS S3**.
* **No Closed-Source Trackers**: Firebase Analytics, Firebase Crashlytics, and GMS trackers are completely stripped out.
* **No Store Integration**: Google Play Billing (In-App Purchases) and Google Play review prompts have been disabled. 
* **Sponsorship / Donation Screen**: The Tip Jar in the FOSS build links directly to open donation web interfaces (Ko-fi and GitHub Sponsors) instead of using store billing services.

If you require Google Drive, OneDrive, or Dropbox integrations, please download the official version on [Google Play](https://play.google.com/store/apps/details?id=za.kilowatch.ultimatefilemanager).

---

## 📦 Download Pre-compiled APKs

You can download the two pre-compiled production APKs directly from the [Releases](https://github.com/Kilowatch/ultimate-file-manager-pro/releases) page:
1. **Mobile Build (`app-mobile-foss-release.apk`)**: Optimized for phones and tablets.
2. **TV Build (`app-tv-foss-release.apk`)**: Optimized for Android TV / Fire TV with full D-pad remote controller support.

---

## 🛠️ Build Instructions

For developer compilation and testing:

### Debug vs. Production (Release) Builds
* **Debug (`debug`)**: Use only for local development and debugging. It runs slower, does not include code shrinking/optimization, and uses a temporary debug signature.
* **Production (`release`)**: **Must** be used for sharing, distribution, and publishing. Production builds are fully optimized with R8/Proguard code shrinking (reducing size and improving security) and are signed with your secure release keys.

### Build Commands

To build the FOSS edition via Gradle, select the FOSS build variants in Android Studio, or run the following terminal commands:

#### For Local Debugging:
* **Mobile (Debug)**:
  ```bash
  ./gradlew assembleMobileFossDebug
  ```
  *Output APK: `app/build/outputs/apk/mobileFoss/debug/app-mobile-foss-debug.apk`*
* **TV (Debug)**:
  ```bash
  ./gradlew assembleTvFossDebug
  ```
  *Output APK: `app/build/outputs/apk/tvFoss/debug/app-tv-foss-debug.apk`*

#### For Production Release:
* **Mobile (Release)**:
  ```bash
  ./gradlew assembleMobileFossRelease
  ```
  *Output APK: `app/build/outputs/apk/mobileFoss/release/app-mobile-foss-release-unsigned.apk`*
* **TV (Release)**:
  ```bash
  ./gradlew assembleTvFossRelease
  ```
  *Output APK: `app/build/outputs/apk/tvFoss/release/app-tv-foss-release-unsigned.apk`*

*Note: For release builds, you will need to sign the output unsigned APKs using `apksigner` or configure signing configs in `app/build.gradle.kts` using your custom release keys.*

---

## 📄 License
Licensed under the GNU General Public License v3.0. See [LICENSE](LICENSE) for details.
