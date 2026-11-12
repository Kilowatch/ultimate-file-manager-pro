# Specification: Actual vs. Nominal File Size Detection & Sparse/Incomplete File Handling

## Overview
Files created by torrent clients, download managers, or virtual disk utilities are frequently written as sparse or pre-allocated files where the reported nominal (logical) size reflects the expected final size rather than the actual physical disk space allocated. Standard Android file managers and duplicate detection utilities only inspect nominal size, leading users to mistakenly delete complete files in favor of incomplete sparse downloads, or misjudge actual storage consumption. This specification defines requirements for detecting, displaying, and handling physical disk allocation (actual size) versus logical file size (nominal size) across file properties, file browsing, duplicate detection, storage analysis, and file comparison.

---

## User Stories
- **As a user managing downloaded or torrented files**, I want to see both the actual disk space allocated and the nominal file size in file properties, so that I can immediately determine whether a file is partially downloaded or fully complete without needing command-line tools like Termux.
- **As a user running the Duplicate Finder**, I want the tool to distinguish between fully completed files and partially downloaded sparse files of identical nominal size, so that I don't accidentally keep an incomplete download and delete the complete copy.
- **As a user reviewing duplicate candidates**, I want the deduplication engine to prioritize retaining files with greater physical allocation (or completed downloads) and explicitly warn me if a candidate is a sparse/incomplete file.
- **As a power user comparing two identically named files (e.g. in Twin Window or Compare)**, I want to compare actual allocated bytes, completion percentage, and header/content offset signatures rather than relying solely on nominal size and full hashes that inevitably mismatch.
- **As a user analyzing storage usage in Storage Analyzer**, I want disk occupancy calculations and large file reports to reflect true allocated disk blocks rather than phantom nominal sizes, preventing distorted storage statistics.

---

## Functional Requirements

### 1. Dual Size Representation (Actual vs. Nominal)
- **FR-01: Physical Allocation & Nominal Size Exposure**: The file manager must be capable of inspecting both the file's nominal size (logical byte length) and actual physical allocation (blocks allocated on disk).
- **FR-02: File Properties & Details Display**: When inspecting file details/properties:
  - If actual size matches nominal size (within normal filesystem block-alignment tolerances), the standard size is displayed.
  - If a discrepancy exists (e.g., actual size is substantially less than nominal size, indicating a sparse or incomplete file), both sizes must be shown: "Nominal Size: X GB" and "Actual Allocated: Y MB (Z% allocated)".
  - A visual badge or status (e.g., "Sparse / Incomplete Pre-allocation") must indicate the partial allocation state.
- **FR-03: File Browser List/Grid Metadata Option**: In file listings, provide an option or visual indicator (such as an icon overlay or secondary size subtitle) for files detected as sparse or incompletely allocated.

### 2. Duplicate Finder & Deduplication Integration
- **FR-04: Sparse File Awareness in Duplicate Detection**: The duplicate finder must evaluate actual allocated size alongside nominal size during candidate grouping and verification.
- **FR-05: Completed vs. Incomplete Grouping & Differentiation**:
  - Files with matching nominal sizes but mismatched actual allocations must not be blindly treated as identical duplicates.
  - The duplicate review UI must flag incomplete/sparse candidates with a clear warning tag (e.g., "Partial Download - 45% Allocated").
- **FR-06: Intelligent Retention Policies for Partial Files**:
  - Automatic selection and retention policies (Keep Oldest, Keep Newest, Keep in Folder X) must never recommend deleting a 100% complete file in favor of a partially allocated file.
  - Introduce or adapt retention strategies to prioritize "Keep Most Complete / Fully Allocated" when resolving duplicates with identical nominal targets.
- **FR-07: Partial Hash & Offset Comparison**: For files sharing the same nominal size and matching initial headers (e.g. media containers or archive headers) where one is incomplete, the system should identify that the files represent the same target asset at different completion stages rather than treating them as unrelated distinct files.

