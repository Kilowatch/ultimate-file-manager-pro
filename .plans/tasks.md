# Task List: Anonymous Loyalty Pass & Central GPA Tip Verification System

**Target Platform Boundary**: Google Mobile Only (`mobileGoogle`, `!isTv && !isAmazonDevice`)  
**Backend**: PHP APIs (`F:\APK\api\`), MySQL (`kilowatc_ufm_tips`)  
**Status**: Completed

---

## Phase 1: Database Architecture & Schema Migration
- [x] **[T001]** Create `F:\APK\api\schema_loyalty_system.sql` defining:
  - Table `loyalty_passes`: `id`, `loyalty_id` (VARCHAR 19 UNIQUE), `passkey_hash`, `total_points`, `total_amount_usd`, `failed_attempts`, `locked_until`, `created_at`, `updated_at`.
  - Table `gpa_orders`: `id`, `pass_id` (FK to `loyalty_passes.id`), `loyalty_id`, `order_id` (VARCHAR 64 UNIQUE), `product_id`, `tip_amount`, `currency`, `points`, `call_count`, `last_called_at`, `claimed_at`, `created_at`, `updated_at`.

---

## Phase 2: Backend Historical Backfill & Real-Time Ingestion
- [x] **[T002]** Implement `F:\APK\api\backfill_gpa_orders.php`:
  - Connects using `Database::getInstance()->getConnection()`.
  - Queries `event_log` for messages matching `Tip processed: $X.XX USD (product: ..., order: GPA....)`.
  - Computes `points = ROUND(tip_amount * 10)`.
  - Batch inserts into `gpa_orders` with initial `call_count = 0` and `pass_id = NULL`.
  - Reports total records scanned, inserted, and skipped.
- [x] **[T003]** Update `F:\APK\api\rtdn.php`:
  - In step 7d (purchase verification `notificationType === 1`), add atomic insertion to `gpa_orders`:
    - Normalizes `$orderId` to uppercase.
    - Computes `points = (int) round($usdAmount * 10)`.
    - Upserts into `gpa_orders` (`order_id`, `product_id`, `tip_amount`, `currency`, `points`, `call_count = 0`).

---

## Phase 3: Security, Rate Limiter & Verification API
- [x] **[T004]** Implement `F:\APK\api\RateLimiter.php`:
  - Sliding window file-backed rate limiter.
  - Global IP throttle: 30 requests per minute per IP.
  - Brute-force passkey throttle: 5 consecutive failed attempts on a Loyalty ID locks it for 15 minutes.
  - Provides `checkLimit($key, $max, $window)` and `recordFailure($key)`.
- [x] **[T005]** Implement `F:\APK\api\verify_gpa.php` with 4 case-insensitive actions:
  - Normalize all inputs: `UPPER(TRIM(...))`.
  - `check_gpa`: Validates regex `^GPA\.\d{4}-\d{4}-\d{4}-\d{5}$/i`, checks if order exists in `gpa_orders`, returns `{ success: true, status: "unclaimed" }` or `{ success: true, status: "already_claimed", loyalty_id_masked: "B4K-***-***-8R1" }`.
  - `create_pass`:
    - Collision-proof generation loop: checks `SELECT 1 FROM loyalty_passes WHERE loyalty_id = :id` up to 10 times to guarantee the new Loyalty ID does not already exist.
    - Generates 16-character secure random Passkey (`random_bytes`).
    - Hashes with `password_hash($passkey, PASSWORD_BCRYPT)`.
    - Inserts row into `loyalty_passes` and links `gpa_orders` record.
    - Returns Loyalty ID, raw Passkey, points, and tip history.
  - `claim_to_existing`:
    - Validates Loyalty ID and verifies `password_verify($passkey, $row['passkey_hash'])`.
    - Attaches unclaimed GPA to the Loyalty Pass.
    - Recalculates `total_points = SUM(points)` in `gpa_orders` and updates `loyalty_passes`.
    - Returns all tips and updated total points.
  - `restore`:
    - Resolves Loyalty ID from direct input or previous claimed GPA lookup.
    - Verifies passkey.
    - Returns full tip history and cumulative points for local delta sync.
  - Includes 30–60ms micro-sleep jitter on failed lookups to defeat timing analysis.

---

## Phase 4: Android Client Formatting & State (Google Mobile Only)
- [x] **[T006]** Implement `app/src/google/java/za/kilowatch/ultimatefilemanager/billing/GpaInputFormatter.kt`:
  - Fixed, non-editable `GPA.` prefix in input fields.
  - Smart `TextWatcher` that auto-inserts hyphens as user types: `XXXX-XXXX-XXXX-XXXXX`.
  - Smooth backspacing support without getting stuck on hyphens.
  - Paste-aware sanitization: strips any leading `GPA.` or `gpa.`, extracts the 17 digits, and applies hyphens.
  - Loyalty ID formatter: uppercase auto-transform and `XXX-XXX-XXX-XXX` auto-hyphenation.
- [x] **[T007]** Implement `app/src/google/java/za/kilowatch/ultimatefilemanager/billing/LoyaltyPassManager.kt` (Google Mobile):
  - Manages private shared preferences storage of active Loyalty ID, Passkey, cached orders, and points.
  - Performs async HTTPS API calls to `verify_gpa.php` using coroutines and `UfmHttpClient`.
  - Delta Tip Syncing: compares server tip history with local cache, inserts only missing entries, and maintains authoritative `total_points`.
  - Exposes `checkGpa(orderId)`, `createPass(context, orderId)`, `claimToExisting(context, orderId, loyaltyId, passkey)`, `restorePass(context, identifier, passkey)`, and `syncPass(context)`.

---

## Phase 5: UFMStandard Glass Dialogs
- [x] **[T008]** Create `app/src/main/res/layout/dialog_unclaimed_tip_prompt.xml`:
  - Strictly follows UFMStandard: `@drawable/bg_dialog_glass`, 56×56dp frosted squircle header icon (`@drawable/bg_btn_icon_frosted`), 20sp `sans-serif-black` title.
  - Prompts: *"Do you already have a Loyalty ID (`XXX-XXX-XXX-XXX`)?"*
  - Buttons: *"Yes, Connect to Existing Loyalty ID"* & *"No, Generate My Loyalty Pass"*.
  - Discloses: *"🔒 100% Anonymous: UFM never captures names, emails, or personal details."*
- [x] **[T009]** Create `app/src/main/res/layout/dialog_loyalty_pass_credentials.xml`:
  - Displays generated Loyalty ID and Passkey in frosted glass card (`@color/mobile_glass_card`).
  - "Copy to Clipboard" button.
  - Explanatory note to save credentials for future reinstalls or device changes.
  - "I Have Saved My Passkey" action button.
- [x] **[T010]** Create `app/src/main/res/layout/dialog_loyalty_pass_restore.xml`:
  - Input field with auto-hyphenation for entering Loyalty ID or previous GPA.
  - Password input field for Passkey.
  - "Unlock & Restore Points" primary action button.
- [x] **[T011]** Create `app/src/main/res/layout/dialog_claim_past_gpa.xml`:
  - Input field for existing supporters to enter historical GPA order numbers.
  - Fixed `GPA.` prefix and auto-hyphenation.
  - "Verify & Claim" button.
- [x] Create `app/src/main/res/layout/dialog_connect_existing_pass.xml`:
  - Input fields for existing Loyalty ID (`XXX-XXX-XXX-XXX`) and Passkey to link an unclaimed tip.

---

## Phase 6: Google Mobile UI Integration & Points Replacement
- [x] **[T012]** Update strings in `app/src/main/res/values/strings.xml` for all Loyalty Pass dialogs, notices, and buttons (zero "account" terminology).
- [x] **[T013]** Update `app/src/main/res/layout/activity_supporter_loyalty.xml` (Google Mobile layout):
  - Add **Anonymous Loyalty Pass Card** above tip items:
    - If no pass active: Shows "Anonymous Loyalty Pass", "No Loyalty Pass connected yet", "Claim Past Order", and "Restore Loyalty Pass".
    - If pass active: Displays Loyalty ID (`XXX-XXX-XXX-XXX`), synced points, "Claim Past Order", "View Passkey / Backup", and "Switch / Restore Pass".
- [x] **[T014]** Integrate in `app/src/google/java/za/kilowatch/ultimatefilemanager/billing/SupporterLoyaltyActivity.kt`:
  - Strictly guarded: only runs when `!isTv && !isAmazonDevice`.
  - When tip purchase finishes (`onPurchaseSuccessWithOrder`), invokes auto-claim if pass exists or prompts unclaimed tip dialog.
  - Replaces local `LoyaltyPrefs.getTotalTipped()` points with authoritative `LoyaltyPassManager.getTotalPoints(context)` across milestone badges, custom app icon unlocks, and stamp progress.
  - Connects "Claim Past Tip" and "Restore Loyalty Pass" button flows.

---

## Phase 7: Verification & Testing
- [x] **[T015]** Validate PHP syntax on all scripts:
  - `php -l F:\APK\api\RateLimiter.php` (No syntax errors detected)
  - `php -l F:\APK\api\verify_gpa.php` (No syntax errors detected)
  - `php -l F:\APK\api\backfill_gpa_orders.php` (No syntax errors detected)
  - `php -l F:\APK\api\rtdn.php` (No syntax errors detected)
- [x] **[T016]** Compile Android app for Google Mobile Debug:
  - `./gradlew compileMobileGoogleDebugKotlin` (BUILD SUCCESSFUL)
  - `./gradlew assembleMobileGoogleDebug` (BUILD SUCCESSFUL)
- [x] **[T017]** Verify Amazon and FOSS flavors are completely unaffected:
  - `./gradlew compileMobileAmazonDebugKotlin` (BUILD SUCCESSFUL)
  - `./gradlew compileMobileFossDebugKotlin` (BUILD SUCCESSFUL)
