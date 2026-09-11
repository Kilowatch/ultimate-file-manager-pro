# 📦 Understanding the Size of Ultimate File Manager Pro: APK Download (~240 MB) & Storage Footprint

When you download the FOSS release of Ultimate File Manager Pro from GitHub or an open-source repository, you will notice that the universal `.apk` file is **~240 MB**.

When installed on your device (**Settings > Apps > Ultimate File Manager Pro > Storage**), device storage typically uses between **100 MB and 185 MB**.

This document explains in detail:
1. **Why the universal FOSS APK download is ~240 MB.**
2. **The exact byte breakdown across CPU architectures and native engines.**
3. **Why installed device footprint is significantly smaller.**
4. **Why an all-in-one offline architecture benefits your privacy.**

---

## 🚀 Quick Summary: Why is the APK Download ~240 MB?

The ~240 MB download size is driven by two main architectural factors:

1. **4-in-1 Universal Architecture ("FAT" APK):**
   The FOSS APK is packaged as a universal binary that runs out-of-the-box on every Android hardware platform without requiring the user to guess their device's CPU architecture:
   - **`arm64-v8a`** (64-bit ARM): Modern Android smartphones, tablets, and high-end TV boxes (Google TV Streamer, Nvidia Shield).
   - **`armeabi-v7a`** (32-bit ARM): Streaming sticks (e.g. Fire TV Stick Lite / 4K Max Gen 1), budget TV boxes, and older devices.
   - **`x86_64`** (64-bit Intel/AMD): Chromebooks, Windows Subsystem for Android (WSA), and Android desktop PCs.
   - **`x86`** (32-bit Intel): PC emulators and legacy Intel Android tablets.

2. **Uncompressed, 16 KB Page-Aligned Native Libraries:**
   Android 15 standards and modern Android runtime (API 23+) mandate that native `.so` files must be 16 KB page-aligned and stored **uncompressed** inside the APK (`android:extractNativeLibs="false"`). This allows Android to directly memory-map (`mmap`) native binaries into RAM with zero extraction lag or double-disk caching. 
   
   Because they are stored uncompressed inside the APK, **all four CPU architectures' raw binaries reside inside the download file**, totaling **~214 MB of native code alone**.

---

## 📊 Exact APK Breakdown (240.15 MB Total)

An exact audit of the production `mobile-foss-release.apk` (and `tv-foss-release.apk`) reveals where every megabyte goes:

| Category | Component / Folder | Size (MB) | % of APK | Description |
| :--- | :--- | :---: | :---: | :--- |
| 🤖 **Native Code** | `lib/arm64-v8a` | **62.09 MB** | 25.9% | 64-bit ARM native engines (Go, FFmpeg, JXL, AVIF, NFS, ZSTD) |
| 🤖 **Native Code** | `lib/armeabi-v7a` | **57.47 MB** | 23.9% | 32-bit ARM native engines (Go, FFmpeg, JXL, AVIF, NFS, ZSTD) |
| 🤖 **Native Code** | `lib/x86_64` | **48.28 MB** | 20.1% | 64-bit x86 native engines (Go, JXL, AVIF, NFS, ZSTD) |
| 🤖 **Native Code** | `lib/x86` | **45.88 MB** | 19.1% | 32-bit x86 native engines (Go, JXL, AVIF, NFS, ZSTD) |
| ⚡ **App Logic** | `classes.dex` (DEX) | **10.93 MB** | 4.6% | Compiled & R8-optimized Kotlin/Java application code |
| 📜 **Packaging** | `META-INF` / Signatures | **8.50 MB** | 3.5% | APK v2/v3 signatures, certificates & open-source license manifests |
| 🎨 **UI Resources** | `res/` (Resources) | **4.17 MB** | 1.7% | Compressed layouts, vector drawables, 18 language string bundles |
| 📦 **App Assets** | `assets/` | **1.86 MB** | 0.8% | Web companion remote UI, bundled font icons, media assets |
| **GRAND TOTAL** | **Universal FOSS APK** | **240.15 MB** | **100%** | **Self-contained, 100% offline standalone suite** |

