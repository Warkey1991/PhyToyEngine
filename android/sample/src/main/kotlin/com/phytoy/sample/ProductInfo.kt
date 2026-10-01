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
            .setMessage(R.string.product_info_body)
            .setPositiveButton(R.string.product_info_close, null)
            .show()
    }

    fun showPrivacy(activity: Activity) {
        dismiss()
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.product_privacy_title)
            .setMessage(R.string.product_privacy_body)
            .setPositiveButton(R.string.product_info_close, null)
            .show()
    }

    fun showLicenses(activity: Activity) {
        dismiss()
        val notices = activity.resources.openRawResource(R.raw.open_source_notices)
            .bufferedReader().use { it.readText() }
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
