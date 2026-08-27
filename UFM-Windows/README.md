# Ultimate File Manager Pro Companion

> **Official Windows desktop companion for [Ultimate File Manager Pro (UFM)](https://github.com/Kilowatch/ultimate-file-manager-pro)** — a high-performance dual-pane file transfer, APK/XAPK sideloading, and live device management utility operating over secure local network channels.

![Platform](https://img.shields.io/badge/platform-Windows%2010%2B-blue?logo=windows)
![Tauri](https://img.shields.io/badge/built%20with-Tauri%202-24C8DB?logo=tauri)
![Rust](https://img.shields.io/badge/backend-Rust-orange?logo=rust)
![React](https://img.shields.io/badge/frontend-React%2019-61DAFB?logo=react)
![License](https://img.shields.io/badge/license-GPL--3.0-blue)

---

## Technical Overview

The Ultimate File Manager Pro Companion allows power users to establish a secure, lightning-fast connection to their Android mobile or Android TV device running UFM Pro. It provides a dual-pane browsing layout side by side with the local Windows filesystem, facilitating fluid drag-and-drop transfers, batch operations, and remote application deployment.

```
+-------------------------------------------------------------+
|               ULTIMATE FILE MANAGER PRO COMPANION           |
+------------------------------+------------------------------+
|       LOCAL FILESYSTEM       |       REMOTE FILESYSTEM      |
|     (Windows Explorer)       |     (UFM Pro Mobile / TV)    |
|   Browse C:\, D:\, Network   |   Browse /sdcard, USB OTG    |
|  --------------------------  |  --------------------------  |
|      <- Download / Upload    |      Upload / Download ->    |
+------------------------------+------------------------------+
|                     REMOTE SIDELOAD ZONE                    |
|           Drag & Drop APK / XAPK -> Remote Install          |
+-------------------------------------------------------------+
```

---

## Core Features & Architecture Enhancements

### 🎨 Premium Glassmorphic Design System
* Completely designed with a modern **Dark Glassmorphic UI** theme featuring standard CSS variables.
* Implements dynamic backdrop-filter blurs, smooth linear gradients, micro-animations, and interactive layouts.

### 🌐 Zero-Dependency Flag Dictionary & Localization
* Supports 14 major international languages (English, Arabic, German, Spanish, French, Hindi, Indonesian, Italian, Japanese, Korean, Portuguese, Russian, Turkish, and Ukrainian) with 100% localized application elements.
* Bypasses the native Windows OS flag emoji rendering limitation (which renders flags as country code letters rather than graphic flags) by embedding high-quality offline Base64 PNG assets directly inside the CSP-compliant translation layer.

### 📡 Multi-NIC Subnet LAN Auto-Discovery
* Incorporates interface-level LAN discovery using the Rust [`get_if_addrs`](https://crates.io/crates/get_if_addrs) library.
* Solves common UDP packet drop or routing problems (such as those caused by WSL, VMware, or Docker virtual switches) by broadcasting the discovery probe (`"UFM_DISCOVER:"`) directly to interface-specific subnet broadcast addresses (e.g. `192.168.1.255`), instead of relying on the widely filtered `255.255.255.255` global broadcast.

### 🔒 Self-Healing Auth & Automatic Re-pairing
* Monitors connection state continuously. If a client attempt returns a `401 Unauthorized` payload (e.g., when the PIN on the mobile or TV app has been regenerated), the companion app automatically deletes the stale token, prompts the connection modal, and launches it pre-focused on the target device's PIN entry.

### ⚡ Optimized Large File Transfer Engine
* Built with custom Rust stream buffers that prevent stack overflows and loop crashes on transfers exceeding 2GB.
* Emits live, byte-level tracking telemetry to update progress bars with correct speeds, percentages, and status alerts.

---

## Prerequisites

| Requirement | Supported Version | Details |
|---|---|---|
| **Rust Compiler** | `rustup` 1.78+ | Required to compile Tauri's native Rust backend modules |
| **Node.js** | 20 LTS (or higher) | Required to run Vite build tools and compile React code |
| **Windows SDK** | 10.0+ | Native build toolchain (usually bundled with Visual Studio C++) |
| **UFM Android App** | Latest release | Loaded on target device ([Google Play Store](https://play.google.com/store/apps/details?id=za.kilowatch.ultimatefilemanager)) |

---

## Getting Started

### 1. Clone the Workspace

```bash
git clone https://github.com/Kilowatch/ultimate-file-manager-pro.git
cd ultimate-file-manager-pro/UFM-Windows
```

### 2. Install NPM Packages

```bash
npm install
```

### 3. Run Development Build

```bash
npm run tauri dev
```
Starts the React Vite compilation under Tauri window runner with full Hot Module Replacement (HMR).

### 4. Build Production Bundle

```bash
npm run tauri build
```
Creates fully optimized, bundled installers under `src-tauri/target/release/bundle/`:
* **MSI Package:** `Ultimate File Manager Pro Companion_0.1.0_x64_en-US.msi`
* **NSIS Installer:** `Ultimate File Manager Pro Companion_0.1.0_x64-setup.exe`

---

## Setup & Pairing Guide

1. Ensure both the Windows PC and the target Android mobile/TV are connected to the same local area network (LAN).
2. Open **Ultimate File Manager Pro** on the Android device.
3. Open the main menu, select **Remote Manage**, then choose **Windows App** and follow the instructions to show the dynamic 4-digit PIN.
4. Launch the desktop Companion on Windows.
5. If auto-discovery finds the device, click the **Pair** button next to its name. If not, enter the IP address manually in the IP field.
6. Enter the 4-digit PIN shown on the device and press **Submit**.
7. The companion securely saves the device’s TLS fingerprint to verify identity on subsequent launches.

---

## Repository Directory Structure

```
UFM-Windows/
├── src/                          # TypeScript React Frontend
│   ├── App.tsx                   # Main Shell Layout & State coordinator
│   ├── App.css                   # Theme styles, glassmorphic layout components
│   ├── components/
│   │   ├── FilePane.tsx          # Directory structure explorer pane
│   │   ├── DeviceSelector.tsx    # LAN discovery & PIN credentials entry
│   │   ├── ProgressBar.tsx       # Byte-level progression panel
│   │   └── SideloadZone.tsx      # Target APK/XAPK installer drag zone
│   └── hooks/
│       ├── useUfmApi.ts          # State handlers & Tauri IPC bridge
│       └── translations.ts       # Base64 flag dictionary & localization texts
├── src-tauri/                    # Rust Tauri Desktop Backend
│   ├── src/
│   │   ├── commands.rs           # Disk I/O command implementations & TLS cert validation
│   │   ├── discovery.rs          # Interface-level UDP socket LAN scanning
│   │   ├── lib.rs                # Rust libraries & Tauri Command registries
│   │   └── main.rs               # Execution entry point
│   ├── Cargo.toml                # Rust crate definitions (get_if_addrs, reqwest, etc.)
│   ├── tauri.conf.json           # Native windows properties & CSP configs
│   └── capabilities/
│       └── default.json          # System IPC capability rules
├── package.json
└── README.md                     # Documentation file
```

---

## Security Architecture

| Boundary | Technical Design |
|---|---|
| **HTTPS Transport** | All communication runs on TLS 1.2/1.3 using rustls. Plain HTTP endpoints are strictly prohibited. |
| **Trust on First Use (TOFU)** | Certificates are verified directly using SHA-256 fingerprint hashes stored upon first pairing, eliminating CA authority dependencies. |
| **Path Sanitization** | Paths are checked for relative traversal payloads (`..`) prior to command routing in Rust to prevent unauthorized reads/writes. |
| **CSP Compliance** | Hardened Content Security Policy inside `tauri.conf.json` prevents script injection or unsanctioned network endpoints. |
| **IPC Guard** | The front-end has no raw filesystem access. All read/write operations must go through the Rust Tauri IPC handler. |

---

## Contributions

Contributions are welcome! For bugs, feature requests, and pull requests, please open an issue or pull request on the [main repository](https://github.com/Kilowatch/ultimate-file-manager-pro).

---

## License

Ultimate File Manager Pro Companion is licensed under the **GNU General Public License v3.0**. See the root [LICENSE](../LICENSE) file for complete details.