---

## 🔬 Cross-Architecture Native Library Matrix

Here is the exact size in megabytes of each native library (`.so`) packaged for each CPU architecture:

| Native Shared Library | Primary Engine / Purpose | ARM64 (`arm64-v8a`) | ARMv7 (`armeabi-v7a`) | x86_64 | x86 | Total Across All ABIs |
| :--- | :--- | :---: | :---: | :---: | :---: | :---: |
| **`libgojni.so`** | Go Runtime + Rclone Cloud Storage + Native Go SMB2/3 Engine | 23.87 MB | 23.06 MB | 25.59 MB | 23.42 MB | **95.94 MB** |
| **`libavcodec.so`** | FFmpeg Video Decoder (MKV, MP4, AVI, FLV thumbnail generator) | 12.25 MB | 11.63 MB | — | — | **23.88 MB** |
| **`libaom.so`** | AV1 Software Decoder (`avif-coder`) | 4.48 MB | 6.12 MB | 6.99 MB | 7.59 MB | **25.18 MB** |
| **`libavfilter.so`** | FFmpeg Video Filter Engine | 3.76 MB | 3.02 MB | — | — | **6.78 MB** |
| **`libavformat.so`** | FFmpeg Demuxer / Container Parser | 2.54 MB | 2.38 MB | — | — | **4.92 MB** |
| **`libjxl.so`** | JPEG XL Reference Decoder | 1.93 MB | 1.52 MB | 2.55 MB | 2.40 MB | **8.40 MB** |
| **`libcoder.so`** | AVIF JNI Coder Bridge | 1.95 MB | 1.16 MB | 2.04 MB | 2.08 MB | **7.23 MB** |
| **`libx265.so`** | HEVC / H.265 Decoder (bundled in `jxl-coder`) | 1.87 MB | 1.65 MB | 2.25 MB | 2.27 MB | **8.04 MB** |
| **`libjxlcoder.so`** | JPEG XL JNI Bridge | 1.54 MB | 1.12 MB | 1.61 MB | 1.65 MB | **5.92 MB** |
| **`libde265.so`** | H.265 Decoder Support (bundled in `jxl-coder`) | 1.54 MB | 1.02 MB | 1.64 MB | 1.68 MB | **5.88 MB** |
| **`libheif.so`** | HEIF / ISO Media Container Parser | 1.49 MB | 0.99 MB | 1.58 MB | 1.53 MB | **5.59 MB** |
| **`libswscale.so`** | FFmpeg Video Frame Rescaler & Color Converter | 1.11 MB | 0.75 MB | — | — | **1.86 MB** |
| **`libavutil.so`** | FFmpeg Core Utilities | 0.70 MB | 0.62 MB | — | — | **1.32 MB** |
| **`libdav1d.so`** | Fast AV1 Decoder (`avif-coder`) | 0.68 MB | 0.64 MB | 1.54 MB | 0.90 MB | **3.76 MB** |
| **`libbrotlienc.so`** | Brotli Compression Engine (for JXL compression) | 0.67 MB | 0.54 MB | 0.78 MB | 0.70 MB | **2.69 MB** |
| **`libzstd-jni-*.so`** | Zstandard High-Speed Compression Engine | 0.45 MB | 0.35 MB | 0.52 MB | 0.53 MB | **1.85 MB** |
| **`libnfs_jni.so`** | Native NFS v2/v3/v4 Client Engine (C / CMake) | 0.38 MB | 0.25 MB | 0.37 MB | 0.37 MB | **1.37 MB** |
| **`libjxl_cms.so`** | Color Management Engine (LittleCMS for JXL) | 0.31 MB | 0.21 MB | 0.34 MB | 0.33 MB | **1.19 MB** |
| **`libjxl_threads.so`** | Multi-threaded JXL Decoding Engine | 0.25 MB | 0.15 MB | 0.25 MB | 0.21 MB | **0.86 MB** |
| **Other Helper Libs** | `libbrotlicommon`, `libbrotlidec`, `libswresample`, `libspake2`, `libffmpeg_jni`, `libavdevice` | 0.31 MB | 0.29 MB | 0.21 MB | 0.22 MB | **1.03 MB** |
| **TOTAL NATIVE LIBS** | | **62.09 MB** | **57.47 MB** | **48.28 MB** | **45.88 MB** | **213.72 MB** |

