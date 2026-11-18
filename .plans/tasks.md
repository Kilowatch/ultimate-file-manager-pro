# Task List: Root Storage Global Mount Namespace Access

## Setup
- [x] [T001] [P] Configure `libsu` default builder in `app/src/main/java/za/kilowatch/ultimatefilemanager/UfmApplication.kt` with `Shell.FLAG_REDIRECT_STDERR or Shell.FLAG_MOUNT_MASTER` — enables global mount namespace (PID 1) connection by default on root sessions.

## Foundation
- [x] [T002] [P] Implement resilient shell acquisition with automatic fallback in `app/src/main/java/za/kilowatch/ultimatefilemanager/storage/RootShellWrapper.kt` — catches unsupported `--mount-master` flags on legacy `su` binaries and falls back gracefully to `FLAG_REDIRECT_STDERR`.
- [x] [T003] [P] Update streaming process execution in `app/src/main/java/za/kilowatch/ultimatefilemanager/storage/RootShellWrapper.kt` (`openInputStream` and `openOutputStream`) to pass `--mount-master` when active, with fallback to standard `su -c`.

## Feature Implementation & Path Refinement
- [x] [T004] [P] Refine `isRootPath()` in `app/src/main/java/za/kilowatch/ultimatefilemanager/storage/RootShellWrapper.kt` so that package directories under `/data/data` and `/data/user/0` (including `za.kilowatch.ultimatefilemanager`) are properly recognized as root paths when browsing Root Storage.
- [x] [T005] Verify and ensure child directory enumeration in `RootShellWrapper.listFiles()` accurately parses and populates all package metadata (item counts, sizes, POSIX permissions, and timestamps) under `/data/data`.

## Polish & Testing
- [x] [T006] [P] Update and expand unit test coverage in `app/src/test/java/za/kilowatch/ultimatefilemanager/storage/RootShellWrapperTest.kt` to validate root path recognition for `/data/data`, `/data/user/0`, and package paths.
- [x] [T007] Execute Robolectric unit test suite (`./gradlew testMobileGoogleDebugUnitTest --tests za.kilowatch.ultimatefilemanager.storage.RootShellWrapperTest`).
- [x] [T008] Run full build compilation check (`./gradlew assembleMobileGoogleDebug`) to ensure clean compilation without errors or regressions.
