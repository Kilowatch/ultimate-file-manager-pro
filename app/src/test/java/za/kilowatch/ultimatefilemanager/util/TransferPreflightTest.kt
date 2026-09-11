package za.kilowatch.ultimatefilemanager.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TransferPreflight.evaluate].
 *
 * Every branch is exercised against a hand-built [DestinationCapabilities], so the verdict logic
 * is testable with no device, no Android framework and no I/O. The sizes are the real ones: the
 * FAT ceiling from the reported failure (`.plans/spec.md`), and the free space measured on the
 * target card during clarification.
 */
class TransferPreflightTest {

    private companion object {
        const val MOVIE = "Behind Enemy Lines (2001).mkv"
    }

    private val gib = 1024L * 1024L * 1024L

    /** The FAT32 card from the incident: 4 GiB−1 per file, ~15.7 GB free. */
    private val fat32Card = DestinationCapabilities(
        mountPath = "/mnt/pass_through/0/7DE2-1219",
        fsType = "vfat",
        fatSubtype = FatSubtype.FAT32,
        maxFileSize = FilesystemCapabilities.FAT_MAX,
        freeBytes = 478502L * 32768L,
        determined = true
    )

    /** Internal storage: no ceiling, plenty of room. */
    private val internalStorage = DestinationCapabilities(
        mountPath = "/mnt/pass_through/0/emulated",
        fsType = "f2fs",
        fatSubtype = null,
        maxFileSize = DestinationCapabilities.UNCONSTRAINED,
        freeBytes = 10001535L * 4096L,
        determined = true
    )

    // ------------------------------------------------------------ the block (FR-04–FR-07)

    @Test
    fun `the reported failure is blocked instead of transferring for 23 minutes`() {
        // 4,294,967,295 bytes is exactly the size the truncated .mkv was left at; the source was
        // larger, so it exceeds the limit.
        val verdict = TransferPreflight.evaluate(
            fileName = "Behind Enemy Lines (2001).mkv",
            fileSize = FilesystemCapabilities.FAT_MAX + 1,
            capabilities = fat32Card
        )

        assertTrue(verdict is PreflightVerdict.Block)
        val block = verdict as PreflightVerdict.Block
        assertEquals(BlockReason.EXCEEDS_DESTINATION_FILE_SIZE, block.reason)
        assertEquals("Behind Enemy Lines (2001).mkv", block.fileName)
        assertEquals(FilesystemCapabilities.FAT_MAX + 1, block.fileSize)
        // FR-05 needs the limit and the filesystem too; both travel on the capabilities.
        assertEquals(FilesystemCapabilities.FAT_MAX, block.capabilities.maxFileSize)
        assertEquals(FatSubtype.FAT32, block.capabilities.fatSubtype)
    }

    @Test
    fun `a file exactly at the limit is allowed`() {
        // FR-06: the ceiling is inclusive. Blocking this would be a false positive (NFR-02).
        val verdict = TransferPreflight.evaluate(
            fileName = "exactly-at-the-limit.mkv",
            fileSize = FilesystemCapabilities.FAT_MAX,
            capabilities = fat32Card
        )

        assertEquals(PreflightVerdict.Allow, verdict)
    }

    @Test
    fun `one byte over the limit is blocked`() {
        val verdict = TransferPreflight.evaluate(
            fileName = "one-over.mkv",
            fileSize = FilesystemCapabilities.FAT_MAX + 1,
            capabilities = fat32Card
        )

        assertTrue(verdict is PreflightVerdict.Block)
    }

    @Test
    fun `the same oversized file is allowed on a filesystem with no ceiling`() {
        // FR-07, and the NFR-02 contrast case: 8 GiB to internal storage must not be blocked.
        val verdict = TransferPreflight.evaluate(
            fileName = "Behind Enemy Lines (2001).mkv",
            fileSize = 8 * gib,
            capabilities = internalStorage
        )

        assertEquals(PreflightVerdict.Allow, verdict)
    }

    @Test
    fun `an undetermined destination never blocks`() {
        // FR-03: fail open. The largest file imaginable still passes an unresolved destination.
        val verdict = TransferPreflight.evaluate(
            fileName = "enormous.mkv",
            fileSize = Long.MAX_VALUE,
            capabilities = DestinationCapabilities.unknown()
        )

        assertEquals(PreflightVerdict.Allow, verdict)
    }

