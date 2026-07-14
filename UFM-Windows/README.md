# UFM Windows Companion

> **Windows desktop companion for [Ultimate File Manager Pro](https://github.com/Kilowatch/ultimate-file-manager-pro)** — dual-pane file transfer, APK sideloading, and live device management over your local network.

![Platform](https://img.shields.io/badge/platform-Windows%2010%2B-blue?logo=windows)
![Tauri](https://img.shields.io/badge/built%20with-Tauri%202-24C8DB?logo=tauri)
![Rust](https://img.shields.io/badge/backend-Rust-orange?logo=rust)
![React](https://img.shields.io/badge/frontend-React%2019-61DAFB?logo=react)
![License](https://img.shields.io/badge/license-proprietary-red)

---

## What Is This?

UFM Windows Companion is a **native Windows desktop app** that pairs with the [Ultimate File Manager Pro](https://play.google.com/store/apps/details?id=za.kilowatch.ultimatefilemanager) Android app to give you a professional dual-pane file manager — just like WinSCP or Total Commander, but for your Android phone or TV, over Wi-Fi.

```
+--------------------------+--------------------------+
|      LOCAL (Windows)     |      REMOTE (Android)    |
|  Browse  C:\, D:\, ...   |  Browse /sdcard, USB, …  |
|  -----------------------  |  -----------------------  |
|  <- Download  Upload ->  |  <- Download  Upload ->  |
+--------------------------+--------------------------+
                     Sideload Zone
               Drop APK / XAPK -> Install
```

---

## Features

| Feature | Description |
|---|---|
| ?? **Auto-Discovery** | Finds UFM devices on your LAN via UDP broadcast — no IP needed |
| ?? **PIN Pairing** | Authenticates with a 4-digit PIN shown in the Android app |
| ?? **Dual-Pane Browser** | Navigate Windows and Android filesystems side by side |
| ?? **Upload** | Transfer files and entire folder trees to Android with real byte-level progress |
| ?? **Download** | Download files and folders from Android to any local path |
| ?? **APK Sideloading** | Drag-and-drop `.apk` or `.xapk` files onto the Sideload Zone to install remotely |
| ??? **Remote Delete** | Multi-select and delete files/folders on Android |
| ?? **Live Progress Bar** | Accurate byte-level transfer progress fed directly from the Rust backend |
| ??? **Android TV Support** | Works with both phones and Android TV devices |
| ?? **TLS Certificate Pinning** | Pins to the device self-signed cert after first pairing — MITM-resistant |

---

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| [Rust](https://rustup.rs/) | 1.78+ | Install via `rustup` |
| [Node.js](https://nodejs.org/) | 20 LTS+ | Required for the Vite/React frontend |
| [Tauri CLI v2](https://v2.tauri.app/start/prerequisites/) | 2.x | Installed via `npm install` |
| Windows SDK | 10.0+ | Usually already present on Windows 10/11 |
| UFM Android App | Latest | [Play Store](https://play.google.com/store/apps/details?id=za.kilowatch.ultimatefilemanager) |

---

## Getting Started

### 1. Clone the repository

```bash
git clone https://github.com/Kilowatch/ultimate-file-manager-pro.git
cd ultimate-file-manager-pro/UFM-Windows
```

### 2. Install JavaScript dependencies

```bash
npm install
```

### 3. Run in development mode

```bash
npm run tauri dev
```

This starts both the Vite dev server and the Tauri app with hot reload.

### 4. Build a production installer

```bash
npm run tauri build
```

The signed installer will be output to `src-tauri/target/release/bundle/`.

> **Note:** The first build downloads Rust crates and can take 5–10 minutes. Subsequent builds are much faster.

---

## Pairing with Your Android Device

1. Open **Ultimate File Manager Pro** on your Android device.
2. Go to **Menu ? Windows Companion** and note the PIN displayed.
3. Launch UFM Windows Companion on your PC.
4. Click the device icon in the toolbar ? **Search for Devices**.
5. Select your device, enter the PIN, and click **Pair**.
6. The app stores the device TLS certificate fingerprint for secure future connections.

---

## Project Structure

```
UFM-Windows/
+-- src/                        # React + TypeScript frontend
¦   +-- App.tsx                 # Main layout, window chrome, transfer logic
¦   +-- App.css                 # Design system (dark glassmorphism theme)
¦   +-- components/
¦   ¦   +-- FilePane.tsx        # Dual-pane file browser component
¦   ¦   +-- DeviceSelector.tsx  # Device discovery & PIN pairing dialog
¦   ¦   +-- ProgressBar.tsx     # Transfer progress indicator
¦   ¦   +-- SideloadZone.tsx    # APK drag-and-drop installer
¦   +-- hooks/
¦       +-- useUfmApi.ts        # All Tauri invoke calls & device state
+-- src-tauri/                  # Rust + Tauri backend
¦   +-- src/
¦   ¦   +-- commands.rs         # All Tauri commands (file ops, transfer, TLS pinning)
¦   ¦   +-- discovery.rs        # UDP LAN device discovery
¦   ¦   +-- lib.rs              # Tauri app setup & command registration
¦   ¦   +-- main.rs             # Binary entry point
¦   +-- Cargo.toml              # Rust dependencies
¦   +-- tauri.conf.json         # Tauri config, CSP, window settings
¦   +-- capabilities/
¦       +-- default.json        # Tauri IPC capability grants
+-- package.json
+-- README.md           # This file
```

---

## Security Architecture

| Layer | Implementation |
|---|---|
| **Transport** | All communication uses `https://` (TLS 1.2/1.3 via rustls) |
| **Certificate Pinning** | After first pairing, the device SHA-256 cert fingerprint is stored and verified on every subsequent connection |
| **Authentication** | Bearer token from PIN-based exchange — never passed as a URL query parameter |
| **Path Traversal Guard** | All user-supplied file paths validated for `..` components before any filesystem operation |
| **Content Security Policy** | Strict CSP in `tauri.conf.json` — blocks all external script/resource loading |
| **IPC Scope** | Capabilities limited to only window controls needed (minimize, maximize, close, drag) |
| **No Cleartext Traffic** | All device endpoints use port `8444` over HTTPS only |

---

## Architecture Notes

- **Backend (Rust):** Handles all file I/O and network operations. Transfer progress is tracked at the byte level using a custom `ProgressReader` wrapper and emitted to the frontend via Tauri events.
- **Frontend (React):** Pure UI layer. Never makes network requests directly — all HTTPS calls go through the Rust backend via Tauri `invoke()`.
- **Discovery:** UDP broadcast on port `8086`. Responses are parsed but still require PIN authentication before any data access.

---

## Building for Release

```bash
# Full production build (creates NSIS installer + MSI in src-tauri/target/release/bundle/)
npm run tauri build

# Frontend-only TypeScript/Vite check
npm run build
```

---

## Contributing

This is a companion app for **Ultimate File Manager Pro**. Contributions are welcome — please open an issue first to discuss what you would like to change.

- Follow the existing Rust/TypeScript code style
- All Tauri commands must go through the capability system
- Run `npm run build` (TypeScript check) before submitting a PR

---

## License

Proprietary — © Kilowatch. All rights reserved.
See the [main repository](https://github.com/Kilowatch/ultimate-file-manager-pro) for the full license.
