package za.kilowatch.ultimatefilemanager.util

import android.content.Context
import za.kilowatch.ultimatefilemanager.R

/**
 * Composes the summary text shown when a transfer finishes, for both browsers.
 *
 * A finished transfer has three outcomes that are independent of each other, and the user needs all
 * of them reported (FR-15): files that were transferred, files that failed, and files the pre-flight
 * check excluded because the destination filesystem cannot hold them. Reporting only the first two
 * is what made a FAT32-blocked file look like a silent no-op.
 *
 * Lives here rather than on either Activity because the same rule is needed at four call sites
 * across `FileBrowserActivity` and `NetworkBrowserActivity` — a live
 * [TransferManager.TransferSummary] in each, plus the Quick Transfer result-intent handoff in each.
 * Two Activities that share no base class, so a helper on one cannot serve the other.
 */
object TransferSummaryText {

    /**
     * Builds the snackbar text for a finished paste.
     *
     * The result is two independent parts, joined only when both exist:
     *
     * - the **headline** — what happened overall;
     * - the **skipped note** — the exclusions, which belong alongside a success too, not only
     *   alongside a failure.
     *
     * When nothing was transferred *and* nothing failed, every file was excluded (FR-14). There is
     * deliberately no headline for that case — the skipped note is the whole story, and the generic
     * paste-error string would tell the user something failed when nothing did.
     *
     * [message] is quoted as the skip reason **only when no failure outranks it**.
     * [TransferManager.summaryFrom] resolves `message = message ?: lastError ?: skipReason`, so once
     * `failCount > 0` the message is the error. Appending that after "3 file(s) skipped:" would
     * blame a socket timeout on the destination filesystem — a worse failure than silence, because
     * it is confidently wrong.
     *
     * @param successCount files transferred.
     * @param failCount    files that failed for a reason other than a pre-flight exclusion.
     * @param skippedCount files the pre-flight check excluded. Neither transferred nor failed.
     * @param message      the transfer's free-form message: an error when [failCount] > 0, otherwise
     *                     the first skip reason. `null` falls back per-branch below.
     * @param isExtract    whether this was a Move/Extract, which words the headline differently.
     * @return text ready for `showPremiumSnackbar`, never blank.
     */
    fun pasteResult(
        context: Context,
        successCount: Int,
        failCount: Int,
        skippedCount: Int,
        message: String?,
        isExtract: Boolean
    ): String {
        val skippedNote = when {
            skippedCount <= 0 -> null
            failCount > 0 -> context.getString(R.string.preflight_skipped_count, skippedCount)
            else -> context.getString(
                R.string.preflight_skipped_summary,
                skippedCount,
                message ?: context.getString(R.string.preflight_skipped_too_large)
            )
        }

        val headline = when {
            failCount > 0 -> message ?: context.getString(R.string.paste_error)
            successCount > 0 ->
                if (isExtract) context.getString(R.string.extract_move_success, successCount)
                else context.getString(R.string.paste_success, successCount)
            else -> null
        }

        // " — " is punctuation, not prose, so there is nothing here to translate; the project
        // already joins this way in AutoBackupPrefs and AdvancedSyncActivity.
        return listOfNotNull(headline, skippedNote).joinToString(" — ")
            .ifBlank { context.getString(R.string.paste_error) }
    }
}
