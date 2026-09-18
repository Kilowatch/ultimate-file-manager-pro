package za.kilowatch.ultimatefilemanager.settings

import android.content.Context

/**
 * Manages the user preference for text positioning in Grid View items.
 *
 * Options:
 * - [Position.BELOW] (Default): File name is displayed below the square icon/thumbnail block.
 * - [Position.OVERLAY]: File name is displayed overlaid inside the bottom of the icon block with a shadow gradient.
 */
object GridTextPositionPreferenceManager {

    private const val PREFS_NAME = "grid_text_position_prefs"
    private const val KEY_POSITION = "grid_text_position"

    enum class Position(val id: String) {
        BELOW("below"),
        OVERLAY("overlay");

        companion object {
            fun fromId(id: String?): Position = entries.firstOrNull { it.id == id } ?: BELOW
        }
    }

    /**
     * Returns the configured [Position], defaulting to [Position.BELOW].
     */
    fun getPosition(context: Context): Position {
        val id = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_POSITION, Position.BELOW.id)
        return Position.fromId(id)
    }

    /**
     * Persists the selected [position].
     */
    fun setPosition(context: Context, position: Position) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_POSITION, position.id)
            .apply()
    }

    /**
     * Returns true if text is configured to display below the icon block.
     */
    fun isBelow(context: Context): Boolean = getPosition(context) == Position.BELOW
}
