# Code Review: Anonymous Loyalty Pass, Past Tip Claiming (GPA), Custom App Icon & PHP Backend

## Summary
Reviewed the Anonymous Loyalty Pass, Google Play Order ID (`GPA.XXXX-XXXX-XXXX-XXXXX`) claiming/restoration, Custom App Icon unlock & UI updates, and the supporting PHP API scripts (`verify_gpa.php`, `rtdn.php`, `backfill_gpa_orders.php`, `Database.php`, `RateLimiter.php`). All previously identified Major, Minor, and Suggestion items have been resolved and verified.

## Findings

### 🔴 Critical
*None.*

### 🟠 Major
*All resolved:*
- **[SupporterLoyaltyActivity.kt:988-1035, 1058, 1174-1240, 1269-1305, 1334-1370]** *Fixed:* Added `refreshLoyaltySectionsAfterPassChange()` to refresh `setupCustomIconSection()`, `setupBadgesSection()`, and `refreshLoyaltyUI()` (plus `setupLoyaltyPassSection()` when appropriate) across all pass creation, claim, restore, and silent sync flows.
- **[verify_gpa.php:253-305, 393-435]** *Fixed:* Moved `SELECT ... FROM gpa_orders ... FOR UPDATE` inside `$pdo->beginTransaction()` in both `create_pass` and `claim_to_existing`, and guarded `UPDATE gpa_orders` with `AND pass_id IS NULL` / `AND (pass_id IS NULL OR pass_id = :pid_check)` with `rowCount() !== 1` rollback checks.
- **[LoyaltyPassManager.kt:333-348]** *Fixed:* `restorePass()` now clears `KEY_ORDERS_JSON` before `deltaSync()` whenever the restored `serverLoyaltyId` differs from the previously cached `loyaltyId`.

### 🟡 Minor
*All resolved:*
- **[verify_gpa.php:57-79]** *Fixed:* Replaced path-disclosing HTTP 500 messages with `error_log(...)` and a generic `'Service configuration error.'` response.
- **[backfill_gpa_orders.php:20-26]** *Fixed:* Replaced the static `?key=ufm_admin_migration` query parameter with CLI-only access by default (or `hash_equals` against `getenv('UFM_MIGRATION_KEY')` when configured).
- **[SupporterLoyaltyActivity.kt & values/strings.xml]** *Fixed:* Extracted hardcoded dialog titles, subtitles, and messages into `loyalty_dialog_*` string resources.
- **[verify_gpa.php:513-532]** *Fixed:* Added `$rateLimiter->isLocked("ident_$ident", 5, 900, ...)` enforcement and `resetFailure("ident_$ident")` on lookup success in `restore`.

### 🔵 Suggestions
*Applied:*
- **[LoyaltyPassManager.kt:88-99]** *Fixed:* `getTotalPoints()` now returns `syncedPoints` directly when an active Loyalty Pass is connected, ensuring switched passes reflect authoritative server points.
- **[GpaInputFormatter.kt:24-28 & dialog_claim_past_gpa.xml:84-92]** Verified: `TYPE_CLASS_PHONE` + `DigitsKeyListener("0123456789-")` + `TextWatcher` keeps the keyboard numeric while preserving auto-inserted hyphens (`XXXX-XXXX-XXXX-XXXXX`) and the fixed `GPA.` prefix.

## Checklist Coverage
- **Privacy & Security**: Clean. Zero PII collected or stored; `no-referrer` / `no-store` / `X-Frame-Options: DENY` headers set; bcrypt (`cost: 12`) for Passkeys; PDO prepared statements and transactional `FOR UPDATE` locking across all claim paths.
- **Idempotency & Point Integrity**: Clean. `check_gpa` blocks already-claimed GPAs and triggers the UFMStandard `Already Claimed` dialog; `claim_to_existing` recalculates `SUM(points)` per `pass_id`.
- **Android Architecture & Flavor Isolation**: Clean. `CustomIconManager` reflection isolates `src/main` from `src/google`.
- **UI & UFMStandard Compliance**: Clean. Vertical pass buttons, single-line custom icon pill, high-contrast unclaimed tip prompt buttons, and frosted glass UFMStandard info/claim dialogs verified.

## Verdict
**Approve** — All findings and suggestions resolved; ready for verification build.
