package za.kilowatch.ultimatefilemanager.billing

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.amazon.device.iap.PurchasingListener
import com.amazon.device.iap.PurchasingService
import com.amazon.device.iap.model.FulfillmentResult
import com.amazon.device.iap.model.ProductDataResponse
import com.amazon.device.iap.model.ProductType
import com.amazon.device.iap.model.PurchaseResponse
import com.amazon.device.iap.model.PurchaseUpdatesResponse
import com.amazon.device.iap.model.UserDataResponse
import za.kilowatch.ultimatefilemanager.R

/**
 * Manages Amazon Appstore In-App Purchasing (SDK 3.0.8) for the "Fuel the Developer" Tip Jar.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * IMPORTANT: Before this works in production, you must:
 *   1. Create 3 Consumable IAP items in the Amazon Developer Console with
 *      exactly the SKU IDs defined in the companion object below.
 *   2. Place your AppstoreAuthenticationKey.pem in app/src/main/assets/.
 *   3. Set BuildConfig.AMAZON_IAP_ENABLED = true in build.gradle.kts once ready.
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Lifecycle (mirrors Google Play BillingManager pattern):
 *  - Call [register] from SupporterLoyaltyActivity.onCreate() BEFORE setContentView.
 *  - Call [onResume] from SupporterLoyaltyActivity.onResume() to refresh user/product data.
 *  - Call [purchase] when a tip button is tapped.
 */
