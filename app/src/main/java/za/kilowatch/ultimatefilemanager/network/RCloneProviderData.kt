package za.kilowatch.ultimatefilemanager.network

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import za.kilowatch.ultimatefilemanager.R

/**
 * The type of input field shown for a provider's configuration value.
 */
enum class FieldType {
    /** Plain text input (email, username, host, token, api key). */
    TEXT,
    /** Password input with visibility toggle (password_toggle end icon). */
    PASSWORD,
    /** API key / token input — single-line text, often masked. */
    API_KEY
}

/**
 * Defines a single configuration field for an RClone storage provider.
 *
 * @param key         The config key written to rclone.conf (e.g. "email", "password", "api_key").
 * @param labelResId  Translatable string resource for the field label.
 * @param inputType   The type of input widget to render.
 * @param required    Whether the field must be filled before Save is enabled.
 * @param defaultValue Optional default value pre-filled into the input field.
 */
data class RCloneProviderField(
    val key: String,
    @StringRes val labelResId: Int,
    val inputType: FieldType,
    val required: Boolean = true,
    /** URL to setup instructions shown as a clickable link below the field, or null for no link. */
    val helpUrl: String? = null,
    /** Default value pre-filled into the input field (e.g. placeholder URL). */
    val defaultValue: String? = null,
    /** Helper text shown below the field to guide the user. */
    @StringRes val helperTextResId: Int? = null
)

/**
 * Metadata for an RClone storage provider.
 *
 * @param id         Unique slug matching the rclone backend package (e.g. "filen").
 * @param nameResId  Translatable string resource for the display name.
 * @param iconResId  Drawable resource for the provider icon.
 * @param typeName   The rclone config "type" value (e.g. "filen", "mega", "drive").
 * @param fields     The authentication fields this provider requires.
 */
data class RCloneProviderInfo(
    val id: String,
    @StringRes val nameResId: Int,
    @field:DrawableRes val iconResId: Int,
    val typeName: String,
    val fields: List<RCloneProviderField>
)

/**
 * Registry of all known RClone providers available in the app.
 *
 * To add a new provider:
 * 1. Add its backend import to `rclone-custom/gomobile.go` and rebuild the .aar.
 * 2. Add its config builder function to `RCloneConfig.kt`.
 * 3. Add a new [RCloneProviderInfo] entry to this list.
 * 4. Add the corresponding string resources to `strings.xml`.
 *
 * @see RCloneProviderInfo
 * @see RCloneProviderField
 */
val ALL_RCLONE_PROVIDERS: List<RCloneProviderInfo> = listOf(
    RCloneProviderInfo(
        id = "filen",
        nameResId = R.string.rclone_provider_filen,
        iconResId = R.drawable.ic_rclone,
        typeName = "filen",
        fields = listOf(
            RCloneProviderField(
                key = "email",
                labelResId = R.string.rclone_field_email,
                inputType = FieldType.TEXT
            ),
            RCloneProviderField(
                key = "password",
                labelResId = R.string.rclone_field_password,
                inputType = FieldType.PASSWORD
            ),
            RCloneProviderField(
                key = "api_key",
                labelResId = R.string.rclone_field_api_key,
                inputType = FieldType.API_KEY
            )
        )
    ),
    RCloneProviderInfo(
        id = "drime",
        nameResId = R.string.rclone_provider_drime,
        iconResId = R.drawable.ic_rclone,
        typeName = "drime",
        fields = listOf(
            RCloneProviderField(
                key = "access_token",
                labelResId = R.string.rclone_field_access_token,
                inputType = FieldType.TEXT,
                helpUrl = "https://app.drime.cloud/developer"
            )
        )
    ),
    RCloneProviderInfo(
        id = "mega",
        nameResId = R.string.rclone_provider_mega,
        iconResId = R.drawable.ic_rclone,
        typeName = "mega",
        fields = listOf(
            RCloneProviderField(
                key = "user",
                labelResId = R.string.rclone_field_email,
                inputType = FieldType.TEXT
            ),
            RCloneProviderField(
                key = "pass",
                labelResId = R.string.rclone_field_password,
                inputType = FieldType.PASSWORD
            )
        )
    ),
    RCloneProviderInfo(
        id = "koofr",
        nameResId = R.string.rclone_provider_koofr,
        iconResId = R.drawable.ic_rclone,
        typeName = "koofr",
        fields = listOf(
            RCloneProviderField(
                key = "user",
                labelResId = R.string.rclone_field_email,
                inputType = FieldType.TEXT
            ),
            RCloneProviderField(
                key = "password",
                labelResId = R.string.rclone_field_password,
                inputType = FieldType.PASSWORD,
                helpUrl = "https://app.koofr.net/app/admin/preferences/password",
                helperTextResId = R.string.rclone_field_koofr_password_hint
            ),
            RCloneProviderField(
                key = "endpoint",
                labelResId = R.string.rclone_field_server_url,
                inputType = FieldType.TEXT,
                required = false,
                defaultValue = "https://app.koofr.net"
            )
        )
    ),
    RCloneProviderInfo(
        id = "premiumizeme",
        nameResId = R.string.rclone_provider_premiumizeme,
        iconResId = R.drawable.ic_rclone,
        typeName = "premiumizeme",
        fields = listOf(
            RCloneProviderField(
                key = "api_key",
                labelResId = R.string.rclone_field_api_key,
                inputType = FieldType.API_KEY,
                helpUrl = "https://www.premiumize.me/account"
            )
        )
    )
)
