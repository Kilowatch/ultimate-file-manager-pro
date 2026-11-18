# Clarifications: Storage Analyzer – Large Files Action Menu (Move & Delete)

## Status
Answers received — ready for /plan and /tasks

## Questions and Answers

### UI and Layout
**Q1.** Which actions should be displayed in the long-press contextual dialog?  
**A:** **Move, Delete, Open File, and Open Folder Location**. In addition to Move and Delete, include options to open/view the file and open the containing directory in FileBrowserActivity with the file focused.

**Q2.** How should the Delete confirmation dialog be styled?  
**A:** **Reuse the standard UFM glass delete confirmation** (`dialog_analyzer_delete_confirm`) displaying the target filename, formatted size, danger badge, and permanent deletion warning with vertical primary/cancel buttons.

### Navigation and Entry Points
**Q3.** Should the Move destination folder picker allow moving across different storage volumes?  
**A:** **Yes, allow moving across any accessible storage volume** (Internal Storage, SD Card, USB OTG). If crossing volumes, the file will be safely transferred to the target storage and removed from the source upon verification.

### Business Logic and Edge Cases
**Q4.** How should name collisions be handled if a file with the same name exists at the destination?  
**A:** **Re-use the current conflict detector (`TransferConflictHelper`)**, providing standard conflict options: Overwrite (atomic temp-then-rename), Skip, Keep Both (auto-rename), or Cancel.

### Data and State
**Q5.** What reindexing scope should be executed after a Move or Delete operation?  
**A:** **Targeted & Instant**: Directly update/remove entries in the `IndexingRepository` database, trigger background reindex for the affected source/destination folders, and instantly refresh the in-memory Large Files list and analyzer summary statistics for immediate UI feedback.

---

## Spec / Plan Impact
- **spec.md updated**:
  - `FR-02` updated to include "Open File" and "Open Folder Location" in addition to "Move" and "Delete".
  - `FR-05` updated to explicitly integrate `TransferConflictHelper` for collision resolution.
  - Scope confirmed: Cross-storage volume moves permitted; targeted index updates with folder background reindex.

---

## Confirmed — Ready for /plan or /tasks
All clarification questions are answered and incorporated into the specification.