class AmazonBillingManager(
    private val context: Context,
    private val onPurchaseSuccess: (sku: String) -> Unit,
    private val onProductsLoaded: () -> Unit,
    private val onError: (message: String) -> Unit
) {
    companion object {
        private const val TAG = "AmazonBillingManager"

        // ─── Match these exactly with the SKUs created in the Amazon Developer Console ────
        const val SKU_ESPRESSO = "tip_espresso_01"
        const val SKU_LATTE    = "tip_latte_05"
        const val SKU_BEANS    = "tip_beans_15"

        val ALL_SKUS: Set<String> = setOf(SKU_ESPRESSO, SKU_LATTE, SKU_BEANS)

        /**
         * Amazon IAP SDK allows only ONE call to [PurchasingService.registerListener] per
         * process lifetime. Any subsequent call throws "Resource already registered".
         */
        @Volatile
        private var listenerRegistered = false

        /** Cached product details across activity lifecycle. */
        val cachedProductDetails = mutableMapOf<String, com.amazon.device.iap.model.Product>()

        /** Currently active billing manager delegate to route callbacks to. */
        @Volatile
        private var activeDelegate: AmazonBillingManager? = null

        private val mainHandler = Handler(Looper.getMainLooper())

        // ─── Process-level PurchasingListener ─────────────────────────────────────────────
        private val processPurchasingListener = object : PurchasingListener {

            override fun onUserDataResponse(response: UserDataResponse?) {
                if (response == null) return
                Log.d(TAG, "onUserDataResponse: status=${response.requestStatus}")
                when (response.requestStatus) {
                    UserDataResponse.RequestStatus.SUCCESSFUL -> {
                        val userData = response.userData
                        if (userData != null) {
                            activeDelegate?.currentUserId = userData.userId
                            activeDelegate?.currentMarketplace = userData.marketplace
                            Log.d(TAG, "Amazon user: ${userData.userId}, marketplace: ${userData.marketplace}")
                        }
                    }
                    else -> Log.w(TAG, "getUserData failed: ${response.requestStatus}")
                }
            }

            override fun onProductDataResponse(response: ProductDataResponse?) {
                if (response == null) return
                Log.d(TAG, "onProductDataResponse: status=${response.requestStatus}")
                when (response.requestStatus) {
                    ProductDataResponse.RequestStatus.SUCCESSFUL -> {
                        response.productData?.values?.forEach { product ->
                            if (product != null) {
                                cachedProductDetails[product.sku] = product
                                Log.d(TAG, "  Product: ${product.sku} = ${product.price}")
                            }
                        }
                        if (response.unavailableSkus?.isNotEmpty() == true) {
                            Log.w(TAG, "Unavailable SKUs: ${response.unavailableSkus}")
                        }
                        mainHandler.post {
                            activeDelegate?.let { delegate ->
                                delegate.productDetails.putAll(cachedProductDetails)
                                delegate.onProductsLoaded()
                            }
                        }
                    }
                    else -> {
                        Log.w(TAG, "getProductData failed: ${response.requestStatus}")
                        val errorStatus = response.requestStatus?.toString() ?: "UNKNOWN"
                        mainHandler.post {
                            activeDelegate?.let { delegate ->
                                delegate.onError(delegate.context.getString(R.string.amazon_iap_error, errorStatus))
                            }
                        }
                    }
                }
            }

            override fun onPurchaseResponse(response: PurchaseResponse?) {
                if (response == null) return
                Log.d(TAG, "onPurchaseResponse: status=${response.requestStatus}, sku=${response.receipt?.sku}")
                when (response.requestStatus) {
                    PurchaseResponse.RequestStatus.SUCCESSFUL -> {
                        val receipt = response.receipt
                        if (receipt != null) {
                            mainHandler.post {
                                try {
                                    PurchasingService.notifyFulfillment(receipt.receiptId, FulfillmentResult.FULFILLED)
                                } catch (t: Throwable) {
                                    Log.e(TAG, "Failed to notify fulfillment", t)
                                }
                                val sku = receipt.sku ?: ""
                                activeDelegate?.onPurchaseSuccess?.invoke(sku)
                            }
                        }
                    }
                    PurchaseResponse.RequestStatus.ALREADY_PURCHASED -> {
                        val receipt = response.receipt
                        if (receipt != null) {
                            mainHandler.post {
                                try {
                                    PurchasingService.notifyFulfillment(receipt.receiptId, FulfillmentResult.FULFILLED)
                                } catch (t: Throwable) {
                                    Log.e(TAG, "Failed to notify fulfillment", t)
                                }
                            }
                        }
                    }
                    PurchaseResponse.RequestStatus.FAILED,
                    PurchaseResponse.RequestStatus.NOT_SUPPORTED -> {
                        Log.w(TAG, "Purchase not completed: ${response.requestStatus}")
                    }
                    PurchaseResponse.RequestStatus.INVALID_SKU -> {
                        mainHandler.post {
                            activeDelegate?.let { delegate ->
                                delegate.onError(delegate.context.getString(R.string.amazon_iap_error, "Invalid SKU"))
                            }
                        }
                    }
                    else -> Log.w(TAG, "Purchase: unexpected status ${response.requestStatus}")
                }
            }

            override fun onPurchaseUpdatesResponse(response: PurchaseUpdatesResponse?) {
                if (response == null) return
                Log.d(TAG, "onPurchaseUpdatesResponse: status=${response.requestStatus}")
                when (response.requestStatus) {
                    PurchaseUpdatesResponse.RequestStatus.SUCCESSFUL -> {
                        mainHandler.post {
                            try {
                                response.receipts?.forEach { receipt ->
                                    if (receipt != null && receipt.productType == ProductType.CONSUMABLE) {
                                        Log.d(TAG, "  Re-fulfilling pending receipt: ${receipt.receiptId}")
                                        PurchasingService.notifyFulfillment(receipt.receiptId, FulfillmentResult.FULFILLED)
                                    }
                                }
                                if (response.hasMore()) {
                                    PurchasingService.getPurchaseUpdates(false)
                                }
                            } catch (t: Throwable) {
                                Log.e(TAG, "Failed in onPurchaseUpdatesResponse processing", t)
                            }
                        }
                    }
                    else -> Log.w(TAG, "getPurchaseUpdates failed: ${response.requestStatus}")
                }
            }
        }

        private fun registerProcessListener(appContext: Context) {
            if (listenerRegistered) return
            synchronized(this) {
                if (listenerRegistered) return
                try {
                    PurchasingService.registerListener(appContext, processPurchasingListener)
                    listenerRegistered = true
                    Log.d(TAG, "Process PurchasingListener registered successfully.")
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to register PurchasingListener", t)
                }
            }
        }
    }

    // Live product data fetched from Amazon — keyed by SKU.
    val productDetails = mutableMapOf<String, com.amazon.device.iap.model.Product>()

    var currentUserId: String? = null
    var currentMarketplace: String? = null

    // ─── Public API ───────────────────────────────────────────────────────────────────────

    /**
     * Registers the active billing delegate and ensures the process listener is active.
     */
    fun register(context: Context) {
        activeDelegate = this
        productDetails.putAll(cachedProductDetails)
        registerProcessListener(context.applicationContext)
        if (cachedProductDetails.isNotEmpty()) {
            mainHandler.post { onProductsLoaded() }
        }
    }

    /**
     * Refreshes user data and product prices.
     * Call from Activity.onResume().
     */
    fun onResume() {
        activeDelegate = this
        // If listener registration failed earlier, retry before calling SDK methods
        if (!listenerRegistered) {
            registerProcessListener(context.applicationContext)
        }
        if (!listenerRegistered) {
            Log.w(TAG, "onResume: PurchasingListener not registered, skipping SDK calls")
            return
        }
        try {
            PurchasingService.getUserData()
            PurchasingService.getPurchaseUpdates(false)
            PurchasingService.getProductData(ALL_SKUS)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to call onResume PurchasingService methods", t)
        }
    }

    /**
     * Initiates a purchase for the given [sku].
     */
    fun purchase(activity: Activity, sku: String) {
        Log.d(TAG, "purchase: $sku")
        // If listener registration failed earlier, retry before purchasing
        if (!listenerRegistered) {
            registerProcessListener(context.applicationContext)
        }
        if (!listenerRegistered) {
            Log.e(TAG, "Cannot purchase: PurchasingListener not registered")
            onError(context.getString(R.string.amazon_iap_not_ready))
            return
        }
        mainHandler.post {
            try {
                PurchasingService.purchase(sku)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to call PurchasingService.purchase", t)
                onError("Failed to initiate purchase: ${t.localizedMessage ?: t.javaClass.simpleName}")
            }
        }
    }

    /**
     * Returns true if products have already been loaded into cache.
     */
    fun hasCachedProducts(): Boolean = cachedProductDetails.isNotEmpty()

    /**
     * Returns the formatted price string for a SKU, or null if not yet loaded.
     */
    fun priceFor(sku: String): String? = productDetails[sku]?.price ?: cachedProductDetails[sku]?.price

    /**
     * Detaches the active delegate to avoid leaking the activity.
     */
    fun destroy() {
        if (activeDelegate === this) {
            activeDelegate = null
        }
    }
}
