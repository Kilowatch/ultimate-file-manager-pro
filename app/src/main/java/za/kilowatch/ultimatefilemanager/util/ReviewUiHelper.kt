package za.kilowatch.ultimatefilemanager.util

import android.app.Activity
import android.view.LayoutInflater
import android.util.Log
import android.view.View
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.billing.SupporterLoyaltyActivity
import androidx.fragment.app.FragmentActivity
import za.kilowatch.ultimatefilemanager.BuildConfig

/**
 * Handles the visual presentation of the "Rate Us" popup for both Mobile and TV.
 */
object ReviewUiHelper {

    private const val TAG = "GoRoRating"

    /**
     * Shows a premium, theme-reactive review popup.
     */
    fun showReviewPopup(activity: Activity) {
        val isTv = DeviceUtils.isTvDevice(activity)
        
        // Use a themed builder to ensure premium look
        val builder = MaterialAlertDialogBuilder(activity, R.style.UFM_Dialog)
        
        val inflater = LayoutInflater.from(activity)
        val view = if (isTv) {
            inflater.inflate(R.layout.dialog_review_premium_tv, null)
        } else {
            inflater.inflate(R.layout.dialog_review_premium, null)
        }

        builder.setView(view)
        val dialog = builder.create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setCancelable(false)
        dialog.window?.setBackgroundDrawableResource(if (isTv) R.drawable.bg_dialog_glass else R.drawable.bg_dialog_surface)

        val btnRate = view.findViewById<MaterialButton>(R.id.btnRate)
        
        // Dynamically set button text based on store
        if (za.kilowatch.ultimatefilemanager.util.DeviceUtils.isAmazonDevice(activity)) {
            btnRate.text = activity.getString(R.string.rate_us_button_amazon)
        } else {
            btnRate.text = activity.getString(R.string.rate_us_button_play)
        }

        val btnTipLink = view.findViewById<View>(R.id.btnTipLink)
        val btnMaybeLater = view.findViewById<MaterialButton>(R.id.btnMaybeLater)
        val btnNoThanks = view.findViewById<MaterialButton>(R.id.btnNoThanks)

        Log.d(TAG, "showReviewPopup: displaying dialog (isTv=$isTv)")

        btnRate.setOnClickListener {
            Log.d(TAG, "Dialog: Tapped 'Rate the App'")
            if (za.kilowatch.ultimatefilemanager.util.DeviceUtils.isAmazonDevice(activity) && !BuildConfig.AMAZON_RATING_ENABLED) {
                android.widget.Toast.makeText(activity, R.string.rating_unavailable_amazon_msg, android.widget.Toast.LENGTH_LONG).show()
            } else {
                ReviewPrefs.onRateUsTapped(activity)
                ReviewHelper.launchInAppReview(activity)
            }
            dialog.dismiss()
        }

        btnTipLink.setOnClickListener {
            Log.d(TAG, "Dialog: Tapped 'Tip Jar Link'")
            if (za.kilowatch.ultimatefilemanager.util.DeviceUtils.isAmazonDevice(activity) && !BuildConfig.AMAZON_IAP_ENABLED) {
                android.widget.Toast.makeText(activity, R.string.billing_unavailable_amazon_coming_soon, android.widget.Toast.LENGTH_LONG).show()
            } else {
                ReviewPrefs.onNoThanks(activity) // Mark as no thanks/never ask
                if (activity is FragmentActivity) {
                    activity.startActivity(android.content.Intent(activity, SupporterLoyaltyActivity::class.java))
                }
            }
            dialog.dismiss()
        }

        btnMaybeLater.setOnClickListener {
            Log.d(TAG, "Dialog: Tapped 'Maybe Later'")
            ReviewPrefs.onMaybeLater(activity)
            dialog.dismiss()
        }

        btnNoThanks.setOnClickListener {
            Log.d(TAG, "Dialog: Tapped 'No Thanks'")
            ReviewPrefs.onNoThanks(activity)
            dialog.dismiss()
        }

        // TV-specific focus management
        if (isTv) {
            btnRate.requestFocus()
            
            val defaultColor = activity.getColor(R.color.tv_button_bg_tint)
            val focusColor = activity.getColor(R.color.tv_button_focused_yellow)
            val defaultTextColor = activity.getColor(R.color.tv_text_primary)
            val tipDefaultTextColor = activity.getColor(R.color.tile_tip_jar_accent)
            val focusTextColor = activity.getColor(R.color.tv_button_focused_yellow_text)

            listOf(btnRate, btnMaybeLater, btnNoThanks, btnTipLink).forEach { btn ->
                btn.setOnFocusChangeListener { _, hasFocus ->
                    if (btn is MaterialButton) {
                        if (hasFocus) {
                            btn.setBackgroundColor(focusColor)
                            btn.setTextColor(focusTextColor)
                        } else {
                            btn.setBackgroundColor(defaultColor)
                            btn.setTextColor(defaultTextColor)
                        }
                    } else if (btn is TextView) {
                        // For the Tip Link text
                        if (hasFocus) {
                            btn.setTextColor(focusTextColor)
                        } else {
                            btn.setTextColor(tipDefaultTextColor)
                        }
                    }
                }
            }
        }

        // Removed forced GONE to allow BuildConfig.AMAZON_IAP_ENABLED to control visibility if needed
        // if (za.kilowatch.ultimatefilemanager.util.DeviceUtils.isAmazonDevice(activity)) {
        //     btnTipLink.visibility = View.GONE
        // }

        dialog.show()
    }
}
