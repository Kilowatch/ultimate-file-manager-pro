# Implementation Plan: Network Favorite Breadcrumb Navigation on SMB Server Shares

## Goals
Fix the breadcrumb navigation behavior when browsing network shares (specifically SMB shares configured in server mode, as well as FTP, SFTP, WebDAV, Cloud Drive, and TV paired storages) launched via Favorites, shortcuts, or tiles:
1. Display the full server hierarchy in the breadcrumb bar: `Home > <Server Name> > <Folder 1> > <Folder 2> ...` (per clarified decision A1).
2. The top server breadcrumb (e.g., "NAS") maps to the server root (`""` where shares are listed).
3. Every subsequent folder breadcrumb (e.g., "Docker", "_Projects", "Immich") maps to its actual directory path (`Docker`, `Docker/_Projects`, etc.), using real directory names (per clarified decision A2).
4. Tapping any folder breadcrumb navigates directly to that folder without collapsing or resetting to the server root (`""`).
5. Tapping the server breadcrumb navigates to the server root (`""`), resetting the remote share path in server mode.
6. Pressing the system Back button steps up segment-by-segment to the server root before exiting (per clarified decision A3).
7. Apply the fix uniformly across all network storage types (per clarified decision A4).
8. Bump version to `v2.2.7` (versionCode `316`) and update documentation.

---

## Architecture Overview

### Current Defect Analysis
In `NetworkBrowserActivity.kt:8573-8607`:
1. `val storageLabel = intent.getStringExtra(EXTRA_STORAGE_LABEL) ?: share.name`
   - When launched from a favorite (e.g., favorite named "Docker"), `EXTRA_STORAGE_LABEL` overrides the server profile name with the favorite name ("Docker").
2. `list.add(Pair(storageLabel, if (share.type == ShareType.TV) initialRootPath ?: "" else ""))`
   - Binds the favorite name ("Docker") to an empty string (`""` — the server root).
3. The deduplication loop at line 8596:
   ```kotlin
   if (isFirstPart && (part.equals(storageLabel, ignoreCase = true) || part.equals(share.name, ignoreCase = true))) {
       accumulated = part
       isFirstPart = false
       continue
   }
   ```
   - Skips the first segment ("Docker") because `part.equals(storageLabel)` is true!
   - This discards the folder breadcrumb with path `"Docker"`, leaving only `Pair("Docker", "")`.
   - Result: Tapping "Docker" navigates to `""`, kicking the user back to the NAS root.

### Architectural Fix
1. **Server Root Breadcrumb**:
   - The root breadcrumb representing the connection profile must use the server profile name `share.name.ifEmpty { share.host }` (or for online storage, the profile display name).
   - Its target path is `if (share.type == ShareType.TV) initialRootPath ?: "" else ""`.
   - Clicking it sets `currentPath = ""` (and resets `share.remotePath` in server mode), navigating to the server root.
2. **Path Segment Breadcrumbs**:
   - Split `currentPath` into segments using `.split("/").filter { it.isNotEmpty() }`.
   - Accumulate subpaths for each segment (`Docker`, `Docker/_Projects`, etc.).
   - Each folder segment is appended to the breadcrumb trail with its real folder name and its accumulated path as its target.
   - Do NOT skip the first segment when it differs from the server profile root.
   - If the first segment name matches `serverLabel` and the share is a standard single-share connection (where root already represents that share), avoid duplicate root labels; for server-mode SMB, the server profile and the share are distinct entities in the hierarchy (`Home > NAS > Docker`).
3. **Launch & Path Normalization**:
   - Ensure `EXTRA_INITIAL_PATH` (which may have leading slashes like `"/Docker"`) is normalized consistently so that segment accumulation produces matching relative paths for `loadDirectory()`.

---

## Files to Create
*None — this is a surgical bug fix and version bump.*

---

## Files to Modify

### `app/src/main/java/za/kilowatch/ultimatefilemanager/network/NetworkBrowserActivity.kt`
- **What**: Update `updateBreadcrumbs()` to implement the full server hierarchy and accurate folder target resolution.
- **Key details**:
  - In `updateBreadcrumbs()`:
    - Determine `serverLabel = if (share.type == ShareType.GOOGLE_DRIVE || share.type == ShareType.ONEDRIVE || share.type == ShareType.DROPBOX) { intent.getStringExtra(EXTRA_STORAGE_LABEL) ?: share.name } else { share.name.ifEmpty { share.host } }`.
    - Add server root breadcrumb: `Pair(serverLabel, if (share.type == ShareType.TV) initialRootPath ?: "" else "")`.
    - Iterate through `currentPath.split("/").filter { it.isNotEmpty() }`:
      - Accumulate path: `accumulated = if (accumulated.isEmpty()) part else "$accumulated/$part"`.
      - Add breadcrumb: `Pair(part, accumulated)`.
      - On click: set `currentPath = item.second`, handle server-mode remote path reset when empty, and call `loadDirectory()`.

### `app/build.gradle.kts`
- **What**: Bump `appVersionCode` from 314 to 316 (+2) and `appVersionName` from "2.2.6" to "2.2.7".
- **Key details**:
  - Update `val appVersionCode = 316`.
  - Update `val appVersionName = "2.2.7"`.

### `CHANGELOG.md`
- **What**: Add release notes for `[2.2.7] — 2026-11-17` under `### Fixed`.
- **Key details**: Document the SMB server favorite breadcrumb navigation fix as approved by the user.

---

## Data Model
*No changes to database entities or persistent storage schemas.*

---

## Dependencies
*No new external dependencies.*

---

## Test Plan

### Unit / Build Verification
- Execute `./gradlew assembleMobileGoogleDebug` to ensure compilation and R8 integrity succeed with no regressions.

### Scenario Verification
1. **SMB Server Favorite (Root of Share)**:
   - Favorite points to `SMB://server/Docker/`.
   - Open favorite -> verify breadcrumbs: `Home > NAS > Docker` ("Docker" highlighted as current folder).
   - Tap subfolder `_Projects` -> verify breadcrumbs: `Home > NAS > Docker > _Projects`.
   - Tap subfolder `Immich` -> verify breadcrumbs: `Home > NAS > Docker > _Projects > Immich`.
   - Tap `Docker` in breadcrumb bar -> verify navigation returns to `SMB://server/Docker/`.
   - Tap `NAS` in breadcrumb bar -> verify navigation returns to server root `SMB://server/` showing all shares.
2. **System Back Navigation**:
   - In `Immich`, press Back -> navigates to `_Projects`.
   - Press Back -> navigates to `Docker`.
   - Press Back -> navigates to `NAS` root share listing.
   - Press Back -> exits activity.
3. **Standard Network Share (Non-Server Mode)**:
   - Open standard SMB/FTP share -> verify breadcrumbs: `Home > ShareName > Subfolder`.
   - Tap `ShareName` -> navigates to share root `""`.

---

## Risks & Mitigations
- **Risk**: Potential duplicate breadcrumb segment if `share.name` equals the first folder name in server mode (e.g. server named "Docker" with a share named "Docker").
  - **Mitigation**: Distinguish the server root (`""`) from the folder target (`"Docker"`). The server root breadcrumb represents the server container, while the folder breadcrumb represents the specific share directory. Each has a distinct target path.