    @Test
    fun `an unknown source size is allowed through`() {
        // The spec's first edge case: validation cannot be performed, so it is not attempted.
        assertEquals(
            PreflightVerdict.Allow,
            TransferPreflight.evaluate("unknown.mkv", 0L, fat32Card)
        )
        assertEquals(
            PreflightVerdict.Allow,
            TransferPreflight.evaluate("negative.mkv", -1L, fat32Card)
        )
    }

    // ------------------------------------------------------------- the warning (FR-08–FR-10)

    /**
     * The incident card cannot express a space warning at all: its 15.7 GB of free space is
     * larger than its 4 GiB per-file ceiling, so any file big enough to exhaust the space is
     * already blocked on size. A genuinely short card is needed to reach the warning branch.
     */
    private val nearlyFullCard = fat32Card.copy(freeBytes = 1 * gib)

    @Test
    fun `insufficient space warns rather than blocks`() {
        val verdict = TransferPreflight.evaluate(
            fileName = "big-but-legal.mkv",
            fileSize = 3 * gib,
            capabilities = nearlyFullCard
        )

        assertTrue(verdict is PreflightVerdict.Warn)
        val warn = verdict as PreflightVerdict.Warn
        assertEquals(3 * gib - 1 * gib, warn.shortfallBytes)
        assertTrue(warn.shortfallBytes > 0)
    }

    @Test
    fun `the warning is never a block, even by a wide margin`() {
        // FR-10: a free-space estimate may be stale, so it must stay overridable.
        val verdict = TransferPreflight.evaluate(
            fileName = "far-too-big.mkv",
            fileSize = 900 * gib,
            capabilities = internalStorage
        )

        assertTrue(verdict is PreflightVerdict.Warn)
        assertTrue(verdict !is PreflightVerdict.Block)
    }

    @Test
    fun `a file that exactly fits the free space does not warn`() {
        val verdict = TransferPreflight.evaluate(
            fileName = "exactly-fits.mkv",
            fileSize = internalStorage.freeBytes,
            capabilities = internalStorage
        )

        assertEquals(PreflightVerdict.Allow, verdict)
    }

    @Test
    fun `unknown free space never warns`() {
        val verdict = TransferPreflight.evaluate(
            fileName = "big.mkv",
            fileSize = 900 * gib,
            capabilities = DestinationCapabilities(
                fsType = "f2fs",
                maxFileSize = DestinationCapabilities.UNCONSTRAINED,
                freeBytes = DestinationCapabilities.FREE_UNKNOWN,
                determined = true
            )
        )

        assertEquals(PreflightVerdict.Allow, verdict)
    }

    // -------------------------------------------------------------- reason precedence

    @Test
    fun `a file that is both oversized and short of space reports the size block`() {
        // The size block is the actionable one: no amount of freeing space makes it succeed.
        val verdict = TransferPreflight.evaluate(
            fileName = "huge.mkv",
            fileSize = FilesystemCapabilities.FAT_MAX + 1,
            capabilities = fat32Card.copy(freeBytes = 1024L)
        )

        assertTrue(verdict is PreflightVerdict.Block)
    }

    @Test
    fun `a legal file on a full card warns rather than blocks`() {
        val verdict = TransferPreflight.evaluate(
            fileName = "fits-the-limit.mkv",
            fileSize = 3 * gib,
            capabilities = fat32Card.copy(freeBytes = 1024L)
        )

        assertTrue(verdict is PreflightVerdict.Warn)
    }

    @Test
    fun `zero free space still warns rather than blocking`() {
        val verdict = TransferPreflight.evaluate(
            fileName = "fits-the-limit.mkv",
            fileSize = 3 * gib,
            capabilities = fat32Card.copy(freeBytes = 0L)
        )

        assertTrue(verdict is PreflightVerdict.Warn)
        assertEquals(3 * gib, (verdict as PreflightVerdict.Warn).shortfallBytes)
    }

    // ---------------------------------------------------- exclusion log (T008)

    private fun blockFor(name: String, size: Long = FilesystemCapabilities.FAT_MAX + 1) =
        TransferPreflight.evaluate(name, size, fat32Card) as PreflightVerdict.Block

    @Test
    fun `exclusions are recorded with the reason they were blocked for`() {
        val log = ExclusionLog()

        log.record("/storage/7DE2-1219/$MOVIE", blockFor(MOVIE))

        val exclusion = log.snapshot().single()
        assertEquals("/storage/7DE2-1219/$MOVIE", exclusion.destPath)
        assertEquals(MOVIE, exclusion.fileName)
        assertEquals(FilesystemCapabilities.FAT_MAX + 1, exclusion.fileSize)
        assertEquals(BlockReason.EXCEEDS_DESTINATION_FILE_SIZE, exclusion.reason)
        assertEquals(FatSubtype.FAT32, exclusion.capabilities.fatSubtype)
    }

