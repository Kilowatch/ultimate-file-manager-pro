# Clarifications: Folder File List Stability & Background Sync Optimization (Fix Ghost Scrolling & Lag)

## Status
Answers received — ready for /tasks

## Questions and Answers

### UI and RecyclerView Update Behavior
**Q1.** How should FileAdapter handle directory list submissions and refreshes?
**A:** Adopt DiffUtil and early-return if contents are identical (`if (oldList == newList) return`) to preserve scroll position, view holders, and touch drag state natively without resets or jumping.

**Q2.** When folder child counts and total sizes finish computing in the background, how should the adapter update?
**A:** Update only directory rows using granular payloads (`notifyItemChanged(pos, PAYLOAD_COUNT)`), leaving other rows untouched, avoiding full adapter re-binds.

### Data and State Synchronization
**Q3.** How should background folder sync (`syncFolderWithIndex`) and Room Flow emissions be optimized?
**A:** Apply both:
1. Compare physical file timestamps, sizes, and paths against existing database rows before writing to Room (`syncFolderWithIndex`), avoiding redundant table invalidations for untouched folders.
2. Deduplicate Room Flow emissions using `distinctUntilChanged` on folder file lists so identical database notifications never trigger UI updates.

### Gestures and Scrolling
**Q4.** What should happen if a background update arrives while the user is actively touching or flinging the list?
**A:** Apply updates silently in-place via DiffUtil without interrupting active touch drags or fling velocity.

### TV and Focus Handling
**Q5.** On Android TV, how should item focus be preserved when background sync completes?
**A:** Keep focus strictly locked on the currently selected item without posting synthetic focus requests or re-scrolling.

---

## Spec / Plan Impact
- The answers align with and reinforce all functional and non-functional requirements in [`.plans/spec.md`](file:///F:/Share/UFM/.plans/spec.md).
- No changes required to `spec.md`.

---

## Confirmed — Ready for /tasks
All questions have been answered. The specifications and implementation decisions are finalized.
