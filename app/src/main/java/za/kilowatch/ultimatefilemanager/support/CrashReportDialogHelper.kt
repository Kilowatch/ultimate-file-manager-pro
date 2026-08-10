package za.kilowatch.ultimatefilemanager.support

import android.app.Activity
import android.view.LayoutInflater
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import za.kilowatch.ultimatefilemanager.BuildConfig
import za.kilowatch.ultimatefilemanager.R
import za.kilowatch.ultimatefilemanager.UfmApplication
import za.kilowatch.ultimatefilemanager.util.DeviceUtils
import java.util.concurrent.TimeUnit

/**
 * CrashReportDialogHelper
 *
 * Stateless helper that checks for a pending crash/ANR report and, if found,
 * shows a dialog asking the user to submit or discard it.
 *
 * Call [maybeShowCrashReportDialog] from StorageBrowserActivity.onResume(),
 * guarded by a one-per-session boolean flag.
 */
object CrashReportDialogHelper {

    private const val CRASH_ENDPOINT = "https://www.kilowatch.co.za/UFM/api/crash.php"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Checks for a pending crash/ANR report and shows the appropriate dialog.
     * Safe to call on every onResume — returns immediately if no report exists.
     */
    fun maybeShowCrashReportDialog(activity: Activity, lifecycleScope: LifecycleCoroutineScope) {
        val app = activity.application as? UfmApplication ?: return

        // The pending-report check does synchronous file I/O — getFilesDir() (which itself
        // runs File.exists() on the app private dir), mkdirs(), listFiles() and reading the
        // report JSON. On a slow or busy TV that I/O blocked the main thread for >5 s while
        // StorageBrowserActivity.onResume ran, tripping the ANR watchdog, so the whole
        // check + parse now runs on Dispatchers.IO and only the dialog itself is shown back
        // on the main thread.
        lifecycleScope.launch(Dispatchers.IO) {
            if (!CrashReportManager.isEnabled(activity)) {
                if (CrashReportManager.hasPendingReport(app)) {
                    CrashReportManager.deleteAllPendingReports(app)
                }
                return@launch
            }
            if (!CrashReportManager.hasPendingReport(app)) return@launch

            val reportFile = CrashReportManager.getPendingReportFile(app) ?: return@launch
            val fields = CrashReportManager.parseReport(reportFile)
            val reportType = fields["type"] ?: "crash"
            val fingerprint = fields["fingerprint"] ?: ""

            // Fingerprint deduplication applies ONLY to ANRs (freezes). Crashes ALWAYS display.
            if (reportType == "anr" && fingerprint.isNotEmpty() && CrashReportManager.isFingerprintReported(activity, fingerprint)) {
                CrashReportManager.deleteReport(reportFile)
                return@launch
            }

            withContext(Dispatchers.Main) {
                showReportDialog(activity, reportFile, reportType, fingerprint, fields, lifecycleScope)
            }
        }
    }

