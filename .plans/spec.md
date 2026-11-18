# Specification: Root Storage Global Mount Namespace Access

## Overview
When browsing root storage directories (specifically `/data/data` and `/data/user/0`) with superuser privileges on modern Android versions (Android 14+, Android 15, and Android 16, such as Nothing OS on Nothing Phone (2a)), Ultimate File Manager Pro previously only displayed two directories: `com.google.android.gms` and `za.kilowatch.ultimatefilemanager`. This occurs because modern Android Zygote creates an isolated mount namespace for each unprivileged application process containing only its own package and Google Play Services, and UFM's elevated shell session inherited the application's private mount namespace rather than attaching to the global/master mount namespace. This specification defines the requirements to ensure elevated root sessions operate within the global mount namespace, enabling full visibility, traversal, and file management across `/data/data` and all root filesystem paths across all devices, root solutions, and Android versions.

---

## User Stories
- **US-01 (Full Root Directory Visibility):** As a user with root access on a modern Android device (Android 14, 15, or 16), I want UFM to display all installed application folders and system directories under `/data/data` so that I can inspect and manage any app's data.
- **US-02 (Seamless Device & Root Manager Interoperability):** As a power user utilizing Magisk, KernelSU, or APatch with default "Inherit" or "Isolated" mount namespace settings, I want UFM to automatically connect to the global mount namespace without requiring manual reconfiguration of my root manager.
- **US-03 (Consistent Elevated File Operations):** As a power user, I want all file operations (copy, move, delete, view, edit, permissions) performed inside root directories to execute within the master mount namespace across Standalone, Tabbed, and Twin Window browser modes.
- **US-04 (Graceful Legacy Handling):** As a user on older Android versions or custom root implementations that do not support mount namespace flags, I want root sessions to continue functioning reliably without errors.

---

## Functional Requirements
- **FR-01 (Master Mount Namespace Configuration):** 
  - Elevated root shell sessions must be initialized with master/global mount namespace flags (`--mount-master` / `Shell.FLAG_MOUNT_MASTER`).
  - The shell session must run in the global/init mount namespace (PID 1) rather than inheriting the calling application's sandboxed mount namespace.
- **FR-02 (Unrestricted Directory Traversal):** 
  - Navigating to `/data/data`, `/data/user/0`, `/data/user_de/0`, or any system partition must enumerate all existing subdirectories and files without omission.
  - Directories must accurately display child item counts, POSIX permissions, ownership (UID:GID), timestamps, and file sizes.
- **FR-03 (Symlink & Multi-User Resolution):** 
  - Navigating through symlinks such as `/data/data` (which targets `/data/user/0` on modern Android) must resolve correctly and list the target directory contents in the master mount namespace.
  - Secondary user spaces (e.g., `/data/user/10`) must be accessible if present on multi-user or work-profile configurations.
- **FR-04 (Fallback & Compatibility):** 
  - If a device's `su` implementation fails to initialize or rejects the master mount namespace flag, the system must fall back to standard root shell initialization so basic root access is not interrupted.
- **FR-05 (Cross-Environment Synchronization):** 
  - The master mount namespace execution must apply uniformly across all browser implementations: Standalone (`FileBrowserActivity`), Tabbed Mode (`FileBrowserFragment`), and Twin Window Mode.

---

## Non-Functional Requirements
- **NFR-01 (Performance):** Spawning and querying the master mount root shell must maintain existing performance benchmarks (<150ms for directory listings under normal load).
- **NFR-02 (Stability & Main-Thread Safety):** Superuser initialization and mount namespace negotiation must remain strictly non-blocking and off the main UI thread.
- **NFR-03 (Security & Explicit Consent):** Elevated master mount sessions must only be activated when Root Storage is explicitly enabled by the user in Settings and superuser permission is granted via the device's root manager.

---

## Edge Cases & Error States
- **EC-01 (Root Access Denied or Revoked):** If the user denies root permissions in Magisk/KernelSU/APatch or revokes them while browsing, UFM must display an informative permission error and gracefully return to the previous valid directory or storage root.
- **EC-02 (Legacy SU Without `--mount-master` Support):** Older SuperSU or custom su binaries that error on `--mount-master` must fall back cleanly without terminating the application.
- **EC-03 (SELinux Enforcing Restrictions):** In environments where strict SELinux rules restrict traversal of certain domain labels, any unreadable entries must be flagged with standard restricted-access indicators rather than crashing directory listing.
- **EC-04 (Dual-Pane Twin Window Mode):** Navigating root storage in one pane and non-root or SAF storage in the other pane must maintain independent, isolated state without cross-contamination.

---

## Out of Scope
- Modifying unrooted SAF or Shizuku storage pipelines (which operate under standard Android permission frameworks).
- Creating or altering system-level SELinux policies on the device.
- Bypassing Android OS security boundaries when root privileges are not granted.

---

## Success Criteria
- [ ] Navigating to `/data/data` on Nothing Phone (2a) running Android 16 displays all installed package folders (matching system/master namespace listing) instead of only `com.google.android.gms` and `za.kilowatch.ultimatefilemanager`.
- [ ] Directory listing reflects accurate item counts, permissions, and timestamps for all packages.
- [ ] File operations (inspection, reading, copying, moving, deletion) inside `/data/data` function properly under root access.
- [ ] Root access continues to work as expected on devices running older Android versions and diverse root managers (Magisk, KernelSU, APatch).
