# Clarifications: Enhanced Long Filename Display & Interactive Scrolling Options

## Status
Answers received — ready for /tasks

---

## Questions and Answers

### 1. UI and Settings Presentation
**Q1.** How should the Settings UI present 'Truncate Middle' and the new Marquee sub-options?  
**A:** Add 'Truncate Middle' as a 6th primary option in the display mode dialog, and show Marquee sub-options (Scroll Speed, Loop Mode, Scroll Scope) inline within the same configuration view when Marquee is selected.

### 2. Middle Truncation Mechanics
**Q2.** How should the middle truncation split and preserve characters at the end of the filename?  
**A:** Preserve at least 4 trailing characters of the base filename before the extension (e.g., `prefix...xyz2.docx`) and always preserve the full file extension.

### 3. Interactive Touch Scrubbing Gesture
**Q3.** How should manual touch scrubbing behave on mobile screens?  
**A:** Touch specifically on the filename text to scrub. When manually scrubbed, the text remains fixed at the scrubbed position and marquee scrolling halts. It does not auto-reset while the row remains on screen. When the row is scrolled off-screen and becomes visible again (re-bound by RecyclerView), it resets to standard behavior.

### 4. Scroll Scope (Mobile vs Android TV)
**Q4.** How should the 'Selected / Focused Item Only' scroll scope behave on mobile vs Android TV?  
**A:** On mobile touchscreens, scroll only checked/selected items when multi-select mode is active. On Android TV, scroll the currently D-pad focused row.

### 5. Marquee Loop Defaults
**Q5.** What should be the default marquee looping behavior out-of-the-box?  
**A:** One-Shot (scroll forward, pause, scroll back, and stop) by default to conserve battery, reduce CPU cycles, and avoid visual distraction. Continuous loop is available as a configurable option.

---

## Spec / Plan Impact
- **spec.md FR-06 (Manual Touch Scrubbing)**: Updated to specify that manual scrubbing locks the offset in place and stops the marquee until the item scrolls off-screen and is re-bound by the RecyclerView.

---

## Confirmed — Ready for /tasks
All requirements, edge cases, and user interactions are clarified and confirmed. Ready to proceed to `/plan` or `/tasks`.
