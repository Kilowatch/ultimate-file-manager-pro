# Clarifications: Root Storage Global Mount Namespace Access

## Status
Answers received — ready for /plan and /tasks

## Questions and Answers

### Behavior & Settings
**Q1. Should Master Mount Namespace mode be applied automatically or controlled via a Settings toggle?**
**A:** (Recommended) Automatic: Apply `FLAG_MOUNT_MASTER` automatically whenever Root Storage is active. No extra settings toggle needed.

### Error Handling & Fallback
**Q2. How should UFM handle legacy or non-standard `su` binaries that reject the `--mount-master` flag?**
**A:** (Recommended) Automatic Fallback: If spawning with `FLAG_MOUNT_MASTER` fails on legacy/unsupported `su` binaries, gracefully fall back to a standard root shell without crashing or disabling root.

### Application Sandbox Path Filtering
**Q3. When browsing `/data/data` in Root Storage mode, how should UFM's own package folder (`za.kilowatch.ultimatefilemanager`) be handled?**
**A:** (Recommended) Allow Full Traversal: When browsing in Root Storage mode, allow root access and traversal into all packages in `/data/data`, including `za.kilowatch.ultimatefilemanager`.

---

## Spec / Plan Impact
- The answers align with and solidify FR-01, FR-02, and FR-04 in [.plans/spec.md](file:///F:/Share/UFM/.plans/spec.md).
- In the implementation plan, shell initialization will configure `FLAG_MOUNT_MASTER` by default, include graceful fallback catch logic, and ensure `RootShellWrapper.isRootPath()` permits root browsing into UFM's own package directory when the user is operating within Root Storage mode.

---

## Confirmed — Ready for Next Phase
All questions have been confirmed. Proceed with `/plan` to define the architecture and implementation design.
