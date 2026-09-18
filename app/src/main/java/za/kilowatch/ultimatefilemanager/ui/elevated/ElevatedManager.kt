package za.kilowatch.ultimatefilemanager.ui.elevated

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.storage.ShizukuShellWrapper

/**
 * The third-party elevated-access managers UFM can delegate to.
 *
 * Declaration order **is** display order: Porter (recommended) → Shizuku → Shevery (FR-02, FR-04).
 * Ordering deliberately lives here rather than in layout XML so it cannot drift between the mobile
 * and TV layouts, and so it is present regardless of what is installed (FR-05).
 *
 * Package names are taken from [ShizukuShellWrapper] where they already exist rather than re-typed
 * as literals. A package string that drifts by one character fails silently — the same failure mode
 * as the authority mismatch in CR-08 — so there is exactly one definition of each.
 */
enum class ElevatedManager(
    val packageName: String,
    val permission: String,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    @DrawableRes val brandIconRes: Int,
    /** The not-installed call to action. Names the app, because the three cards sit together. */
    @StringRes val downloadLabelRes: Int,
    /** Porter only. Renders the "Recommended" pill beside the card title (FR-03). */
    val isRecommended: Boolean = false
) {
    PORTER(
        packageName = ShizukuShellWrapper.PORTER_PACKAGE,
        permission = "eu.darken.porter.permission.API_V23",
        titleRes = R.string.shizuku_manager_porter_title,
        descriptionRes = R.string.shizuku_manager_porter_desc,
        brandIconRes = R.drawable.ic_porter_logo,
        downloadLabelRes = R.string.shizuku_download_porter_apk,
        isRecommended = true
    ),
    SHIZUKU(
        packageName = ShizukuShellWrapper.SHIZUKU_PACKAGE,
        permission = "moe.shizuku.manager.permission.API_V23",
        titleRes = R.string.shizuku_manager_shizuku_title,
        descriptionRes = R.string.shizuku_manager_shizuku_desc,
        brandIconRes = R.drawable.ic_shizuku_logo,
        downloadLabelRes = R.string.shizuku_download_shizuku_apk
    ),
    SHEVERY(
        packageName = ShizukuShellWrapper.SHEVERY_PACKAGE,
        permission = "moe.shizuku.manager.permission.API_V23",
        titleRes = R.string.shizuku_manager_shevery_title,
        descriptionRes = R.string.shizuku_manager_shevery_desc,
        // Shevery has no brand vector of its own; ic_lightning is what the download manager
        // already uses to represent it, so the two surfaces agree.
        brandIconRes = R.drawable.ic_lightning,
        downloadLabelRes = R.string.shizuku_download_shevery_apk
    )
}
