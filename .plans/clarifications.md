# Clarifications: Network Favorite Breadcrumb Navigation on SMB Server Shares

## Status
Answers received — ready for /plan and /tasks

## Questions and Answers

### Navigation & Breadcrumb Trail Structure
**Q1.** When opening an SMB server favorite (e.g., server profile named "NAS", favorite folder "Docker", deep path `Docker/_Projects/Immich`), how should the breadcrumbs be structured?
**A:** **Full Server Hierarchy**: `Home > NAS > Docker > _Projects > Immich`
- "NAS" navigates to the server root (`""` where shares are listed).
- "Docker" navigates to `/Docker`.
- "_Projects" navigates to `/Docker/_Projects`.
- "Immich" is the active directory.

### Custom Favorite Labels
**Q2.** If a favorite has a custom label (e.g., "Containers" for `/Docker`), how should it display in breadcrumbs?
**A:** **Always show real directory name in breadcrumbs (ignoring custom label)**. Breadcrumb segments always reflect the actual filesystem directory names (`Docker`), ensuring strict parity with the folder structure and avoiding path resolution ambiguity.

### Back Button Navigation Boundary
**Q3.** What should happen when pressing the system Back button at the initial favorite folder?
**A:** **Step up to server root**: Pressing Back at the favorite folder navigates up to the server root share list (`SMB://server/`), and pressing Back again exits the browser activity.

### Storage Scope
**Q4.** Where should the breadcrumb fix apply?
**A:** **Apply uniformly across all network storage types** (SMB, FTP, SFTP, WebDAV, Cloud Drive, TV Paired Storage) so that any network session opened with an initial subfolder path produces a correct, non-degrading breadcrumb hierarchy.

---

## Spec / Plan Impact
- **spec.md**: Update FR-01 and FR-03 to explicitly specify the full storage hierarchy structure (`Home > <Storage/Server Name> > <Folder 1> > <Folder 2> ...`) where the storage root segment maps to the root (`""`), subsequent segments map to their actual folder paths using real directory names, and the fix uniformly applies across all network storage types.
- **plan.md**: The architecture will ensure `NetworkBrowserActivity` breadcrumbs use `share.name` for the storage root (instead of overriding it with `EXTRA_STORAGE_LABEL`), append segments using actual directory names, and assign accurate accumulated subpaths to each segment.

---

## Confirmed — Ready for /plan & /tasks
All questions are answered and architectural requirements are aligned.
