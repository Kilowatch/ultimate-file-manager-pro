# Clarifications: Modern Categorized View Expanded Category Redesign (Mobile)

## Status
Answers received — ready for /plan and /tasks.

## Questions and Answers

### UI and Layout
**Q1. How should the hairline dividers between child items be aligned?**  
**A:** Inset dividers — indented past the icon badge (~68dp from the left edge of the card) to align with the text column, extending to the right edge, matching `expanded.jpg`.

**Q2. How should the item count be displayed in the category header?**  
**A:** Remove the count pill badge. Use clean inline/subtitle item count text:
- When collapsed: category title and item count rendered inline/subtitle without a background pill.
- When expanded: item count rendered cleanly as a subtitle directly under the category title.

**Q3. Should the collapsed 'Storage' category display a summary progress bar?**  
**A:** Yes. When collapsed, display an overall storage progress bar and aggregate capacity text showing the sum of total free vs. available (e.g., "52.97 GB free of 145 GB"). When expanded, display individual progress bars and capacities on each individual drive tile as it currently functions.

**Q4. Should expanded category headers have a subtle contrasting glass background highlight?**  
**A:** Yes. Apply a subtle contrasting glass highlight on expanded headers with rounded top corners matching the outer card container (24dp), creating a clear visual header band.

## Spec / Plan Impact
- `spec.md` updated with FR-03 for collapsed Storage summary capacity and progress bar.
- Architecture plan will update `item_storage_category_group.xml` and `StorageAdapter.kt` to handle collapsed storage summary calculation and inset child row rendering without standalone nested cards.

## Confirmed — Ready for /plan
All clarification questions answered. Proceeding to design the implementation plan.
