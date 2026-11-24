package za.kilowatch.ultimatefilemanager.billing

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import za.kilowatch.ultimatefilemanager.network.UfmHttpClient

/**
 * Data model for a Google Play Order ID (GPA) linked to a loyalty pass.
 */
data class GpaOrder(
    val orderId: String,
    val amount: Double,
    val points: Int,
    val createdAt: String? = null
)

/**
 * Result of checking a Google Play Order ID against the central repository.
 */
sealed class GpaCheckResult {
    data class Unclaimed(val orderId: String, val amount: Double, val points: Int) : GpaCheckResult()
    data class AlreadyClaimed(val orderId: String, val loyaltyId: String) : GpaCheckResult()
    data class NotFound(val orderId: String) : GpaCheckResult()
    data class Error(val message: String) : GpaCheckResult()
}

/**
 * Result of creating, claiming, or restoring an Anonymous Loyalty Pass.
 */
sealed class PassOperationResult {
    data class Success(
        val loyaltyId: String,
        val passkey: String?,
        val totalPoints: Int,
        val newlyAddedCount: Int,
        val orders: List<GpaOrder>
    ) : PassOperationResult()

    data class Error(val message: String) : PassOperationResult()
}

/**
 * Privacy-first Manager for Anonymous Loyalty Passes and GPA Tip Orders.
 *
 * Exclusively active on Google Mobile builds (Google Play flavor, !isTv && !isAmazonDevice).
 * No personal data (name, email, device ID, phone number) is ever collected or transmitted.
 */
object LoyaltyPassManager {

    private const val PREFS_NAME = "ufm_loyalty_pass_prefs"
    private const val KEY_LOYALTY_ID = "loyalty_id"
    private const val KEY_PASSKEY = "passkey"
    private const val KEY_TOTAL_POINTS = "total_points"
    private const val KEY_ORDERS_JSON = "cached_orders_json"
    private const val KEY_LAST_SYNC_TIME = "last_sync_timestamp"

    private const val API_ENDPOINT = "https://www.kilowatch.co.za/UFM/api/verify_gpa.php"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Local State Accessors
    // ─────────────────────────────────────────────────────────────────────────

    fun hasLoyaltyPass(context: Context): Boolean {
        return getLoyaltyId(context).isNotEmpty()
    }

    fun getLoyaltyId(context: Context): String {
        return getPrefs(context).getString(KEY_LOYALTY_ID, "") ?: ""
    }

    fun getPasskey(context: Context): String {
        return getPrefs(context).getString(KEY_PASSKEY, "") ?: ""
    }

    /**
     * Authoritative points calculation.
     * If an Anonymous Loyalty Pass exists, returns the authoritative synced points.
     * Otherwise, falls back gracefully to local tip count * 10.
     */
    fun getTotalPoints(context: Context): Int {
        val prefs = getPrefs(context)
        val hasPass = hasLoyaltyPass(context)
        val syncedPoints = prefs.getInt(KEY_TOTAL_POINTS, 0)
        val localPoints = LoyaltyPrefs.getTotalTipped(context) * BadgeIconManager.POINTS_PER_DOLLAR

        return if (hasPass) {
            syncedPoints
        } else {
            localPoints
        }
    }

    fun getCachedOrders(context: Context): List<GpaOrder> {
        val rawJson = getPrefs(context).getString(KEY_ORDERS_JSON, null) ?: return emptyList()
        return parseOrdersJson(rawJson)
    }

    fun savePassCredentials(context: Context, loyaltyId: String, passkey: String?) {
        val editor = getPrefs(context).edit().putString(KEY_LOYALTY_ID, loyaltyId.trim().uppercase())
        if (!passkey.isNullOrBlank()) {
            editor.putString(KEY_PASSKEY, passkey.trim())
        }
        editor.apply()
    }

    fun saveOrdersAndPoints(context: Context, orders: List<GpaOrder>, totalPoints: Int) {
        val jsonArray = JSONArray()
        orders.forEach { order ->
            val obj = JSONObject()
            obj.put("order_id", order.orderId)
            obj.put("amount", order.amount)
            obj.put("points", order.points)
            obj.put("created_at", order.createdAt ?: "")
            jsonArray.put(obj)
        }
        getPrefs(context).edit()
            .putInt(KEY_TOTAL_POINTS, totalPoints)
            .putString(KEY_ORDERS_JSON, jsonArray.toString())
            .putLong(KEY_LAST_SYNC_TIME, System.currentTimeMillis())
            .apply()
    }

