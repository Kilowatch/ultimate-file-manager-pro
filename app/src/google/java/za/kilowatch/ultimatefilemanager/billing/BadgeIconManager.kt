package za.kilowatch.ultimatefilemanager.billing

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import za.kilowatch.ultimatefilemanager.R

data class BadgeItem(
    val points: Int,
    val titleRes: Int,
    val descRes: Int,
    val iconRes: Int,
    val cutoutRes: Int,
    val aliasName: String,
    val strokeColorHex: String
)

object BadgeIconManager {

    private const val PREFS_NAME = "ufm_badge_prefs"
    private const val KEY_SELECTED_POINTS = "selected_badge_points"
    const val POINTS_PER_DOLLAR = 10

    // DEBUG: Set to true to unlock all badges for testing/previewing, false for normal loyalty check.
    var DEBUG_FORCE_ALL_UNLOCKED = false

    val BADGES = listOf(
        BadgeItem(5, R.string.badge_title_5, R.string.badge_desc_5, R.drawable.app_icon_points_5, R.drawable.badge_points_5, "za.kilowatch.ultimatefilemanager.BadgeIcon5", "#00B4D8"),
        BadgeItem(10, R.string.badge_title_10, R.string.badge_desc_10, R.drawable.app_icon_points_10, R.drawable.badge_points_10, "za.kilowatch.ultimatefilemanager.BadgeIcon10", "#CD7F32"),
        BadgeItem(20, R.string.badge_title_20, R.string.badge_desc_20, R.drawable.app_icon_points_20, R.drawable.badge_points_20, "za.kilowatch.ultimatefilemanager.BadgeIcon20", "#A8B2C1"),
        BadgeItem(50, R.string.badge_title_50, R.string.badge_desc_50, R.drawable.app_icon_points_50, R.drawable.badge_points_50, "za.kilowatch.ultimatefilemanager.BadgeIcon50", "#FFB800"),
        BadgeItem(100, R.string.badge_title_100, R.string.badge_desc_100, R.drawable.app_icon_points_100, R.drawable.badge_points_100, "za.kilowatch.ultimatefilemanager.BadgeIcon100", "#38BDF8"),
        BadgeItem(200, R.string.badge_title_200, R.string.badge_desc_200, R.drawable.app_icon_points_200, R.drawable.badge_points_200, "za.kilowatch.ultimatefilemanager.BadgeIcon200", "#E2E8F0"),
        BadgeItem(300, R.string.badge_title_300, R.string.badge_desc_300, R.drawable.app_icon_points_300, R.drawable.badge_points_300, "za.kilowatch.ultimatefilemanager.BadgeIcon300", "#60A5FA"),
        BadgeItem(500, R.string.badge_title_500, R.string.badge_desc_500, R.drawable.app_icon_points_500, R.drawable.badge_points_500, "za.kilowatch.ultimatefilemanager.BadgeIcon500", "#A855F7"),
        BadgeItem(750, R.string.badge_title_750, R.string.badge_desc_750, R.drawable.app_icon_points_750, R.drawable.badge_points_750, "za.kilowatch.ultimatefilemanager.BadgeIcon750", "#F97316"),
        BadgeItem(1000, R.string.badge_title_1000, R.string.badge_desc_1000, R.drawable.app_icon_points_1000, R.drawable.badge_points_1000, "za.kilowatch.ultimatefilemanager.BadgeIcon1000", "#EF4444")
    )

    fun getTotalTippedPoints(context: Context): Int {
        return LoyaltyPrefs.getTotalTipped(context) * POINTS_PER_DOLLAR
    }

    fun isBadgeUnlocked(context: Context, points: Int): Boolean {
        if (DEBUG_FORCE_ALL_UNLOCKED) return true
        if (points <= 0) return true
        return getTotalTippedPoints(context) >= points
    }

    fun getSelectedBadgePoints(context: Context): Int? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pts = prefs.getInt(KEY_SELECTED_POINTS, 0)
        return if (pts > 0) pts else null
    }

    fun setSelectedBadge(context: Context, points: Int?) {
        val pm = context.packageManager
        val pkg = context.packageName
        val defaultComponent = ComponentName(pkg, "za.kilowatch.ultimatefilemanager.onboarding.LanguageWelcomeActivity")

        val targetAlias = if (points != null) {
            BADGES.find { it.points == points }?.aliasName
        } else null

        // 1. Enable target alias or re-enable default
        if (targetAlias != null) {
            pm.setComponentEnabledSetting(
                ComponentName(pkg, targetAlias),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            pm.setComponentEnabledSetting(
                defaultComponent,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } else {
            pm.setComponentEnabledSetting(
                defaultComponent,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        }

        // 2. Disable all other aliases
        for (badge in BADGES) {
            if (badge.aliasName != targetAlias) {
                pm.setComponentEnabledSetting(
                    ComponentName(pkg, badge.aliasName),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        }

        // 3. Save selection
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_SELECTED_POINTS, points ?: 0).apply()
    }
}