### 3. Storage Analyzer & Cleanup Integration
- **FR-08: Accurate Disk Space Reporting in Analyzer**: Storage Analyzer must reflect actual disk usage rather than nominal file length when calculating category totals, top folders, and reclaimable space, avoiding reporting gigabytes of "used space" for sparsely populated files that only consume megabytes.
- **FR-09: Dedicated "Incomplete / Sparse Downloads" Filter**: Storage Analyzer should provide a filter or report category identifying incomplete downloads / sparse files across the device, enabling users to easily clean up abandoned torrents and incomplete downloads.

### 4. File Comparison & Twin Window Mode
- **FR-10: Side-by-Side Allocation Comparison**: When comparing two files in Twin Window or via File Compare, the comparison screen must highlight:
  - Nominal file size comparison
  - Actual disk allocation comparison
  - Allocation percentage comparison
  - Content offset match status (whether non-zero allocated regions match the other file's data at the same offsets)

---

## Non-Functional Requirements
- **NFR-01: Performance**: Inspecting file allocation (stat/block queries) must be fast and lightweight, introducing no noticeable latency during directory listing or initial duplicate pre-filtering.
- **NFR-02: Filesystem Compatibility**: Must work seamlessly across supported Android local storage filesystems (ext4, f2fs) and handle filesystems that do not support sparse files (e.g. FAT32/exFAT on SD cards/OTG) gracefully without false positives.
- **NFR-03: Security & Sandbox Compliance**: Must operate entirely within standard Android user-space APIs and permissions without requiring root or external Termux shells.
- **NFR-04: UI Consistency**: Sparse indicators, dual-size labels, and warnings must follow UFM's existing theme, design tokens, and dual-layout requirements (mobile and Android TV).

---

## Edge Cases & Error States
- **Block Allocation Rounding**: Filesystems allocate space in discrete block sizes (typically 4 KB). Small files may show allocated blocks slightly larger than nominal byte size; such minor variations must not trigger "sparse" or "discrepancy" status.
- **Non-Sparse Partially Downloaded Files**: Some downloaders append sequentially to a file without pre-allocating. In such cases, nominal size equals actual size, but header checks against a completed copy reveal an incomplete file. The system should detect truncated files when comparing known headers.
- **Zero-Byte Files & Empty Sparse Files**: A sparse file with 0 allocated blocks must be handled gracefully without division-by-zero errors when calculating allocation percentage.
- **Removable Media / Non-Sparse Filesystems (FAT32/exFAT)**: On filesystems that do not support sparse allocation, torrent clients write zeros across the entire allocated space. The system should distinguish between true sparse files (block count < length) and zero-padded files where possible.
- **Network and Cloud Storage**: Remote protocols (SMB, SFTP, WebDAV, Cloud) may not expose filesystem block allocation. In such environments, actual size detection must gracefully fall back to nominal size or indicate allocation status is unavailable.
- **Read Permission Limits**: Files residing in directories with restricted access must display appropriate fallback states without crashing.

---

## Out of Scope
- Creating or editing torrent/magnet files or functioning as a BitTorrent client.
- Resuming or repairing broken downloads directly within UFM (must be handled by the original downloading app).
- Modifying underlying kernel filesystem drivers or performing defragmentation.

---

## Success Criteria
- [ ] Users can view both nominal size and actual allocated disk size in File Properties for any file on internal storage.
- [ ] Sparse or partially allocated files are visually identified with an indicator/badge and allocation percentage.
- [ ] The Duplicate Finder correctly differentiates between complete and partial downloads with identical nominal sizes.
- [ ] Retention policies prevent the accidental deletion of complete files when duplicate candidates include sparse/incomplete files.
- [ ] Storage Analyzer reports accurate physical disk consumption for sparse files and offers a way to locate incomplete downloads.
- [ ] File Compare in Twin Window clearly highlights actual allocation differences between two copies of a file.
