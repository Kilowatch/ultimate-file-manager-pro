# Task List: Network Favorite Breadcrumb Navigation on SMB Server Shares (v2.2.7)

## Setup & Versioning
- [x] [T001] [P] Version Bump in `app/build.gradle.kts`:
  - Bump `appVersionCode` from 314 to 316 (+2).
  - Bump `appVersionName` from "2.2.6" to "2.2.7".
- [x] [T002] Release Documentation in `CHANGELOG.md`:
  - Add version header `## [2.2.7] — 2026-11-17`.
  - Add approved entry under `### Fixed`:
    ```markdown
    - **SMB Server Favorite Breadcrumb Navigation (`NetworkBrowserActivity`)**: Fixed an issue where tapping an ancestor or favorite folder segment in the breadcrumb navigation bar dropped back to the SMB server root (`SMB://server/`) instead of opening the tapped favorite folder when browsing an SMB share configured in server mode (`isServerMode`).
    ```
  - Verify `SECURITY.md` supported versions table covers 2.2.x.

## Feature Implementation
- [x] [T003] [P] Refactor Breadcrumb Resolution in `NetworkBrowserActivity.kt` (`updateBreadcrumbs`):
  - Isolate the storage/server root breadcrumb: use `share.name.ifEmpty { share.host }` (or online storage label) bound to the server root path (`""` or `initialRootPath` for TV).
  - Parse `currentPath.split("/").filter { it.isNotEmpty() }`:
    - Accumulate the full subpath progressively for each folder level (`Docker`, `Docker/_Projects`, `Docker/_Projects/Immich`).
    - Append each segment with its actual directory name and its accumulated subpath as its click target.
  - Remove the flawed `isFirstPart` deduplication that skipped the first folder segment and collapsed targets to `""`.
  - Ensure clicking the server root breadcrumb continues to properly reset `share.remotePath` to `originalRemotePath` under server-mode SMB.

## Polish & Verification
- [x] [T004] [P] Compile & Build Verification:
  - Run `./gradlew assembleMobileGoogleDebug` to verify compilation, DEX generation, and absence of build warnings.
- [x] [T005] Scenario Verification:
  - Verify favorite launch hierarchy: `Home > <Server Name> > <Folder 1> > <Folder 2> ...`
  - Verify clicking ancestor folders (`Docker`) navigates directly to that folder.
  - Verify clicking server name (`NAS`) returns to root share listing.
  - Verify system Back navigation steps back segment-by-segment to server root before exiting.
