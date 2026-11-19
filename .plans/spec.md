# Specification: Translation English String Inspection, Re-translation & Verification Engine

## Overview
Ultimate File Manager Pro (UFM) supports 18+ localized languages across Android mobile and Android TV platforms (`values-ar`, `values-de`, `values-es`, `values-fr`, `values-hi`, `values-id`, `values-in`, `values-it`, `values-ja`, `values-ko`, `values-nl`, `values-pt`, `values-ru`, `values-sv`, `values-tr`, `values-uk`, `values-zh`). Over time, hundreds of localization entries in non-English resource files (`res/values-*/strings.xml` and `res/values-*/strings_policy.xml`) have ended up containing untranslated English text due to template fallback copy, translator network timeouts, or partial syncs. Because standard synchronization previously considered a key "translated" if the XML tag simply existed, these English strings remain permanently untranslated in localized builds.

This specification defines a comprehensive inspection, re-translation, and verification engine for UFM localization:
1. **Audit & Re-translation Mode**: Systematically inspects every non-English language directory, detects strings whose content is still in English, re-translates them into their target language, and writes clean XML updates.
2. **Transformation Logging**: Detailed output logs for every re-translated string displaying language code, resource key, source English text (`from`), and translated localized text (`to`).
3. **Verification Gate**: Enhances standard verify mode (`translate_strings.py verify`) with an automated post-translation audit that inspects all localized strings to guarantee no English strings linger, failing or warning if discrepancies remain.
4. **Build System & Batch Integration**: Updates `build.bat` and the desktop build tool console (`APKScripts/build_tool.py`) to expose single-click and CLI execution of the English audit and re-translation process.

---

## User Stories
- **US-01 (Automated Locale Audit):** As a developer or release manager, I want to execute a command that scans all non-English string resources so that every string erroneously left in English is identified without manual file-by-file inspection.
- **US-02 (Targeted Re-translation):** As a developer, I want the translation engine to automatically re-translate only those strings identified as English into their respective target languages so that localized users receive fully translated UI text.
- **US-03 (Transparent Transformation Logs):** As a developer, I want to see detailed console and file logs showing the language, string key, English source (`from`), and new translation (`to`) so that I can audit and verify translation accuracy.
- **US-04 (Build Tool & Batch Execution):** As a developer running `build.bat` or the GUI build tool, I want a dedicated command and UI action to run this English audit and re-translation workflow directly without manually crafting python terminal commands.
- **US-05 (Standard Verify Quality Gate):** As a developer running the standard verification pipeline (`translate_strings.py verify`), I want the tool to automatically check translated files upon completion to verify no English strings remain, alerting me if any strings could not be localized.

---

## Functional Requirements

### 1. English String Detection & Inspection Engine
- **FR-01 (Comprehensive Scope):** The inspection engine must inspect all non-English localized values directories under `app/src/main/res/` (e.g. `values-es`, `values-de`, `values-fr`, `values-zh`, etc.) and all localization files (`strings.xml`, `strings_policy.xml`). Default `res/values/` is treated as the English source of truth and must never be modified.
- **FR-02 (String Item Detection):**
  - Compare each localized `<string>` entry against the corresponding source entry in `values/strings.xml`.
  - A string is flagged as requiring re-translation if:
    1. The localized text matches the English text identically (case-insensitive, normalized whitespace).
    2. The string is not marked `translatable="false"` in the source English file.
    3. The string is not exempt under the Brand / Technical Symbol Allowlist (see FR-05).
- **FR-03 (String-Array Item Detection):**
  - Inspect every `<item>` in `<string-array>` definitions within localized files.
  - Compare items at each index against the source English array item at that same index.
  - Flag individual items for re-translation if their content remains identical to English.

### 2. Re-translation Engine
- **FR-04 (Targeted Re-translation):**
  - For each detected English string or array item, submit the source text to the translation provider chain (Google -> MyMemory -> Pons) targeting the specific locale.
  - Preserve placeholder tokens (`%s`, `%d`, `%1$s`, `$variable`, `[[PH_*]]`) and HTML formatting tags (`<b>`, `<i>`, `<br/>`, `<![CDATA[...]]>`) intact throughout translation.
  - Only write the translation if the provider returns a non-empty, localized result distinct from the English source text (unless the term is intrinsically identical in that language).
- **FR-05 (Brand Name & Technical Term Exemption Handling):**
  - Maintain a configurable allowlist of terms, brand names, and technical acronyms that are legitimate in non-English files (e.g. "Ultimate File Manager Pro", "Google Drive", "Dropbox", "OneDrive", "WebDAV", "FTP", "SFTP", "SMB", "NFS", "LAN", "IP", "URL", "PIN", "PDF", "RAM", "CPU", "MD5", "SHA-256", "KB", "MB", "GB", "OK", "TV").
  - Strings matching allowlisted terms or consisting purely of punctuation, digits, or format specifiers must not be flagged as untranslated English errors.

