package za.kilowatch.ultimatefilemanager.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import za.kilowatch.ultimatefilemanager.R

/**
 * Represents a debloat recommendation for a package.
 */
data class DebloatApp(
    val packageName: String,
    val recommendation: String, // Recommended, Safe, Caution, Dangerous, Unsafe
    val description: String,
    val labels: List<String> = emptyList()
)

object DebloatParser {

    /**
     * Parses the Next-Generation Universal Android Debloater (UAD) JSON format.
     * Example format:
     * {
     *   "com.android.chrome": {
     *     "list": "Google",
     *     "description": "Google Chrome browser.",
     *     "removal": "Recommended",
     *     "labels": ["browser"]
     *   }
     * }
     */
    fun parseUadJson(context: Context, jsonString: String): Map<String, DebloatApp> {
        val result = mutableMapOf<String, DebloatApp>()
        try {
            val root = JSONObject(jsonString)
            val keys = root.keys()
            
            while (keys.hasNext()) {
                val pkg = keys.next()
                if (pkg.isEmpty()) continue

                val obj = root.optJSONObject(pkg) ?: continue
                val recommendation = obj.optString("removal", "Safe")
                val description = obj.optString("description", context.getString(R.string.no_description_available))
                
                val labelsList = mutableListOf<String>()
                val labelsArray = obj.optJsonArray("labels")
                if (labelsArray != null) {
                    for (j in 0 until labelsArray.length()) {
                        labelsList.add(labelsArray.getString(j))
                    }
                }

                result[pkg] = DebloatApp(
                    packageName = pkg,
                    recommendation = recommendation,
                    description = description,
                    labels = labelsList
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    private fun JSONObject.optJsonArray(name: String): JSONArray? {
        return if (has(name) && !isNull(name)) getJSONArray(name) else null
    }
}
