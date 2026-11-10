# Clarifications: Advanced Sync Remote Folder Target Resolution

## Status
Answers received — ready for /tasks

## Questions and Answers

### Business Logic and Edge Cases
**Q1.** When an Upload (local → remote) or Two-way sync job runs and the target remote folder does not exist on the server, what should happen?  
**A:** Automatically create the remote folder hierarchy (`mkdir`) and proceed with sync.

**Q2.** When a Download (remote → local) sync job runs and the target remote folder does not exist on the server, what should happen?  
**A:** Abort/fail the job with a descriptive notification without modifying or deleting any local files.

### Scope & Engine Parity
**Q3.** Which sync engines should be updated and verified?  
**A:** Align both `AdvancedSyncWorker` and `SyncWorker` to ensure consistent remote path handling across both sync engines.

### Protocol & Path Structure
**Q4.** Should SMB server-mode share name stripping be strictly isolated to `ShareType.SMB`, while preserving the full `profile.remotePath` for WebDAV and all other protocols?  
**A:** Yes, isolate SMB-specific share name stripping strictly to `ShareType.SMB` and preserve full `profile.remotePath` for WebDAV and other network/cloud protocols.

---

## Spec / Plan Impact
- **Engine Scope**: Expanded from `AdvancedSyncWorker` only to encompass both `AdvancedSyncWorker` (Advanced Sync engine) and `SyncWorker` (Classic 1-way Folder Sync engine) for architectural consistency.
- **Upload Auto-Creation**: Defined that during Upload / Two-Way sync, missing remote folders will be created recursively before syncing files into them.
- **Download Safety**: Defined that during Download sync, a missing remote folder will trigger an immediate failure with a notification, preventing any local data loss when "Sync deletions" is active.

---

## Confirmed — Ready for /tasks
All questions have been answered and recorded. You can now run `/tasks` to proceed with task breakdown.
