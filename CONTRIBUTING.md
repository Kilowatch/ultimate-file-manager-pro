# Contributing to Ultimate File Manager Pro

First off — thank you for taking the time to contribute! 🎉

Ultimate File Manager Pro (UFM) is a dual-pane file manager for **Android Mobile**, **Android TV / Fire TV**, and **Windows PC**. This repository is the **Free and Open Source Software (FOSS)** edition, licensed under the **GNU GPL v3.0**.

This guide explains how to set up the project, branch, build, and open a pull request. Please read it before contributing.

---

## 🧑‍💻 Development Setup

### Prerequisites

| Tool | Version | Notes |
|---|---|---|
| **JDK** | **17 or newer** | Required to run the Gradle 9.x wrapper. The app compiles to Java 11 bytecode. |
| **Android Studio** | Latest stable | Recommended IDE. |
| **Android SDK** | `compileSdk 36`, `minSdk 26` | Install `platforms;android-36` + current `build-tools`. |
| **NDK & CMake** | NDK `28.2.13676358`, CMake `3.31.6` | Used by the native `libnfs` build. AGP auto-installs the pinned versions once SDK licenses are accepted. |

> **Important:** Always use the Gradle wrapper — `./gradlew` (Windows: `.\gradlew.bat`). Never use a system-installed Gradle; the wrapper version is the only supported one.

### First-time setup

```bash
# Clone the repo
git clone https://github.com/Kilowatch/ultimate-file-manager-pro.git
cd ultimate-file-manager-pro

# Accept Android SDK licenses (required for AGP to auto-install SDK/NDK/CMake components)
# then run a first build to confirm everything is set up:
./gradlew assembleMobileFossDebug
```

---

## 🌿 Branching

Use short, descriptive branch names with a `/` prefix. Work on your own fork or a feature branch:

- `feature/<what>` — new features, e.g. `feature/webdav-bookmarks`
- `fix/<what>` — bug fixes, e.g. `fix/tv-focus-jump`

Keep branches focused on a single change. Open pull requests against `main`.

---

## 🛠️ Building

UFM uses a two-dimension flavor model (`device` × `store`). The **FOSS** variants are what this repo builds:

```bash
# Debug builds (for local development)
./gradlew assembleMobileFossDebug     # Android Mobile
./gradlew assembleTvFossDebug         # Android TV / Fire TV

# Release builds (unsigned — signing is configured separately for distribution)
./gradlew assembleMobileFossRelease
./gradlew assembleTvFossRelease
```

> `assembleMobileGoogleDebug` etc. target the **proprietary** store build, which includes closed-source SDKs and signing configuration that are **not** part of this repo. Build the `Foss` variants unless you are specifically working on store-only code.

Debug APKs are output under `app/build/outputs/apk/<device>/foss/debug/`.

There is also a **Windows companion** app (Rust + Tauri) under [`UFM-Windows/`](UFM-Windows/README.md) with its own build steps.

---

## 📝 Commit Guidelines

Keep it simple and human-readable — this is a small project, so plain descriptive messages are preferred:

- Use the imperative or short descriptive style, e.g. `Fix TV focus jump on grid view`.
- One logical change per commit.
- If a change is user-facing, note it in `CHANGELOG.md` under the current version header (or flag it in the PR so the maintainer can).
- Optional: prefix commits with a Conventional-Commits tag (`feat:`, `fix:`, `docs:`, `chore:`) — helpful but not required.

Example:
```
fix: prevent scroll jumping to top when navigating back on Mobile and TV
```

---

## ✅ Submitting a Pull Request

Before opening a PR, make sure:

- [ ] Your branch builds locally: `./gradlew assembleMobileFossDebug assembleTvFossDebug`
- [ ] You made **no unrelated changes** (no whitespace churn, no bundled dependency bumps)
- [ ] `CHANGELOG.md` reflects any user-facing change (or you noted it for the maintainer)
- [ ] The **FOSS constraints** are respected — see below
- [ ] If UI-related, include screenshots in the PR description

Use the [pull request template](.github/PULL_REQUEST_TEMPLATE.md) — it lists everything needed.

---

## 🔒 FOSS Constraints

This branch is the **FOSS edition** and must stay free of closed-source components. Do **not** reintroduce:

- Proprietary cloud SDKs (**Google Drive, OneDrive, Dropbox**)
- **Google Firebase / Crashlytics / analytics**
- **Google Play Billing** or Amazon equivalent

For why these are removed and what the FOSS build includes instead, see the [FOSS Edition vs. Store Edition](README.md#-foss-edition-vs-store-edition) section in the README.

---

## 💬 Getting Help

- **Questions / support** — prefer the [XDA forum thread](https://xdaforums.com/t/app-free-ultimate-file-manager-pro-dual-pane-android-mobile-android-tv-foss-edition-available.4791958/) or [r/UFManagerPro](https://www.reddit.com/r/UFManagerPro/) over issues, so bug reports stay actionable.
- **Bugs / feature requests** — open an issue using the provided templates (see [`.github/ISSUE_TEMPLATE/`](.github/ISSUE_TEMPLATE/)).
- Read our [Code of Conduct](CODE_OF_CONDUCT.md) before participating.

---

## 📄 License

By contributing, you agree that your contributions are licensed under the [GNU GPL v3.0](LICENSE).
