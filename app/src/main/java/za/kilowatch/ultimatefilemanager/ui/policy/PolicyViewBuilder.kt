package za.kilowatch.ultimatefilemanager.ui.policy

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import za.kilowatch.ultimatefilemanager.R

/**
 * Builds all policy screen views programmatically from string resources.
 * Call the appropriate build function and add the returned views to your container.
 * Supports both Mobile and TV form factors with appropriate styling.
 */
object PolicyViewBuilder {

    private var isTv = false

    // ─────────────────────────────────────────────────────────────
    // Public entry points
    // ─────────────────────────────────────────────────────────────

    fun buildTermsViews(context: Context, isTvDevice: Boolean): List<View> {
        isTv = isTvDevice
        return buildList {
        add(summaryBanner(context, context.getString(R.string.tc_summary)))
        add(spacer(context, if (isTv) 24 else 16))

        // Section 1
        add(sectionCard(context) {
            addView(sectionTitle(context, context.getString(R.string.tc_s1_title), "📋"))
            addView(bodyText(context, context.getString(R.string.tc_s1_body)))
        })

        // Section 2 — Features
        add(sectionCard(context) {
            addView(sectionTitle(context, context.getString(R.string.tc_s2_title), "⚙️"))
            addView(bodyText(context, context.getString(R.string.tc_s2_body)))
            addView(spacer(context, if (isTv) 12 else 8))
            context.resources.getStringArray(R.array.tc_features).forEach { feature ->
                addView(checkRow(context, feature, CheckStyle.BULLET))
            }
        })

        // Section 3 — Risk table
        add(sectionCard(context) {
            addView(sectionTitle(context, context.getString(R.string.tc_s3_title), "⚠️"))
            addView(bodyText(context, context.getString(R.string.tc_s3_body)))
            addView(spacer(context, if (isTv) 12 else 8))
            val labels = context.resources.getStringArray(R.array.tc_risk_labels)
            val details = context.resources.getStringArray(R.array.tc_risk_details)
            for (i in 0 until minOf(labels.size, details.size)) {
                addView(tableRow(context, labels[i], details[i], i % 2 == 1))
            }
            addView(spacer(context, 8))
            addView(alertBox(context, "💡", context.getString(R.string.practical_advice),
                context.getString(R.string.before_using_any_file_manager_back_up_your_important_files_this_is_good_practice_regardless_of_which_app_you_use),
                AlertStyle.INFO))
        })

        // Section 3a — In-App Purchases & Billing
        add(sectionCard(context) {
            addView(sectionTitle(context, context.getString(R.string.tc_s3a_title), "☕"))
            addView(bodyText(context, context.getString(R.string.tc_s3a_body)))
            addView(spacer(context, 8))
            val billingLabels = listOf(context.getString(R.string.payment_processor), context.getString(R.string.refunds), context.getString(R.string.recurring_charges), context.getString(R.string.no_obligation))
            val billingDetails = listOf(
                context.getString(R.string.tc_s3a_payment),
                context.getString(R.string.tc_s3a_refunds),
                context.getString(R.string.tc_s3a_recurring),
                context.getString(R.string.tc_s3a_no_obligation)
            )
            for (i in 0 until minOf(billingLabels.size, billingDetails.size)) {
                addView(tableRow(context, billingLabels[i], billingDetails[i], i % 2 == 1))
            }
            addView(spacer(context, 8))
            addView(alertBox(context, "💡", context.getString(R.string.plainenglish_summary),
                context.getString(R.string.tips_are_processed_safely_by_google_we_never_see_your_payment_details_you_will_never_be_charged_without_tapping_a_button_yourself),
                AlertStyle.INFO))
        })

        // Section 3b — RClone Cloud Integration
        add(sectionCard(context) {
            addView(sectionTitle(context, context.getString(R.string.tc_s3b_title), "☁️"))
            addView(bodyText(context, context.getString(R.string.tc_s3b_body)))
            addView(spacer(context, if (isTv) 12 else 8))
            val labels = context.resources.getStringArray(R.array.tc_rclone_labels)
            val details = context.resources.getStringArray(R.array.tc_rclone_details)
            for (i in 0 until minOf(labels.size, details.size)) {
                addView(tableRow(context, labels[i], details[i], i % 2 == 1))
            }
            addView(spacer(context, 8))
            addView(alertBox(context, "💡", context.getString(R.string.plainenglish_summary),
                context.getString(R.string.tc_s3b_summary),
                AlertStyle.INFO))
        })

        // Section 4 — Responsibilities
        add(sectionCard(context) {
            addView(sectionTitle(context, context.getString(R.string.tc_s4_title), "✅"))
            addView(bodyText(context, context.getString(R.string.tc_s4_body)))
            addView(spacer(context, 8))
            context.resources.getStringArray(R.array.tc_responsibilities).forEach { item ->
                addView(checkRow(context, item, CheckStyle.CHECK))
            }
        })

        // Section 5 — Permissions table
        add(sectionCard(context) {
            addView(sectionTitle(context, context.getString(R.string.tc_s5_title), "🔐"))
            addView(bodyText(context, context.getString(R.string.tc_s5_body)))
            addView(spacer(context, 8))
            val labels = context.resources.getStringArray(R.array.tc_permission_labels)
            val details = context.resources.getStringArray(R.array.tc_permission_details)
            for (i in 0 until minOf(labels.size, details.size)) {
                addView(tableRow(context, labels[i], details[i], i % 2 == 1))
            }
        })

        // Section 6
        add(sectionCard(context) {
            addView(sectionTitle(context, context.getString(R.string.tc_s6_title), "©"))
            addView(bodyText(context, context.getString(R.string.tc_s6_body)))
        })

        // Section 7 — Open source
        add(sectionCard(context) {
            addView(sectionTitle(context, context.getString(R.string.tc_s7_title), "🧩"))
            addView(bodyText(context, context.getString(R.string.tc_s7_body)))
            addView(spacer(context, 8))
            context.resources.getStringArray(R.array.tc_opensource).forEach { item ->
                addView(checkRow(context, item, CheckStyle.BULLET))
            }
        })

        // Sections 8, 9, 10
        listOf(
            Triple(R.string.tc_s8_title, R.string.tc_s8_body, "🔄"),
            Triple(R.string.tc_s9_title, R.string.tc_s9_body, "🚫"),
            Triple(R.string.tc_s10_title, R.string.tc_s10_body, "⚖️")
        ).forEach { (titleRes, bodyRes, icon) ->
            add(sectionCard(context) {
                addView(sectionTitle(context, context.getString(titleRes), icon))
                addView(bodyText(context, context.getString(bodyRes)))
            })
        }

        // Section 11 — Contact
        add(sectionCard(context) {
            addView(sectionTitle(context, context.getString(R.string.tc_s11_title), "📬"))
            addView(bodyText(context, context.getString(R.string.tc_s11_body)))
            addView(spacer(context, 8))
            val labels = context.resources.getStringArray(R.array.tc_contact_labels)
            val values = context.resources.getStringArray(R.array.tc_contact_values)
            for (i in 0 until minOf(labels.size, values.size)) {
                addView(checkRow(context, "${labels[i]}: ${values[i]}", CheckStyle.BULLET))
            }
        })

        add(footerText(context, context.getString(R.string.tc_footer)))
        add(spacer(context, 24))
    }
}

