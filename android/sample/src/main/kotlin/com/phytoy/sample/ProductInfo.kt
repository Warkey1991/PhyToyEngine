package com.phytoy.sample

import android.app.Activity
import android.app.AlertDialog

/** Product and privacy information is available offline, without a web tracker. */
internal object ProductInfo {
    private var dialog: AlertDialog? = null

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    fun showAbout(activity: Activity) {
        @Suppress("DEPRECATION")
        val version = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty()
        showAbout(activity, version)
    }

    private fun showAbout(activity: Activity, version: String) {
        dismiss()
        dialog = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.product_info_title, version))
            .setMessage(listOf(activity.getString(R.string.product_info_body),
                SupportInfo.contactText(activity)).filter(String::isNotBlank).joinToString("\n\n"))
            .setPositiveButton(R.string.product_info_close, null)
            .apply { if (SupportInfo.hasEmail) setNeutralButton(R.string.support_title) { _, _ -> SupportInfo.email(activity) } }
            .show()
    }

    fun showPrivacy(activity: Activity) {
        dismiss()
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.product_privacy_title)
            .setMessage(listOf(activity.getString(R.string.privacy_channel_body,
                activity.getString(R.string.billing_store_name), activity.getString(R.string.billing_account_name)),
                SupportInfo.contactText(activity)).filter(String::isNotBlank).joinToString("\n\n"))
            .setPositiveButton(R.string.product_info_close, null)
            .apply { if (SupportInfo.hasPolicyUrl) setNeutralButton(R.string.support_online_policy) { _, _ -> SupportInfo.openPolicy(activity) } }
            .show()
    }

    fun showLicenses(activity: Activity) {
        dismiss()
        val notices = listOf(R.raw.open_source_notices, R.raw.channel_billing_notices).joinToString("\n\n") { resource ->
            activity.resources.openRawResource(resource).bufferedReader().use { it.readText() }
        }
        val text = android.widget.TextView(activity).apply {
            setText(notices)
            setTextIsSelectable(true)
            textSize = 12f
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.product_licenses_title)
            .setView(android.widget.ScrollView(activity).apply { addView(text) })
            .setPositiveButton(R.string.product_info_close, null)
            .show()
    }
}