---

## 📱 What Happens on Your Device When Installed?

When you install `mobile-foss-release.apk` on a specific Android phone (e.g. an `arm64-v8a` device):
1. **Android only runs the matching architecture:** The operating system uses only the **62.09 MB** of ARM64 native libraries. The other three architectures (151.63 MB) sit dormant in the APK container and are never loaded into memory.
2. **Real-world installed footprint:**
   - On an ARM64 phone or tablet, the active application binary footprint is approximately **~90–120 MB**.
   - As you use the app, the Android runtime (ART) compiles frequently used code into an optimized oat/odex execution cache and baseline profile (~30–50 MB).
   - This brings the typical **Settings > Apps** storage footprint to **~140–185 MB**.

> [!NOTE]
> On the Google Play Store, users download an **Android App Bundle (AAB)**. Google Play automatically strips out the 3 unused architectures on Google's servers and delivers a device-tailored APK of only **~65–75 MB**. The FOSS edition is ~240 MB because GitHub Releases provides a single, universal APK that works everywhere without Google Play.

---

## 🛠️ Detailed Breakdown of the Built-In Engines

### 1. ☁️ Native Go Cloud & SMB2/3 Engine (~96 MB across 4 ABIs / ~24 MB per device)
* **What it does:** Powers both cloud storage synchronization (Koofr, Drime, Filen, MEGA, Nextcloud, WebDAV, AWS S3) and native wire-speed SMB2/3 network transfers.
* **Why it needs space:** Gomobile compiles the full Go runtime (memory allocator, garbage collector, concurrency scheduler) alongside Rclone cloud backends and pure Go SMB clients with 16 KB page alignment.

### 2. 🎬 FFmpeg Offline Video Engine (~42 MB across 2 ABIs / ~22 MB per device)
* **What it does:** Generates high-quality video thumbnails instantly for MKV, MP4, AVI, FLV, TS, and other video formats directly within the dual-pane file browser.
* **Why it needs space:** It bundles native `libavcodec`, `libavfilter`, and `libavformat` shared libraries to parse complex video containers completely offline.

### 3. 🖼️ Modern Image Decoders (JPEG XL & AVIF) (~75 MB across 4 ABIs / ~17 MB per device)
* **What it does:** Allows instant, hardware-independent viewing of modern image formats (.jxl and .avif) without needing external viewer apps.
* **Why it needs space:** Bundles standalone software decoders (`libjxl`, `libaom`, `libdav1d`, `libheif`, and `libx265`).

### 4. 🗜️ High-Speed Archives & NFS Network Engine (~3.4 MB across 4 ABIs / ~0.8 MB per device)
* **What it does:** Enables high-speed Zstandard (`zstd`) compression and decompression, and native low-latency Network File System (NFS v2/v3/v4) mounts over JNI.
* **Why it needs space:** Native C/C++ libraries compiled for maximum throughput.

---

## 🔒 100% Private, Local & Standalone

Many other file managers keep their download size below 30 MB by doing one of two things:
1. **Relying on external apps:** Prompting you to install a separate video player, office viewer, unzipper, and cloud sync tool.
2. **Uploading your files to remote cloud servers:** Sending files to third-party cloud servers to extract thumbnails, convert documents, or process video streams.

**Ultimate File Manager Pro never does this:**
* 🚫 **No data leaves your device**
* 🚫 **No external server conversions**
* 🚫 **Zero proprietary trackers or closed-source telemetry**

---

## 💡 The Result

Instead of having to install **5+ separate apps** (a video player, an office viewer, an archive utility, a PC transfer tool, and cloud clients) that would easily take up **over 500 MB combined**, Ultimate File Manager Pro delivers an all-in-one, fully offline, privacy-first workstation in a single package.