    private fun showReportDialog(
        activity: Activity,
        reportFile: java.io.File,
        reportType: String,
        fingerprint: String,
        fields: Map<String, String>,
        lifecycleScope: LifecycleCoroutineScope
    ) {
        val isTv = DeviceUtils.isTvDevice(activity)

        val layoutRes = if (isTv) R.layout.dialog_crash_report_tv else R.layout.dialog_crash_report
        val view = LayoutInflater.from(activity).inflate(layoutRes, null)

        val dialog = MaterialAlertDialogBuilder(activity, R.style.UFM_Dialog)
            .setView(view)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawableResource(
            if (isTv) R.drawable.bg_dialog_glass else R.drawable.bg_dialog_surface
        )

        // Bind title and message based on crash vs ANR
        val titleRes = if (reportType == "anr") R.string.crash_report_anr_title else R.string.crash_report_crash_title
        val messageRes = if (reportType == "anr") R.string.crash_report_anr_message else R.string.crash_report_crash_message

        view.findViewById<TextView>(R.id.txtCrashTitle).text = activity.getString(titleRes)
        view.findViewById<TextView>(R.id.txtCrashMessage).text = activity.getString(messageRes)

        val btnSend = view.findViewById<Button>(R.id.btnCrashSend)
        val btnNoThanks = view.findViewById<Button>(R.id.btnCrashNoThanks)

        btnSend.text = activity.getString(R.string.crash_report_send)
        btnNoThanks.text = activity.getString(R.string.crash_report_no_thanks)

        // TV focus styling
        if (isTv) {
            val yellowCsl = android.content.res.ColorStateList.valueOf(
                activity.getColor(R.color.tv_button_focused_yellow_text)
            )
            val defaultCsl = android.content.res.ColorStateList.valueOf(
                activity.getColor(R.color.tv_text_primary)
            )
            val defaultSecondaryCsl = android.content.res.ColorStateList.valueOf(
                activity.getColor(R.color.tv_text_secondary)
            )
            btnSend.setTextColor(defaultCsl)
            btnSend.setOnFocusChangeListener { _, hasFocus ->
                btnSend.setTextColor(if (hasFocus) yellowCsl else defaultCsl)
                btnSend.setBackgroundResource(
                    if (hasFocus) R.drawable.selector_tv_button_yellow else R.drawable.selector_tv_button
                )
            }
            btnNoThanks.setTextColor(defaultSecondaryCsl)
            btnNoThanks.setOnFocusChangeListener { _, hasFocus ->
                btnNoThanks.setTextColor(if (hasFocus) yellowCsl else defaultSecondaryCsl)
                btnNoThanks.setBackgroundResource(
                    if (hasFocus) R.drawable.selector_tv_button_yellow else R.drawable.selector_tv_button
                )
            }
        }

        btnNoThanks.setOnClickListener {
            if (reportType == "anr" && fingerprint.isNotEmpty()) {
                CrashReportManager.markFingerprintReported(activity, fingerprint)
            }
            dialog.dismiss()
            CrashReportManager.deleteReport(reportFile)
        }

        btnSend.setOnClickListener {
            btnSend.isEnabled = false
            btnNoThanks.isEnabled = false
            btnSend.text = activity.getString(R.string.crash_report_sending)
            if (reportType == "anr" && fingerprint.isNotEmpty()) {
                CrashReportManager.markFingerprintReported(activity, fingerprint)
            }
            submitReport(activity, lifecycleScope, fields) { statusCode ->
                dialog.dismiss()
                CrashReportManager.deleteReport(reportFile)
                val msgRes = when (statusCode) {
                    200 -> R.string.crash_report_sent
                    429 -> R.string.support_rate_limited
                    else -> R.string.crash_report_failed
                }
                Toast.makeText(activity, msgRes, Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()

        if (isTv) btnSend.requestFocus()
    }

    // ── Submission ───────────────────────────────────────────────────────────

    private fun submitReport(
        activity: Activity,
        lifecycleScope: LifecycleCoroutineScope,
        fields: Map<String, String>,
        onDone: (statusCode: Int) -> Unit
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            var resultCode = 500
            try {
                // 1. Attempt dedicated crash.php endpoint first
                val bodyBuilder1 = MultipartBody.Builder().setType(MultipartBody.FORM)
                fields.forEach { (key, value) ->
                    if (value.isNotEmpty()) {
                        bodyBuilder1.addFormDataPart(key, value)
                    }
                }
                val request1 = Request.Builder()
                    .url(CRASH_ENDPOINT)
                    .post(bodyBuilder1.build())
                    .build()

                val response1 = httpClient.newCall(request1).execute()
                val code1 = response1.code
                response1.close()
                android.util.Log.d("CrashReport", "crash.php HTTP response code: $code1")

                if (code1 == 200 || code1 == 429) {
                    resultCode = code1
                } else {
                    // 2. Fallback to live support.php endpoint if crash.php returns server error or unexpected code
                    val reportType = fields["type"] ?: "crash"
                    val subject = fields["subject"] ?: (if (reportType == "crash") "[Crash] Report" else "[ANR] Report")
                    val messageBody = buildString {
                        if (reportType == "crash") {
                            appendLine("Exception: ${fields["exception_class"]}")
                            appendLine("Message: ${fields["message"]}")
                            appendLine("Thread: ${fields["thread_name"]}")
                            appendLine("\n--- Stack Trace ---\n")
                            appendLine(fields["stack_trace"])
                        } else {
                            appendLine("ANR Reason: ${fields["anr_reason"]}")
                            appendLine("\n--- All Thread Stacks ---\n")
                            appendLine(fields["all_threads"])
                        }
                    }

                    val isTv = DeviceUtils.isTvDevice(activity)
                    val bodyBuilder2 = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("type", "bug")
                        .addFormDataPart("subject", subject)
                        .addFormDataPart("message", messageBody)
                        .addFormDataPart("timestamp", (System.currentTimeMillis() / 1000).toString())
                        .addFormDataPart("app_version", BuildConfig.VERSION_NAME)
                        .addFormDataPart("app_code", BuildConfig.VERSION_CODE.toString())
                        .addFormDataPart("sdk_version", android.os.Build.VERSION.SDK_INT.toString())
                        .addFormDataPart("manufacturer", android.os.Build.MANUFACTURER)
                        .addFormDataPart("device_model", android.os.Build.MODEL)
                        .addFormDataPart("is_tv", if (isTv) "1" else "0")
                        .addFormDataPart("package_name", BuildConfig.APPLICATION_ID)
                        .addFormDataPart("honeypot", "")

                    val request2 = Request.Builder()
                        .url("https://www.kilowatch.co.za/UFM/api/support.php")
                        .post(bodyBuilder2.build())
                        .build()

                    val response2 = httpClient.newCall(request2).execute()
                    val code2 = response2.code
                    response2.close()
                    android.util.Log.d("CrashReport", "support.php fallback HTTP response code: $code2")
                    resultCode = code2
                }
            } catch (e: Exception) {
                android.util.Log.e("CrashReport", "Failed to send crash report", e)
                resultCode = 500
            }
            withContext(Dispatchers.Main) {
                onDone(resultCode)
            }
        }
    }
}