    // ─────────────────────────────────────────────────────────────

    fun buildPrivacyViews(context: Context, isTvDevice: Boolean): List<View> {
        isTv = isTvDevice
        return buildList {
        add(summaryBanner(context, context.getString(R.string.pp_summary)))
        add(spacer(context, if (isTv) 24 else 16))

        // Section 1
        add(sectionCard(context) {
            addView(sectionTitle(context, context.getString(R.string.pp_s1_title), "👋"))
            addView(bodyText(context, context.getString(R.string.pp_s1_body)))
        })

        // Section 2 — What we collect
        add(sectionCard(context) {
            addView(sectionTitle(context, context.getString(R.string.pp_s2_title), "📦"))

            addView(subHeading(context, context.getString(R.string.pp_s2_local_heading)))
            addView(bodyText(context, context.getString(R.string.pp_s2_local_body)))
            addView(spacer(context, if (isTv) 10 else 6))
            val localLabels = context.resources.getStringArray(R.array.pp_local_data_labels)
            val localDetails = context.resources.getStringArray(R.array.pp_local_data_details)
            for (i in 0 until minOf(localLabels.size, localDetails.size)) {
                addView(tableRow(context, localLabels[i], localDetails[i], i % 2 == 1))
            }
            addView(spacer(context, 8))
            addView(alertBox(context, "🛡️", context.getString(R.string.your_privacy_is_protected),
                context.getString(R.string.pp_s2_local_alert), AlertStyle.SUCCESS))

            addView(spacer(context, 16))
            addView(subHeading(context, context.getString(R.string.pp_s2_analytics_heading)))
            addView(bodyText(context, context.getString(R.string.pp_s2_analytics_body)))
            addView(spacer(context, 6))
            val analyticsLabels = context.resources.getStringArray(R.array.pp_analytics_labels)
            val analyticsDetails = context.resources.getStringArray(R.array.pp_analytics_details)
            for (i in 0 until minOf(analyticsLabels.size, analyticsDetails.size)) {
                addView(tableRow(context, analyticsLabels[i], analyticsDetails[i], i % 2 == 1))
            }

            addView(spacer(context, 16))
            addView(subHeading(context, context.getString(R.string.pp_s2_billing_heading)))
            addView(bodyText(context, context.getString(R.string.pp_s2_billing_body)))
            addView(spacer(context, 8))
            addView(alertBox(context, "ℹ️", context.getString(R.string.a_note_on_billing),
                context.getString(R.string.pp_s2_billing_note), AlertStyle.INFO))

            addView(spacer(context, 16))
            addView(subHeading(context, context.getString(R.string.pp_s2_crash_heading)))
            addView(bodyText(context, context.getString(R.string.pp_s2_crash_body)))

            addView(spacer(context, 16))
            addView(subHeading(context, context.getString(R.string.pp_s2_nocollect_heading)))
            context.resources.getStringArray(R.array.pp_never_collect).forEach { item ->
                addView(checkRow(context, item, CheckStyle.CROSS))
            }
        })

        // Section 3 — How we use data
        add(sectionCard(context) {
            addView(sectionTitle(context, context.getString(R.string.pp_s3_title), "🎯"))
            addView(subHeading(context, context.getString(R.string.pp_s3_local_heading)))
            context.resources.getStringArray(R.array.pp_use_local).forEach { item ->
                addView(checkRow(context, item, CheckStyle.CHECK))
            }
            addView(spacer(context, 12))
            addView(subHeading(context, context.getString(R.string.pp_s3_analytics_heading)))
            context.resources.getStringArray(R.array.pp_use_analytics).forEach { item ->
                addView(checkRow(context, item, CheckStyle.CHECK))
            }
        })

        // Section 4 — Permissions
        add(sectionCard(context) {
            addView(sectionTitle(context, context.getString(R.string.pp_s4_title), "🔐"))

            addView(subHeading(context, context.getString(R.string.pp_s4_storage_heading)))
            val storageLabels = context.resources.getStringArray(R.array.pp_storage_perm_labels)
            val storageDetails = context.resources.getStringArray(R.array.pp_storage_perm_details)
            for (i in 0 until minOf(storageLabels.size, storageDetails.size)) {
                addView(tableRow(context, storageLabels[i], storageDetails[i], i % 2 == 1))
            }

            addView(spacer(context, 16))
            addView(subHeading(context, context.getString(R.string.pp_s4_network_heading)))
            val networkLabels = context.resources.getStringArray(R.array.pp_network_perm_labels)
            val networkDetails = context.resources.getStringArray(R.array.pp_network_perm_details)
            for (i in 0 until minOf(networkLabels.size, networkDetails.size)) {
                addView(tableRow(context, networkLabels[i], networkDetails[i], i % 2 == 1))
            }
            addView(spacer(context, 8))
            addView(alertBox(context, "📡", context.getString(R.string.about_remote_management),
                context.getString(R.string.pp_s4_network_alert), AlertStyle.INFO))

            addView(spacer(context, 16))
            addView(subHeading(context, context.getString(R.string.pp_s4_other_heading)))
            val otherLabels = context.resources.getStringArray(R.array.pp_other_perm_labels)
            val otherDetails = context.resources.getStringArray(R.array.pp_other_perm_details)
            for (i in 0 until minOf(otherLabels.size, otherDetails.size)) {
                addView(tableRow(context, otherLabels[i], otherDetails[i], i % 2 == 1))
            }
        })

        // Section 5 — Security
        add(sectionCard(context) {
            addView(sectionTitle(context, context.getString(R.string.pp_s5_title), "🛡️"))

            addView(subHeading(context, context.getString(R.string.pp_s5_vault_heading)))
            addView(bodyText(context, context.getString(R.string.pp_s5_vault_body)))
            addView(spacer(context, 6))
            val encLabels = context.resources.getStringArray(R.array.pp_encryption_labels)
            val encDetails = context.resources.getStringArray(R.array.pp_encryption_details)
            for (i in 0 until minOf(encLabels.size, encDetails.size)) {
                addView(tableRow(context, encLabels[i], encDetails[i], i % 2 == 1))
            }
            addView(spacer(context, 8))
            addView(alertBox(context, "⚠️", context.getString(R.string.important_warning),
                context.getString(R.string.pp_s5_vault_alert), AlertStyle.DANGER))

            addView(spacer(context, 16))
            addView(subHeading(context, context.getString(R.string.pp_s5_local_heading)))
            context.resources.getStringArray(R.array.pp_local_protection).forEach { item ->
                addView(checkRow(context, item, CheckStyle.CHECK))
            }

            addView(spacer(context, 12))
            addView(subHeading(context, context.getString(R.string.pp_s5_remote_heading)))
            context.resources.getStringArray(R.array.pp_remote_security).forEach { item ->
                addView(checkRow(context, item, CheckStyle.CHECK))
            }
        })

        // Section 6 — Google API Limited Use
        add(sectionCard(context) {
            addView(sectionTitle(context, context.getString(R.string.pp_s6_title), "📱"))
            addView(alertBox(context, "⚠️", context.getString(R.string.important_warning),
                context.getString(R.string.pp_s6_compliance_banner), AlertStyle.INFO))
            addView(spacer(context, 12))
            addView(subHeading(context, context.getString(R.string.pp_s6_google_data_heading)))
            val labels = context.resources.getStringArray(R.array.pp_google_data_labels)
            val when_array = context.resources.getStringArray(R.array.pp_google_data_when)
            val purposes = context.resources.getStringArray(R.array.pp_google_data_purposes)
            for (i in 0 until minOf(labels.size, when_array.size, purposes.size)) {
                addView(tableRow(context, labels[i], "${when_array[i]} · ${purposes[i]}", i % 2 == 1))
            }
            addView(spacer(context, 16))
            addView(subHeading(context, context.getString(R.string.pp_s6_google_never_heading)))
            addView(bodyText(context, context.getString(R.string.pp_s6_google_never)))
            addView(spacer(context, 12))
            addView(subHeading(context, context.getString(R.string.pp_s6_permitted_heading)))
            context.resources.getStringArray(R.array.pp_google_permitted).forEach { item ->
                addView(checkRow(context, item, CheckStyle.CHECK))
            }
            addView(spacer(context, 12))
            addView(subHeading(context, context.getString(R.string.pp_s6_prohibited_heading)))
            context.resources.getStringArray(R.array.pp_google_prohibited).forEach { item ->
                addView(checkRow(context, item, CheckStyle.CROSS))
            }
            addView(spacer(context, 8))
            addView(bodyText(context, context.getString(R.string.pp_s6_minimum_access)))
        })

        // Section 6b — RClone Compliance / Integration
        add(sectionCard(context) {
            addView(sectionTitle(context, context.getString(R.string.pp_s6b_title), "☁️"))
            addView(alertBox(context, "☁️", context.getString(R.string.rclone_title),
                context.getString(R.string.pp_s6b_compliance_banner), AlertStyle.INFO))
            addView(spacer(context, 12))
            addView(bodyText(context, context.getString(R.string.pp_s6b_intro)))
            addView(spacer(context, 12))
            addView(subHeading(context, context.getString(R.string.pp_s6b_local_heading)))
            addView(bodyText(context, context.getString(R.string.pp_s6b_local_body)))
            addView(spacer(context, 12))
            addView(subHeading(context, context.getString(R.string.pp_s6b_permitted_heading)))
            context.resources.getStringArray(R.array.pp_s6b_permitted).forEach { item ->
                addView(checkRow(context, item, CheckStyle.CHECK))
            }
        })

        // Section 7 — Data sharing
        add(sectionCard(context) {
            addView(sectionTitle(context, context.getString(R.string.pp_s7_title), "🤝"))
            addView(alertBox(context, "✅", context.getString(R.string.we_do_not_sell_your_data),
                context.getString(R.string.pp_s7_nosell_alert), AlertStyle.SUCCESS))
            addView(spacer(context, 12))
            addView(subHeading(context, context.getString(R.string.pp_s7_third_heading)))
            val names = context.resources.getStringArray(R.array.pp_third_party_names)
            val providers = context.resources.getStringArray(R.array.pp_third_party_providers)
            val purposes = context.resources.getStringArray(R.array.pp_third_party_purposes)
            for (i in 0 until minOf(names.size, providers.size, purposes.size)) {
                addView(tableRow(context, names[i], "${providers[i]} · ${purposes[i]}", i % 2 == 1))
            }
            addView(spacer(context, 8))
            addView(bodyText(context, context.getString(R.string.pp_s7_legal_note)))
        })

        // Section 8 — Retention
        add(sectionCard(context) {
            addView(sectionTitle(context, context.getString(R.string.pp_s8_title), "🗓️"))
            val retLabels = context.resources.getStringArray(R.array.pp_retention_labels)
            val retDetails = context.resources.getStringArray(R.array.pp_retention_details)
            for (i in 0 until minOf(retLabels.size, retDetails.size)) {
                addView(tableRow(context, retLabels[i], retDetails[i], i % 2 == 1))
            }
        })

        // Section 9 — Your rights
        add(sectionCard(context) {
            addView(sectionTitle(context, context.getString(R.string.pp_s9_title), "⚖️"))
            addView(bodyText(context, context.getString(R.string.pp_s9_body)))
            addView(spacer(context, 8))
            context.resources.getStringArray(R.array.pp_rights).forEach { item ->
                addView(checkRow(context, item, CheckStyle.CHECK))
            }
            addView(spacer(context, 12))
            addView(subHeading(context, context.getString(R.string.pp_s9_delete_heading)))
            context.resources.getStringArray(R.array.pp_delete_steps).forEachIndexed { i, step ->
                addView(numberedRow(context, i + 1, step))
            }
            addView(spacer(context, 8))
            addView(alertBox(context, "⚠️", context.getString(R.string.before_you_clear_data),
                context.getString(R.string.pp_s9_delete_alert), AlertStyle.DANGER))
        })

        // Sections 10, 11, 12
        listOf(
            Triple(R.string.pp_s10_title, R.string.pp_s10_body, "👶"),
            Triple(R.string.pp_s11_title, R.string.pp_s11_body, "🌍"),
            Triple(R.string.pp_s12_title, R.string.pp_s12_body, "🔄")
        ).forEach { (titleRes, bodyRes, icon) ->
            add(sectionCard(context) {
                addView(sectionTitle(context, context.getString(titleRes), icon))
                addView(bodyText(context, context.getString(bodyRes)))
            })
        }

        // Section 13 — Google Play compliance
        add(sectionCard(context) {
            addView(sectionTitle(context, context.getString(R.string.pp_s13_title), "✔️"))
            addView(bodyText(context, context.getString(R.string.pp_s13_body)))
            addView(spacer(context, 8))
            val compLabels = context.resources.getStringArray(R.array.pp_compliance_labels)
            val compDetails = context.resources.getStringArray(R.array.pp_compliance_details)
            for (i in 0 until minOf(compLabels.size, compDetails.size)) {
                addView(tableRow(context, compLabels[i], compDetails[i], i % 2 == 1))
            }
        })

        // Section 13b — Prominent Disclosure — Installed Application Information
        add(sectionCard(context) {
            addView(sectionTitle(context, context.getString(R.string.pp_s13b_title), "📱"))
            addView(bodyText(context, context.getString(R.string.pp_s13b_intro)))
            
            addView(spacer(context, 12))
            addView(subHeading(context, context.getString(R.string.pp_s13b_data_heading)))
            context.resources.getStringArray(R.array.pp_s13b_data_items).forEach { item ->
                addView(checkRow(context, item, CheckStyle.BULLET))
            }
            
            addView(spacer(context, 12))
            addView(subHeading(context, context.getString(R.string.pp_s13b_purpose_heading)))
            context.resources.getStringArray(R.array.pp_s13b_purpose_items).forEach { item ->
                addView(checkRow(context, item, CheckStyle.BULLET))
            }
            
            addView(spacer(context, 12))
            addView(subHeading(context, context.getString(R.string.pp_s13b_location_heading)))
            addView(bodyText(context, context.getString(R.string.pp_s13b_location_body)))
        })

        // Section 14 — Contact
        add(sectionCard(context) {
            addView(sectionTitle(context, context.getString(R.string.pp_s14_title), "📬"))
            addView(bodyText(context, context.getString(R.string.pp_s14_body)))
            addView(spacer(context, 8))
            val labels = context.resources.getStringArray(R.array.pp_contact_labels)
            val values = context.resources.getStringArray(R.array.pp_contact_values)
            for (i in 0 until minOf(labels.size, values.size)) {
                addView(checkRow(context, "${labels[i]}: ${values[i]}", CheckStyle.BULLET))
            }
        })

        add(footerText(context, context.getString(R.string.pp_footer)))
        add(spacer(context, 24))
    }
}