### 3. Transformation Logging
- **FR-06 (Structured Console & File Logging):**
  - Every string re-translated by the audit command must emit an explicit log entry with the format:
    `[{lang_code}] [{file_name}:{key}] from -> to: "{english_text}" -> "{translated_text}"`
  - Array items must log the array key and index:
    `[{lang_code}] [{file_name}:{array_key}[{idx}]] from -> to: "{english_text}" -> "{translated_text}"`
  - When the inspection completes, the engine must print a summary:
    - Total files inspected
    - Total strings inspected per language
    - Total English strings detected
    - Total strings successfully re-translated
    - Total strings skipped / allowlisted
    - Total failures (if translation API failed or returned identical English)

### 4. Verification Gate ("Standard Verify" Integration)
- **FR-07 (Post-Translation Quality Audit):**
  - When running standard verification (`python -u translate_strings.py verify` or clicking "Verify All Translations" in build tools):
    1. The script first synchronizes missing or modified keys as normal.
    2. Immediately following synchronization, a post-translation audit pass must execute across all non-English files.
    3. The audit scans all active strings against English source text.
- **FR-08 (Lingering English Reporting & Alerting):**
  - If any untranslated English strings remain in localized files after synchronization, they must be listed explicitly in the verification report with their language and key.
  - The verification process must report an exit status or warning summarizing the unlocalized entries so that developers are aware of incomplete translations before building releases.

### 5. Build System & Tooling Integration
- **FR-09 (`build.bat` Support):**
  - Update `build.bat` to support invoking the English audit and re-translation process.
  - Support both CLI argument invocation (e.g., `build.bat --audit-translations` or `build.bat retranslate`) and interactive invocation.
- **FR-10 (Build Tool GUI Integration):**
  - In `APKScripts/build_tool.py` (Translations tab):
    - Add a dedicated action button: **"🔄 Audit & Fix English Strings"** (or **"🌐 Re-translate Lingering English"**).
    - Connect the button to run the audit command on the active translation engine (`translate_strings.py` or DeepSeek), routing live logs directly into the GUI console.
    - Update the status badge to reflect scanning, re-translating, and completion states.

---

## Non-Functional Requirements
- **NFR-01 (XML File Integrity & Formatting):** All XML modifications must use single-pass substitution preserving existing file structure, comments, encoding (`UTF-8`), and valid Android string escaping (`\'`, `\"`, `\n`, `&amp;`).
- **NFR-02 (API Rate Limiting & Concurrency):** Re-translation calls must respect rate limits and back-off policies (minimum 250ms spacing or retry back-off) across worker threads to avoid HTTP 429 Too Many Requests errors.
- **NFR-03 (Idempotency):** Running the audit command multiple times consecutively without changing English source text must be safe and idempotent, detecting 0 remaining English strings on subsequent passes.
- **NFR-04 (Performance):** Reading and inspecting localized files must be fast (under 3 seconds for all 18+ languages), with network calls dispatched only for strings requiring re-translation.

---

## Edge Cases & Error States
- **EC-01 (Legitimately Identical Words Across Languages):** Certain words (e.g. "Terminal", "Menu", "Audio", "Radio", "Filter", "Host", "Port" in Spanish/Dutch/German) are identical in English and the target language. The detection algorithm must compare normalized full phrases and check allowlists or length heuristics to avoid unnecessary churn.
- **EC-02 (Compound Formatting Tags & CDATA):** Strings containing complex CDATA blocks or nested HTML tags (e.g. `<string name="..."><b>Warning:</b> Do not disconnect</string>`) must isolate the translatable text nodes, preserving HTML tags and CDATA envelopes.
- **EC-03 (Translation API Outages / Fallbacks):** If Google Translate fails, fallback to MyMemory and Pons. If all providers fail for a string, the system must not overwrite the existing string, must log a failure warning, and must report the unresolved key in the summary.
- **EC-04 (Strings Marked `translatable="false"`):** Strings with `translatable="false"` in `values/strings.xml` must be completely ignored by both the audit and verify routines.
- **EC-05 (Empty or Blank Nodes):** Strings that are empty or whitespace-only must not trigger translation requests.

---

## Out of Scope
- Modifying or re-translating the source English files in `res/values/`.
- Adding new third-party paid translation API subscriptions.
- Translating non-Android resources outside of `app/src/main/res/values-*/`.

---

## Success Criteria
- [ ] Command added to `translate_strings.py` to audit and re-translate English strings across all non-English `values-*` folders.
- [ ] All 18+ non-English language folders are inspected for identical/lingering English strings.
- [ ] Logs show language code, resource key, source English text (`from`), and translated text (`to`) for every re-translated string.
- [ ] Allowlist and heuristic rules prevent false positives on brand names ("Ultimate File Manager Pro"), cloud providers ("Google Drive", "Dropbox"), protocols ("SMB", "WebDAV"), and symbols.
- [ ] `build.bat` updated to support executing the audit and re-translation command.
- [ ] `APKScripts/build_tool.py` updated with a dedicated button on the Translations tab to run the audit and stream `from -> to` logs.
- [ ] Standard verify command (`python translate_strings.py verify`) includes post-translation validation checking all translated files to ensure no unexempt English strings linger, reporting any findings in the final summary.
