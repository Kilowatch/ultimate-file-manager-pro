# Clarifications: Translation English String Inspection, Re-translation & Verification Engine

## Status
Answers received and confirmed — ready for /plan and /tasks

---

## Questions and Answers

### 1. English String Detection & Matching Rule
**Q:** How should the audit engine determine if a string in a non-English file is "in English"?  
**A:** **Exact text match against English source (normalized whitespace) with an allowlist**: Compare localized strings against the English source in `values/strings.xml` (normalized whitespace, case-insensitive). Exclude strings on an allowlist of brand names, proper nouns, protocols, and technical symbols (e.g. "Ultimate File Manager Pro", "Google Drive", "Dropbox", "SMB", "FTP", "WebDAV", "OK", "%s").

### 2. Command-Line Syntax in `translate_strings.py`
**Q:** What CLI command keyword should trigger the audit and re-translation pass?  
**A:** **`audit`**: Triggered via `python -u translate_strings.py audit`.

### 3. `build.bat` Integration Workflow
**Q:** How should `build.bat` support this new command?  
**A:** **Dual support (CLI argument + GUI button)**:
- CLI argument: Running `build.bat audit` (or `build.bat --audit-translations`) executes the Python audit directly in the console with live `from -> to` logs.
- GUI: Running `build.bat` without arguments launches `APKScripts/build_tool.py`, which features a dedicated **"🔄 Audit & Fix English Strings"** button on the Translations tab.

### 4. Standard Verify Post-Translation Behavior
**Q:** In standard verify mode (`python -u translate_strings.py verify`), what should the post-translation check do if lingering English strings are found?  
**A:** **Auto-retranslate then report**: During standard `verify`, immediately run an automatic re-translation pass for any detected lingering English strings. If any still remain unresolved (e.g. translation provider API timeout/failure), list them as warnings with their language and key in the final summary.

### 5. Translation Engine Scope
**Q:** Which translation engines should support this audit/re-translation command and verify check?  
**A:** **`translate_strings.py` only**: Scope is specifically focused on the primary `translate_strings.py` (Google/MyMemory/Pons) engine.

---

## Spec & Plan Impact
- **Engine Scope**: Confirmed that implementation is strictly targeted to `translate_strings.py`.
- **Command Keyword**: Confirmed keyword `audit` for CLI execution in `translate_strings.py` and `build.bat`.
- **Verify Workflow**: Standard verify mode (`translate_strings.py verify`) will execute an active auto-retranslation pass on lingering English strings before generating its final report.

---

## Confirmed — Ready for Next Phase
All questions confirmed. The next step is to run `/plan` to define the architecture, file modifications, and design details.