    // ─────────────────────────────────────────────────────────────

    fun buildAcceptanceUi(context: Context, isTvDevice: Boolean, policyType: String): View {
        val isTerms = policyType == PolicyActivity.TYPE_TERMS
        val prefsKey = if (isTerms) "terms_accepted_time" else "privacy_accepted_time"
        val prefs = context.getSharedPreferences("acceptance_prefs", Context.MODE_PRIVATE)
        val acceptedTime = prefs.getLong(prefsKey, 0L)

        if (acceptedTime == 0L) {
            val checkBox = android.widget.CheckBox(context).apply {
                tag = "acceptance_checkbox"
                text = if (isTerms) context.getString(R.string.i_have_read_and_accept_the_terms_conditions) else context.getString(R.string.i_have_read_and_accept_the_privacy_policy)
                setTextColor(ContextCompat.getColor(context, R.color.policy_ink))
                val paddingMultiplier = if (isTvDevice) 1.5f else 1f
                textSize = 15f * paddingMultiplier
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    bottomMargin = dp(context, 16)
                }
            }

            val continueButton = android.widget.Button(context).apply {
                text = "Continue"
                isEnabled = false
                isAllCaps = false
                val paddingMultiplier = if (isTvDevice) 1.5f else 1f
                textSize = 16f * paddingMultiplier
                setTextColor(ContextCompat.getColor(context, za.kilowatch.ultimatefilemanager.R.color.white))
                background = context.createRoundedBackground(R.color.policy_accent, 8f * paddingMultiplier)
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)

                setOnClickListener {
                    val currentTime = System.currentTimeMillis()
                    prefs.edit().putLong(prefsKey, currentTime).apply()
                    val toastMsg = if (isTerms) context.getString(R.string.terms_conditions_accepted) else context.getString(R.string.privacy_policy_accepted)
                    android.widget.Toast.makeText(context, toastMsg, android.widget.Toast.LENGTH_SHORT).show()
                    if (context is androidx.appcompat.app.AppCompatActivity) {
                        context.finish()
                    }
                }
            }

