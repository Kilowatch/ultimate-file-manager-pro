package za.kilowatch.ultimatefilemanager.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import za.kilowatch.ultimatefilemanager.R

/**
 * Manages Google Play Billing (v8.3.0) for the "Fuel the Developer" Tip Jar.
 *
 * ─────────────────────────────────────────────────────────────────────
 * IMPORTANT: Replace the placeholder SKU strings below with your real
 * In-App Product IDs from the Google Play Console.
 * ─────────────────────────────────────────────────────────────────────
 */
class BillingManager(
    private val context: Context,
    private val onPurchaseSuccess: (sku: String) -> Unit,
    private val onError: (message: String) -> Unit
) {
    companion object {
        // ─────────────────────────────────────────────────────────────
        // TODO: Replace these with your real Google Play product IDs
        //       configured in the Play Console → Monetize → Products.
        // ─────────────────────────────────────────────────────────────
        const val SKU_ESPRESSO = "tip_espresso_01"   // e.g. "tip_espresso_01"
        const val SKU_LATTE    = "tip_latte_05"      // e.g. "tip_latte_05"
        const val SKU_BEANS    = "tip_beans_15"      // e.g. "tip_beans_15"

        val ALL_SKUS = listOf(SKU_ESPRESSO, SKU_LATTE, SKU_BEANS)
    }

    // Live prices fetched from Play — keyed by product ID
    val productDetails = mutableMapOf<String, ProductDetails>()

    private val scope = CoroutineScope(Dispatchers.IO)

    private val purchasesUpdatedListener = PurchasesUpdatedListener { result, purchases ->
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { handlePurchase(it) }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> { /* Silently ignore — user dismissed */ }

            // Code 6 (ERROR) and 7 (ITEM_ALREADY_OWNED) are spurious second callbacks
            // that Google Play fires on test accounts after a purchase is successfully
            // consumed. The purchase already went through — these are safe to ignore.
            BillingClient.BillingResponseCode.ERROR,
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> { /* Ignore — known test-account artifact */ }

            else -> onError(context.getString(R.string.purchase_failed_code, result.responseCode))
        }
    }

    private val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(purchasesUpdatedListener)
        .enablePendingPurchases(
            // v8: must specify pending purchase params
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .enableAutoServiceReconnection() // v8 addition: auto-reconnects on drops
        .build()

    /** Connect to Google Play and fetch live product details. */
    fun connect(onReady: () -> Unit) {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    scope.launch {
                        queryProducts()
                        withContext(Dispatchers.Main) { onReady() }
                    }
                } else {
                    onError(context.getString(R.string.billing_unavailable, result.debugMessage))
                }
            }

            override fun onBillingServiceDisconnected() {
                // Auto-reconnection is handled via enableAutoServiceReconnection()
            }
        })
    }

    /** Queries in-app product details and populates [productDetails]. */
    private suspend fun queryProducts() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                ALL_SKUS.map { sku ->
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(sku)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                }
            )
            .build()

        // v8: queryProductDetails returns QueryProductDetailsResult directly
        val result = billingClient.queryProductDetails(params)
        if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            result.productDetailsList?.forEach { details ->
                productDetails[details.productId] = details
            }
        }
    }

    /** Launches the Google Play purchase sheet for the given [sku]. Must be called from UI thread. */
    fun launchPurchaseFlow(activity: Activity, sku: String) {
        val details = productDetails[sku] ?: run {
            onError(context.getString(R.string.product_not_found))
            return
        }

        val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .build()

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams))
            .build()

        billingClient.launchBillingFlow(activity, flowParams)
    }

    /** Consumes a completed purchase so the user can tip again in the future. */
    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return

        val consumeParams = ConsumeParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()

        scope.launch {
            val result = billingClient.consumePurchase(consumeParams)
            if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val sku = purchase.products.firstOrNull() ?: return@launch
                withContext(Dispatchers.Main) {
                    onPurchaseSuccess(sku)
                }
            }
        }
    }

    /** Call from Activity/Fragment onDestroy to release resources. */
    fun destroy() {
        if (billingClient.isReady) {
            billingClient.endConnection()
        }
    }
}
