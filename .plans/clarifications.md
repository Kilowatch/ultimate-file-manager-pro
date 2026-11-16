# Clarifications: Picture-in-Picture Compatibility for Custom Launchers and Modern Android

## Status
Answers received — ready for /tasks

## Questions and Answers

### Playback State & Auto-Enter Behavior
**Q1.** How should Picture-in-Picture behave when the video is paused and the user navigates to the home screen?  
**A:** Do not enter PiP when paused (`autoEnterEnabled = false`). Navigating home minimizes/backgrounds the player without popping up a floating window.

### Aspect Ratio & Visual Transition
**Q2.** How should the PiP window aspect ratio and transition animation be handled?  
**A:** Dynamically match the video's actual aspect ratio (constrained to Android's 1:2.39 to 2.39:1 limits) and supply the active video surface bounds (`sourceRectHint`) for a seamless zoom-to-home animation.

### Playlist Transitions Inside PiP
**Q3.** When playing a playlist in PiP mode and the next track is audio-only, what should happen?  
**A:** Automatically close the PiP window (`finish()`) and continue audio playback seamlessly in the background notification service.

### Preference & Background Video Mode
**Q4.** How should the user's "Background Video Mode" setting (PiP vs Audio Only) affect auto-enter PiP?  
**A:** When configured as "Audio Only", disable PiP auto-enter (`autoEnterEnabled = false`) so navigating home continues audio in the background service without opening a floating window.

## Spec / Plan Impact
All confirmed answers align directly with the specifications defined in [.plans/spec.md](file:///F:/Share/UFM/.plans/spec.md) (specifically FR-01, FR-02, FR-03, FR-04, FR-07, FR-08, EC-01, and EC-05). No changes to the specification are needed.

## Confirmed — Ready for /tasks
All questions have been resolved. The clarification phase is complete.
