package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import za.kilowatch.ultimatefilemanager.UfmApplication
import za.kilowatch.ultimatefilemanager.indexing.FileIndex
import za.kilowatch.ultimatefilemanager.indexing.MetadataExtractor
import za.kilowatch.ultimatefilemanager.indexing.UfmIndexingDatabase
import za.kilowatch.ultimatefilemanager.network.NetworkFile
import za.kilowatch.ultimatefilemanager.network.NetworkShare
import za.kilowatch.ultimatefilemanager.network.NetworkShareRepository
import za.kilowatch.ultimatefilemanager.network.OnlineStorageProvider
import za.kilowatch.ultimatefilemanager.network.OnlineStorageRepository
import za.kilowatch.ultimatefilemanager.network.PairingManager
import za.kilowatch.ultimatefilemanager.network.RCloneShareClient
import za.kilowatch.ultimatefilemanager.network.ShareType
import za.kilowatch.ultimatefilemanager.network.SmbShareClient
import za.kilowatch.ultimatefilemanager.network.FtpShareClient
import za.kilowatch.ultimatefilemanager.network.TvShareClient
import za.kilowatch.ultimatefilemanager.network.SshShareClient
import za.kilowatch.ultimatefilemanager.network.OnedriveShareClient
import za.kilowatch.ultimatefilemanager.network.GoogleDriveShareClient
import za.kilowatch.ultimatefilemanager.network.DropboxShareClient
import za.kilowatch.ultimatefilemanager.network.S3ShareClient
import za.kilowatch.ultimatefilemanager.network.WebDavShareClient
import za.kilowatch.ultimatefilemanager.network.NfsShareClient
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.util.DestinationCapabilities
import za.kilowatch.ultimatefilemanager.util.ExclusionLog
import za.kilowatch.ultimatefilemanager.util.FileTransferGuard
import za.kilowatch.ultimatefilemanager.util.FilesystemCapabilities
import za.kilowatch.ultimatefilemanager.util.PreflightBlockedException
import za.kilowatch.ultimatefilemanager.util.PreflightVerdict
import za.kilowatch.ultimatefilemanager.util.TransferConflictHelper
import za.kilowatch.ultimatefilemanager.util.TransferManager
import za.kilowatch.ultimatefilemanager.util.TransferPreflight
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Paste-into-LOCAL-storage engine, relocated verbatim from `FileBrowserActivity.performPaste`
 * (local→local incl. SAF/Root/Shizuku, and network/cloud→local downloads). It is
 * activity-agnostic: it runs on a [TransferManager.TransferSession] and never touches UI,
 * the foreground service, or the Activity. Byte-copy primitives are wrapped in
 * [TransferManager.TransferSession.withFileRetry].
 */
object LocalPasteEngine {

    /** Everything the recursion reads off the launching Activity, snapshotted at start. */
    data class LocalPasteContext(
        val appContext: Context,
        /** The folder visible in the Activity — destination for network/cloud items. */
        val currentDir: File,
        /** Actual paste destination root (quickTransferDestDir ?: currentDir). */
        val effectiveDestDir: File,
        val storageId: String,
        val storageType: String,
        val slots: List<FileClipboard.Slot>,
        val targetSlotId: Long?
    )