    fun clearLoyaltyPass(context: Context) {
        getPrefs(context).edit().clear().apply()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Remote Operations (Suspend / IO Dispatcher)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Checks if a GPA order exists in the central repository and whether it is claimed.
     */
    suspend fun checkGpa(orderId: String): GpaCheckResult = withContext(Dispatchers.IO) {
        val sanitized = GpaInputFormatter.sanitizeGpa(orderId)
        if (!GpaInputFormatter.isValidGpa(sanitized)) {
            return@withContext GpaCheckResult.Error("Invalid Google Play Order ID format. Must be GPA.XXXX-XXXX-XXXX-XXXXX.")
        }

        try {
            val response = UfmHttpClient.postForm(
                url = API_ENDPOINT,
                formFields = mapOf(
                    "action" to "check_gpa",
                    "order_id" to sanitized
                )
            )

            val json = try {
                JSONObject(response.body)
            } catch (_: Exception) {
                null
            }

            if (json != null) {
                val status = json.optString("status")
                when {
                    status == "unclaimed" -> {
                        val amount = json.optDouble("amount", 0.0)
                        val points = json.optInt("points", 0)
                        GpaCheckResult.Unclaimed(sanitized, amount, points)
                    }
                    status == "already_claimed" -> {
                        val claimedLoyaltyId = json.optString("loyalty_id_masked", json.optString("loyalty_id", "EXISTING_PASS"))
                        GpaCheckResult.AlreadyClaimed(sanitized, claimedLoyaltyId)
                    }
                    status == "not_found" || response.statusCode == 404 -> {
                        GpaCheckResult.NotFound(sanitized)
                    }
                    status == "invalid_format" -> {
                        GpaCheckResult.Error(json.optString("message", "Invalid Google Play Order ID format."))
                    }
                    else -> {
                        val msg = json.optString("message", "Unable to verify Order ID.")
                        GpaCheckResult.Error(msg)
                    }
                }
            } else {
                if (response.statusCode == 404) {
                    GpaCheckResult.NotFound(sanitized)
                } else {
                    GpaCheckResult.Error("Server error (${response.statusCode}). Please check your connection.")
                }
            }
        } catch (e: Exception) {
            GpaCheckResult.Error(e.message ?: "Network error while checking Order ID.")
        }
    }

    /**
     * Generates a new Anonymous Loyalty Pass linked to the given GPA order ID.
     */
    suspend fun createPass(context: Context, orderId: String): PassOperationResult = withContext(Dispatchers.IO) {
        val sanitized = GpaInputFormatter.sanitizeGpa(orderId)
        try {
            val response = UfmHttpClient.postForm(
                url = API_ENDPOINT,
                formFields = mapOf(
                    "action" to "create_pass",
                    "order_id" to sanitized
                )
            )

            val json = try {
                JSONObject(response.body)
            } catch (_: Exception) {
                null
            }

            if (json == null || (!json.optBoolean("success", false) && json.optString("status") != "success")) {
                val errMsg = json?.optString("message") ?: "Server returned code ${response.statusCode}."
                return@withContext PassOperationResult.Error(errMsg)
            }

            val loyaltyId = json.getString("loyalty_id")
            val passkey = json.getString("passkey")
            val totalPoints = json.optInt("total_points", 0)
            val incomingOrders = parseOrdersFromJson(json)

            savePassCredentials(context, loyaltyId, passkey)
            val newlyAdded = deltaSync(context, incomingOrders, totalPoints)

            PassOperationResult.Success(
                loyaltyId = loyaltyId,
                passkey = passkey,
                totalPoints = totalPoints,
                newlyAddedCount = newlyAdded,
                orders = incomingOrders
            )
        } catch (e: Exception) {
            PassOperationResult.Error(e.message ?: "Failed to generate Loyalty Pass.")
        }
    }

    /**
     * Claims an unclaimed GPA order to an existing Anonymous Loyalty Pass using its Loyalty ID and Passkey.
     */
    suspend fun claimToExisting(
        context: Context,
        orderId: String,
        loyaltyId: String,
        passkey: String
    ): PassOperationResult = withContext(Dispatchers.IO) {
        val sanitizedGpa = GpaInputFormatter.sanitizeGpa(orderId)
        val sanitizedLoyaltyId = GpaInputFormatter.sanitizeLoyaltyId(loyaltyId)

        try {
            val response = UfmHttpClient.postForm(
                url = API_ENDPOINT,
                formFields = mapOf(
                    "action" to "claim_to_existing",
                    "order_id" to sanitizedGpa,
                    "loyalty_id" to sanitizedLoyaltyId,
                    "passkey" to passkey.trim()
                )
            )

            val json = try {
                JSONObject(response.body)
            } catch (_: Exception) {
                null
            }

            if (json == null || (!json.optBoolean("success", false) && json.optString("status") != "success")) {
                val errMsg = json?.optString("message") ?: "Server returned code ${response.statusCode}."
                return@withContext PassOperationResult.Error(errMsg)
            }

            val serverLoyaltyId = json.optString("loyalty_id", sanitizedLoyaltyId)
            val totalPoints = json.optInt("total_points", 0)
            val incomingOrders = parseOrdersFromJson(json)

            savePassCredentials(context, serverLoyaltyId, passkey.trim())
            val newlyAdded = deltaSync(context, incomingOrders, totalPoints)

            PassOperationResult.Success(
                loyaltyId = serverLoyaltyId,
                passkey = null,
                totalPoints = totalPoints,
                newlyAddedCount = newlyAdded,
                orders = incomingOrders
            )
        } catch (e: Exception) {
            PassOperationResult.Error(e.message ?: "Failed to claim order to existing pass.")
        }
    }

    /**
     * Restores an Anonymous Loyalty Pass using either a Loyalty ID or a previously claimed GPA number,
     * along with the strong Passkey.
     */
    suspend fun restorePass(
        context: Context,
        identifier: String,
        passkey: String
    ): PassOperationResult = withContext(Dispatchers.IO) {
        val trimmed = identifier.trim()
        val sanitizedIdentifier = if (trimmed.startsWith("GPA.", ignoreCase = true)) {
            GpaInputFormatter.sanitizeGpa(trimmed)
        } else {
            GpaInputFormatter.sanitizeLoyaltyId(trimmed)
        }

        try {
            val response = UfmHttpClient.postForm(
                url = API_ENDPOINT,
                formFields = mapOf(
                    "action" to "restore",
                    "identifier" to sanitizedIdentifier,
                    "passkey" to passkey.trim()
                )
            )

            val json = try {
                JSONObject(response.body)
            } catch (_: Exception) {
                null
            }

            if (json == null || (!json.optBoolean("success", false) && json.optString("status") != "success")) {
                val errMsg = json?.optString("message") ?: "Invalid Passkey or ID (Code ${response.statusCode})."
                return@withContext PassOperationResult.Error(errMsg)
            }

            val serverLoyaltyId = json.getString("loyalty_id")
            val totalPoints = json.optInt("total_points", 0)
            val incomingOrders = parseOrdersFromJson(json)

            val previousLoyaltyId = getLoyaltyId(context)
            if (previousLoyaltyId.isNotEmpty() && previousLoyaltyId != serverLoyaltyId.trim().uppercase()) {
                getPrefs(context).edit().remove(KEY_ORDERS_JSON).apply()
            }

            savePassCredentials(context, serverLoyaltyId, passkey.trim())
            val newlyAdded = deltaSync(context, incomingOrders, totalPoints)

            PassOperationResult.Success(
                loyaltyId = serverLoyaltyId,
                passkey = passkey.trim(),
                totalPoints = totalPoints,
                newlyAddedCount = newlyAdded,
                orders = incomingOrders
            )
        } catch (e: Exception) {
            PassOperationResult.Error(e.message ?: "Failed to restore Loyalty Pass.")
        }
    }

    /**
     * Silently synchronizes orders and points for an already active pass.
     */
    suspend fun syncPass(context: Context): PassOperationResult = withContext(Dispatchers.IO) {
        val loyaltyId = getLoyaltyId(context)
        val passkey = getPasskey(context)

        if (loyaltyId.isEmpty() || passkey.isEmpty()) {
            return@withContext PassOperationResult.Error("No active credentials to sync.")
        }

        restorePass(context, loyaltyId, passkey)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Delta Sync Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun deltaSync(
        context: Context,
        incomingOrders: List<GpaOrder>,
        serverTotalPoints: Int
    ): Int {
        val currentOrders = getCachedOrders(context)
        val currentOrderIds = currentOrders.map { it.orderId.uppercase() }.toSet()

        val merged = currentOrders.toMutableList()
        var newlyAdded = 0

        incomingOrders.forEach { incoming ->
            if (incoming.orderId.uppercase() !in currentOrderIds) {
                merged.add(incoming)
                newlyAdded++
            }
        }

        saveOrdersAndPoints(context, merged, serverTotalPoints)
        return newlyAdded
    }

    private fun parseOrdersFromJson(json: JSONObject?): List<GpaOrder> {
        if (json == null) return emptyList()
        val arr = json.optJSONArray("orders") ?: json.optJSONArray("tips")
        return parseOrdersFromJson(arr)
    }

    private fun parseOrdersFromJson(arr: JSONArray?): List<GpaOrder> {
        if (arr == null) return emptyList()
        val list = mutableListOf<GpaOrder>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            list.add(
                GpaOrder(
                    orderId = obj.optString("order_id", ""),
                    amount = obj.optDouble("amount", 0.0),
                    points = obj.optInt("points", 0),
                    createdAt = obj.optString("created_at", "")
                )
            )
        }
        return list
    }

    private fun parseOrdersJson(rawJson: String): List<GpaOrder> {
        return try {
            parseOrdersFromJson(JSONArray(rawJson))
        } catch (e: Exception) {
            emptyList()
        }
    }
}
