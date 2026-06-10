package za.kilowatch.ultimatefilemanager.billing

import android.app.Activity
import android.content.Context

/**
 * FOSS build stub for BillingManager.
 *
 * Google Play Billing is not available in the FOSS build.
 * All methods are no-ops. The constructor accepts (but ignores) the same parameters
 * as the real BillingManager so call-sites in shared code continue to compile.
 */
class BillingManager(
    private val context: Context,
    private val onPurchaseSuccess: (sku: String) -> Unit,
    private val onError: (message: String) -> Unit
) {
    companion object {
        const val SKU_ESPRESSO = "tip_espresso_01"
        const val SKU_LATTE    = "tip_latte_05"
        const val SKU_BEANS    = "tip_beans_15"
        val ALL_SKUS = listOf(SKU_ESPRESSO, SKU_LATTE, SKU_BEANS)
    }

    /** No-op — billing is not available in the FOSS build. */
    val productDetails = mutableMapOf<String, Any>()

    /** No-op — billing is not available in the FOSS build. */
    fun connect(onReady: () -> Unit) { /* no-op */ }

    /** No-op — billing is not available in the FOSS build. */
    fun launchPurchaseFlow(activity: Activity, sku: String) { /* no-op */ }

    /** No-op — billing is not available in the FOSS build. */
    fun destroy() { /* no-op */ }
}
