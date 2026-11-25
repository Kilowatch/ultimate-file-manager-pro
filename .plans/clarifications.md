# Clarifications: Android TV Onboarding Screens Redesign & D-Pad Navigation Fix

## Status
Answers received — ready for `/tasks`

## Questions and Answers

### UI and Layout
**Q1.** What overall layout direction should the 8 TV onboarding screens use?
**A:** Keep the two-column split (options on the left, live preview on the right) and fix proportions, spacing, and vertical fit per screen so all 8 fit cleanly without awkward scrolling or dead space.

### TV-Specific
**Q2.** How should D-pad focus be visually indicated across the TV onboarding screens?
**A:** Use a distinct focus state (bright focus ring + subtle scale/highlight via `TvFocusHelper` and TV focus selectors) separate from the selected state, applied consistently to Back, Skip, Next, option cards, and chips.

**Q3.** For Step 3 (Scrolling Text), how should the Marquee Scroll sub-options be structured for D-pad navigation?
**A:** Place Marquee sub-options (Speed, Loop, Scope) as independent focusable items below the Marquee card, visible/enabled only when Marquee is selected.

**Q4.** When a TV onboarding screen opens, where should initial D-pad focus land?
**A:** Land initial focus on the currently selected option card (or first option), with predictable D-pad navigation to Skip, Next, and Back.

## Spec / Plan Impact
None — `spec.md` and `plan.md` already incorporate all four confirmed decisions and align with the existing `TvFocusHelper` and `BaseOnboardingStepActivity` architecture.

## Confirmed — Ready for /tasks
All questions are answered and no spec or plan changes are outstanding. Proceed to `/tasks`.
