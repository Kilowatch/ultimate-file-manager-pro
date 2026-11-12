# Clarifications: Actual vs. Nominal File Size Detection & Sparse/Incomplete File Handling

## Status
Answers received — ready for /plan and /tasks.

---

## Questions and Answers

### 1. Duplicate Finder & Grouping
**Q1.** How should the Duplicate Finder handle files with identical nominal size where one is complete and one is partially downloaded?  
**A:** Group together in the same duplicate candidate group with a prominent warning badge (e.g. `Partial Download — X% Allocated`) and prevent auto-deleting complete files.

**Q2.** How should auto-retention rules (Keep Oldest, Keep Newest) handle groups with complete vs. partial files?  
**A:** Always protect complete files (never auto-delete complete over partial) AND add a new explicit retention preset: **"Keep Most Complete"**.

---

### 2. File Compare & Header/Offset Verification
**Q3.** In File Compare (`FileCompareDialogFragment`), how should partial files with matching headers be verified?  
**A:** Fast header + sampled non-zero block verification: Check initial header (64 KB) and sample allocated offsets to report `Matching Header / Partial Copy (X% complete)`.

---

### 3. UI and Display
**Q4.** Where should sparse / partial allocation indicators appear in the File Browser UI?  
**A:** Show dual size & badges in **File Properties**, **Duplicate Finder**, and **Storage Analyzer** to maintain fast directory listing performance during everyday navigation.

**Q5.** Should Storage Analyzer include a dedicated cleanup category for incomplete/sparse files?  
**A:** Yes, add a dedicated **"Incomplete / Sparse Downloads"** card in Storage Analyzer alongside Large Files, Duplicates, and Junk.

---

### 4. Storage Scope & Compatibility
**Q6.** How should storage locations that do not support block allocation (Cloud, SMB, FAT32/exFAT) be handled?  
**A:** Graceful degradation: For network, cloud, and filesystems where physical block allocation is unsupported, display standard nominal size without sparse badges.

---

## Spec / Plan Impact
- **spec.md**: All clarifications confirm and refine the requirements outlined in `.plans/spec.md`. No conflicting changes are required.
- **plan.md**: The architecture will incorporate:
  1. A low-overhead POSIX `stat.st_blocks` helper to obtain physical disk allocation on supported Android filesystems.
  2. Integration of dual size ("Nominal" and "Actual Allocated") + sparse warning badge into `FilePropertiesBottomSheet`.
  3. Sparse-aware candidate grouping, partial file badges, and safe retention strategies ("Keep Most Complete" and complete file protection) in `FolderDuplicateFinderViewModel`, `AnalyzerDuplicateAdapter`, and `DuplicateRetentionManager`.
  4. Header (64 KB) + offset verification in `FileCompareDialogFragment` to detect partial/sparse copies when nominal sizes match.
  5. A dedicated "Incomplete Downloads" category card in `StorageAnalyzerActivity` and `StorageAnalyzerEngine`.
  6. Graceful fallback on network shares, SAF, and unsupported filesystems.

---

## Confirmed — Ready for Next Phase
All questions have been answered and recorded. You can now proceed to run `/plan` to design the implementation architecture.
