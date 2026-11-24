# Clarifications: High-Quality Badge & Custom App Icon Asset Pipeline

## Status
Answers received — ready for /plan and /tasks

## Questions and Answers

### 1. Visual Styling & Background (Set 2: Square App Icons)
**Q1.1:** What background treatment do you prefer for the Set 2 square app icons?
**A:** Subtle radial gradient (centered soft highlight behind badge fading to darker tone at edges).

**Q1.2:** Should the badge emblem have a soft drop shadow on the app icon background?
**A:** No, flat emblem directly on the background.

---

### 2. Android Icon Architecture (Adaptive Icons vs Flat Icons)
**Q2.1:** What Android icon architecture should be generated for Set 2?
**A:** Full set: Flattened square icons (2048px & 512px) PLUS Android Adaptive Icon layers (foreground with mobile launcher padding + matching background layer).

---

### 3. Android TV Banners & Formats
**Q3.1:** Will this pipeline also cover the Android TV banner (`tv_banner`)?
**A:** Yes. We are explicitly adding **Set 3: Android TV Leanback Banners** (`images/tv_banners/`):
- Standard 16:9 widescreen format (1920×1080 master, 1280×720 xxxhdpi, and 320×180 baseline Leanback).
- The milestone emblem centered on the matching harmonized radial gradient background with TV overscan margins.
- Exported in both PNG and WebP.

---

### 4. Output Location & Integration Scope
**Q4.1:** What is the scope and destination for this phase?
**A:** Create new folders just in `images/` for now, each set to have their own dedicated folder:
- `images/badges_transparent/`
- `images/app_icons_square/`
- `images/app_icons_adaptive/`
- `images/tv_banners/`
No code or resources inside `app/src/main/res/` will be altered during this phase.

---

### 5. Image Formats
**Q5.1:** Which file format should be generated?
**A:** Both lossless master PNGs (32-bit RGBA) and high-quality WebP assets for production efficiency.

---

## Spec / Plan Impact
- **Added FR-06 (TV Leanback Banners)** to [.plans/spec.md](file:///F:/Share/UFM/.plans/spec.md): Generates 16:9 banners in `images/tv_banners/` for each of the 10 milestone point tiers at 1920×1080, 1280×720, and 320×180 in both PNG and WebP formats.

---

### 6. Phase 2: In-App Badges Section in "Fuel the Developer" (Google Mobile Only)
**Q6.1:** For the mobile launcher icon update ("for the badge to be used, use cutouts"), how should the updated launcher icon be formatted?
**A:** (Recommended) Adaptive Icon with Cutout Foreground: Use the cutout emblem as the foreground layer with its matching color gradient background (ensures standard circular/squircle mask support on modern Android without being squished onto a plain white plate).

**Q6.2:** Should all 10 badges be immediately selectable, or unlocked through tipping?
**A:** (Recommended) All 10 badges freely selectable immediately in the Badges section.

**Q6.3:** What platforms/devices should this Badges section appear on?
**A:** Only for GOOGLE Mobile, nothing else (not for TV, not for Amazon, not for FOSS). Listed vertically from smallest (5 points) to biggest (1,000 points). Items display the `app_icons_square` preview and cutout badge thumbnail. Selecting an item immediately updates the home screen app icon via `<activity-alias>`.


---

### 7. Phase 3: Unlocking Expansion & Selection Confirmation Dialog
**Q7.1:** For the upcoming unlock expansion, how should users unlock the milestone badges?
**A:** Dollar-to-Points Scale ($1 = 10 Points):
- Every **$1 spent** grants **10 Points**.
- Point values per purchase:
  - Ristretto Shot ($1) -> 10 Points (unlocks 5, 10 pt tiers).
  - A Quick Espresso ($3) -> 30 Points (unlocks 20 pt tier).
  - The Cappuccino ($5) -> 50 Points (unlocks 50 pt tier).
  - The Full Latte ($10) -> 100 Points (unlocks 100 pt tier).
  - The Coffee Bean Bag ($25) -> 250 Points (unlocks 200 pt tier).
- Cumulative tipping accumulates points across all purchases toward higher tiers:
  - 300 Points ($30 total)
  - 500 Points ($50 total)
  - 750 Points ($75 total)
  - 1,000 Points ($100 total).

**Q7.2:** How should locked badges look in the selection list before they are unlocked?
**A:** Translucent overlay with Lock icon & Progress: The badge art remains visible with a subtle lock icon and progress indicator (e.g., "15 / 50 pts" or "Need 35 more pts to unlock").

**Q7.3:** When the user selects a badge, what user feedback should be shown?
**A:** Confirmation Dialog: A popup modal showing the updated icon preview with title, description, and an "OK" button (informing the user that the home screen icon has updated).

---

## Confirmed — Ready for /plan & /tasks
All requirements, dimensions, safe zones, and asset suites (Mobile Badges, Mobile App Icons, Adaptive Layers, and TV Banners) are completely specified.
