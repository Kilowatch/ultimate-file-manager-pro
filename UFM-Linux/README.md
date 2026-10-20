# Ultimate File Manager Pro Companion (Linux)

> **Official Linux desktop companion for [Ultimate File Manager Pro (UFM)](https://github.com/Kilowatch/ultimate-file-manager-pro)** — a high-performance dual-pane file manager, remote APK/XAPK sideloading tool, and device management utility designed Wayland-first for modern Linux desktops and tiling window managers.

![Platform](https://img.shields.io/badge/platform-Linux%20(Wayland%20%2F%20X11)-orange?logo=linux)
![Packaging](https://img.shields.io/badge/package-AppImage%20%7C%20Deb%20%7C%20RPM%20%7C%20AUR-blue)
[![AUR](https://img.shields.io/aur/version/ufm-companion-bin?label=AUR%20ufm-companion-bin&logo=archlinux)](https://aur.archlinux.org/packages/ufm-companion-bin)
![Tauri](https://img.shields.io/badge/built%20with-Tauri%202-24C8DB?logo=tauri)
![Rust](https://img.shields.io/badge/backend-Rust-orange?logo=rust)
![React](https://img.shields.io/badge/frontend-React%2019-61DAFB?logo=react)
![License](https://img.shields.io/badge/license-GPL--3.0-blue)

---

## Technical Overview

The Ultimate File Manager Pro Linux Companion allows power users to establish a secure, lightning-fast connection to their Android phone or Android TV running UFM Pro over local Wi-Fi.

```
+-------------------------------------------------------------+
|             ULTIMATE FILE MANAGER PRO LINUX COMPANION       |
+------------------------------+------------------------------+
|     LOCAL POSIX FILESYSTEM   |       REMOTE FILESYSTEM      |
|    Browse /, $HOME, /media   |     Browse /sdcard, USB OTG    |
|  --------------------------  |  --------------------------  |
|      <- Download / Upload    |      Upload / Download ->    |
+------------------------------+------------------------------+
|                     REMOTE SIDELOAD ZONE                    |
|           Drag & Drop APK / XAPK -> Remote Install          |
+-------------------------------------------------------------+
```

---

## Wayland-First Features

### 🪟 Tiling Compositor & Window Rule Friendly
- **Stable `app_id`**: Configured as `za.kilowatch.ufm-companion` for seamless window rule targeting:
  - **Hyprland**: `windowrule = float, class:^(za\.kilowatch\.ufm-companion)$`
  - **Sway**: `for_window [app_id="za.kilowatch.ufm-companion"] floating enable`
  - **Niri**: `window-rule { match app-id="za.kilowatch.ufm-companion"; default-column-width {}; }`
- **Native Window Decorations**: Integrates cleanly with server-side decorations (SSD) without redundant custom titlebar chrome.

### 📁 Linux Desktop Portal Integration
- Native file picking and external file openers integrate with `xdg-desktop-portal` via DBus.
- Safe path canonicalization and directory navigation across root `/`, `$HOME`, `/media`, and `/mnt`.

### ⚡ Native Rust Backend Engine
- High-performance native Rust engine delivering multi-NIC UDP broadcast LAN discovery (`UFM_DISCOVER:`), Trust-On-First-Use (TOFU) SHA-256 certificate fingerprint pinning, and chunked streaming file transfers.

---

## Installation

> ⚡ **One-line install** (Linux auto-picks `.deb` / `.rpm` / AUR / AppImage):
> ```bash
> curl -fsSL https://raw.githubusercontent.com/Kilowatch/ultimate-file-manager-pro/main/UFM-Linux/scripts/install.sh | sh
> ```
> Options: `sh -s -- --tag v2.1.8 --yes`, `--method deb|rpm|aur|appimage|source`,
> `--system` (AppImage → `/opt`), `--uninstall`, `--dry-run`, `--help`.
> No prebuilt FreeBSD binary exists — on FreeBSD the same script performs an
> experimental native source build (see below).

Pre-built Linux binaries are published on every
[GitHub Release](https://github.com/Kilowatch/ultimate-file-manager-pro/releases/latest):

| File | Target | Install |
|---|---|---|
| `ufm-companion-x86_64.AppImage` | Universal (Wayland-first, no install) | `chmod +x ufm-companion-x86_64.AppImage && ./ufm-companion-x86_64.AppImage` |
| `ufm-companion_amd64.deb` | Debian / Ubuntu | `sudo apt install ./ufm-companion_amd64.deb` |
| `ufm-companion-x86_64.rpm` | Fedora / RHEL / openSUSE | `sudo dnf install ./ufm-companion-x86_64.rpm` (openSUSE: `sudo zypper install ./ufm-companion-x86_64.rpm`) |

### Arch Linux (AUR)

The AUR package **`ufm-companion-bin`** wraps the official AppImage
(installs to `/opt/ufm-companion`, symlinks `/usr/bin/ufm-companion`, ships a
tiling-WM friendly desktop entry):

```bash
yay -S ufm-companion-bin
# or: paru -S ufm-companion-bin
# manual:
git clone https://aur.archlinux.org/ufm-companion-bin.git
cd ufm-companion-bin
makepkg -si
```

- AUR page: https://aur.archlinux.org/packages/ufm-companion-bin
- Packaging sources in this repo: [`packaging/aur/`](packaging/aur/) (see its
  `README.md` for bumping `pkgver` / refreshing `sha256sums` / republishing).

### FreeBSD (experimental — native source build)

There is no prebuilt FreeBSD binary. The installer script detects FreeBSD and
builds natively (all required libs exist in ports: `webkit2-gtk_41`,
`gtk3`, `libsoup3`, `librsvg2-rust`, `libappindicator`, plus `tauri-cli`):

```sh
curl -fsSL https://raw.githubusercontent.com/Kilowatch/ultimate-file-manager-pro/main/UFM-Linux/scripts/install.sh | sh
```

This installs build deps via `pkg(8)` (needs root), compiles the frontend +
Rust backend (`cargo build --release`), and installs
`/usr/local/bin/ufm-companion` with a desktop entry. First build takes several
minutes. Uninstall with `sh install.sh --uninstall`.

---

## Prerequisites

| Requirement | Supported Version | Details |
|---|---|---|
| **Rust** | 1.78+ (`rustup`) | Compiles Tauri native Rust backend modules |
| **Node.js** | 20 LTS+ | Runs Vite dev server and bundles React frontend |
| **System Libraries** | WebKitGTK 4.1 | `libwebkit2gtk-4.1-dev`, `libayatana-appindicator3-dev`, `librsvg2-dev` |

### Installing Linux Build Dependencies (Debian / Ubuntu / WSL)
```bash
sudo apt update
sudo apt install -y \
  libwebkit2gtk-4.1-dev \
  build-essential \
  curl \
  wget \
  file \
  libxdo-dev \
  libssl-dev \
  libayatana-appindicator3-dev \
  librsvg2-dev
```

### Installing Linux Build Dependencies (Fedora / RHEL — for RPM builds)
```bash
sudo dnf install -y \
  webkit2gtk4.1-devel \
  libayatana-appindicator-gtk3-devel \
  librsvg2-devel \
  openssl-devel \
  curl wget file \
  gcc gcc-c++ make patchelf
```

### Runtime Dependencies (Arch — covered automatically by the AUR package)
`webkit2gtk-4.1`, `gtk3`, `libayatana-appindicator`, `librsvg`, `libsoup3`,
`cairo`, `gdk-pixbuf2`, `glib2`, `pango`, `openssl` — see
[`packaging/aur/PKGBUILD`](packaging/aur/PKGBUILD).

---

## Building Locally

### 1. Install Node Dependencies
```bash
cd UFM-Linux
npm install
```

### 2. Run in Development Mode
```bash
npm run tauri dev
```

### 3. Build Production AppImage, Deb & RPM
```bash
npm run tauri build -- --bundles appimage,deb,rpm
# or via npm scripts:
npm run build:linux
```
Bundles are produced in:
- `src-tauri/target/release/bundle/appimage/` (`.AppImage`)
- `src-tauri/target/release/bundle/deb/` (`.deb` for Debian/Ubuntu)
- `src-tauri/target/release/bundle/rpm/` (`.rpm` for Fedora/RHEL/openSUSE)

> RPM metadata (`depends`, `release`, `epoch`) is configured in
> `src-tauri/tauri.conf.json` → `bundle.linux.rpm`:
> `webkit2gtk4.1`, `gtk3`, `libayatana-appindicator-gtk3`, `librsvg2`.
> Release assets are renamed by CI to `ufm-companion-x86_64.rpm`.
> See `.github/workflows/linux-companion.yml`.

---

## Testing in WSL

If you are developing inside Windows Subsystem for Linux (WSL) without a physical Linux machine:

1. **Unit & Protocol Tests (CLI):**
   ```bash
   cargo test --manifest-path src-tauri/Cargo.toml
   ```

2. **AppImage Extraction Test (FUSE Bypass):**
   ```bash
   ./src-tauri/target/release/bundle/appimage/*.AppImage --appimage-extract
   ./squashfs-root/AppRun --version
   ```

3. **Wayland Display Execution (WSLg):**
   ```bash
   GDK_BACKEND=wayland ./squashfs-root/AppRun
   ```

> **Important:** always test a **production** build (`npm run tauri build`). A Tauri *dev*
> build embeds no frontend assets and loads the Vite dev server at
> `http://localhost:1420` instead — running such an `AppRun` without `npm run dev`
> active shows a blank window ("Could not connect to localhost: Connection refused").
> Run the verification step after every release build to confirm the frontend bundle
> is embedded:
> ```bash
> npm run tauri build -- --bundles appimage,deb,rpm
> npm run verify:appimage
> ```

---

## License

Ultimate File Manager Pro Linux Companion is licensed under the **GNU General Public License v3.0**. See the root [LICENSE](../LICENSE) file for complete details.
