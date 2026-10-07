package com.phytoy.sample

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.TextView

/** Public publisher information comes from the release configuration, never a guessed identity. */
internal object SupportInfo {
    val hasEmail: Boolean get() = BuildConfig.SUPPORT_EMAIL.isNotBlank()
    val hasPolicyUrl: Boolean get() = BuildConfig.PRIVACY_POLICY_URL.startsWith("https://")

    fun contactText(activity: Activity): String = listOfNotNull(
        BuildConfig.PUBLISHER_NAME.takeIf(String::isNotBlank)?.let {
            activity.getString(R.string.support_publisher, it)
        },
        BuildConfig.SUPPORT_EMAIL.takeIf(String::isNotBlank)?.let {
            activity.getString(R.string.support_contact_email, it)
        },
    ).joinToString("\n")

    fun email(activity: Activity) {
        if (!hasEmail) return
        // appendQueryParameter clears an opaque mailto recipient in Uri.Builder.
        val subject = "ToviCam ${BuildConfig.VERSION_NAME} · ${BuildConfig.STORE_CHANNEL}"
        val uri = Uri.parse("mailto:${Uri.encode(BuildConfig.SUPPORT_EMAIL)}?subject=${Uri.encode(subject)}")
        try {
            activity.startActivity(Intent(Intent.ACTION_SENDTO, uri))
        } catch (_: ActivityNotFoundException) {
            val padding = (24 * activity.resources.displayMetrics.density).toInt()
            val email = TextView(activity).apply {
                text = BuildConfig.SUPPORT_EMAIL
                textSize = 16f
                setTextIsSelectable(true)
                setPadding(padding, padding, padding, padding)
            }
            AlertDialog.Builder(activity).setTitle(R.string.support_title).setView(email)
                .setPositiveButton(R.string.product_info_close, null).show()
        }
    }

    fun openPolicy(activity: Activity) {
        if (!hasPolicyUrl) return
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PRIVACY_POLICY_URL)))
        } catch (_: ActivityNotFoundException) {
            // The complete policy remains available offline in ProductInfo.
        }
    }
}