    suspend fun pasteIntoLocal(session: TransferManager.TransferSession, ctx: LocalPasteContext) {
        val appContext = ctx.appContext
        val currentDir = ctx.currentDir
        val effectiveDestDir = ctx.effectiveDestDir
        val storageId = ctx.storageId
        val storageType = ctx.storageType

        val db = UfmIndexingDatabase.getInstance(appContext)
        val dao = db.fileIndexDao()
        val metadataExtractor = MetadataExtractor(appContext)
        val pendingIndices = mutableListOf<FileIndex>()

        suspend fun flushIndices() {
            if (pendingIndices.isNotEmpty()) {
                dao.insertAll(pendingIndices.toList())
                pendingIndices.clear()
            }
        }

        val applyToAllRef = booleanArrayOf(false)
        var globalAction: TransferConflictHelper.ConflictAction? = null

        val conflictMutex = Mutex()
        suspend fun askConflict(
            name: String,
            isFolder: Boolean,
            destSize: Long
        ): TransferConflictHelper.ConflictAction = conflictMutex.withLock {
            val existing = globalAction
            if (existing != null) return existing
            val action = session.resolveConflict(name, isFolder, destSize, applyToAllRef)
            if (applyToAllRef[0]) globalAction = action
            return action
        }

        fun isSaf(path: File): Boolean =
            path is SafFile || SafTreeManager.isSafPath(path.absolutePath) ||
                SafTreeManager.hasTreePermissionForPath(appContext, path.absolutePath)

        fun isRoot(path: File): Boolean =
            path is RootFile || RootShellWrapper.isRootPath(path.absolutePath)

        /**
         * Reports a file the destination cannot hold (FR-11, FR-15).
         *
         * A skip, never a failure: nothing was attempted and no byte was written, so counting it
         * as a failure would both mis-report the outcome and teach the user to ignore a failure
         * count that has refusals mixed into it.
         */
        fun notePreflightSkip() {
            session.noteSkipped(appContext.getString(R.string.preflight_skipped_too_large))
        }

        // ── Pre-flight scan and total file count (FR-11, FR-12, FR-13) ──────
        //
        // One walk, not two. The count loop below already had to descend every local source, so
        // evaluating each file as it is counted costs no extra traversal — which is what keeps
        // this inside NFR-01. The mount table is read once here for the whole operation and
        // reused by every file, never re-read per file.
        val mounts = FilesystemCapabilities.readMountTable()
        val exclusionLog = ExclusionLog()
        // Keyed on the *source* path, not the destination. A KEEP_BOTH copy has its destination
        // chosen later, at copy time, so a destination-keyed set would miss exactly the files
        // whose name changed — and the miss would show up as progress reading "9 of 8".
        val excludedSources = ConcurrentHashMap.newKeySet<String>()
        // Total bytes that will actually be written, for the free-space check (FR-08). Excluded
        // files are left out of it for the same reason they are left out of the count: they will
        // not be written, so counting them would warn about space that is never needed.
        var totalBytes = 0L

        // Destination capabilities by probed path. The pre-scan asks the same question once per
        // file, and on a root or Shizuku destination each answer costs a `stat -f` subprocess. The
        // answer cannot change mid-operation for a given directory — it is a property of the mount
        // the path resolves to — so it is asked once per directory instead. `TruncatedFileScanner`
        // keeps the same cache for the same reason; this is the paste path catching up with it.
        //
        // Not a synchronized map, and deliberately so: the walk below is a single sequential
        // recursion, so one directory is never probed from two places at once.
        val capabilitiesByPath = ConcurrentHashMap<String, DestinationCapabilities>()

        /** [FilesystemCapabilities.probe] once per directory, reused for every file inside it. */
        suspend fun capabilitiesAt(path: File): DestinationCapabilities {
            capabilitiesByPath[path.absolutePath]?.let { return it }
            return FilesystemCapabilities.probe(appContext, path, mounts)
                .also { capabilitiesByPath[path.absolutePath] = it }
        }

        /**
         * Walks [source], counting the files that will actually transfer and recording the ones
         * the destination provably cannot hold (FR-11).
         *
         * A refused file counts as zero: the count the progress text divides by is then truthful
         * by construction, with no subtraction step to drift out of step with the transfer.
         *
         * Scanned against the **parent directory** rather than the not-yet-existing file, so the
         * capability is read from a path that exists. This cannot disagree with the per-file gate
         * about a *block*: the ceiling comes from the mount that the path resolves to, and a file
         * and its parent always resolve to the same one.
         */
        suspend fun scanLocalItem(source: File, destBase: File): Int {
            val isSrcSaf = isSaf(source)
            val isSrcRoot = isRoot(source)

            if (!source.isDirectory) {
                val size = if (isSrcSaf) SafTreeManager.getFileSize(appContext, source.absolutePath)
                           else if (isSrcRoot) RootShellWrapper.getFileSize(source.absolutePath)
                           else source.length()
                val verdict = TransferPreflight.evaluate(
                    fileName = source.name,
                    fileSize = size,
                    capabilities = capabilitiesAt(destBase.parentFile ?: destBase)
                )
                if (verdict is PreflightVerdict.Block) {
                    // FR-24: one line per file, because the pre-scan is the *only* record of a
                    // refusal that never throws — nothing downstream will mention this file again,
                    // since it leaves the walk and the progress arithmetic here. Guarded on the
                    // return value so the log matches what the user is shown: `record` de-duplicates,
                    // and a file reached twice is one row and one line.
                    if (exclusionLog.record(destBase.absolutePath, verdict)) {
                        android.util.Log.w(
                            FileTransferGuard.TAG,
                            "Pre-flight excluded: ${verdict.fileName} (${verdict.fileSize} bytes) → " +
                                "${destBase.absolutePath} — exceeds the " +
                                "${verdict.capabilities.maxFileSize} byte per-file limit of the " +
                                "${verdict.capabilities.fsType} filesystem at " +
                                "${verdict.capabilities.mountPath}"
                        )
                    }
                    excludedSources.add(source.absolutePath)
                    return 0
                }
                // FR-16: the whole tree is walked, so this sums the real bytes of the transfer and
                // not just the sizes of the top-level entries the user selected.
                if (size > 0L) totalBytes += size
                return 1
            }

            val children = if (isSrcSaf) {
                SafTreeManager.listFiles(appContext, source.absolutePath)
            } else if (isSrcRoot) {
                RootShellWrapper.listFiles(source.absolutePath)
            } else {
                source.listFiles()?.toList()
            } ?: return 0

            var count = 0
            for (child in children) {
                count += scanLocalItem(child, File(destBase, child.name))
            }
            return count
        }

        var totalFiles = 0
        for (slot in ctx.slots) {
            for (item in slot.items) {
                when (item) {
                    is FileClipboard.ClipItem.Local -> {
                        totalFiles += scanLocalItem(item.file, File(effectiveDestDir, item.file.name))
                    }
                    is FileClipboard.ClipItem.Remote -> {
                        // Not scanned: descending a remote tree here would mean a network round
                        // trip per directory before the transfer even starts. Remote sources are
                        // still classified correctly — the copy gate refuses them and the catches
                        // below record the skip (FR-15) — they just are not in the pre-transfer
                        // list, which T017 addresses from the network side.
                        totalFiles++
                    }
                }
            }
        }

        // ── FR-08: total bytes against the destination's free space ──────────
        //
        // Operation-level, not per-file: FR-08 compares the sum of everything about to be written.
        // The destination is probed once for the whole operation, off the mount table read above.
        // Unknown free space yields Allow (FR-03), so a destination whose space cannot be read
        // never produces a spurious warning.
        val spaceVerdict = TransferPreflight.evaluateSpace(
            totalBytes = totalBytes,
            capabilities = capabilitiesAt(effectiveDestDir)
        )
        // Logged rather than only shown, because FR-09's warning is the one pre-flight outcome
        // that is deliberately overridable — when a transfer then fails on space, this line is
        // what distinguishes "warned and overridden" from "never warned".
        if (spaceVerdict is PreflightVerdict.Warn) {
            android.util.Log.w(
                FileTransferGuard.TAG,
                "Pre-flight: $totalBytes bytes to write, ${spaceVerdict.capabilities.freeBytes} " +
                    "free at ${spaceVerdict.capabilities.mountPath} " +
                    "(${spaceVerdict.capabilities.fsType}) — short by ${spaceVerdict.shortfallBytes}; " +
                    "warning only, transfer not blocked"
            )
        }
        // Not an `else if`: a transfer can be short on space *and* carrying exclusions, and the
        // `else if` this replaces silently dropped the exclusion summary in exactly that case —
        // the one case where logcat most needs to say both. The two verdicts are independent.
        if (!exclusionLog.isEmpty) {
            android.util.Log.i(
                FileTransferGuard.TAG,
                "Pre-flight: excluded ${exclusionLog.count} file(s) of ${exclusionLog.count + totalFiles}; " +
                    "transferring $totalFiles ($totalBytes bytes)"
            )
        }

        // ── FR-12: present the exclusions before any byte is written ──────────
        //
        // The one place this belongs: after the scan above, before the transfer below, which is
        // what "once before the transfer starts" (FR-12) means.
        //
        // The transfer is NOT gated on the answer per file — those files are already out of the
        // walk and out of `totalFiles`. The answer decides exactly two things: whether the
        // operation proceeds at all when *everything* was excluded (FR-14, which the dialog
        // expresses by having no confirm action), and whether the user accepts a shortfall on
        // space (FR-10, which must be overridable).
        //
        // Not asked when there is nothing to say. A transfer with no exclusions and no space
        // shortfall skips this entirely, so the ordinary case costs nothing (NFR-01) and the
        // dialog only ever appears when it has something to tell the user.
        val spaceWarning = spaceVerdict as? PreflightVerdict.Warn
        if (!exclusionLog.isEmpty || spaceWarning != null) {
            val proceed = session.showPreflightExclusions(
                exclusions = exclusionLog.snapshot(),
                // `totalFiles` is already the *reduced* count — the files that will actually be
                // transferred — so zero here is FR-14: nothing left to confirm.
                remainingFiles = totalFiles,
                spaceWarning = spaceWarning,
                // The Q5 cleanup scan searches where this transfer was going, which is the only
                // place the limit can have left damage the user is being asked about.
                scanRoot = effectiveDestDir
            )
            if (!proceed) {
                // FR-14: with every file excluded the dialog has no confirm action, so this same
                // unwind is reached by a user who declined nothing — there was no reduced transfer
                // to refuse. Recording the skips first is what makes the summary truthful: without
                // it `summaryFrom` reports 0/0/0, and 0/0/0 renders as a bare paste-error, telling
                // the user the operation failed when in fact it was correctly refused and they were
                // told why. `totalFiles == 0` is exactly the FR-14 state — `totalFiles` is already
                // the reduced count, so nothing else can produce it, and a free-space warning needs
                // bytes to exist at all.
                if (totalFiles == 0) {
                    repeat(exclusionLog.count) { notePreflightSkip() }
                }
                // FR-13: cancelling the reduced transfer cancels the operation. Thrown rather
                // than returned because that is the idiom every other user-cancel in this engine
                // uses (`askConflict`'s CANCEL below), and because the alternative — an early
                // `return` — would skip the clipboard clear at the foot of this function, leaving
                // the user's cut/copy still staged after they declined to act on it.
                //
                // Nothing is lost by unwinding here: this point is ahead of the transfer, so no
                // byte has been written, no partial exists to clean up (FR-17), and
                // `pendingIndices` is still empty, so there is no index work to flush.
                throw CancellationException("Pre-flight exclusions declined by the user")
            }
        }

        val fileIndexCounter = AtomicInteger(0)
        val defaultConcurrency = za.kilowatch.ultimatefilemanager.settings.NetworkTransferPreferenceManager.getThreadCount(appContext)
        val semaphore = Semaphore(defaultConcurrency)

        suspend fun processLocalItem(source: File, destBase: File, operation: FileClipboard.Operation) {
            session.checkCancelled()
            val isSrcSaf = isSaf(source)
            val isSrcRoot = isRoot(source)
            val isDestSaf = isSaf(destBase)
            val isDestRoot = isRoot(destBase)

            if (source.isDirectory) {
                val hasConflict = TransferConflictHelper.localFileExists(
                    destBase.parentFile ?: effectiveDestDir, destBase.name, appContext
                )

                var effectiveDest = destBase
                if (hasConflict) {
                    when (askConflict(source.name, true, -1L)) {
                        TransferConflictHelper.ConflictAction.CANCEL -> throw CancellationException()
                        TransferConflictHelper.ConflictAction.SKIP -> { session.noteSuccess(); return }
                        TransferConflictHelper.ConflictAction.KEEP_BOTH -> {
                            effectiveDest = TransferConflictHelper.uniqueLocalFolder(
                                destBase.parentFile ?: effectiveDestDir, source.name, appContext
                            )
                        }
                        TransferConflictHelper.ConflictAction.OVERWRITE -> {
                            effectiveDest = destBase
                        }
                    }
                }

                val isEffSaf = isSaf(effectiveDest)
                val isEffRoot = isRoot(effectiveDest)
                try {
                    if (isEffSaf) {
                        if (!SafTreeManager.exists(appContext, effectiveDest.absolutePath)) {
                            SafTreeManager.mkdir(appContext, effectiveDest.parent ?: effectiveDestDir.absolutePath, effectiveDest.name)
                        }
                    } else if (isEffRoot) {
                        if (!RootShellWrapper.exists(effectiveDest.absolutePath)) {
                            RootShellWrapper.mkdir(effectiveDest.absolutePath)
                        }
                    } else if (ShizukuShellWrapper.canUseShizukuForPath(effectiveDest.absolutePath)) {
                        if (!ShizukuShellWrapper.exists(effectiveDest.absolutePath)) {
                            ShizukuShellWrapper.mkdir(effectiveDest.absolutePath)
                        }
                    } else {
                        if (!effectiveDest.exists()) effectiveDest.mkdirs()
                    }
                    if (!isEffRoot && !UfmApplication.indexingRepository.hasUserDeclinedIndexing(storageId)) {
                        pendingIndices.add(metadataExtractor.extractMetadata(effectiveDest, storageId, storageType, MetadataExtractor.HashAlgorithm.NONE))
                        if (pendingIndices.size >= 50) flushIndices()
                    }
                } catch (_: Exception) {}

                val children = if (isSrcSaf) {
                    SafTreeManager.listFiles(appContext, source.absolutePath)
                } else if (isSrcRoot) {
                    RootShellWrapper.listFiles(source.absolutePath)
                } else {
                    source.listFiles()?.toList()
                }
                if (children != null) {
                    for (child in children) {
                        session.checkCancelled()
                        try {
                            val childDest = if (isEffSaf) {
                                SafFile(effectiveDest.absolutePath, child.name, child.isDirectory)
                            } else if (isEffRoot) {
                                RootFile(effectiveDest.absolutePath, child.name, child.isDirectory)
                            } else {
                                File(effectiveDest, child.name)
                            }
                            processLocalItem(child, childDest, operation)
                        } catch (e: PreflightBlockedException) {
                            // FR-15: the gate refused it, so it is a skip — not the failure the
                            // generic catch below would have recorded it as.
                            notePreflightSkip()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            session.checkCancelled()
                            session.noteFailure(e.message)
                        }
                    }
                }
                if (operation == FileClipboard.Operation.MOVE || operation == FileClipboard.Operation.EXTRACT) {
                    try {
                        if (isSrcSaf) {
                            SafTreeManager.deleteRecursively(appContext, source.absolutePath)
                        } else if (isSrcRoot) {
                            RootShellWrapper.delete(source.absolutePath)
                        } else if (ShizukuShellWrapper.canUseShizukuForPath(source.absolutePath)) {
                            ShizukuShellWrapper.delete(source.absolutePath)
                        } else {
                            source.delete()
                        }
                        if (!isSrcRoot) {
                            UfmApplication.indexingRepository.deleteTreeFromIndex(source.absolutePath)
                        }
                    } catch (_: Exception) {}
                    FileTagsManager.onPathMoved(appContext, source.absolutePath, effectiveDest.absolutePath)
                } else {
                    FileTagsManager.onPathCopied(appContext, source.absolutePath, effectiveDest.absolutePath)
                }
            } else {
                // FR-11: the pre-flight scan already refused this file. Skipping it here rather
                // than letting the copy gate refuse it is what keeps the progress index in step
                // with `totalFiles`, which no longer includes it — the gate would still refuse it
                // correctly, but only after the index below had already been advanced.
                if (excludedSources.contains(source.absolutePath)) {
                    notePreflightSkip()
                    return
                }

                val currentIndex = fileIndexCounter.incrementAndGet()
                val hasConflict = TransferConflictHelper.localFileExists(
                    destBase.parentFile ?: effectiveDestDir, destBase.name, appContext
                )
                val resolvedAction = if (hasConflict) {
                    val destSize = TransferConflictHelper.localFileSize(
                        destBase.parentFile ?: effectiveDestDir, destBase.name, appContext
                    )
                    askConflict(source.name, false, destSize)
                } else TransferConflictHelper.ConflictAction.KEEP_BOTH

                if (resolvedAction == TransferConflictHelper.ConflictAction.CANCEL) throw CancellationException()
                if (resolvedAction == TransferConflictHelper.ConflictAction.SKIP) { session.noteSuccess(); return }

                val sourceSize = if (isSrcSaf) SafTreeManager.getFileSize(appContext, source.absolutePath)
                                 else if (isSrcRoot) RootShellWrapper.getFileSize(source.absolutePath)
                                 else source.length()
                session.reportProgress(source.name, 0, sourceSize, currentIndex, totalFiles)
                val finalDest = if (resolvedAction == TransferConflictHelper.ConflictAction.KEEP_BOTH)
                    TransferConflictHelper.uniqueLocalFile(destBase.parentFile ?: effectiveDestDir, destBase.name, appContext)
                else destBase
                val writtenDest = session.withFileRetry(source.name) {
                    TransferConflictHelper.copyLocalToLocalAtomic(source, finalDest, resolvedAction) { c, t ->
                        session.reportProgress(source.name, c, t, currentIndex, totalFiles)
                    }
                }

                val isEffDestRoot = isRoot(writtenDest)
                if (!isEffDestRoot && !UfmApplication.indexingRepository.hasUserDeclinedIndexing(storageId)) {
                    pendingIndices.add(metadataExtractor.extractMetadata(writtenDest, storageId, storageType, MetadataExtractor.HashAlgorithm.NONE))
                    if (pendingIndices.size >= 50) flushIndices()
                }

                if (operation == FileClipboard.Operation.MOVE || operation == FileClipboard.Operation.EXTRACT) {
                    val isEffSafDest = isSaf(writtenDest)
                    val writtenSize = if (isEffSafDest) SafTreeManager.getFileSize(appContext, writtenDest.absolutePath)
                                      else if (isEffDestRoot) RootShellWrapper.getFileSize(writtenDest.absolutePath)
                                      else writtenDest.length()
                    val isSafeToDelete = if (isEffSafDest && writtenSize <= 0L) {
                        SafTreeManager.exists(appContext, writtenDest.absolutePath)
                    } else if (isEffDestRoot && writtenSize <= 0L) {
                        RootShellWrapper.exists(writtenDest.absolutePath)
                    } else {
                        FileTransferGuard.requireSourceSafeToDelete(writtenSize, sourceSize, source.name)
                    }
                    if (isSafeToDelete) {
                        if (isSrcSaf) {
                            SafTreeManager.delete(appContext, source.absolutePath)
                        } else if (isSrcRoot) {
                            RootShellWrapper.delete(source.absolutePath)
                        } else if (ShizukuShellWrapper.canUseShizukuForPath(source.absolutePath)) {
                            ShizukuShellWrapper.delete(source.absolutePath)
                        } else {
                            source.delete()
                        }
                        if (!isSrcRoot) {
                            UfmApplication.indexingRepository.deleteTreeFromIndex(source.absolutePath)
                        }
                    }
                    FileTagsManager.onPathMoved(appContext, source.absolutePath, writtenDest.absolutePath)
                } else {
                    FileTagsManager.onPathCopied(appContext, source.absolutePath, writtenDest.absolutePath)
                }
                session.noteSuccess()
            }
        }

        suspend fun processNetItem(source: NetworkFile, destBase: File, share: NetworkShare, operation: FileClipboard.Operation) {
            session.checkCancelled()
            val isDestSaf = isSaf(destBase)
            val isDestRoot = isRoot(destBase)

            if (source.isDirectory) {
                val hasConflict = TransferConflictHelper.localFileExists(
                    destBase.parentFile ?: effectiveDestDir, destBase.name, appContext
                )

                var effectiveDest = destBase
                if (hasConflict) {
                    when (askConflict(source.name, true, -1L)) {
                        TransferConflictHelper.ConflictAction.CANCEL -> throw CancellationException()
                        TransferConflictHelper.ConflictAction.SKIP -> { session.noteSuccess(); return }
                        TransferConflictHelper.ConflictAction.KEEP_BOTH -> {
                            effectiveDest = TransferConflictHelper.uniqueLocalFolder(
                                destBase.parentFile ?: effectiveDestDir, source.name, appContext
                            )
                        }
                        TransferConflictHelper.ConflictAction.OVERWRITE -> {
                            effectiveDest = destBase
                        }
                    }
                }

                val isEffSaf = isSaf(effectiveDest)
                val isEffRoot = isRoot(effectiveDest)
                try {
                    if (isEffSaf) {
                        if (!SafTreeManager.exists(appContext, effectiveDest.absolutePath)) {
                            SafTreeManager.mkdir(appContext, effectiveDest.parent ?: effectiveDestDir.absolutePath, effectiveDest.name)
                        }
                    } else if (isEffRoot) {
                        if (!RootShellWrapper.exists(effectiveDest.absolutePath)) {
                            RootShellWrapper.mkdir(effectiveDest.absolutePath)
                        }
                    } else if (ShizukuShellWrapper.canUseShizukuForPath(effectiveDest.absolutePath)) {
                        if (!ShizukuShellWrapper.exists(effectiveDest.absolutePath)) {
                            ShizukuShellWrapper.mkdir(effectiveDest.absolutePath)
                        }
                    } else {
                        if (!effectiveDest.exists()) effectiveDest.mkdirs()
                    }
                    if (!isEffRoot && !UfmApplication.indexingRepository.hasUserDeclinedIndexing(storageId)) {
                        pendingIndices.add(metadataExtractor.extractMetadata(effectiveDest, storageId, storageType, MetadataExtractor.HashAlgorithm.NONE))
                        if (pendingIndices.size >= 50) flushIndices()
                    }
                } catch (_: Exception) {}

                val children = when (share.type) {
                    ShareType.SMB          -> SmbShareClient.listFiles(share, source.path)
                    ShareType.FTP          -> FtpShareClient.listFiles(share, source.path)
                    ShareType.TV           -> TvShareClient.listFiles(share, source.path)
                    ShareType.SFTP, ShareType.SCP -> SshShareClient.listFiles(share, source.path)
                    ShareType.ONEDRIVE     -> OnedriveShareClient.listFiles(share, source.path)
                    ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.listFiles(share, source.path)
                    ShareType.DROPBOX      -> DropboxShareClient.listFiles(share, source.path)
                    ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.listFiles(share, source.path)
                    ShareType.WEBDAV       -> WebDavShareClient.listFiles(share, source.path)
                    ShareType.NFS          -> NfsShareClient.listFiles(share, source.path)
                    ShareType.DLNA         -> throw UnsupportedOperationException("DLNA is read-only")
                }
                val concurrency = if (share.type == ShareType.FTP || share.type == ShareType.SFTP) share.effectiveThreads(appContext).coerceIn(1, 8) else 1
                if (concurrency > 1) {
                    coroutineScope {
                        for (child in children) {
                            session.checkCancelled()
                            val childDest = if (isEffSaf) {
                                SafFile(effectiveDest.absolutePath, child.name, child.isDirectory)
                            } else if (isEffRoot) {
                                RootFile(effectiveDest.absolutePath, child.name, child.isDirectory)
                            } else {
                                File(effectiveDest, child.name)
                            }
                            if (child.isDirectory) {
                                processNetItem(child, childDest, share, operation)
                            } else {
                                launch(Dispatchers.IO) {
                                    semaphore.withPermit {
                                        session.checkCancelled()
                                        try {
                                            processNetItem(child, childDest, share, operation)
                                        } catch (e: PreflightBlockedException) {
                                            notePreflightSkip()
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            session.checkCancelled()
                                            session.noteFailure(e.message)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    for (child in children) {
                        session.checkCancelled()
                        try {
                            val childDest = if (isEffSaf) {
                                SafFile(effectiveDest.absolutePath, child.name, child.isDirectory)
                            } else if (isEffRoot) {
                                RootFile(effectiveDest.absolutePath, child.name, child.isDirectory)
                            } else {
                                File(effectiveDest, child.name)
                            }
                            processNetItem(child, childDest, share, operation)
                        } catch (e: PreflightBlockedException) {
                            notePreflightSkip()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            session.checkCancelled()
                            session.noteFailure(e.message)
                        }
                    }
                }
                if (operation == FileClipboard.Operation.MOVE) {
                    try {
                        when (share.type) {
                            ShareType.SMB          -> SmbShareClient.deleteDir(share, source.path)
                            ShareType.FTP          -> FtpShareClient.deleteDir(share, source.path)
                            ShareType.TV           -> TvShareClient.deleteDir(share, source.path)
                            ShareType.SFTP, ShareType.SCP -> SshShareClient.delete(share, source.path, true)
                            ShareType.ONEDRIVE     -> OnedriveShareClient.deleteFile(share, source.path)
                            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.deleteFile(share, source.path)
                            ShareType.DROPBOX      -> DropboxShareClient.deleteFile(share, source.path)
                            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.deleteFile(share, source.path)
                            ShareType.WEBDAV       -> WebDavShareClient.deleteFile(share, source.path)
                            ShareType.NFS          -> NfsShareClient.deleteDir(share, source.path)
                            ShareType.DLNA         -> throw UnsupportedOperationException("DLNA is read-only")
                        }
                    } catch (_: Exception) {}
                    FileTagsManager.onPathMoved(appContext, source.path, effectiveDest.absolutePath)
                } else {
                    FileTagsManager.onPathCopied(appContext, source.path, effectiveDest.absolutePath)
                }
            } else {
                val currentIndex = fileIndexCounter.incrementAndGet()
                val hasConflict = TransferConflictHelper.localFileExists(
                    destBase.parentFile ?: effectiveDestDir, destBase.name, appContext
                )
                val resolvedAction = if (hasConflict) {
                    val destSize = TransferConflictHelper.localFileSize(
                        destBase.parentFile ?: effectiveDestDir, destBase.name, appContext
                    )
                    askConflict(source.name, false, destSize)
                } else TransferConflictHelper.ConflictAction.KEEP_BOTH

                if (resolvedAction == TransferConflictHelper.ConflictAction.CANCEL) throw CancellationException()
                if (resolvedAction == TransferConflictHelper.ConflictAction.SKIP) { session.noteSuccess(); return }

                session.reportProgress(source.name, 0, source.size, currentIndex, totalFiles)
                var connToUnregister: AutoCloseable? = null
                val writtenDest = try {
                    session.withFileRetry(source.name) {
                        TransferConflictHelper.downloadNetworkToLocalAtomic(
                            share, source, destBase, resolvedAction,
                            onProgress = { c, t -> session.reportProgress(source.name, c, t, currentIndex, totalFiles) },
                            onConnectionReady = { conn ->
                                connToUnregister = conn
                                session.registerConnection(conn)
                            }
                        )
                    }
                } finally {
                    connToUnregister?.let { session.unregisterConnection(it) }
                }

                val isEffDestRoot = isRoot(writtenDest)
                if (!isEffDestRoot && !UfmApplication.indexingRepository.hasUserDeclinedIndexing(storageId)) {
                    pendingIndices.add(metadataExtractor.extractMetadata(writtenDest, storageId, storageType, MetadataExtractor.HashAlgorithm.NONE))
                    if (pendingIndices.size >= 50) flushIndices()
                }

                if (operation == FileClipboard.Operation.MOVE) {
                    val isDestSafLocal = isSaf(writtenDest)
                    val writtenSize = if (isDestSafLocal) SafTreeManager.getFileSize(appContext, writtenDest.absolutePath) else writtenDest.length()
                    val isSafeToDelete = if (isDestSafLocal && writtenSize <= 0L) {
                        SafTreeManager.exists(appContext, writtenDest.absolutePath)
                    } else {
                        FileTransferGuard.requireSourceSafeToDelete(writtenSize, source.size, source.name)
                    }
                    if (isSafeToDelete) {
                        when (share.type) {
                            ShareType.SMB          -> SmbShareClient.deleteFile(share, source.path)
                            ShareType.FTP          -> FtpShareClient.deleteFile(share, source.path)
                            ShareType.TV           -> TvShareClient.deleteFile(share, source.path)
                            ShareType.SFTP, ShareType.SCP -> SshShareClient.delete(share, source.path, false)
                            ShareType.ONEDRIVE     -> OnedriveShareClient.deleteFile(share, source.path)
                            ShareType.GOOGLE_DRIVE -> GoogleDriveShareClient.deleteFile(share, source.path)
                            ShareType.DROPBOX      -> DropboxShareClient.deleteFile(share, source.path)
                            ShareType.AWS_S3, ShareType.IDRIVE_E2 -> S3ShareClient.deleteFile(share, source.path)
                            ShareType.WEBDAV       -> WebDavShareClient.deleteFile(share, source.path)
                            ShareType.NFS          -> NfsShareClient.deleteFile(share, source.path)
                            ShareType.DLNA         -> throw UnsupportedOperationException("DLNA is read-only")
                        }
                    }
                    FileTagsManager.onPathMoved(appContext, source.path, writtenDest.absolutePath)
                } else {
                    FileTagsManager.onPathCopied(appContext, source.path, writtenDest.absolutePath)
                }
                session.noteSuccess()
            }
        }

        // ── Process target slots ──────────────────────────────────────────
        coroutineScope {
            for (slot in ctx.slots) {
                for (item in slot.items) {
                    session.checkCancelled()
                    when (item) {
                        is FileClipboard.ClipItem.Local -> {
                            try {
                                processLocalItem(item.file, File(effectiveDestDir, item.file.name), item.operation)
                            } catch (e: PreflightBlockedException) {
                                // The top-level counterpart of the recursive-descent clause above.
                                // A directory's children are caught as they descend, but a *file*
                                // selected directly has no descent to pass through, so its refusal
                                // arrives here — and without this clause the generic catch below
                                // would record it as a failure (FR-15) carrying the exception's
                                // English text (NFR-06). The pre-scan already excludes these, so
                                // this is the gate's own refusal as a second line of defence, which
                                // is exactly the case that must not be the one that misreports.
                                notePreflightSkip()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                session.checkCancelled()
                                session.noteFailure(e.message)
                            }
                        }
                        is FileClipboard.ClipItem.Remote -> {
                            var share = NetworkShareRepository.getInstance(appContext).getById(item.sourceShareId)
                            if (share?.isServerMode == true && item.sourceRemotePath.isNotEmpty()) {
                                share = share.copy(remotePath = item.sourceRemotePath)
                            }
                            if (share == null) {
                                val pairedDevice = PairingManager.getInstance(appContext).getPairedDevice(item.sourceShareId)
                                if (pairedDevice != null && pairedDevice.isConnected) {
                                    share = NetworkShare(
                                        id = pairedDevice.deviceId,
                                        name = pairedDevice.name,
                                        type = ShareType.TV,
                                        host = pairedDevice.lastIp,
                                        port = pairedDevice.lastPort
                                    )
                                }
                            }
                            if (share == null) {
                                val onlineStorage = OnlineStorageRepository.getInstance(appContext).getById(item.sourceShareId)
                                if (onlineStorage != null) {
                                    share = NetworkShare(
                                        id = onlineStorage.id,
                                        name = onlineStorage.displayName,
                                        type = when (onlineStorage.provider) {
                                            OnlineStorageProvider.ONEDRIVE     -> ShareType.ONEDRIVE
                                            OnlineStorageProvider.GOOGLE_DRIVE -> ShareType.GOOGLE_DRIVE
                                            OnlineStorageProvider.DROPBOX      -> ShareType.DROPBOX
                                            OnlineStorageProvider.AWS_S3       -> ShareType.AWS_S3
                                            OnlineStorageProvider.IDRIVE_E2    -> ShareType.IDRIVE_E2
                                            OnlineStorageProvider.WEBDAV       -> ShareType.WEBDAV
                                            OnlineStorageProvider.RCLONE       -> ShareType.WEBDAV
                                        },
                                        host = when (onlineStorage.provider) {
                                            OnlineStorageProvider.RCLONE -> RCloneShareClient.RCLONE_HOST_MARKER
                                            else -> if (onlineStorage.isWebDavProvider) onlineStorage.webDavUrl ?: onlineStorage.email else onlineStorage.s3Endpoint ?: onlineStorage.email
                                        },
                                        username = when (onlineStorage.provider) {
                                            OnlineStorageProvider.RCLONE -> onlineStorage.id
                                            else -> if (onlineStorage.isWebDavProvider) onlineStorage.webDavUsername ?: "" else onlineStorage.s3AccessKey ?: ""
                                        },
                                        password = if (onlineStorage.isWebDavProvider) onlineStorage.webDavPassword ?: "" else onlineStorage.s3SecretKey ?: "",
                                        readOnly = false
                                    )
                                }
                            }

                            if (share != null) {
                                val itemConcurrency = if (share.type == ShareType.FTP || share.type == ShareType.SFTP) share.effectiveThreads(appContext).coerceIn(1, 8) else 1
                                if (itemConcurrency > 1 && !item.file.isDirectory) {
                                    launch(Dispatchers.IO) {
                                        semaphore.withPermit {
                                            session.checkCancelled()
                                            try {
                                                processNetItem(item.file, File(currentDir, item.file.name), share, item.operation)
                                            } catch (e: PreflightBlockedException) {
                                                notePreflightSkip()
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                session.checkCancelled()
                                                session.noteFailure(e.message)
                                            }
                                        }
                                    }
                                } else {
                                    try {
                                        processNetItem(item.file, File(currentDir, item.file.name), share, item.operation)
                                    } catch (e: PreflightBlockedException) {
                                        notePreflightSkip()
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        session.checkCancelled()
                                        session.noteFailure(e.message)
                                    }
                                }
                            } else {
                                session.noteFailure(null)
                            }
                        }
                    }
                }
                FileClipboard.removeSlot(slot.id)
            }
        }

        if (ctx.targetSlotId == null) {
            FileClipboard.clear()
        }
        flushIndices()
    }
}