            if (isTvDevice) {
                checkBox.isFocusable = true
                checkBox.isFocusableInTouchMode = false
                val defaultColor = ContextCompat.getColor(context, R.color.policy_ink)
                val focusColor = ContextCompat.getColor(context, R.color.tv_button_focused_yellow_text)
                
                checkBox.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        checkBox.setTextColor(focusColor)
                        checkBox.background = context.createRoundedBackground(R.color.tv_button_focused_yellow, 8f)
                    } else {
                        checkBox.setTextColor(defaultColor)
                        checkBox.background = null
                    }
                }
            }

            continueButton.background = context.createRoundedBackground(R.color.policy_slate, 8f)

            checkBox.setOnCheckedChangeListener { _, isChecked ->
                continueButton.isEnabled = isChecked
                if (!continueButton.isFocused) {
                    continueButton.background = if (isChecked) {
                        context.createRoundedBackground(R.color.policy_accent, 8f)
                    } else {
                        context.createRoundedBackground(R.color.policy_slate, 8f)
                    }
                }
            }

            if (isTvDevice) {
                continueButton.isFocusable = true
                continueButton.isFocusableInTouchMode = false
                val whiteColor = ContextCompat.getColor(context, R.color.white)
                val focusTextColor = ContextCompat.getColor(context, R.color.tv_button_focused_yellow_text)
                
                continueButton.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        continueButton.setTextColor(focusTextColor)
                        continueButton.background = context.createRoundedBackground(R.color.tv_button_focused_yellow, 8f)
                    } else {
                        continueButton.setTextColor(whiteColor)
                        continueButton.background = if (checkBox.isChecked) {
                            context.createRoundedBackground(R.color.policy_accent, 8f)
                        } else {
                            context.createRoundedBackground(R.color.policy_slate, 8f)
                        }
                    }
                }
            }

            return sectionCard(context) {
                addView(checkBox)
                addView(continueButton)
            }
        } else {
            val dateFormat = java.text.SimpleDateFormat("MMM dd, yyyy HH:mm", java.util.Locale.getDefault())
            val dateString = dateFormat.format(java.util.Date(acceptedTime))
            val title = if (isTerms) context.getString(R.string.terms_accepted) else context.getString(R.string.policy_accepted)
            val msg = if (isTerms) context.getString(R.string.terms_conditions_accepted_on_datestring, dateString) else context.getString(R.string.privacy_policy_accepted_on_datestring, dateString)
            return alertBox(context, "✓", title, msg, AlertStyle.SUCCESS)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // View factory helpers
    // ─────────────────────────────────────────────────────────────

    private fun summaryBanner(context: Context, contentText: String): View {
        val textMultiplier = if (isTv) 1.3f else 1f
        val paddingMultiplier = if (isTv) 1.3f else 1f
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val bgRes = if (isTv) R.color.tv_glass_white_10 else R.color.policy_accent
            setBackgroundColor(ContextCompat.getColor(context, bgRes))
            background = context.createRoundedBackground(bgRes, 16f * paddingMultiplier)
            setPadding(dp(context, (20 * paddingMultiplier).toInt()), dp(context, (16 * paddingMultiplier).toInt()), dp(context, (20 * paddingMultiplier).toInt()), dp(context, (16 * paddingMultiplier).toInt()))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = dp(context, (4 * paddingMultiplier).toInt())
            }
            
            addView(TextView(context).apply {
                text = "Plain-English Summary"
                setTextColor(ContextCompat.getColor(context, if (isTv) R.color.tv_text_primary else R.color.policy_toolbar_text))
                textSize = 13f * textMultiplier
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(context, (4 * paddingMultiplier).toInt()))
            })
            addView(TextView(context).apply {
                text = contentText
                setTextColor(if (isTv) ContextCompat.getColor(context, R.color.tv_text_secondary) else 0xCCFFFFFF.toInt())
                textSize = 13.5f * textMultiplier
                setLineSpacing(0f, 1.45f)
            })
        }
    }

    private fun sectionCard(context: Context, block: LinearLayout.() -> Unit): View {
        val paddingMultiplier = if (isTv) 1.3f else 1f
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val bgRes = if (isTv) R.color.tv_glass_white_10 else R.color.policy_surface
            background = context.createRoundedBackground(bgRes, 16f * paddingMultiplier)
            elevation = if (isTv) 0f else dp(context, 2).toFloat()
            setPadding(dp(context, (20 * paddingMultiplier).toInt()), dp(context, (20 * paddingMultiplier).toInt()), dp(context, (20 * paddingMultiplier).toInt()), dp(context, (20 * paddingMultiplier).toInt()))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = dp(context, (14 * paddingMultiplier).toInt())
            }
            
            block()
        }
    }

    private fun sectionTitle(context: Context, title: String, icon: String): View {
        val textMultiplier = if (isTv) 1.3f else 1f
        val paddingMultiplier = if (isTv) 1.3f else 1f
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(context, (14 * paddingMultiplier).toInt()))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)

            // Icon badge
            addView(TextView(context).apply {
                text = icon
                textSize = 20f * textMultiplier
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(context, (40 * paddingMultiplier).toInt()), dp(context, (40 * paddingMultiplier).toInt())).apply {
                    marginEnd = dp(context, (12 * paddingMultiplier).toInt())
                }
                background = context.createRoundedBackground(if (isTv) R.color.tv_glass_white_20 else R.color.policy_accent_light, 10f * paddingMultiplier)
            })

            // Title
            addView(TextView(context).apply {
                text = title
                setTextColor(ContextCompat.getColor(context, if (isTv) R.color.tv_text_primary else R.color.policy_ink))
                textSize = 16f * textMultiplier
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
        }
    }

    private fun subHeading(context: Context, contentText: String): TextView {
        val textMultiplier = if (isTv) 1.3f else 1f
        val paddingMultiplier = if (isTv) 1.3f else 1f
        return TextView(context).apply {
            text = contentText
            setTextColor(ContextCompat.getColor(context, if (isTv) R.color.tv_accent else R.color.policy_accent))
            textSize = 11.5f * textMultiplier
            setTypeface(typeface, Typeface.BOLD)
            isAllCaps = true
            letterSpacing = 0.08f
            setPadding(0, 0, 0, dp(context, (6 * paddingMultiplier).toInt()))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(context, (4 * paddingMultiplier).toInt())
            }
        }
    }

    private fun bodyText(context: Context, contentText: String): TextView {
        val textMultiplier = if (isTv) 1.3f else 1f
        val paddingMultiplier = if (isTv) 1.3f else 1f
        return TextView(context).apply {
            text = contentText
            setTextColor(ContextCompat.getColor(context, if (isTv) R.color.tv_text_secondary else R.color.policy_ink))
            textSize = 14.5f * textMultiplier
            setLineSpacing(0f, 1.5f)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = dp(context, (4 * paddingMultiplier).toInt())
            }
        }
    }

    private enum class CheckStyle { CHECK, CROSS, BULLET }

    private fun checkRow(context: Context, rowText: String, style: CheckStyle): View {
        val textMultiplier = if (isTv) 1.3f else 1f
        val paddingMultiplier = if (isTv) 1.3f else 1f
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(context, (6 * paddingMultiplier).toInt()), 0, dp(context, (6 * paddingMultiplier).toInt()))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)

            val (symbol, bgColor, fgColor) = when (style) {
                CheckStyle.CHECK  -> Triple("✓", if (isTv) R.color.tv_glass_green else R.color.policy_green_bg, R.color.policy_green)
                CheckStyle.CROSS  -> Triple("✕", if (isTv) R.color.tv_glass_red else R.color.policy_red_bg,   R.color.policy_red)
                CheckStyle.BULLET -> Triple("•", if (isTv) R.color.tv_glass_blue else R.color.policy_accent_light, if (isTv) R.color.tv_accent else R.color.policy_accent)
            }

            addView(TextView(context).apply {
                text = symbol
                setTextColor(ContextCompat.getColor(context, fgColor))
                textSize = 11f * textMultiplier
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                background = context.createRoundedBackground(bgColor, 11f * paddingMultiplier)
                layoutParams = LinearLayout.LayoutParams(dp(context, (22 * paddingMultiplier).toInt()), dp(context, (22 * paddingMultiplier).toInt())).apply {
                    marginEnd = dp(context, (12 * paddingMultiplier).toInt())
                    topMargin = dp(context, (2 * paddingMultiplier).toInt())
                }
            })

            addView(TextView(context).apply {
                text = rowText
                setTextColor(ContextCompat.getColor(context, if (isTv) R.color.tv_text_secondary else R.color.policy_ink))
                textSize = 14f * textMultiplier
                setLineSpacing(0f, 1.45f)
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
        }
    }

    private fun numberedRow(context: Context, number: Int, rowText: String): View {
        val textMultiplier = if (isTv) 1.3f else 1f
        val paddingMultiplier = if (isTv) 1.3f else 1f
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(context, (6 * paddingMultiplier).toInt()), 0, dp(context, (6 * paddingMultiplier).toInt()))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)

            addView(TextView(context).apply {
                text = number.toString()
                setTextColor(ContextCompat.getColor(context, if (isTv) R.color.tv_text_primary else R.color.policy_toolbar_text))
                textSize = 11f * textMultiplier
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                background = context.createRoundedBackground(if (isTv) R.color.tv_glass_white_20 else R.color.policy_accent, 11f * paddingMultiplier)
                layoutParams = LinearLayout.LayoutParams(dp(context, (24 * paddingMultiplier).toInt()), dp(context, (24 * paddingMultiplier).toInt())).apply {
                    marginEnd = dp(context, (12 * paddingMultiplier).toInt())
                    topMargin = dp(context, (2 * paddingMultiplier).toInt())
                }
            })

            addView(TextView(context).apply {
                text = rowText
                setTextColor(ContextCompat.getColor(context, if (isTv) R.color.tv_text_secondary else R.color.policy_ink))
                textSize = 14f * textMultiplier
                setLineSpacing(0f, 1.45f)
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
        }
    }

    private fun tableRow(context: Context, label: String, detail: String, alternate: Boolean): View {
        val textMultiplier = if (isTv) 1.3f else 1f
        val paddingMultiplier = if (isTv) 1.3f else 1f
        val bgColor = if (isTv) {
            if (alternate) R.color.tv_glass_white_10 else android.R.color.transparent
        } else {
            if (alternate) R.color.policy_row_alt else R.color.policy_surface
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(ContextCompat.getColor(context, bgColor))
            setPadding(dp(context, (12 * paddingMultiplier).toInt()), dp(context, (10 * paddingMultiplier).toInt()), dp(context, (12 * paddingMultiplier).toInt()), dp(context, (10 * paddingMultiplier).toInt()))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)

            addView(TextView(context).apply {
                text = label
                setTextColor(ContextCompat.getColor(context, if (isTv) R.color.tv_text_primary else R.color.policy_accent))
                textSize = 13f * textMultiplier
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.38f)
            })

            addView(TextView(context).apply {
                text = detail
                setTextColor(ContextCompat.getColor(context, if (isTv) R.color.tv_text_secondary else R.color.policy_slate))
                textSize = 13f * textMultiplier
                setLineSpacing(0f, 1.4f)
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.62f)
            })
        }
    }

    private enum class AlertStyle { INFO, SUCCESS, DANGER }

    private fun alertBox(
        context: Context,
        icon: String,
        title: String,
        message: String,
        style: AlertStyle
    ): View {
        val textMultiplier = if (isTv) 1.3f else 1f
        val paddingMultiplier = if (isTv) 1.3f else 1f
        val (bgRes, borderColor) = when (style) {
            AlertStyle.INFO    -> (if (isTv) R.color.tv_glass_blue else R.color.policy_accent_light) to R.color.policy_accent
            AlertStyle.SUCCESS -> (if (isTv) R.color.tv_glass_green else R.color.policy_green_bg) to R.color.policy_green
            AlertStyle.DANGER  -> (if (isTv) R.color.tv_glass_red else R.color.policy_red_bg) to R.color.policy_red
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = context.createRoundedBackground(bgRes, 6f)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(context, (4 * paddingMultiplier).toInt())
            }

            // Left colored border
            addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(context, 6), MATCH_PARENT)
                setBackgroundColor(ContextCompat.getColor(context, borderColor))
            })

            // Content Container
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                setPadding(
                    dp(context, (16 * paddingMultiplier).toInt() - dp(context, 6)), 
                    dp(context, (14 * paddingMultiplier).toInt()), 
                    dp(context, (16 * paddingMultiplier).toInt()), 
                    dp(context, (14 * paddingMultiplier).toInt())
                )

                addView(TextView(context).apply {
                    text = icon
                    textSize = 18f * textMultiplier
                    gravity = Gravity.TOP
                    layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                        marginEnd = dp(context, (12 * paddingMultiplier).toInt())
                        topMargin = dp(context, (2 * paddingMultiplier).toInt())
                    }
                })

                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)

                    addView(TextView(context).apply {
                        text = title
                        val titleRes = when (style) {
                            AlertStyle.INFO    -> (if (isTv) R.color.tv_text_primary else R.color.policy_ink)
                            AlertStyle.SUCCESS -> R.color.policy_green
                            AlertStyle.DANGER  -> R.color.policy_red
                        }
                        setTextColor(ContextCompat.getColor(context, titleRes))
                        textSize = 13.5f * textMultiplier
                        setTypeface(typeface, Typeface.BOLD)
                        setPadding(0, 0, 0, dp(context, (4 * paddingMultiplier).toInt()))
                    })

                    addView(TextView(context).apply {
                        text = message
                        val msgRes = if (isTv) R.color.tv_text_hint else R.color.policy_slate
                        setTextColor(ContextCompat.getColor(context, msgRes))
                        textSize = 13f * textMultiplier
                        setLineSpacing(0f, 1.45f)
                    })
                })
            })
        }
    }

    private fun footerText(context: Context, contentText: String): TextView {
        val textMultiplier = if (isTv) 1.3f else 1f
        val paddingMultiplier = if (isTv) 1.3f else 1f
        return TextView(context).apply {
            text = contentText
            setTextColor(ContextCompat.getColor(context, if (isTv) R.color.tv_text_hint else R.color.policy_slate))
            textSize = 13f * textMultiplier
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(context, (16 * paddingMultiplier).toInt()), dp(context, (16 * paddingMultiplier).toInt()), dp(context, (16 * paddingMultiplier).toInt()), dp(context, (8 * paddingMultiplier).toInt()))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
    }

    private fun spacer(context: Context, heightDp: Int): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(context, heightDp))
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Drawing helpers
    // ─────────────────────────────────────────────────────────────

    private fun Context.createRoundedBackground(
        colorRes: Int,
        radiusDp: Float
    ): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(this@createRoundedBackground, radiusDp.toInt()).toFloat()
            setColor(ContextCompat.getColor(this@createRoundedBackground, colorRes))
        }
    }

    // Removed createLeftBorderBackground as it relies on overlapping layers

    private fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
}

