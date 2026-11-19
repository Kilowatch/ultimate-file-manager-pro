# Task List: Translation English String Inspection, Re-translation & Verification Engine

## Phase 1: Foundation & Allowlist Engine (`translate_strings.py`)
- [x] [T001] [P] Define `ALLOWLIST_EXEMPT_STRINGS` and implement `is_string_exempt(raw_text, name=None) -> bool` in `translate_strings.py` to identify brand names, proper nouns, protocols, format tokens, and technical acronyms that are exempt from re-translation.
- [x] [T002] Implement `detect_english_strings_in_file(english_text, localized_text)` in `translate_strings.py` to inspect `<string>` and `<string-array>` items against normalized English source text, excluding `translatable="false"` and exempt entries.

## Phase 2: Audit & Re-translation Implementation (`translate_strings.py`)
- [x] [T003] [P] Implement `run_audit_mode()` in `translate_strings.py` to scan all 18+ non-English `values-*` directories and files (`strings.xml`, `strings_policy.xml`), queue detected English strings, and dispatch re-translations via the multi-threaded fallback chain.
- [x] [T004] Implement detailed transformation logging in `run_audit_mode()` printing `[{lang}] [{file}:{key}] from -> to: "{en_text}" -> "{translated_text}"` for every update, and output an end-of-run statistical summary (total inspected, detected, re-translated, skipped, and unresolved).
- [x] [T005] Update `main()` in `translate_strings.py` to route `audit` from CLI arguments (`sys.argv[1] == 'audit'`) and add the audit mode option to the interactive `questionary` menu.

## Phase 3: Post-Translation Verification Gate (`translate_strings.py`)
- [x] [T006] [P] Integrate post-translation verification audit into `run_verify_mode()` in `translate_strings.py` to inspect all translated files immediately following synchronization.
- [x] [T007] Implement active auto-retranslation retry in `run_verify_mode()` for lingering English strings, logging retries and highlighting any unresolved strings as warnings in the final verify report.

## Phase 4: Batch Script & Build Tool GUI Integration
- [x] [T008] Update `build.bat` to support CLI argument invocation (`build.bat audit`, `build.bat --audit-translations`) routing directly to console execution while preserving GUI startup when executed without arguments.
- [x] [T009] Add **"🔄 Audit & Fix English Strings"** button to the Translations tab in `APKScripts/build_tool.py`, wire its click handler to execute `translate_strings.py audit`, and manage its disabled/enabled state during translation tasks.

## Phase 5: Verification & Quality Assurance
- [x] [T010] [P] Verify exemption and detection logic with targeted unit tests covering brands, acronyms, placeholders, and translatable sentences.
- [ ] [T011] [USER TEST] Execute `python -u translate_strings.py audit` and verify that `from -> to` transformations are logged and target XML files remain structurally valid (to be run by user).
- [ ] [T012] [USER TEST] Execute `python -u translate_strings.py verify` to confirm the post-translation quality audit and lingering English warning checks operate cleanly (to be run by user).
- [ ] [T013] [USER TEST] Run `./gradlew assembleMobileGoogleDebug` to guarantee all updated localized XML files compile cleanly without AAPT resource errors (to be run by user).