    @Test
    fun `concurrent writes from many threads record every exclusion`() {
        // The engines assess files in parallel on Dispatchers.IO; a non-thread-safe accumulator
        // would lose entries here rather than fail loudly.
        val log = ExclusionLog()
        val threads = 8
        val perThread = 250
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        val start = java.util.concurrent.CountDownLatch(1)

        try {
            val futures = (0 until threads).map { t ->
                pool.submit {
                    start.await()
                    for (i in 0 until perThread) {
                        val path = "/storage/7DE2-1219/file-$t-$i.mkv"
                        log.record(path, blockFor("file-$t-$i.mkv"))
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(threads * perThread, log.count)
        assertEquals(threads * perThread, log.snapshot().size)
        assertEquals(threads * perThread, log.snapshot().map { it.destPath }.toSet().size)
    }

    @Test
    fun `recording the same destination twice counts once`() {
        // A directory walk can reach a file by more than one route; a duplicate would both show a
        // second row and corrupt the progress count T016 derives from this.
        val log = ExclusionLog()

        assertTrue(log.record("/storage/7DE2-1219/$MOVIE", blockFor(MOVIE)))
        assertTrue(!log.record("/storage/7DE2-1219/$MOVIE", blockFor(MOVIE)))

        assertEquals(1, log.count)
        assertEquals(1, log.snapshot().size)
    }

    @Test
    fun `a fresh log is empty and reports so`() {
        val log = ExclusionLog()

        assertTrue(log.isEmpty)
        assertEquals(0, log.count)
        assertTrue(log.snapshot().isEmpty())
    }

    @Test
    fun `an empty log is not empty once something is recorded`() {
        val log = ExclusionLog()
        log.record("/storage/7DE2-1219/$MOVIE", blockFor(MOVIE))

        assertTrue(!log.isEmpty)
    }

    @Test
    fun `clear forgets everything, including the de-duplication keys`() {
        val log = ExclusionLog()
        log.record("/storage/7DE2-1219/$MOVIE", blockFor(MOVIE))

        log.clear()

        assertTrue(log.isEmpty)
        // The path must be recordable again after a clear, or a second operation reusing the same
        // log would silently drop the same file.
        assertTrue(log.record("/storage/7DE2-1219/$MOVIE", blockFor(MOVIE)))
        assertEquals(1, log.count)
    }

    @Test
    fun `exclusions keep the order they were recorded in`() {
        val log = ExclusionLog()
        listOf("a.mkv", "b.mkv", "c.mkv").forEach {
            log.record("/storage/7DE2-1219/$it", blockFor(it))
        }

        assertEquals(listOf("a.mkv", "b.mkv", "c.mkv"), log.snapshot().map { it.fileName })
    }

    // ------------------------------------------- the gate's signal (T014)

    @Test
    fun `the gate's exception carries everything the explanation needs`() {
        // FR-05: the message can be built without re-probing the destination.
        val block = blockFor(MOVIE)
        val thrown = PreflightBlockedException(block)

        assertEquals(block, thrown.block)
        assertEquals(MOVIE, thrown.block.fileName)
        assertEquals(FilesystemCapabilities.FAT_MAX + 1, thrown.block.fileSize)
        assertEquals(FilesystemCapabilities.FAT_MAX, thrown.block.capabilities.maxFileSize)
        assertEquals(FatSubtype.FAT32, thrown.block.capabilities.fatSubtype)
    }

    @Test
    fun `a blocked file is terminal and is never retried`() {
        // The guard that matters most. `withFileRetry` retries anything it classifies TRANSIENT —
        // five attempts, five seconds apart, inside a five-minute budget. A blocked file cannot
        // succeed on any of them, so a misclassification would stall the batch for minutes and
        // report a perfectly predictable refusal as a flaky network failure.
        val thrown = PreflightBlockedException(blockFor(MOVIE))

        assertEquals(
            TransferRetryPolicy.RetryClass.TERMINAL,
            TransferRetryPolicy.classify(thrown)
        )
    }

    @Test
    fun `no fatal or transient retry marker leaks into the block message`() {
        // Belt and braces on the above: the classifier matches on message *text*, so this pins
        // the reason the message reads the way it does. Worth keeping — a future tweak that adds
        // the words "timed out" or "stream closed" to explain a block would quietly resurrect
        // five pointless retries per blocked file, and nothing else would catch it.
        val message = PreflightBlockedException(blockFor(MOVIE)).message.orEmpty().lowercase()

        val markers = listOf(
            "connection reset", "connection refused", "connection timed out", "connect timed out",
            "connection lost", "reset by peer", "broken pipe", "socket", "timed out", "timeout",
            "read timed", "write timed", "econnreset", "econnrefused", "network is unreachable",
            "no route to host", "host unreachable", "unable to connect", "failed to connect",
            "transport", "end of stream", "eof", "peer", "stream closed", "i/o error",
            "io exception", "server error", "bad gateway", "service unavailable",
            "too many requests", "rate limit", "502", "503", "504", "closed by remote", "disconnected"
        )
        val leaked = markers.filter { message.contains(it) }

        assertEquals(emptyList<String>(), leaked)
    }

    // ------------------------------------------- operation-level free space (T018)

    @Test
    fun `a batch larger than the free space warns with the exact shortfall`() {
        val verdict = TransferPreflight.evaluateSpace(
            totalBytes = 12 * gib,
            capabilities = internalStorage.copy(freeBytes = 5 * gib)
        )

        assertTrue(verdict is PreflightVerdict.Warn)
        assertEquals(7 * gib, (verdict as PreflightVerdict.Warn).shortfallBytes)
    }

    @Test
    fun `a batch that exactly fits the free space does not warn`() {
        // FR-08 compares against available space; a batch that fits has nothing to warn about,
        // and an off-by-one here would warn on every transfer that fills a volume exactly.
        val verdict = TransferPreflight.evaluateSpace(
            totalBytes = 5 * gib,
            capabilities = internalStorage.copy(freeBytes = 5 * gib)
        )

        assertEquals(PreflightVerdict.Allow, verdict)
    }

    @Test
    fun `a batch total is never run through the per-file ceiling`() {
        // The reason `evaluateSpace` exists as its own function. 20 files of 3 GiB each is 60 GiB
        // as a *total*, which is far over the 4 GiB FAT32 per-file ceiling — but no individual
        // file is. Running the total through `cannotHold` would hard-block a batch the card can
        // legitimately hold (much of) and would be a straight violation of NFR-02.
        val verdict = TransferPreflight.evaluateSpace(
            totalBytes = 60 * gib,
            capabilities = fat32Card.copy(freeBytes = 200 * gib)
        )

        assertEquals(PreflightVerdict.Allow, verdict)
    }

    @Test
    fun `insufficient space is never a block, however large the shortfall`() {
        // FR-10: free space is an estimate and must stay overridable. Nothing about the size of
        // the shortfall may escalate it into a refusal.
        val verdict = TransferPreflight.evaluateSpace(
            totalBytes = 900 * gib,
            capabilities = internalStorage.copy(freeBytes = 1024L)
        )

        assertTrue(verdict is PreflightVerdict.Warn)
        assertTrue(verdict !is PreflightVerdict.Block)
    }

    @Test
    fun `unknown free space never warns about a batch`() {
        // FR-03, NFR-02: a destination whose space cannot be read must not produce a warning,
        // or a root/Shizuku destination would warn on every single transfer.
        val verdict = TransferPreflight.evaluateSpace(
            totalBytes = 900 * gib,
            capabilities = DestinationCapabilities(
                fsType = "f2fs",
                maxFileSize = DestinationCapabilities.UNCONSTRAINED,
                freeBytes = DestinationCapabilities.FREE_UNKNOWN,
                determined = true
            )
        )

        assertEquals(PreflightVerdict.Allow, verdict)
    }

    @Test
    fun `an unmeasured batch never warns`() {
        // Nothing could be sized, so there is nothing to compare. Also covers an empty batch,
        // which must not warn merely because the destination happens to be full.
        assertEquals(
            PreflightVerdict.Allow,
            TransferPreflight.evaluateSpace(0L, internalStorage.copy(freeBytes = 0L))
        )
        assertEquals(
            PreflightVerdict.Allow,
            TransferPreflight.evaluateSpace(-1L, internalStorage.copy(freeBytes = 0L))
        )
    }

    @Test
    fun `the verdict carries the destination that produced it`() {
        val verdict = TransferPreflight.evaluate(
            fileName = "big.mkv",
            fileSize = 3 * gib,
            capabilities = nearlyFullCard
        ) as PreflightVerdict.Warn

        assertEquals(nearlyFullCard, verdict.capabilities)
    }
}
